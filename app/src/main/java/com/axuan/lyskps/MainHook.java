package com.axuan.lyskps;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LYSK 私服模块入口。
 * 目标: com.papegames.lysk.cn
 *  1) RSA 公钥替换 —— 游戏启动后延迟改写已提取的 global-metadata.dat (mmap 共享页即时生效)
 *  2) 游戏内悬浮窗配置 RSA；网络接管由模块 APK 内的 VpnService 完成
 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String TARGET = "com.papegames.lysk.cn";
    public static final String MODULE_PKG = "com.axuan.lyskps";
    private static volatile boolean initialized = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpp) {
        if (!TARGET.equals(lpp.packageName) || !TARGET.equals(lpp.processName)) return;

        // 防止游戏多进程重复初始化
        synchronized (MainHook.class) {
            if (initialized) return;
            initialized = true;
        }

        log("module loaded, target=" + lpp.packageName);

        try {
            // Application.attach 在任何 SDK 初始化之前触发, 借此拿到游戏 Context
            XposedHelpers.findAndHookMethod(Application.class, "attach",
                    Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Context ctx = (Context) param.args[0];
                                SharedPreferences sp = ctx.getSharedPreferences(Config.PREFS, 0);
                                Config.load(sp);

                                RsaPatcher.start(ctx);
                                OverlayUi.install(lpp.classLoader, ctx, sp);
                            } catch (Throwable t) {
                                log("init failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("hook Application.attach failed", t);
        }
    }

    public static void log(String msg) {
        XposedBridge.log("[LYSK-PS] " + msg);
    }

    public static void log(String msg, Throwable t) {
        XposedBridge.log("[LYSK-PS] " + msg);
        XposedBridge.log(t);
    }
}
