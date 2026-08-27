package com.axuan.lyskps;

import android.content.Context;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * RSA 公钥替换与恢复器。
 *
 * 游戏会把 APK 内的 global-metadata.dat 提取到外部 files 目录并以共享方式 mmap。
 * 因此写文件不仅会影响当前映射页，也会持久保留到下次启动。启用私服公钥时要等
 * libtprt 的启动完整性校验结束；关闭私服公钥时则必须尽快把持久文件恢复为官方块。
 */
public final class RsaPatcher {

    public interface Completion {
        void done(boolean success, String status);
    }

    private static final Object WRITE_LOCK = new Object();
    private static volatile String state = "等待启动";

    public static String state() { return state; }

    private static void setState(String s) {
        state = s;
        OverlayUi.refreshStatus();
    }

    /** 根据配置启动：启用时延迟打补丁，关闭时立即恢复官方公钥。 */
    public static void start(final Context ctx) {
        if (!Config.rsaPatch) {
            restoreOfficial(ctx, null);
            return;
        }
        Thread t = new Thread(() -> {
            int delay = Config.patchDelayMs;
            if (delay < 4000) delay = 4000; // 不与 libtprt 启动校验赛跑
            sleep(delay);
            if (!Config.rsaPatch) return;
            applyLoop(ctx, true);
        }, "lyskps-rsa-patch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 立即恢复官方公钥。用于游戏内关闭开关，以及下次启动时发现开关已关闭的场景。
     * 注意：如果用户先在 LSPosed 中彻底禁用模块，本方法没有执行机会。
     */
    public static void restoreOfficial(final Context ctx, final Completion completion) {
        setState("RSA:正在恢复官方...");
        Thread t = new Thread(() -> {
            boolean ok = applyLoop(ctx, false);
            if (completion != null) completion.done(ok, state);
        }, "lyskps-rsa-restore");
        t.setDaemon(true);
        t.start();
    }

    private static boolean applyLoop(Context ctx, boolean privateMode) {
        File meta = metadataFile(ctx);
        // RSA 块已内置到 Config，并在首次启动时写入游戏 prefs；不再依赖模块 APK assets。
        byte[] private2048 = Config.replace2048Bytes();
        byte[] private1024 = Config.replace1024Bytes();
        byte[] official2048 = Config.orig2048Bytes();
        byte[] official1024 = Config.orig1024Bytes();
        if (private2048 == null || private1024 == null
                || official2048 == null || official1024 == null) {
            setState("RSA:模块资产读取失败");
            return false;
        }

        if (!privateMode && (meta == null || !meta.exists())) {
            // 文件尚未从原版 APK 解包时没有遗留补丁可恢复。
            setState("RSA:✓官方（无需恢复）");
            return true;
        }

        int attempts = privateMode ? 30 : 40;
        long interval = privateMode ? 3000L : 100L;
        for (int i = 0; i < attempts; i++) {
            if (privateMode && !Config.rsaPatch) {
                setState("RSA:已取消，等待恢复");
                return false;
            }
            if (meta == null || !meta.exists()) {
                setState(privateMode ? "RSA:等待解包..." : "RSA:等待文件以恢复...");
                sleep(interval);
                continue;
            }
            try {
                byte[] desired2048 = privateMode ? private2048 : official2048;
                byte[] desired1024 = privateMode ? private1024 : official1024;
                byte[] expected2048 = privateMode ? official2048 : private2048;
                byte[] expected1024 = privateMode ? official1024 : private1024;
                String result;
                synchronized (WRITE_LOCK) {
                    result = applyPair(meta,
                            Config.parseHex(Config.off2048), desired2048, expected2048,
                            Config.parseHex(Config.off1024), desired1024, expected1024);
                }
                if ("ok".equals(result) || "already".equals(result)) {
                    setState(privateMode ? "RSA:✓私服已生效" : "RSA:✓官方已恢复");
                    MainHook.log(privateMode ? "private RSA keys active" : "official RSA keys restored");
                    return true;
                }
                if (result.startsWith("mismatch")) {
                    setState("RSA:偏移或版本不匹配");
                    MainHook.log("RSA apply refused: " + result);
                    return false;
                }
                setState("RSA:" + result);
            } catch (Throwable t) {
                MainHook.log((privateMode ? "patch" : "restore") + " attempt " + i + " failed", t);
                setState(privateMode ? "RSA:写入失败" : "RSA:恢复失败");
            }
            sleep(interval);
        }
        setState(privateMode ? "RSA:超时未就绪" : "RSA:恢复超时");
        return false;
    }

    /**
     * 两个块先全部校验，再执行写入，避免只改成功一半。
     * 当前块必须等于“目标值”或“另一模式的已知值”，否则拒绝覆盖。
     */
    private static String applyPair(File meta,
                                    long off2048, byte[] desired2048, byte[] expected2048,
                                    long off1024, byte[] desired1024, byte[] expected1024)
            throws Exception {
        if (!validXmlBlock(desired2048) || !validXmlBlock(desired1024)
                || !validXmlBlock(expected2048) || !validXmlBlock(expected1024)) {
            return "mismatch:bad-asset";
        }

        try (RandomAccessFile raf = new RandomAccessFile(meta, "rws")) {
            long required = Math.max(off2048 + desired2048.length, off1024 + desired1024.length);
            if (raf.length() < required) return "short";

            byte[] current2048 = readAt(raf, off2048, desired2048.length);
            byte[] current1024 = readAt(raf, off1024, desired1024.length);
            boolean already2048 = equalsBytes(current2048, desired2048);
            boolean already1024 = equalsBytes(current1024, desired1024);

            if (!already2048 && !equalsBytes(current2048, expected2048))
                return "mismatch:unknown-2048";
            if (!already1024 && !equalsBytes(current1024, expected1024))
                return "mismatch:unknown-1024";

            if (already2048 && already1024) return "already";
            if (!already2048) {
                raf.seek(off2048);
                raf.write(desired2048);
                MainHook.log("wrote 2048-bit RSA block at 0x" + Long.toHexString(off2048));
            }
            if (!already1024) {
                raf.seek(off1024);
                raf.write(desired1024);
                MainHook.log("wrote 1024-bit RSA block at 0x" + Long.toHexString(off1024));
            }
            try { raf.getFD().sync(); } catch (Throwable ignored) {}
            return "ok";
        }
    }

    private static byte[] readAt(RandomAccessFile raf, long offset, int length) throws Exception {
        byte[] out = new byte[length];
        raf.seek(offset);
        raf.readFully(out);
        return out;
    }

    private static boolean validXmlBlock(byte[] value) throws Exception {
        return startsWith(value, "<RSAKeyValue>".getBytes("UTF-8"))
                && endsWith(value, "</RSAKeyValue>".getBytes("UTF-8"));
    }

    private static File metadataFile(Context ctx) {
        try {
            File external = ctx.getExternalFilesDir(null);
            if (external != null) {
                File f = new File(external, "il2cpp/Metadata/global-metadata.dat");
                if (f.exists()) return f;
            }
        } catch (Throwable ignored) {}
        return new File("/storage/emulated/0/Android/data/"
                + MainHook.TARGET + "/files/il2cpp/Metadata/global-metadata.dat");
    }

    static boolean equalsBytes(byte[] a, byte[] b) {
        return java.util.Arrays.equals(a, b);
    }

    static boolean startsWith(byte[] a, byte[] p) {
        if (a.length < p.length) return false;
        for (int i = 0; i < p.length; i++) if (a[i] != p[i]) return false;
        return true;
    }

    static boolean endsWith(byte[] a, byte[] s) {
        if (a.length < s.length) return false;
        int off = a.length - s.length;
        for (int i = 0; i < s.length; i++) if (a[off + i] != s[i]) return false;
        return true;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private RsaPatcher() {}
}
