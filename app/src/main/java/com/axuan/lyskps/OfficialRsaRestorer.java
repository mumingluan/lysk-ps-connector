package com.axuan.lyskps;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/** 使用 Shizuku UserService 恢复官方 RSA，或删除 metadata 让游戏自行重新解包。 */
public final class OfficialRsaRestorer {
    public static final int MODE_RESTORE_BLOCKS = 1;
    public static final int MODE_DELETE_METADATA = 2;

    public interface Callback {
        void done(boolean success, String detail);
    }

    public static void restore(Context context, int mode, Callback callback) {
        Context app = context.getApplicationContext();
        if (mode != MODE_RESTORE_BLOCKS && mode != MODE_DELETE_METADATA) {
            callback(callback, false, "未知恢复模式");
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                callback(callback, false, "Shizuku 未运行或 Binder 尚未就绪");
                return;
            }
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 10) {
                callback(callback, false, "Shizuku 版本过旧，请升级 Shizuku");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                callback(callback, false, "尚未授予 Shizuku 权限");
                return;
            }

            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(app.getPackageName(), ShizukuRsaService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("rsa_restore")
                    .debuggable(false)
                    .version(1);
            Operation operation = new Operation(args,
                    mode == MODE_DELETE_METADATA, callback);
            Shizuku.bindUserService(args, operation);
        } catch (Throwable t) {
            callback(callback, false, "启动 Shizuku 服务失败：" + message(t));
        }
    }

    private static final class Operation implements ServiceConnection {
        private final Shizuku.UserServiceArgs args;
        private final boolean deleteMetadata;
        private final Callback callback;
        private final AtomicBoolean finished = new AtomicBoolean();

        Operation(Shizuku.UserServiceArgs args, boolean deleteMetadata, Callback callback) {
            this.args = args;
            this.deleteMetadata = deleteMetadata;
            this.callback = callback;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder == null || !binder.pingBinder()) {
                finish(false, "Shizuku 返回了无效的 UserService Binder");
                return;
            }
            new Thread(() -> {
                try {
                    IShizukuRsaService service = IShizukuRsaService.Stub.asInterface(binder);
                    String result = service.restore(deleteMetadata);
                    boolean ok = result != null && result.startsWith("OK\n");
                    String detail = result == null ? "Shizuku 服务没有返回结果"
                            : result.replaceFirst("^(OK|ERR)\\n", "").replace('\n', '：');
                    finish(ok, detail);
                } catch (Throwable t) {
                    finish(false, "Shizuku 文件操作失败：" + message(t));
                }
            }, "lyskps-shizuku-rsa").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            finish(false, "Shizuku UserService 意外断开");
        }

        private void finish(boolean success, String detail) {
            if (!finished.compareAndSet(false, true)) return;
            VpnLog.i(success ? "RSA" : "ERROR", detail);
            try { Shizuku.unbindUserService(args, this, true); }
            catch (Throwable ignored) {}
            callback(callback, success, detail);
        }
    }

    private static void callback(Callback callback, boolean success, String detail) {
        if (callback != null) callback.done(success, detail);
    }

    private static String message(Throwable t) {
        String value = t.getMessage();
        return value == null ? t.getClass().getSimpleName() : value;
    }

    private OfficialRsaRestorer() {}
}
