package com.axuan.lyskps;

import android.content.Context;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.RandomAccessFile;

import de.robv.android.xposed.XposedHelpers;

/**
 * RSA 公钥替换器。
 *
 * 原理: 游戏把 APK 内的 global-metadata.dat 提取到
 *   /storage/emulated/0/Android/data/com.papegames.lysk.cn/files/il2cpp/Metadata/global-metadata.dat
 * 并以共享方式 mmap。libtprt 在启动约 4s 时对【当时】的文件内容做完整性校验,
 * 之后不再复检; 客户端握手时才从映射页读取 RSA 公钥。
 * 因此: 启动后延迟 >4s 再原地覆写两处 <RSAKeyValue> XML 即可让客户端使用私服公钥,
 * 且完全不触碰 APK / 签名 (dd 实测路线的进程内版本, 免 root)。
 */
public final class RsaPatcher {

    private static volatile String state = "等待启动";   // 悬浮窗显示的状态

    public static String state() { return state; }

    private static void setState(String s) {
        state = s;
        OverlayUi.refreshStatus();
    }

    public static void start(final Context ctx) {
        if (!Config.rsaPatch) { state = "RSA:关闭"; return; }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                int delay = Config.patchDelayMs;
                if (delay < 4000) delay = 4000;      // 安全下限, 不与 libtprt 校验赛跑
                sleep(delay);
                patchLoop(ctx);
            }
        }, "lyskps-rsa");
        t.setDaemon(true);
        t.start();
    }

    private static void patchLoop(Context ctx) {
        File meta = metadataFile(ctx);
        byte[] bin2048 = readAsset(ctx, "replace_2048.bin");
        byte[] bin1024 = readAsset(ctx, "replace_1024.bin");
        if (bin2048 == null || bin1024 == null) { setState("RSA:资产缺失"); return; }

        for (int i = 0; i < 30; i++) {           // 最多重试 ~90s (等首次资源解包完成)
            if (!Config.rsaPatch) { setState("RSA:关闭"); return; }
            if (meta == null || !meta.exists()) { setState("RSA:等待解包..."); sleep(3000); continue; }
            try {
                String r1 = patchOne(meta, Config.parseHex(Config.off2048), bin2048, "2048");
                String r2 = patchOne(meta, Config.parseHex(Config.off1024), bin1024, "1024");
                boolean ok1 = "ok".equals(r1) || "already".equals(r1);
                boolean ok2 = "ok".equals(r2) || "already".equals(r2);
                if (ok1 && ok2) { setState("RSA:✓已生效"); return; }
                if (r1.startsWith("mismatch") || r2.startsWith("mismatch")) {
                    setState("RSA:偏移失效");     // 版本更新导致偏移漂移
                    return;
                }
                setState("RSA:" + r1 + "/" + r2);
            } catch (Throwable t) {
                MainHook.log("patch attempt " + i + " failed", t);
                setState("RSA:写入失败");
            }
            sleep(3000);
        }
        setState("RSA:超时未就绪");
    }

    /** @return ok | already | mismatch:<detail> | short | <error> */
    private static String patchOne(File meta, long off, byte[] replacement, String tag)
            throws Exception {
        RandomAccessFile raf = new RandomAccessFile(meta, "rws");
        try {
            long len = raf.length();
            if (len < off + replacement.length) return "short";

            byte[] cur = new byte[replacement.length];
            raf.seek(off);
            raf.readFully(cur);

            if (equalsBytes(cur, replacement)) return "already";

            // 必须确认当前内容是合法的 <RSAKeyValue>...</RSAKeyValue> 才动手
            if (!startsWith(cur, "<RSAKeyValue>".getBytes("UTF-8")))
                return "mismatch:no-xml-head@" + tag;
            if (!endsWith(cur, "</RSAKeyValue>".getBytes("UTF-8")))
                return "mismatch:no-xml-tail@" + tag;
            // 替换块自身也必须是完整 XML, 双保险防呆
            if (!startsWith(replacement, "<RSAKeyValue>".getBytes("UTF-8"))
                    || !endsWith(replacement, "</RSAKeyValue>".getBytes("UTF-8")))
                return "mismatch:bad-asset@" + tag;

            raf.seek(off);
            raf.write(replacement);
            try { raf.getFD().sync(); } catch (Throwable ignored) {}   // FUSE 可能不支持 sync
            MainHook.log("patched " + tag + " key at 0x" + Long.toHexString(off)
                    + " (" + replacement.length + "B)");
            return "ok";
        } finally {
            raf.close();
        }
    }

    private static File metadataFile(Context ctx) {
        // 优先用游戏自己的外部文件目录 (进程内即游戏身份, 无需 root/storage 权限)
        try {
            File f = new File(ctx.getExternalFilesDir(null),
                    "il2cpp/Metadata/global-metadata.dat");
            if (f.exists()) return f;
        } catch (Throwable ignored) {}
        // 回退到标准 FUSE 路径
        return new File("/storage/emulated/0/Android/data/"
                + MainHook.TARGET + "/files/il2cpp/Metadata/global-metadata.dat");
    }

    private static byte[] readAsset(Context gameCtx, String name) {
        // 关键: 游戏进程里的 ctx 是游戏自己的 Context, 它的 assets 里没有模块文件。
        // 必须通过 createPackageContext 切到模块自己的包再读。
        Throwable last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Context c = gameCtx;
                if (attempt == 1 || MainHook.TARGET.equals(c.getPackageName())) {
                    c = gameCtx.createPackageContext(MainHook.MODULE_PKG,
                            Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                }
                java.io.InputStream in = c.getAssets().open(name);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                return bo.toByteArray();
            } catch (Throwable t) { last = t; }
        }
        MainHook.log("readAsset " + name + " failed", last);
        return null;
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
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
