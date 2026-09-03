package com.axuan.lyskps;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/** 使用 Shizuku UserService 补丁/恢复 RSA，或删除 il2cpp 目录让游戏自行重建。 */
public final class OfficialRsaRestorer {
    public static final int MODE_RESTORE_BLOCKS = 1;
    public static final int MODE_DELETE_IL2CPP = 2;
    public static final int MODE_APPLY_PRIVATE = 3;
    public static final int MODE_RESTORE_BACKUP = 4;
    private static final Set<Operation> ACTIVE_OPERATIONS = Collections.newSetFromMap(
            new ConcurrentHashMap<Operation, Boolean>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void done(boolean success, String detail);
    }

    public static void restore(Context context, int mode, Callback callback) {
        execute(context, mode, 0, 0, null, null, callback);
    }

    public static void patch(Context context, long off2048, long off1024,
                             byte[] replacement2048, byte[] replacement1024,
                             Callback callback) {
        execute(context, MODE_APPLY_PRIVATE, off2048, off1024,
                replacement2048, replacement1024, callback);
    }

    public static void restoreBackup(Context context, Callback callback) {
        try {
            RsaBackupStore.Backup backup = RsaBackupStore.load(context.getApplicationContext());
            execute(context, MODE_RESTORE_BACKUP, backup.off2048, backup.off1024,
                    backup.block2048, backup.block1024, callback);
        } catch (Throwable t) {
            complete(callback, false, "读取 RSA 自动备份失败：" + message(t));
        }
    }

    private static void execute(Context context, int mode, long off2048, long off1024,
                                byte[] replacement2048, byte[] replacement1024,
                                Callback callback) {
        Context app = context.getApplicationContext();
        VpnLog.init(app);
        VpnLog.i("RSA", "提交 Shizuku 操作：" + actionName(mode));
        if (mode != MODE_RESTORE_BLOCKS && mode != MODE_DELETE_IL2CPP
                && mode != MODE_APPLY_PRIVATE && mode != MODE_RESTORE_BACKUP) {
            complete(callback, false, "未知 RSA 操作");
            return;
        }
        if ((mode == MODE_APPLY_PRIVATE || mode == MODE_RESTORE_BACKUP)
                && (replacement2048 == null || replacement1024 == null)) {
            complete(callback, false, "私服 RSA 数据为空");
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                complete(callback, false, "Shizuku 未运行或 Binder 尚未就绪");
                return;
            }
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 10) {
                complete(callback, false, "Shizuku 版本过旧，请升级 Shizuku");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                complete(callback, false, "尚未授予 Shizuku 权限");
                return;
            }

            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(app.getPackageName(), ShizukuRsaService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("rsa_file")
                    .debuggable(false)
                    .version(5);
            Operation operation = new Operation(app, args, mode, off2048, off1024,
                    replacement2048, replacement1024, callback);
            ACTIVE_OPERATIONS.add(operation);
            try {
                Shizuku.bindUserService(args, operation);
                MAIN.postDelayed(operation.timeoutTask, 30_000L);
            } catch (Throwable t) {
                operation.finish(false, "启动 Shizuku 服务失败：" + message(t));
            }
        } catch (Throwable t) {
            complete(callback, false, "启动 Shizuku 服务失败：" + message(t));
        }
    }

    private static final class Operation implements ServiceConnection {
        private final Context app;
        private final Shizuku.UserServiceArgs args;
        private final int mode;
        private final long off2048;
        private final long off1024;
        private final byte[] replacement2048;
        private final byte[] replacement1024;
        private final Callback callback;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final Runnable timeoutTask = this::timeout;

        Operation(Context app, Shizuku.UserServiceArgs args, int mode, long off2048, long off1024,
                  byte[] replacement2048, byte[] replacement1024, Callback callback) {
            this.app = app;
            this.args = args;
            this.mode = mode;
            this.off2048 = off2048;
            this.off1024 = off1024;
            this.replacement2048 = replacement2048;
            this.replacement1024 = replacement1024;
            this.callback = callback;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            MAIN.removeCallbacks(timeoutTask);
            if (binder == null || !binder.pingBinder()) {
                finish(false, "Shizuku 返回了无效的 UserService Binder");
                return;
            }
            VpnLog.i("RSA", "Shizuku UserService 已连接，开始执行：" + actionName(mode));
            new Thread(() -> {
                try {
                    IShizukuRsaService service = IShizukuRsaService.Stub.asInterface(binder);
                    String backupDetail = "";
                    if (mode == MODE_APPLY_PRIVATE) {
                        File backupPath = RsaBackupStore.path(app);
                        if (!backupPath.isFile()) {
                            byte[] current = service.readRsaBlocks(off2048, off1024);
                            boolean firstPatched = Arrays.equals(
                                    Arrays.copyOfRange(current, 0, replacement2048.length),
                                    replacement2048);
                            boolean secondPatched = Arrays.equals(
                                    Arrays.copyOfRange(current, replacement2048.length, current.length),
                                    replacement1024);
                            if (firstPatched || secondPatched) {
                                finish(false, "当前 RSA 已全部或部分补丁，但找不到修补前备份；拒绝创建无效备份");
                                return;
                            }
                            RsaBackupStore.saveIfAbsent(app, off2048, off1024, current);
                            backupDetail = "已保存修补前 RSA 备份；";
                        } else {
                            RsaBackupStore.load(app);
                            backupDetail = "已保留现有 RSA 备份；";
                        }
                    }
                    String result;
                    if (mode == MODE_APPLY_PRIVATE || mode == MODE_RESTORE_BACKUP) {
                        result = service.patch(off2048, off1024, replacement2048, replacement1024);
                    } else {
                        result = service.restore(mode == MODE_DELETE_IL2CPP);
                    }
                    boolean ok = result != null && result.startsWith("OK\n");
                    String detail = result == null ? "Shizuku 服务没有返回结果"
                            : result.replaceFirst("^(OK|ERR)\\n", "").replace('\n', '：');
                    finish(ok, backupDetail + detail);
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
            MAIN.removeCallbacks(timeoutTask);
            ACTIVE_OPERATIONS.remove(this);
            VpnLog.i(success ? "RSA" : "ERROR", detail);
            try { Shizuku.unbindUserService(args, this, true); }
            catch (Throwable ignored) {}
            callback(callback, success, detail);
        }

        private void timeout() {
            finish(false, "等待 Shizuku UserService 超时，操作未执行");
        }
    }

    private static void complete(Callback callback, boolean success, String detail) {
        VpnLog.i(success ? "RSA" : "ERROR", detail);
        callback(callback, success, detail);
    }

    private static String actionName(int mode) {
        switch (mode) {
            case MODE_APPLY_PRIVATE: return "立即补丁 RSA";
            case MODE_RESTORE_BACKUP: return "从自动备份还原 RSA";
            case MODE_RESTORE_BLOCKS: return "恢复官方 RSA 公钥";
            case MODE_DELETE_IL2CPP: return "删除并重建 il2cpp";
            default: return "未知操作 " + mode;
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
