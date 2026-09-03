package com.axuan.lyskps;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.zip.CRC32;

public class SolverNlsArchiveTest {
    @Test
    public void extractsSolverMethod14NxAndVerifiesCrc() throws Exception {
        File directory = Files.createTempDirectory("nls-archive-test").toFile();
        File archive = new File(directory, "987654321.zip");
        File nx = new File(directory, "987654321.nx");
        byte[] expected = "obfuscated-nx-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeMethod14(archive, "987654321.nx", expected, false);

        SolverNlsArchive.extractSingleNx(archive, nx, "987654321.nx");

        assertArrayEquals(expected, Files.readAllBytes(nx.toPath()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBadCrc() throws Exception {
        File directory = Files.createTempDirectory("nls-archive-crc-test").toFile();
        File archive = new File(directory, "123.zip");
        writeMethod14(archive, "123.nx", new byte[]{1, 2, 3}, true);
        SolverNlsArchive.extractSingleNx(archive, new File(directory, "123.nx"), "123.nx");
    }

    private static void writeMethod14(File archive, String name, byte[] content, boolean badCrc)
            throws Exception {
        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        try (XZOutputStream xz = new XZOutputStream(compressedBytes, new LZMA2Options())) {
            xz.write(content);
        }
        byte[] compressed = compressedBytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(content);
        byte[] encodedName = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(archive))) {
            le32(output, 0x04034b50L);
            le16(output, 20);
            le16(output, 0x0800);
            le16(output, 14);
            le16(output, 0); le16(output, 0);
            le32(output, badCrc ? crc.getValue() + 1 : crc.getValue());
            le32(output, compressed.length);
            le32(output, content.length);
            le16(output, encodedName.length);
            le16(output, 0);
            output.write(encodedName);
            output.write(compressed);
        }
    }

    private static void le16(DataOutputStream output, int value) throws Exception {
        output.writeByte(value & 0xff);
        output.writeByte((value >>> 8) & 0xff);
    }

    private static void le32(DataOutputStream output, long value) throws Exception {
        le16(output, (int) value & 0xffff);
        le16(output, (int) (value >>> 16) & 0xffff);
    }
}
