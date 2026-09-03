package com.axuan.lyskps;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
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
                    if (result != null && result.startsWith("OK\n")) {
                        replacePairThroughShizuku(prepared.zip, prepared.nx, zipName, nxName);
                        result = "OK\nNLS ZIP/NX 已替换并通过 shell 回读校验";
                    }
                } else if (mode == MODE_RESTORE_BACKUP) {
                    replacePairThroughShizuku(backupFile(app, zipName),
                            backupFile(app, nxName), zipName, nxName);
                    result = "OK\nNLS ZIP/NX 已从 connector 私有备份还原";
                } else {
                    runRemote(new String[]{"sh", "-c",
                            "am force-stop com.papegames.lysk.cn && rm -f \"$1\" \"$2\"",
                            "sh", targetZip(zipName), targetNx(nxName)}, null);
                    result = "OK\nNLS ZIP/NX 已删除";
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

    private static void replacePairThroughShizuku(File zip, File nx,
                                                   String zipName, String nxName) throws Exception {
        validateNames(zipName, nxName);
        if (!zip.isFile() || zip.length() <= 0 || !nx.isFile() || nx.length() <= 0) {
            throw new IOException("NLS ZIP/NX 源文件不完整");
        }
        runRemote(new String[]{"am", "force-stop", "com.papegames.lysk.cn"}, null);
        String token = Long.toUnsignedString(System.nanoTime());
        replaceOne(zip, "/storage/emulated/0/Download/LYSKPS_" + token + "_" + zipName,
                targetZip(zipName));
        replaceOne(nx, "/storage/emulated/0/Download/LYSKPS_" + token + "_" + nxName,
                targetNx(nxName));
    }

    private static void replaceOne(File source, String stage, String target) throws Exception {
        String command = "{ rm -f \"$1\"; cat > \"$1\"; chmod 660 \"$1\"; "
                + "mv -f \"$1\" \"$2\"; test -s \"$2\"; } 2>&1";
        try (InputStream input = new FileInputStream(source)) {
            runRemote(new String[]{"sh", "-c", command, "sh", stage, target}, input);
        } catch (Throwable t) {
            try { runRemote(new String[]{"rm", "-f", stage}, null); }
            catch (Throwable ignored) {}
            throw t;
        }
    }

    /** Invokes Shizuku's server-side process API retained for API compatibility. */
    private static String runRemote(String[] command, InputStream stdin) throws Exception {
        Method method = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        method.setAccessible(true);
        final Process process;
        try {
            process = (Process) method.invoke(null, command, null, null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
        try {
            try (OutputStream output = process.getOutputStream()) {
                if (stdin != null) copy(stdin, output);
            }
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            try (InputStream output = process.getInputStream();
                 InputStream error = process.getErrorStream()) {
                copy(output, captured);
                copy(error, captured);
            }
            int code = process.waitFor();
            String text = new String(captured.toByteArray(), StandardCharsets.UTF_8).trim();
            if (code != 0) throw new IOException("Shizuku shell exit=" + code
                    + (text.isEmpty() ? "" : "：" + text));
            return text;
        } finally {
            process.destroy();
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private static void validateNames(String zipName, String nxName) {
        if (zipName == null || nxName == null || !zipName.matches("[0-9]+\\.zip")
                || !nxName.matches("[0-9]+\\.nx")
                || !zipName.substring(0, zipName.length() - 4)
                .equals(nxName.substring(0, nxName.length() - 3))) {
            throw new IllegalArgumentException("NLS ZIP/NX 文件名不安全或不匹配");
        }
    }

    private static String targetZip(String name) {
        return "/storage/emulated/0/Android/data/com.papegames.lysk.cn/files/XFileZip/" + name;
    }

    private static String targetNx(String name) {
        return "/storage/emulated/0/Android/data/com.papegames.lysk.cn/files/XPackage/" + name;
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
