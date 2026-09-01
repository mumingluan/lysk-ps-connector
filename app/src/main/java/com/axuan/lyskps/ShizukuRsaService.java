package com.axuan.lyskps;

import android.content.Context;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Shizuku UserService：以 Shizuku 的 shell/root 身份补丁、恢复 RSA 或重建游戏 il2cpp 数据。 */
public final class ShizukuRsaService extends IShizukuRsaService.Stub {
    private static final String TARGET = "com.papegames.lysk.cn";
    private static final String IL2CPP = "/storage/emulated/0/Android/data/" + TARGET
            + "/files/il2cpp";
    private static final String META = IL2CPP + "/Metadata/global-metadata.dat";
    private static final long OFF_2048 = 0x22aee2fL;
    private static final long OFF_1024 = 0x22af00fL;
    private Context context;

    /** Shizuku API 13 会优先使用带 Context 的构造器。 */
    @SuppressWarnings("unused")
    public ShizukuRsaService(Context context) {
        this.context = context;
    }

    /** 兼容旧实例化路径；没有 Context 时仍可补丁或重建 il2cpp。 */
    @SuppressWarnings("unused")
    public ShizukuRsaService() {}

    @Override
    public String patch(long off2048, long off1024,
                        byte[] replacement2048, byte[] replacement1024) {
        try {
            File metadata = new File(META);
            if (!metadata.isFile()) return fail("找不到 global-metadata.dat，请先让游戏生成该文件");
            String result = writePair(metadata, off2048, replacement2048,
                    off1024, replacement1024);
            if (!"ok".equals(result) && !"already".equals(result)) return fail(result);
            return ok("already".equals(result)
                    ? "当前已经是私服 RSA"
                    : "两处私服 RSA 已补丁并通过回读校验");
        } catch (Throwable t) {
            return fail(t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    @Override
    public String restore(boolean deleteIl2cpp) {
        try {
            CommandResult stopped = runCommand("am", "force-stop", TARGET);
            if (stopped.code != 0) {
                return fail("无法停止游戏，exit=" + stopped.code + suffix(stopped.output));
            }

            if (deleteIl2cpp) {
                File il2cpp = new File(IL2CPP);
                if (!il2cpp.exists()) return ok("il2cpp 目录已不存在，下次启动会自动重建");
                CommandResult removed = runCommand("rm", "-rf", IL2CPP);
                if (removed.code != 0 || il2cpp.exists()) {
                    return fail("删除 il2cpp 目录失败，exit=" + removed.code
                            + suffix(removed.output) + "；当前 Shizuku 身份可能无权访问 Android/data");
                }
                return ok("已删除 il2cpp 目录；下次启动游戏会自动重建");
            }

            File metadata = new File(META);
            if (context == null) return fail("Shizuku UserService 未获得客户端 Context");
            if (!metadata.isFile()) return fail("找不到 global-metadata.dat，可使用重建 il2cpp 后直接启动游戏");

            byte[] official2048 = Config.orig2048Bytes();
            byte[] official1024 = Config.orig1024Bytes();
            String result = writePair(metadata, OFF_2048, official2048, OFF_1024, official1024);
            if (!"ok".equals(result) && !"already".equals(result)) return fail(result);
            return ok("already".equals(result) ? "当前已经是官方 RSA" : "两处官方 RSA 已恢复");
        } catch (Throwable t) {
            return fail(t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    private static String writePair(File metadata, long off2048, byte[] desired2048,
                                    long off1024, byte[] desired1024) throws Exception {
        if (off2048 < 0 || off1024 < 0) return "RSA 偏移无效";
        if (desired2048 == null || desired1024 == null
                || desired2048.length != 480 || desired1024.length != 243
                || !looksLikeRsaBlock(desired2048) || !looksLikeRsaBlock(desired1024)) {
            return "RSA 数据格式或长度异常";
        }
        try (RandomAccessFile raf = new RandomAccessFile(metadata, "rws")) {
            long required = Math.max(off2048 + desired2048.length, off1024 + desired1024.length);
            if (raf.length() < required) return "metadata 长度不足，客户端版本可能已经变化";

            byte[] current2048 = readAt(raf, off2048, desired2048.length);
            byte[] current1024 = readAt(raf, off1024, desired1024.length);
            boolean desiredA = Arrays.equals(current2048, desired2048);
            boolean desiredB = Arrays.equals(current1024, desired1024);
            if (!desiredA && !looksLikeRsaBlock(current2048))
                return "2048 位公钥块格式异常，拒绝覆盖";
            if (!desiredB && !looksLikeRsaBlock(current1024))
                return "1024 位公钥块格式异常，拒绝覆盖";
            if (desiredA && desiredB) return "already";

            if (!desiredA) {
                raf.seek(off2048);
                raf.write(desired2048);
            }
            if (!desiredB) {
                raf.seek(off1024);
                raf.write(desired1024);
            }
            try { raf.getFD().sync(); } catch (Throwable ignored) {}

            if (!Arrays.equals(readAt(raf, off2048, desired2048.length), desired2048)
                    || !Arrays.equals(readAt(raf, off1024, desired1024.length), desired1024)) {
                return "写入后的回读校验失败";
            }
            return "ok";
        }
    }

    private static boolean looksLikeRsaBlock(byte[] value) {
        byte[] head = "<RSAKeyValue>".getBytes(StandardCharsets.UTF_8);
        byte[] tail = "</RSAKeyValue>".getBytes(StandardCharsets.UTF_8);
        if (value.length < head.length + tail.length) return false;
        for (int i = 0; i < head.length; i++) if (value[i] != head[i]) return false;
        int off = value.length - tail.length;
        for (int i = 0; i < tail.length; i++) if (value[off + i] != tail[i]) return false;
        return true;
    }

    private static byte[] readAt(RandomAccessFile raf, long offset, int length) throws Exception {
        byte[] value = new byte[length];
        raf.seek(offset);
        raf.readFully(value);
        return value;
    }

    private static CommandResult runCommand(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) > 0) captured.write(buffer, 0, n);
        }
        int code = process.waitFor();
        return new CommandResult(code,
                new String(captured.toByteArray(), StandardCharsets.UTF_8).trim());
    }

    private static String ok(String message) {
        return "OK\nuid=" + Os.getuid() + "\n" + message;
    }

    private static String fail(String message) {
        return "ERR\nuid=" + Os.getuid() + "\n" + message;
    }

    private static String suffix(String output) {
        return output == null || output.isEmpty() ? "" : "：" + output;
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null ? "无详细信息" : message;
    }

    @Override
    public void destroy() {
        context = null;
        System.exit(0);
    }

    private static final class CommandResult {
        final int code;
        final String output;
        CommandResult(int code, String output) { this.code = code; this.output = output; }
    }
}
