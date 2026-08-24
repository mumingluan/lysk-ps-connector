package com.axuan.lyskps;

import android.content.Context;
import android.os.Build;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.*;

/** 根据目标域名取得随机/导入身份，终止 TLS 后输出明文 HTTP。 */
final class RedirectTlsWrapper {
    private static final char[] PASSWORD="lysk-local".toCharArray();
    private final TlsIdentityStore identities;
    private final ConcurrentHashMap<String,SSLContext> contexts=new ConcurrentHashMap<>();
    RedirectTlsWrapper(Context app)throws Exception{identities=TlsIdentityStore.get(app);}

    SSLSocket wrap(java.net.Socket socket,String targetHost)throws Exception{
        String host=targetHost.toLowerCase(Locale.ROOT);SSLContext ssl=contexts.get(host);
        if(ssl==null){TlsIdentityStore.Material material=identities.materialFor(host);KeyStore store=KeyStore.getInstance("PKCS12");store.load(null,PASSWORD);store.setKeyEntry("leaf",material.key,PASSWORD,material.chain);KeyManagerFactory kmf=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());kmf.init(store,PASSWORD);ssl=SSLContext.getInstance("TLS");ssl.init(kmf.getKeyManagers(),null,new SecureRandom());SSLContext raced=contexts.putIfAbsent(host,ssl);if(raced!=null)ssl=raced;else VpnLog.i("CERT","已为 "+host+" 准备目标证书");}
        SSLSocket tls=(SSLSocket)ssl.getSocketFactory().createSocket(socket,socket.getInetAddress().getHostAddress(),socket.getPort(),false);tls.setUseClientMode(false);SSLParameters p=tls.getSSLParameters();if(Build.VERSION.SDK_INT>=29)p.setApplicationProtocols(new String[]{"http/1.1"});tls.setSSLParameters(p);tls.startHandshake();return tls;
    }
}
