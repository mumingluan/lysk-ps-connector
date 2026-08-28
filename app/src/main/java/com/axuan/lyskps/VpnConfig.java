package com.axuan.lyskps;

import android.content.SharedPreferences;
import java.net.URI;
import java.util.*;

/** 客户端 VPN 配置。 */
public final class VpnConfig {
    public static final String PREFS = "lysk_vpn_config";
    public static final int MODE_PROXY = 101, MODE_REDIRECT = 102;
    public static final String DEFAULT_DOMAINS = "papegames.com\npapegames.cn";
    public static final String DEFAULT_PACKAGES = "com.papegames.lysk.cn";
    public final int mode; public final String proxyEndpoint, redirectEndpoint, endpoint, domainsText, packagesText;
    public final boolean redirectTlsWrapper;
    public final URI endpointUri; public final List<String> domains, packages;
    private VpnConfig(int m,String pe,String re,boolean wrap,String d,String p,URI u,List<String> ds,List<String> ps){mode=m;proxyEndpoint=pe;redirectEndpoint=re;redirectTlsWrapper=wrap;endpoint=m==MODE_PROXY?pe:re;domainsText=d;packagesText=p;endpointUri=u;domains=ds;packages=ps;}
    public static VpnConfig load(SharedPreferences sp) {
        int mode=sp.getInt("mode",MODE_REDIRECT);String legacy=sp.getString("endpoint","");
        String proxy=sp.getString("proxy_endpoint",mode==MODE_PROXY&&!legacy.isEmpty()?legacy:"http://127.0.0.1:8888");
        String redirect=sp.getString("redirect_endpoint",mode==MODE_REDIRECT&&!legacy.isEmpty()?legacy:"http://127.0.0.1:8088");
        return fromInput(mode,proxy,redirect,sp.getBoolean("redirect_tls_wrapper",true),sp.getString("domains",DEFAULT_DOMAINS),sp.getString("packages",DEFAULT_PACKAGES));
    }
    public static VpnConfig fromInput(int mode,String proxyEndpoint,String redirectEndpoint,boolean redirectTlsWrapper,String domains,String packages) {
        if (mode != MODE_PROXY && mode != MODE_REDIRECT) mode = MODE_REDIRECT;
        proxyEndpoint=proxyEndpoint.trim();redirectEndpoint=redirectEndpoint.trim();
        URI proxy=parseEndpoint(proxyEndpoint,true,"上游代理地址"),redirect=parseEndpoint(redirectEndpoint,false,"上游 HTTP 服务地址");
        URI uri=mode==MODE_PROXY?proxy:redirect;
        List<String> ds=lines(domains,true), ps=lines(packages,false);
        if(ds.isEmpty()) throw new IllegalArgumentException("至少填写一个过滤域名");
        if(ps.isEmpty()) throw new IllegalArgumentException("至少填写一个 VPN 作用包名");
        return new VpnConfig(mode,proxyEndpoint,redirectEndpoint,redirectTlsWrapper,join(ds),join(ps),uri,Collections.unmodifiableList(ds),Collections.unmodifiableList(ps));
    }
    public void save(SharedPreferences sp){sp.edit().putInt("mode",mode).putString("proxy_endpoint",proxyEndpoint).putString("redirect_endpoint",redirectEndpoint).putBoolean("redirect_tls_wrapper",redirectTlsWrapper).remove("endpoint").remove("redirect_proxy_port").putString("domains",domainsText).putString("packages",packagesText).apply();}
    public int endpointPort(){int p=endpointUri.getPort();if(p>0)return p;return "https".equalsIgnoreCase(endpointUri.getScheme())?443:80;}
    private static URI parseEndpoint(String value,boolean proxy,String label){URI uri;try{uri=URI.create(value);}catch(Throwable t){throw new IllegalArgumentException(label+"格式无效");}String scheme=uri.getScheme();if(uri.getHost()==null||scheme==null||(!"http".equalsIgnoreCase(scheme)&&(!"https".equalsIgnoreCase(scheme)||proxy)))throw new IllegalArgumentException(label+(proxy?"须为 http://主机:端口":"须以 http:// 或 https:// 开头"));if(uri.getPort()>65535)throw new IllegalArgumentException(label+"端口无效");return uri;}
    public boolean matches(String host) {
        if(host==null)return false; host=host.toLowerCase(Locale.ROOT); int colon=host.lastIndexOf(':');
        if(colon>0 && host.indexOf(':')==colon)host=host.substring(0,colon); if(host.endsWith("."))host=host.substring(0,host.length()-1);
        for(String rule:domains)if(host.equals(rule)||host.endsWith("."+rule))return true; return false;
    }
    private static List<String> lines(String text, boolean domain) {
        ArrayList<String> out=new ArrayList<>(); for(String raw:text.split("[\\r\\n,;]+")) { String s=raw.trim().toLowerCase(Locale.ROOT); if(s.isEmpty())continue;
            if(domain){if(s.startsWith("*."))s=s.substring(2);else if(s.startsWith("."))s=s.substring(1);if(s.contains("://")||s.contains("/")||!s.contains("."))throw new IllegalArgumentException("过滤域名无效："+raw.trim());}
            else if(!"*".equals(s) && !s.matches("[a-z0-9_]+(\\.[a-z0-9_]+)+"))throw new IllegalArgumentException("包名无效："+raw.trim()); if(!out.contains(s))out.add(s); }
        if (!domain && out.contains("*")) { out.clear(); out.add("*"); }
        return out;
    }
    private static String join(List<String> values) { StringBuilder b=new StringBuilder(); for(String v:values){if(b.length()>0)b.append('\n');b.append(v);}return b.toString(); }
}
