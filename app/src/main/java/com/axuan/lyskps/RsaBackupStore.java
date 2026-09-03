package com.axuan.lyskps;

import android.content.Context;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Stores the two RSA blocks in app-private storage before the first patch. */
final class RsaBackupStore {
    private static final int MAGIC = 0x52534142; // RSAB
    private static final int VERSION = 1;
    private static final int LEN_2048 = 480;
    private static final int LEN_1024 = 243;

    static final class Backup {
        final long off2048;
        final long off1024;
        final byte[] block2048;
        final byte[] block1024;

        Backup(long off2048, long off1024, byte[] block2048, byte[] block1024) {
            this.off2048 = off2048;
            this.off1024 = off1024;
            this.block2048 = block2048;
            this.block1024 = block1024;
        }
    }

    static File path(Context context) {
        return new File(new File(context.getFilesDir(), "backups/rsa"), "global-metadata.rsa.bak");
    }

    static synchronized boolean saveIfAbsent(Context context, long off2048, long off1024,
                                             byte[] combined) throws IOException {
        File target = path(context);
        if (target.isFile()) {
            load(context);
            return false;
        }
        if (combined == null || combined.length != LEN_2048 + LEN_1024) {
            throw new IOException("读取到的 RSA 备份长度异常");
        }
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("无法创建 RSA 备份目录");
        }
        File temporary = new File(parent, target.getName() + ".tmp");
        try (FileOutputStream file = new FileOutputStream(temporary);
             DataOutputStream output = new DataOutputStream(file)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeLong(off2048);
            output.writeLong(off1024);
            output.writeInt(LEN_2048);
            output.writeInt(LEN_1024);
            output.write(combined);
            output.flush();
            file.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("无法提交 RSA 备份文件");
        }
        load(context);
        return true;
    }

    static synchronized Backup load(Context context) throws IOException {
        File target = path(context);
        if (!target.isFile()) throw new IOException("找不到 RSA 自动备份文件");
        try (DataInputStream input = new DataInputStream(new FileInputStream(target))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("RSA 备份格式无效");
            }
            long off2048 = input.readLong();
            long off1024 = input.readLong();
            int len2048 = input.readInt();
            int len1024 = input.readInt();
            if (off2048 < 0 || off1024 < 0 || len2048 != LEN_2048 || len1024 != LEN_1024) {
                throw new IOException("RSA 备份元数据无效");
            }
            byte[] combined = new byte[len2048 + len1024];
            input.readFully(combined);
            if (input.read() != -1) throw new IOException("RSA 备份尾部存在多余数据");
            return new Backup(off2048, off1024,
                    Arrays.copyOfRange(combined, 0, len2048),
                    Arrays.copyOfRange(combined, len2048, combined.length));
        }
    }

    private RsaBackupStore() {}
}
