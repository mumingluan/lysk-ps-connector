package com.axuan.lyskps;

import android.content.Context;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Shizuku UserService：以 Shizuku 的 shell/root 身份恢复或删除游戏 metadata。 */
public final class ShizukuRsaService extends IShizukuRsaService.Stub {
    private static final String TARGET = "com.papegames.lysk.cn";
    private static final String META = "/storage/emulated/0/Android/data/" + TARGET
            + "/files/il2cpp/Metadata/global-metadata.dat";
    private static final long OFF_2048 = 0x22aee2fL;
    private static final long OFF_1024 = 0x22af00fL;
    private Context context;

    /** Shizuku API 13 会优先使用带 Context 的构造器。 */
    @SuppressWarnings("unused")
    public ShizukuRsaService(Context context) {
        this.context = context;
    }

    /** 兼容旧实例化路径；没有 Context 时仅支持删除模式。 */
    @SuppressWarnings("unused")
    public ShizukuRsaService() {}

    @Override
    public String restore(boolean deleteMetadata) {
        try {
            CommandResult stopped = runCommand("am", "force-stop", TARGET);
            if (stopped.code != 0) {
                return fail("无法停止游戏，exit=" + stopped.code + suffix(stopped.output));
            }

            File metadata = new File(META);
            if (deleteMetadata) {
                if (!metadata.exists()) return ok("metadata 已不存在，下次启动会从原 APK 重新生成");
                if (!metadata.delete() || metadata.exists()) {
                    return fail("删除 global-metadata.dat 失败；当前 Shizuku 身份可能无权访问 Android/data");
                }
                return ok("已删除 global-metadata.dat；下次启动游戏会从原 APK 重新生成");
            }

            if (context == null) return fail("Shizuku UserService 未获得模块 Context");
            if (!metadata.isFile()) return fail("找不到 global-metadata.dat，可改用删除重建模式后直接启动游戏");

            // RSA 块使用代码内置默认值，不依赖高权限 UserService 读取模块 APK assets。
            byte[] official2048 = Config.orig2048Bytes();
            byte[] official1024 = Config.orig1024Bytes();
            String result = restorePair(metadata, official2048, official1024);
            if (!"ok".equals(result) && !"already".equals(result)) return fail(result);
            return ok("already".equals(result) ? "当前已经是官方 RSA" : "两处官方 RSA 已恢复");
        } catch (Throwable t) {
            return fail(t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    private static String restorePair(File metadata,
                                      byte[] official2048, byte[] official1024) throws Exception {
        if (official2048.length != 480 || official1024.length != 243) {
            return "内置 RSA 资产长度异常";
        }
        try (RandomAccessFile raf = new RandomAccessFile(metadata, "rws")) {
            long required = Math.max(OFF_2048 + official2048.length, OFF_1024 + official1024.length);
            if (raf.length() < required) return "metadata 长度不足，客户端版本可能已经变化";

            byte[] current2048 = readAt(raf, OFF_2048, official2048.length);
            byte[] current1024 = readAt(raf, OFF_1024, official1024.length);
            boolean officialA = Arrays.equals(current2048, official2048);
            boolean officialB = Arrays.equals(current1024, official1024);
            // 启动弹框允许用户修改私服公钥，因此这里不能只匹配默认私服块；
            // 只接受结构和长度正确的 RSA XML，未知版本仍然拒绝覆盖。
            if (!officialA && !looksLikeRsaBlock(current2048))
                return "2048 位公钥块格式异常，拒绝覆盖";
            if (!officialB && !looksLikeRsaBlock(current1024))
                return "1024 位公钥块格式异常，拒绝覆盖";
            if (officialA && officialB) return "already";

            if (!officialA) {
                raf.seek(OFF_2048);
                raf.write(official2048);
            }
            if (!officialB) {
                raf.seek(OFF_1024);
                raf.write(official1024);
            }
            try { raf.getFD().sync(); } catch (Throwable ignored) {}

            if (!Arrays.equals(readAt(raf, OFF_2048, official2048.length), official2048)
                    || !Arrays.equals(readAt(raf, OFF_1024, official1024.length), official1024)) {
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
