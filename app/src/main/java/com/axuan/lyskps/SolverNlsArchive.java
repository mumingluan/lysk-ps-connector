package com.axuan.lyskps;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.tukaani.xz.XZInputStream;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.zip.CRC32;

/** Validates a Solver method-14 archive and extracts its single runtime NX member. */
final class SolverNlsArchive {
    private static final long LOCAL_HEADER = 0x04034b50L;
    private static final long CENTRAL_HEADER = 0x02014b50L;
    private static final long END_HEADER = 0x06054b50L;
    private static final int MAX_COMPRESSED_NX = 64 * 1024 * 1024;

    static final class Prepared {
        final File zip;
        final File nx;
        final String zipName;
        final String nxName;

        Prepared(File zip, File nx, String zipName, String nxName) {
            this.zip = zip;
            this.nx = nx;
            this.zipName = zipName;
            this.nxName = nxName;
        }
    }

    static Prepared prepare(Context context, Uri source) throws Exception {
        String zipName = displayName(context, source);
        if (zipName == null || !zipName.matches("[0-9]+\\.zip")) {
            throw new IllegalArgumentException("请选择 Solver 输出的数字命名 ZIP 文件");
        }
        File directory = new File(context.getCacheDir(), "nls_patch");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建 NLS 临时目录");
        }
        File zip = new File(directory, zipName);
        try (InputStream input = context.getContentResolver().openInputStream(source);
             FileOutputStream output = new FileOutputStream(zip)) {
            if (input == null) throw new IllegalArgumentException("无法打开所选 ZIP");
            copy(input, output);
            output.flush();
            output.getFD().sync();
        }
        if (zip.length() <= 0) throw new IllegalArgumentException("所选 ZIP 为空");
        String nxName = zipName.substring(0, zipName.length() - 4) + ".nx";
        File nx = new File(directory, nxName);
        extractSingleNx(zip, nx, nxName);
        return new Prepared(zip, nx, zipName, nxName);
    }

    static void extractSingleNx(File archive, File output, String expectedName) throws Exception {
        int found = 0;
        try (RandomAccessFile input = new RandomAccessFile(archive, "r")) {
            while (input.getFilePointer() + 4 <= input.length()) {
                long signature = readU32(input);
                if (signature == CENTRAL_HEADER || signature == END_HEADER) break;
                if (signature != LOCAL_HEADER) throw new IllegalArgumentException("ZIP 本地文件头无效");
                readU16(input); // version
                int flags = readU16(input);
                int method = readU16(input);
                readU16(input); readU16(input); // time/date
                long crc = readU32(input);
                long compressedSize = readU32(input);
                long uncompressedSize = readU32(input);
                int nameLength = readU16(input);
                int extraLength = readU16(input);
                if ((flags & 0x0001) != 0) throw new IllegalArgumentException("不支持加密 NLS ZIP");
                if (uncompressedSize > 256L * 1024 * 1024) throw new IllegalArgumentException("NLS 成员解压大小超出限制");
                if ((flags & 0x0008) != 0) {
                    throw new IllegalArgumentException("ZIP 使用了不支持的数据描述符");
                }
                byte[] nameBytes = new byte[nameLength];
                input.readFully(nameBytes);
                String name = new String(nameBytes, (flags & 0x0800) != 0
                        ? java.nio.charset.StandardCharsets.UTF_8
                        : java.nio.charset.StandardCharsets.US_ASCII);
                input.seek(input.getFilePointer() + extraLength);
                if (compressedSize < 0 || compressedSize > MAX_COMPRESSED_NX
                        || input.getFilePointer() + compressedSize > input.length()) {
                    throw new IllegalArgumentException("ZIP 成员压缩长度异常");
                }
                if (name.endsWith(".nx")) {
                    found++;
                    if (!name.equals(expectedName) || name.contains("/") || name.contains("\\")) {
                        throw new IllegalArgumentException("ZIP 与 NX 数字包名不一致");
                    }
                    if (method != 14) throw new IllegalArgumentException("NX 不是 Solver method-14 格式");
                    byte[] compressed = new byte[(int) compressedSize];
                    input.readFully(compressed);
                    CRC32 checksum = new CRC32();
                    long written = 0;
                    try (XZInputStream decoded = new XZInputStream(new ByteArrayInputStream(compressed));
                         FileOutputStream target = new FileOutputStream(output)) {
                        byte[] buffer = new byte[128 * 1024];
                        int count;
                        while ((count = decoded.read(buffer)) != -1) {
                            if(written+count>uncompressedSize)throw new IllegalArgumentException("NX 解压大小超出声明长度");
                            target.write(buffer, 0, count);
                            checksum.update(buffer, 0, count);
                            written += count;
                        }
                        target.flush();
                        target.getFD().sync();
                    }
                    if (written != uncompressedSize || checksum.getValue() != crc) {
                        output.delete();
                        throw new IllegalArgumentException("NX 解压后的大小或 CRC 校验失败");
                    }
                } else {
                    input.seek(input.getFilePointer() + compressedSize);
                }
            }
        }
        if (found != 1 || !output.isFile() || output.length() <= 0) {
            output.delete();
            throw new IllegalArgumentException("ZIP 中必须恰好包含一个同名 NX");
        }
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        }
        return uri.getLastPathSegment();
    }

    private static int readU16(RandomAccessFile input) throws Exception {
        int first = input.read();
        int second = input.read();
        if ((first | second) < 0) throw new IllegalArgumentException("ZIP 头被截断");
        return first | (second << 8);
    }

    private static long readU32(RandomAccessFile input) throws Exception {
        return (readU16(input) & 0xffffL) | ((readU16(input) & 0xffffL) << 16);
    }

    private static void copy(InputStream input, FileOutputStream output) throws Exception {
        byte[] buffer = new byte[128 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private SolverNlsArchive() {}
}
