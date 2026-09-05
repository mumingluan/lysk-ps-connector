package com.axuan.lyskps;
import org.junit.Test;import static org.junit.Assert.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
public class RsaLocatorTest {
 static byte[] block(int size,char fill){String start="<RSAKeyValue><Modulus>",end="</Modulus><Exponent>AQAB</Exponent></RSAKeyValue>";char[] pad=new char[size-start.length()-end.length()];Arrays.fill(pad,fill);return(start+new String(pad)+end).getBytes(StandardCharsets.US_ASCII);}
 static File metadata(int offset,int pairs)throws Exception{File f=File.createTempFile("rsa-test-",".dat");f.deleteOnExit();try(FileOutputStream o=new FileOutputStream(f)){o.write(new byte[offset]);for(int i=0;i<pairs;i++){o.write(block(480,'A'));o.write(block(243,'B'));}}return f;}
 @Test public void findsPairAcrossBufferBoundaryAndMasksOnlyKeys()throws Exception{File f=metadata(65500,1);long[] pos=RsaLocator.locate(f);assertEquals(65500,pos[0]);assertEquals(65980,pos[1]);String before=RsaLocator.fingerprint(f,pos);try(RandomAccessFile out=new RandomAccessFile(f,"rw")){out.seek(pos[0]);out.write(block(480,'C'));}assertEquals(before,RsaLocator.fingerprint(f,pos));try(RandomAccessFile out=new RandomAccessFile(f,"rw")){out.write(1);}assertNotEquals(before,RsaLocator.fingerprint(f,pos));}
 @Test public void rejectsAmbiguousAndMissingPairs()throws Exception{for(int n:new int[]{0,2})try{RsaLocator.locate(metadata(200,n));fail("accepted "+n);}catch(IOException expected){}}
}
