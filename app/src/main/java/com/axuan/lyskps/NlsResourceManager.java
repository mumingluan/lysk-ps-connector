package com.axuan.lyskps;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/** Installs and restores the paired NLS XFileZip/NX resources through Shizuku. */
final class NlsResourceManager {
    static final int MODE_INSTALL = 11;
    static final int MODE_RESTORE_BACKUP = 12;
    static final int MODE_DELETE = 13;
    private static final String PREFS = "nls_resource_state";
    private static final String KEY_ZIP = "zip_name";
    private static final String KEY_NX = "nx_name";

    interface Callback {
        void done(boolean success, String detail);
    }

    static void install(Context context, SolverNlsArchive.Prepared prepared, Callback callback) {
        execute(context, MODE_INSTALL, prepared, prepared.zipName, prepared.nxName, callback);
    }

    static void restoreBackup(Context context, Callback callback) {
        String[] names = storedNames(context);
        if (names == null) {
            callback.done(false, "找不到 NLS 安装记录或自动备份");
            return;
        }
        File zip = backupFile(context, names[0]);
        File nx = backupFile(context, names[1]);
        if (!zip.isFile() || !nx.isFile()) {
            callback.done(false, "找不到 NLS ZIP/NX 自动备份文件");
            return;
        }
        execute(context, MODE_RESTORE_BACKUP, null, names[0], names[1], callback);
    }

    static void deleteInstalled(Context context, Callback callback) {
        String[] names = storedNames(context);
        if (names == null) {
            callback.done(false, "找不到已安装的 NLS ZIP/NX 记录");
            return;
        }
        execute(context, MODE_DELETE, null, names[0], names[1], callback);
    }

    private static void execute(Context context, int mode, SolverNlsArchive.Prepared prepared,
                                String zipName, String nxName, Callback callback) {
        Context app = context.getApplicationContext();
        try {
            if (!Shizuku.pingBinder()) throw new IllegalStateException("Shizuku 未运行或尚未连接");
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 10) {
                throw new IllegalStateException("Shizuku 版本过旧，请升级");
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                throw new IllegalStateException("尚未授予 Shizuku 权限");
            }
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(app.getPackageName(), ShizukuRsaService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("game_files")
                    .debuggable(false)
                    .version(4);
            Shizuku.bindUserService(args,
                    new Operation(app, args, mode, prepared, zipName, nxName, callback));
        } catch (Throwable t) {
            callback.done(false, message(t));
        }
    }

    private static final class Operation implements ServiceConnection {
        private final Context app;
        private final Shizuku.UserServiceArgs args;
        private final int mode;
        private final SolverNlsArchive.Prepared prepared;
        private final String zipName;
        private final String nxName;
        private final Callback callback;
        private final AtomicBoolean finished = new AtomicBoolean();

        Operation(Context app, Shizuku.UserServiceArgs args, int mode,
                  SolverNlsArchive.Prepared prepared, String zipName, String nxName,
                  Callback callback) {
            this.app = app;
            this.args = args;
            this.mode = mode;
            this.prepared = prepared;
            this.zipName = zipName;
            this.nxName = nxName;
            this.callback = callback;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder == null || !binder.pingBinder()) {
                finish(false, "Shizuku 返回了无效的 UserService Binder");
                return;
            }
            new Thread(() -> run(IShizukuRsaService.Stub.asInterface(binder)),
                    "lyskps-nls-files").start();
        }

