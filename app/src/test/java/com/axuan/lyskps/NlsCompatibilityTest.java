package com.axuan.lyskps;
import org.junit.Test;import static org.junit.Assert.*;import java.io.*;
public class NlsCompatibilityTest {
 @Test public void acceptsSingleInPlaceKeyChange()throws Exception{byte[] original=new byte[1000],patched=original.clone();for(int i=80;i<96;i++)patched[i]=1;NlsCompatibility.verify(new ByteArrayInputStream(original),new ByteArrayInputStream(patched));}
 @Test public void rejectsDifferentBuildAndSize()throws Exception{byte[] a=new byte[1000],b=a.clone();b[2]=1;b[900]=1;for(byte[] bad:new byte[][]{b,new byte[999]})try{NlsCompatibility.verify(new ByteArrayInputStream(a),new ByteArrayInputStream(bad));fail();}catch(IOException expected){}}
}
