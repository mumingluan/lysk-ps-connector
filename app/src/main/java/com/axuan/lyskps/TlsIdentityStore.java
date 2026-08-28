package com.axuan.lyskps;

import android.content.Context;
import android.annotation.SuppressLint;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

/** 随机生成、持久化和导入客户端内置 TLS 包装器的 CA/Leaf 身份。 */
@SuppressLint("StaticFieldLeak") // singleton stores only getApplicationContext()
final class TlsIdentityStore {
    static final int CA_CERT=1, CA_KEY=2, LEAF_CERT=3, LEAF_KEY=4;
    static final class Material { final X509Certificate[] chain; final PrivateKey key; Material(X509Certificate[] c,PrivateKey k){chain=c;key=k;} }
    private static TlsIdentityStore instance;
    private final Context app;
    private final File dir;
    private final SecureRandom random=new SecureRandom();
    private X509Certificate caCert, fixedLeaf;
    private PrivateKey caKey, fixedLeafKey;
    private boolean importedLeaf;

    static synchronized TlsIdentityStore get(Context c) throws Exception {
        if(instance==null)instance=new TlsIdentityStore(c.getApplicationContext());
        return instance;
    }
    private TlsIdentityStore(Context c) throws Exception {app=c;dir=new File(c.getFilesDir(),"tls_identity");if(!dir.exists()&&!dir.mkdirs())throw new IOException("无法创建证书目录");loadOrCreate();}

    synchronized Material materialFor(String host) throws Exception {
        if(importedLeaf)return new Material(new X509Certificate[]{fixedLeaf,caCert},fixedLeafKey);
        KeyPair pair=keyPair();X509Certificate leaf=issueLeaf(host,pair.getPublic());
        return new Material(new X509Certificate[]{leaf,caCert},pair.getPrivate());
    }

    synchronized String importPart(int kind,InputStream input)throws Exception{
        byte[] data=readAll(input);File pending=file("pending_"+name(kind));write(pending,data);
        if(kind==CA_CERT||kind==CA_KEY){File cert=file("pending_ca.crt"),key=file("pending_ca.key");if(cert.exists()&&key.exists()){X509Certificate c=readCert(cert);PrivateKey k=readKey(key);verifyPair(c,k);write(file("ca.crt"),read(cert));write(file("ca.key"),k.getEncoded());cert.delete();key.delete();app.getSharedPreferences(VpnConfig.PREFS,0).edit().putBoolean("leaf_imported",false).apply();loadOrCreate();return "CA pair 已导入；默认 Leaf 已重新随机生成";}return "已暂存，继续选择配对文件";}
        File cert=file("pending_leaf.crt"),key=file("pending_leaf.key");if(cert.exists()&&key.exists()){X509Certificate c=readCert(cert);PrivateKey k=readKey(key);verifyPair(c,k);write(file("leaf.crt"),read(cert));write(file("leaf.key"),k.getEncoded());cert.delete();key.delete();app.getSharedPreferences(VpnConfig.PREFS,0).edit().putBoolean("leaf_imported",true).apply();loadOrCreate();return "Leaf pair 已导入并启用";}return "已暂存，继续选择配对文件";
    }

    synchronized void regenerate()throws Exception{for(File f:Objects.requireNonNull(dir.listFiles()))f.delete();app.getSharedPreferences(VpnConfig.PREFS,0).edit().putBoolean("leaf_imported",false).apply();loadOrCreate();VpnLog.i("CERT","已随机生成新的 CA pair 与 Leaf pair");}
    synchronized byte[] caBytes()throws Exception{return caCert.getEncoded();}
    synchronized byte[] caPemBytes()throws Exception{String raw=android.util.Base64.encodeToString(caCert.getEncoded(),android.util.Base64.NO_WRAP);StringBuilder body=new StringBuilder();for(int i=0;i<raw.length();i+=64){body.append(raw,i,Math.min(i+64,raw.length())).append('\n');}return ("-----BEGIN CERTIFICATE-----\n"+body+"-----END CERTIFICATE-----\n").getBytes(StandardCharsets.US_ASCII);}
    synchronized String androidHashFileName()throws Exception{byte[] h=MessageDigest.getInstance("MD5").digest(caCert.getSubjectX500Principal().getEncoded());long value=((long)h[0]&255)|(((long)h[1]&255)<<8)|(((long)h[2]&255)<<16)|(((long)h[3]&255)<<24);return String.format(Locale.ROOT,"%08x.0",value);}
    synchronized String status(){try{return "CA SHA-256  "+fingerprint(caCert)+"\nLeaf  "+(importedLeaf?"手动导入（固定）":"随机生成 + 按目标域名动态签发");}catch(Exception e){return "证书状态异常："+e.getMessage();}}

    private void loadOrCreate()throws Exception{
        File caC=file("ca.crt"),caK=file("ca.key");
        if(!caC.exists()||!caK.exists()){KeyPair pair=keyPair();X509Certificate cert=issueCA(pair);write(caC,cert.getEncoded());write(caK,pair.getPrivate().getEncoded());}
        caCert=readCert(caC);caKey=readKey(caK);verifyPair(caCert,caKey);
        importedLeaf=app.getSharedPreferences(VpnConfig.PREFS,0).getBoolean("leaf_imported",false);
        File leafC=file("leaf.crt"),leafK=file("leaf.key");
        if(!leafC.exists()||!leafK.exists()||!importedLeaf){KeyPair pair=keyPair();X509Certificate cert=issueLeaf(new String[]{"papegames.com","*.papegames.com"},pair.getPublic());write(leafC,cert.getEncoded());write(leafK,pair.getPrivate().getEncoded());importedLeaf=false;}
        fixedLeaf=readCert(leafC);fixedLeafKey=readKey(leafK);verifyPair(fixedLeaf,fixedLeafKey);
    }

