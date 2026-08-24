package com.axuan.lyskps;

import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.*;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * tun2proxy 的本地 HTTP 上游。按 Host/CONNECT 域名选择上游代理、重定向目标或直接出口。
 * 重定向模式由内置 CA 动态签发目标证书并终止 TLS，再把明文 HTTP 交给 Web 后端。
 */
final class SelectiveProxyServer implements Closeable {
    private static final String TAG = "LYSK-PS.Proxy";
    private static final int MAX_HEADER = 64 * 1024;
    private final LyskVpnService vpn;
    private final VpnConfig config;
    private final RedirectTlsWrapper tlsWrapper;
    private final ServerSocket listener;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean closed;

    SelectiveProxyServer(LyskVpnService vpn, VpnConfig config) throws Exception {
        this.vpn = vpn; this.config = config;
        this.tlsWrapper = new RedirectTlsWrapper(vpn);
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
    }
    int port() { return listener.getLocalPort(); }
    void start() { pool.execute(() -> { while (!closed) try { Socket s=listener.accept(); pool.execute(() -> handle(s)); } catch(IOException e){ if(!closed)Log.w(TAG,"accept",e); } }); }

    private void handle(Socket client) {
        Socket remote=null, activeClient=client;
        try {
            client.setTcpNoDelay(true);
            byte[] first=readHeader(client.getInputStream());
            if(first==null)return;
            Request req=Request.parse(first);
            boolean matched=config.matches(req.host);
            boolean viaProxy=matched && config.mode==VpnConfig.MODE_PROXY;
            boolean redirected=matched && config.mode==VpnConfig.MODE_REDIRECT;
            boolean redirectTls=redirected && req.connect;
            boolean wrapTls=redirectTls && config.redirectTlsWrapper;
            String host; int port;
            if(viaProxy || redirected) {
                host=config.endpointUri.getHost(); port=config.endpointPort();
            } else { host=req.host; port=req.port; }
            String action=viaProxy?"PROXY":wrapTls?"REDIRECT-TLS-WRAP":redirectTls?"REDIRECT-TLS-RAW":redirected?"REDIRECT-HTTP":"DIRECT";
            VpnLog.i(action,req.host+":"+req.port+" → "+host+":"+port+(req.connect?" [HTTPS CONNECT]":" [HTTP]"));
            if(redirectTls&&!config.redirectTlsWrapper&&!"https".equalsIgnoreCase(config.endpointUri.getScheme()))VpnLog.i("WARN","包装器关闭且服务地址不是 HTTPS，TLS 连接很可能失败");
            OutputStream cout=client.getOutputStream();
            if(redirectTls){cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));cout.flush();if(wrapTls)activeClient=tlsWrapper.wrap(client,req.host);}
            Socket rawRemote=new Socket();vpn.protect(rawRemote);rawRemote.connect(new InetSocketAddress(host,port),15000);rawRemote.setTcpNoDelay(true);remote=rawRemote;
            boolean backendTls=redirected&&"https".equalsIgnoreCase(config.endpointUri.getScheme())&&(!req.connect||wrapTls);
            if(backendTls){remote=((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(rawRemote,host,port,true);((SSLSocket)remote).startHandshake();}
            OutputStream rout=remote.getOutputStream();
            if(req.connect) {
                if(viaProxy) rout.write(first);
                else if(!redirected) cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            } else {
                rout.write(viaProxy ? first : req.asOriginForm(first,null));
            }
            rout.flush(); cout.flush();
            Socket r=remote,c=activeClient;
            Future<?> up=pool.submit(() -> copy(c,r));
            copy(r,c);
            try { up.get(2,TimeUnit.SECONDS); } catch(Throwable ignored) {}
        } catch(Throwable e) { Log.w(TAG,"connection failed",e); VpnLog.i("ERROR",e.getClass().getSimpleName()+": "+e.getMessage()); }
        finally { if(activeClient!=client)closeQuietly(activeClient);closeQuietly(remote);closeQuietly(client); }
    }

    private static byte[] readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream(1024); int state=0;
        while(b.size()<MAX_HEADER) { int x=in.read(); if(x<0)return b.size()==0?null:b.toByteArray(); b.write(x);
            state=(state==0&&x=='\r')?1:(state==1&&x=='\n')?2:(state==2&&x=='\r')?3:(state==3&&x=='\n')?4:0; if(state==4)return b.toByteArray(); }
        throw new IOException("HTTP header too large");
    }
    private static void copy(Socket from, Socket to) { try { InputStream in=from.getInputStream(); OutputStream out=to.getOutputStream(); byte[] b=new byte[16384]; int n; while((n=in.read(b))>=0){out.write(b,0,n);out.flush();} try{to.shutdownOutput();}catch(Throwable ignored){} } catch(Throwable ignored){} }
    private static void closeQuietly(Closeable c){if(c!=null)try{c.close();}catch(Throwable ignored){}}
    @Override public void close(){closed=true;closeQuietly(listener);pool.shutdownNow();}

    private static final class Request {
        final boolean connect; final String host; final int port; final String target;
        Request(boolean c,String h,int p,String t){connect=c;host=h;port=p;target=t;}
        static Request parse(byte[] raw) throws Exception {
            String h=new String(raw,StandardCharsets.ISO_8859_1); int end=h.indexOf("\r\n"); if(end<0)throw new IOException("bad request");
            String[] p=h.substring(0,end).split(" ",3); if(p.length<3)throw new IOException("bad request line");
            boolean c="CONNECT".equalsIgnoreCase(p[0]); String host=null; int port=c?443:80;
            if(c){HostPort hp=HostPort.parse(p[1],443);host=hp.host;port=hp.port;}
            else { try { URI u=URI.create(p[1]); if(u.getHost()!=null){host=u.getHost();port=u.getPort()>0?u.getPort():("https".equalsIgnoreCase(u.getScheme())?443:80);} }catch(Throwable ignored){}
                if(host==null){String lower=h.toLowerCase(Locale.ROOT);int at=lower.indexOf("\r\nhost:");if(at>=0){int s=at+7,e=h.indexOf("\r\n",s);HostPort hp=HostPort.parse(h.substring(s,e).trim(),80);host=hp.host;port=hp.port;}}}
            if(host==null||host.isEmpty())throw new IOException("request has no host"); return new Request(c,host,port,p[1]);
        }
        byte[] asOriginForm(byte[] raw, String hostOverride) {
            String s=new String(raw,StandardCharsets.ISO_8859_1); int line=s.indexOf("\r\n"), a=s.indexOf(' '), b=s.indexOf(' ',a+1); String path=target;
            try { URI u=URI.create(target); if(u.isAbsolute()){path=u.getRawPath();if(path==null||path.isEmpty())path="/";if(u.getRawQuery()!=null)path+="?"+u.getRawQuery();} }catch(Throwable ignored){}
            s=s.substring(0,a+1)+path+s.substring(b,line)+s.substring(line);
            if(hostOverride!=null){String lower=s.toLowerCase(Locale.ROOT);int at=lower.indexOf("\r\nhost:");if(at>=0){int value=at+7,end=s.indexOf("\r\n",value);s=s.substring(0,value)+" "+hostOverride+s.substring(end);}}
            return s.getBytes(StandardCharsets.ISO_8859_1);
        }
    }
    private static final class HostPort { final String host; final int port; HostPort(String h,int p){host=h;port=p;} static HostPort parse(String s,int def){try{URI u=URI.create("http://"+s);return new HostPort(u.getHost(),u.getPort()>0?u.getPort():def);}catch(Throwable t){return new HostPort(s,def);}} }
}
