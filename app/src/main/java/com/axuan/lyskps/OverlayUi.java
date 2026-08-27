package com.axuan.lyskps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 游戏内悬浮状态条 + 设置弹窗 (GenshinProxy 风格)。
 * 启动后显示 8 秒: "LYSK-PS ▸ RSA 状态", 点击打开设置。
 */
public final class OverlayUi {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<TextView> statusView = new WeakReference<>(null);

    /** 由 RsaPatcher 等任意线程调用, 刷新悬浮条文字 */
    public static void refreshStatus() {
        if (statusView.get() == null) return;
        MAIN.post(() -> {
            TextView view = statusView.get();
            if (view != null) view.setText(statusText());
        });
    }

    private static String statusText() {
        return "→点我设置 LYSK-PS←\nRSA 公钥替换: " + RsaPatcher.state();
    }

    public static void install(final ClassLoader cl, final Context appCtx,
                               final SharedPreferences sp) {
        // 第一个 Activity 恢复时挂悬浮条 (拿它的 WindowManager 做 token)
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                    new XC_MethodHook() {
                        boolean once = true;
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!once) return;
                            once = false;
                            Activity act = (Activity) p.thisObject;
                            showOverlay(act);
                        }
                    });
        } catch (Throwable t) {
            MainHook.log("hook onResume failed", t);
        }
    }

    // ---------------- 悬浮条 ----------------

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static void showOverlay(final Activity act) {
        try {
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(0xf21e1f25);
            pill.setCornerRadius(dp(act, 24));
            pill.setStroke(dp(act, 1), 0xffd0bcff);
            box.setBackground(pill);
            box.setPadding(dp(act,14), dp(act,8), dp(act,14), dp(act,8));

            TextView view = new TextView(act);
            statusView = new WeakReference<>(view);
            view.setTextColor(0xffe6e1e5);
            view.setTextSize(12f);
            view.setTypeface(null, Typeface.BOLD);
            view.setGravity(Gravity.CENTER);
            view.setText(statusText());
            view.setOnClickListener(v -> showDialog(act,
                    act.getSharedPreferences(Config.PREFS, 0)));
            box.addView(view);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    dp(act, 240), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(act, 60);

            act.getWindowManager().addView(box, lp);

            // 8 秒后自动收起 (GenshinProxy 同款节奏)
            MAIN.postDelayed(() -> {
                try { act.getWindowManager().removeView(box); } catch (Throwable ignored) {}
                statusView.clear();
            }, 8000);
        } catch (Throwable t) {
            MainHook.log("showOverlay failed", t);
        }
    }

    // ---------------- 设置弹窗 ----------------

    private static Switch sw(Context c, String text, boolean checked) {
        Switch s = new Switch(c);
        s.setText(text);
        s.setChecked(checked);
        s.setTextColor(0xffe6e1e5);
        return s;
    }

    private static void showDialog(final Activity act, final SharedPreferences sp) {
        Config.load(sp);
        Context c = new ContextThemeWrapper(act, android.R.style.Theme_Material_Dialog_Alert);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act,20), dp(act,10), dp(act,20), 0);

        final Switch swRsa = sw(c, "RSA 公钥替换", Config.rsaPatch);
        root.addView(swRsa);

        AlertDialog dialog = new AlertDialog.Builder(c)
                .setTitle("LYSK-PS")
                .setView(root)
                .setPositiveButton("保存并重启游戏", (d, w) -> {
                    Config.rsaPatch = swRsa.isChecked();
                    if (!Config.edit(sp).commit()) {
                        Toast.makeText(act, "配置写入失败，未执行 RSA 状态切换", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!Config.rsaPatch) {
                        Toast.makeText(act, "正在恢复官方 RSA，完成后重启游戏", Toast.LENGTH_SHORT).show();
                        RsaPatcher.restoreOfficial(act, (ok, status) -> MAIN.post(() -> {
                            Toast.makeText(act, ok ? "官方 RSA 已恢复，即将重启游戏" : "官方 RSA 恢复失败：" + status,
                                    Toast.LENGTH_LONG).show();
                            if (ok) MAIN.postDelayed(() -> System.exit(0), 800L);
                        }));
                    } else {
                        Toast.makeText(act, "已保存，即将重启游戏", Toast.LENGTH_SHORT).show();
                        MAIN.postDelayed(() -> System.exit(0), 1200);
                    }
                })
                .setNeutralButton("RSA 设置", (d, w) -> advancedDialog(act, sp))
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(x -> styleDialog(dialog));
        dialog.show();
    }

    private static void advancedDialog(final Activity act, final SharedPreferences sp) {
        Context c = new ContextThemeWrapper(act, android.R.style.Theme_Material_Dialog_Alert);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act,20), dp(act,10), dp(act,20), 0);

        root.addView(label(c, "2048 位公钥偏移 (hex, 不带0x):"));
        final EditText et1 = darkEdit(c); et1.setSingleLine(true); et1.setText(Config.off2048); root.addView(et1);
        root.addView(label(c, "1024 位公钥偏移 (hex):"));
        final EditText et2 = darkEdit(c); et2.setSingleLine(true); et2.setText(Config.off1024); root.addView(et2);
        root.addView(label(c, "补丁延迟毫秒 (须 4000–120000):"));
        final EditText et3 = darkEdit(c); et3.setSingleLine(true); et3.setText(String.valueOf(Config.patchDelayMs)); root.addView(et3);
        root.addView(label(c, "私服 2048 RSA 块 Base64（留空使用内置默认值）:"));
        final EditText er1 = darkEdit(c); er1.setSingleLine(false); er1.setMinLines(2); er1.setText(Config.replace2048B64()); root.addView(er1);
        root.addView(label(c, "私服 1024 RSA 块 Base64（留空使用内置默认值）:"));
        final EditText er2 = darkEdit(c); er2.setSingleLine(false); er2.setMinLines(2); er2.setText(Config.replace1024B64()); root.addView(er2);

        AlertDialog dialog = new AlertDialog.Builder(c)
                .setTitle("RSA 设置")
                .setView(root)
                .setPositiveButton("保存并重启游戏", (d, w) -> {
                    try {
                        long o1 = Config.parseHex(et1.getText().toString());
                        long o2 = Config.parseHex(et2.getText().toString());
                        int delay = Integer.parseInt(et3.getText().toString().trim());
                        String r1 = er1.getText().toString().trim();
                        String r2 = er2.getText().toString().trim();
                        if (o1 < 0 || o2 < 0 || delay < 4000 || delay > 120000) throw new IllegalArgumentException("范围无效");
                        if (r1.isEmpty()) r1 = Config.replace2048B64();
                        if (r2.isEmpty()) r2 = Config.replace1024B64();
                        Config.off2048 = et1.getText().toString().trim();
                        Config.off1024 = et2.getText().toString().trim();
                        Config.patchDelayMs = delay;
                        Config.setRsaBlocks(sp, r1, r2);
                        if (!Config.edit(sp).commit()) throw new IllegalStateException("配置写入失败");
                    } catch (Throwable ex) {
                        Toast.makeText(act, "输入无效：请检查偏移、延迟和 Base64 RSA 块", Toast.LENGTH_LONG).show();
                        MAIN.postDelayed(() -> advancedDialog(act, sp), 300);
                        return;
                    }
                    Toast.makeText(act, "已保存，即将重启游戏", Toast.LENGTH_SHORT).show();
                    MAIN.postDelayed(() -> System.exit(0), 1200);
                })
                .setNegativeButton("返回", (d, w) -> showDialog(act, sp))
                .create();
        dialog.setOnShowListener(x -> styleDialog(dialog));
        dialog.show();
    }

    private static TextView label(Context c, String s) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextColor(0xffcac4d0);
        tv.setTextSize(11f);
        tv.setPadding(0, dp(c,8), 0, 0);
        return tv;
    }

    private static EditText darkEdit(Context c) {
        EditText e = new EditText(c);
        e.setTextColor(0xffe6e1e5);
        e.setHintTextColor(0xff938f99);
        return e;
    }

    private static void styleDialog(AlertDialog d) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff1e1f25);
        bg.setCornerRadius(dp(d.getContext(), 28));
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(bg);
        int purple = 0xffd0bcff;
        d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(purple);
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(purple);
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(purple);
    }
}