        private void run(IShizukuRsaService service) {
            File temporaryZip = null;
            File temporaryNx = null;
            try {
                String result;
                if (mode == MODE_INSTALL) {
                    File finalZip = backupFile(app, zipName);
                    File finalNx = backupFile(app, nxName);
                    boolean zipExists = finalZip.isFile();
                    boolean nxExists = finalNx.isFile();
                    if (zipExists != nxExists) throw new IOException("NLS 自动备份不完整，拒绝覆盖");
                    ParcelFileDescriptor backupZip = null;
                    ParcelFileDescriptor backupNx = null;
                    if (!zipExists) {
                        File directory = backupDirectory(app);
                        temporaryZip = new File(directory, zipName + ".tmp");
                        temporaryNx = new File(directory, nxName + ".tmp");
                        backupZip = openWrite(temporaryZip);
                        backupNx = openWrite(temporaryNx);
                    }
                    try (ParcelFileDescriptor sourceZip = openRead(prepared.zip);
                         ParcelFileDescriptor sourceNx = openRead(prepared.nx);
                         ParcelFileDescriptor closeBackupZip = backupZip;
                         ParcelFileDescriptor closeBackupNx = backupNx) {
                        result = service.installNls(sourceZip, sourceNx, closeBackupZip,
                                closeBackupNx, zipName, nxName);
                    }
                    if (!zipExists) {
                        commitBackup(temporaryZip, finalZip);
                        commitBackup(temporaryNx, finalNx);
                        temporaryZip = null;
                        temporaryNx = null;
                    }
                } else if (mode == MODE_RESTORE_BACKUP) {
                    try (ParcelFileDescriptor zip = openRead(backupFile(app, zipName));
                         ParcelFileDescriptor nx = openRead(backupFile(app, nxName))) {
                        result = service.restoreNls(zip, nx, zipName, nxName);
                    }
                } else {
                    result = service.deleteNls(zipName, nxName);
                }
                boolean ok = result != null && result.startsWith("OK\n");
                String detail = result == null ? "Shizuku 服务没有返回结果"
                        : result.replaceFirst("^(OK|ERR)\\n", "").replace('\n', '：');
                if (mode == MODE_INSTALL && (ok
                        || (backupFile(app, zipName).isFile() && backupFile(app, nxName).isFile()))) {
                    saveNames(app, zipName, nxName);
                }
                finish(ok, detail);
            } catch (Throwable t) {
                finish(false, message(t));
            } finally {
                if (temporaryZip != null) temporaryZip.delete();
                if (temporaryNx != null) temporaryNx.delete();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            finish(false, "Shizuku UserService 意外断开");
        }

        private void finish(boolean success, String detail) {
            if (!finished.compareAndSet(false, true)) return;
            VpnLog.i(success ? "NLS" : "ERROR", detail);
            try { Shizuku.unbindUserService(args, this, true); }
            catch (Throwable ignored) {}
            callback.done(success, detail);
        }
    }

    private static File backupDirectory(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "backups/nls");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建 NLS 私有备份目录");
        }
        return directory;
    }

    private static File backupFile(Context context, String name) {
        return new File(new File(context.getFilesDir(), "backups/nls"), name + ".original");
    }

    private static ParcelFileDescriptor openRead(File file) throws IOException {
        if (!file.isFile() || file.length() <= 0) throw new IOException("找不到文件：" + file.getName());
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static ParcelFileDescriptor openWrite(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("无法创建备份目录");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE
                | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE);
    }

    private static void commitBackup(File temporary, File target) throws IOException {
        if (temporary == null || !temporary.isFile() || temporary.length() <= 0) {
            throw new IOException("Shizuku 未写入完整的 NLS 自动备份");
        }
        if (target.isFile()) return;
        if (!temporary.renameTo(target)) throw new IOException("无法提交 NLS 自动备份");
    }

    private static void saveNames(Context context, String zipName, String nxName) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ZIP, zipName).putString(KEY_NX, nxName).apply();
    }

    private static String[] storedNames(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String zip = preferences.getString(KEY_ZIP, null);
        String nx = preferences.getString(KEY_NX, null);
        if (zip == null || nx == null || !zip.matches("[0-9]+\\.zip")
                || !nx.matches("[0-9]+\\.nx")) return null;
        return new String[]{zip, nx};
    }

    private static String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null ? throwable.getClass().getSimpleName() : value;
    }

    private NlsResourceManager() {}
}
