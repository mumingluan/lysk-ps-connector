package com.axuan.lyskps;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Locate a unique adjacent XML key pair; reject ambiguous or incompatible metadata. */
final class RsaLocator {
 static final int FIRST=480,SECOND=243,TOTAL=723;
 static long[] locate(File file)throws Exception {
  List<Long> found=new ArrayList<>();byte[] buffer=new byte[65536+TOTAL];int carry=0;long base=0;
  try(InputStream in=new BufferedInputStream(new FileInputStream(file))){int n;while((n=in.read(buffer,carry,65536))!=-1){int total=carry+n;for(int i=0;i+TOTAL<=total;i++)if(buffer[i]=='<'&&valid(Arrays.copyOfRange(buffer,i,i+FIRST))&&valid(Arrays.copyOfRange(buffer,i+FIRST,i+TOTAL))){long offset=base+i;if(!found.contains(offset))found.add(offset);}int keep=Math.min(TOTAL-1,total);System.arraycopy(buffer,total-keep,buffer,0,keep);base+=total-keep;carry=keep;}}
  if(found.size()!=1)throw new IOException("需要唯一一组相邻 RSA 公钥，实际找到 "+found.size()+" 组");
  return new long[]{found.get(0),found.get(0)+FIRST};
 }
 static boolean valid(byte[] bytes){String s=new String(bytes,StandardCharsets.US_ASCII);return (bytes.length==FIRST||bytes.length==SECOND)&&s.startsWith("<RSAKeyValue>")&&s.endsWith("</RSAKeyValue>")&&s.contains("<Modulus>")&&s.contains("</Modulus>")&&s.contains("<Exponent>AQAB</Exponent>");}
 static String fingerprint(File file,long[] offsets)throws Exception {MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];long position=0;try(InputStream in=new FileInputStream(file)){int n;while((n=in.read(b))!=-1){for(int i=0;i<n;i++)if(position+i>=offsets[0]&&position+i<offsets[1]+SECOND)b[i]=0;md.update(b,0,n);position+=n;}}StringBuilder out=new StringBuilder();for(byte v:md.digest())out.append(String.format(Locale.ROOT,"%02x",v&255));return out.toString();}
}
