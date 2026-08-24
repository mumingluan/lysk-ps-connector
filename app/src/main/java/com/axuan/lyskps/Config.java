package com.axuan.lyskps;

/**
 * 模块配置。存储在游戏进程自己的 SharedPreferences 里
 * (借鉴 GenshinProxy 的做法: 模块 UI 跑在游戏进程内, 直接读写游戏命名空间的 prefs,
 *  避免 XSharedPreferences 的跨进程/SELinux 兼容问题)。
 *
 * v1.1: 移除无效的 Java URL/SSL Hook；网络分流改由模块 APK 的 VpnService 完成。
 */
public final class Config {
    public static final String PREFS = "lysk_ps_config";

    public static volatile boolean rsaPatch = true;
    /** RSA 补丁: metadata 内两处公钥的文件偏移 (十六进制字符串) */
    public static volatile String off2048 = "22aee2f";
    public static volatile String off1024 = "22af00f";
    /** 首次补丁前的延迟毫秒数 (需大于 libtprt 启动校验时刻 ~4s) */
    public static volatile int patchDelayMs = 6000;

    public static long parseHex(String s) {
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        return Long.parseLong(s, 16);
    }

    /** 从游戏进程 prefs 恢复配置 (attach 后调用一次; UI 修改时逐项同步) */
    public static void load(android.content.SharedPreferences sp) {
        rsaPatch     = sp.getBoolean("rsa_patch", rsaPatch);
        off2048      = sp.getString("off_2048", off2048);
        off1024      = sp.getString("off_1024", off1024);
        patchDelayMs = sp.getInt("patch_delay_ms", patchDelayMs);
    }

    public static android.content.SharedPreferences.Editor edit(android.content.SharedPreferences sp) {
        return sp.edit()
                .putBoolean("rsa_patch", rsaPatch)
                .putString("off_2048", off2048)
                .putString("off_1024", off1024)
                .putInt("patch_delay_ms", patchDelayMs);
    }
}
