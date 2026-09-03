package com.axuan.lyskps;

import org.bouncycastle.util.encoders.Base64;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RsaPublicKeyImporterTest {
    @Test
    public void parsesSeparatePemFilesAndPreservesFixedBlockLengths() throws Exception {
        assertPemAndBlock(1024, 243);
        assertPemAndBlock(2048, 480);
    }

    private static void assertPemAndBlock(int bits, int blockLength) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        KeyPair pair = generator.generateKeyPair();
        String body = new String(Base64.encode(pair.getPublic().getEncoded()), StandardCharsets.US_ASCII);
        String pem = "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";

        List<RSAPublicKey> keys = RsaPublicKeyImporter.parsePem(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        assertEquals(1, keys.size());
        assertEquals(bits, keys.get(0).getModulus().bitLength());

        byte[] block = Base64.decode(RsaPublicKeyImporter.formatBlock(keys.get(0), bits));
        String xml = new String(block, StandardCharsets.UTF_8);
        assertEquals(blockLength, block.length);
        assertTrue(xml.startsWith("<RSAKeyValue>"));
        assertTrue(xml.endsWith("</RSAKeyValue>"));
    }
}
