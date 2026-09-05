package com.axuan.lyskps;
import java.io.*;
final class NlsCompatibility {
 static void verify(InputStream original,InputStream patched)throws IOException {
  long position=0,first=-1,last=-1;int a,b;
  while((a=original.read())!=-1){b=patched.read();if(b==-1)throw new IOException("NLS 原始 NX 与补丁长度不同，请用此客户端资源重新生成");if(a!=b){if(first<0)first=position;last=position;if(last-first>=128)throw new IOException("NLS 补丁与当前客户端资源不兼容：变化超出单一 AppKey 区域");}position++;}
  if(patched.read()!=-1||position==0)throw new IOException("NLS 原始 NX 与补丁长度不同或为空");
 }
 private NlsCompatibility(){}
}