    private X509Certificate issueCA(KeyPair pair)throws Exception{
        Date now=new Date();X500Name subject=new X500Name("CN=LYSK-PS-Connector Random CA "+Long.toHexString(random.nextLong())+",O=LYSK-PS-Connector");
        JcaX509v3CertificateBuilder b=new JcaX509v3CertificateBuilder(subject,new BigInteger(128,random),new Date(now.getTime()-3600_000L),new Date(now.getTime()+10L*365*24*3600_000L),subject,pair.getPublic());
        b.addExtension(Extension.basicConstraints,true,new BasicConstraints(1));b.addExtension(Extension.keyUsage,true,new KeyUsage(KeyUsage.keyCertSign|KeyUsage.cRLSign|KeyUsage.digitalSignature));
        return finish(b,pair.getPrivate(),pair.getPublic());
    }
    private X509Certificate issueLeaf(String host,PublicKey key)throws Exception{return issueLeaf(new String[]{host},key);}
    private X509Certificate issueLeaf(String[] hosts,PublicKey key)throws Exception{
        Date now=new Date();X500Name issuer=new X500Name(caCert.getSubjectX500Principal().getName()),subject=new X500Name("CN="+hosts[0]+",O=LYSK-PS-Connector Wrapper");
        JcaX509v3CertificateBuilder b=new JcaX509v3CertificateBuilder(issuer,new BigInteger(128,random),new Date(now.getTime()-3600_000L),new Date(Math.min(caCert.getNotAfter().getTime(),now.getTime()+365L*24*3600_000L)),subject,key);
        GeneralName[] names=new GeneralName[hosts.length];for(int i=0;i<hosts.length;i++)names[i]=new GeneralName(GeneralName.dNSName,hosts[i]);
        b.addExtension(Extension.subjectAlternativeName,false,new GeneralNames(names));b.addExtension(Extension.basicConstraints,true,new BasicConstraints(false));b.addExtension(Extension.keyUsage,true,new KeyUsage(KeyUsage.digitalSignature|KeyUsage.keyEncipherment));b.addExtension(Extension.extendedKeyUsage,false,new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        return finish(b,caKey,caCert.getPublicKey());
    }
    private X509Certificate finish(JcaX509v3CertificateBuilder b,PrivateKey signerKey,PublicKey verifyKey)throws Exception{ContentSigner s=new JcaContentSignerBuilder("SHA256withRSA").build(signerKey);X509Certificate c=new JcaX509CertificateConverter().getCertificate(b.build(s));c.verify(verifyKey);return c;}
    private static KeyPair keyPair()throws Exception{KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);return g.generateKeyPair();}
    private static void verifyPair(X509Certificate cert,PrivateKey key)throws Exception{byte[] test="lysk-pair-check".getBytes(StandardCharsets.UTF_8);Signature s=Signature.getInstance("SHA256withRSA");s.initSign(key);s.update(test);byte[] sig=s.sign();s.initVerify(cert.getPublicKey());s.update(test);if(!s.verify(sig))throw new GeneralSecurityException("证书与私钥不匹配");}
    private static X509Certificate readCert(File f)throws Exception{try(InputStream in=new FileInputStream(f)){return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in);}}
    private static PrivateKey readKey(File f)throws Exception{return readKey(read(f));}
    private static PrivateKey readKey(byte[] data)throws Exception{
        String text=new String(data,StandardCharsets.US_ASCII);
        if(text.contains("BEGIN")){try(PEMParser p=new PEMParser(new StringReader(text))){Object o=p.readObject();JcaPEMKeyConverter c=new JcaPEMKeyConverter();if(o instanceof PEMKeyPair)return c.getKeyPair((PEMKeyPair)o).getPrivate();if(o instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo)return c.getPrivateKey((org.bouncycastle.asn1.pkcs.PrivateKeyInfo)o);}}
        try{return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(data));}catch(Exception ignored){}
        RSAPrivateKey rsa=RSAPrivateKey.getInstance(ASN1Primitive.fromByteArray(data));org.bouncycastle.asn1.pkcs.PrivateKeyInfo info=new org.bouncycastle.asn1.pkcs.PrivateKeyInfo(new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption,DERNull.INSTANCE),rsa);return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(info.getEncoded()));
    }
    private static String fingerprint(X509Certificate c)throws Exception{byte[] h=MessageDigest.getInstance("SHA-256").digest(c.getEncoded());StringBuilder b=new StringBuilder();for(int i=0;i<8;i++){if(i>0)b.append(':');b.append(String.format(Locale.ROOT,"%02X",h[i]));}return b.toString();}
    private File file(String n){return new File(dir,n);}
    private static String name(int kind){switch(kind){case CA_CERT:return "ca.crt";case CA_KEY:return "ca.key";case LEAF_CERT:return "leaf.crt";case LEAF_KEY:return "leaf.key";default:throw new IllegalArgumentException();}}
    private static byte[] read(File f)throws IOException{try(InputStream in=new FileInputStream(f)){return readAll(in);}}
    private static byte[] readAll(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>=0)out.write(b,0,n);return out.toByteArray();}
    private static void write(File f,byte[] data)throws IOException{try(FileOutputStream out=new FileOutputStream(f,false)){out.write(data);out.getFD().sync();}}
}
