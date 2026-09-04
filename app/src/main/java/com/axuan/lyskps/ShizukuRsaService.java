package com.axuan.lyskps;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Shizuku UserService：以 Shizuku 的 shell/root 身份补丁、恢复 RSA 或重建游戏 il2cpp 数据。 */
public final class ShizukuRsaService extends IShizukuRsaService.Stub {
    private static final String TARGET = "com.papegames.lysk.cn";
    private static final String IL2CPP = "/storage/emulated/0/Android/data/" + TARGET
            + "/files/il2cpp";
    private static final String META = IL2CPP + "/Metadata/global-metadata.dat";
    private static final String GAME_FILES = "/storage/emulated/0/Android/data/" + TARGET + "/files";
    private static final String XFILEZIP = GAME_FILES + "/XFileZip";
    private static final String XPACKAGE = GAME_FILES + "/XPackage";
    private static final String SHARED_STAGE = "/storage/emulated/0/Download";
    private static final String APK_METADATA =
            "assets/bin/Data/Managed/Metadata/global-metadata.dat";
    private static final long OFF_2048 = 0x22aee2fL;
    private static final long OFF_1024 = 0x22af00fL;
    private Context context;

    /** Shizuku API 13 会优先使用带 Context 的构造器。 */
    @SuppressWarnings("unused")
    public ShizukuRsaService(Context context) {
        this.context = context;
    }

    /** 兼容旧实例化路径；没有 Context 时仍可补丁或重建 il2cpp。 */
    @SuppressWarnings("unused")
    public ShizukuRsaService() {}

    @Override
    public String patch(long off2048, long off1024,
                        byte[] replacement2048, byte[] replacement1024) {
        try {
            File metadata = new File(META);
            if (!metadata.isFile()) return fail("找不到 global-metadata.dat，请先让游戏生成该文件");
            String stopped = stopGame();
            if (stopped != null) return fail(stopped);
            String result = writePair(metadata, off2048, replacement2048,
                    off1024, replacement1024);
            if (!"ok".equals(result) && !"already".equals(result)) return fail(result);
            return ok("already".equals(result)
                    ? "当前已经是私服 RSA"
                    : "两处私服 RSA 已补丁并通过回读校验");
        } catch (Throwable t) {
            return fail(t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    @Override
    public byte[] readRsaBlocks(long off2048, long off1024) {
        try {
            File metadata = new File(META);
            if (!metadata.isFile()) throw new IllegalStateException("找不到 global-metadata.dat");
            try (RandomAccessFile raf = new RandomAccessFile(metadata, "r")) {
                if (off2048 < 0 || off1024 < 0
                        || raf.length() < Math.max(off2048 + 480, off1024 + 243)) {
                    throw new IllegalStateException("RSA 偏移超出 metadata 范围");
                }
                byte[] first = readAt(raf, off2048, 480);
                byte[] second = readAt(raf, off1024, 243);
                if (!looksLikeRsaBlock(first) || !looksLikeRsaBlock(second)) {
                    throw new IllegalStateException("当前 RSA 公钥块格式异常，拒绝备份");
                }
                byte[] combined = new byte[first.length + second.length];
                System.arraycopy(first, 0, combined, 0, first.length);
                System.arraycopy(second, 0, combined, first.length, second.length);
                return combined;
            }
        } catch (Throwable t) {
            throw new IllegalStateException(safeMessage(t), t);
        }
    }

    @Override
    public String installNls(ParcelFileDescriptor sourceZip, ParcelFileDescriptor sourceNx,
                             ParcelFileDescriptor backupZip, ParcelFileDescriptor backupNx,
                             String zipName, String nxName) {
        try {
            validateNlsNames(zipName, nxName);
            if (sourceZip == null || sourceNx == null) return fail("NLS 补丁源文件为空");
            File targetZip = new File(XFILEZIP, zipName);
            File targetNx = new File(XPACKAGE, nxName);
            if (!targetZip.isFile() || !targetNx.isFile()) {
                return fail("找不到待替换的原始 ZIP/NX；请先启动游戏完成资源解包");
            }
            String stopped = stopGame();
            if (stopped != null) return fail(stopped);
            if ((backupZip == null) != (backupNx == null)) return fail("NLS 备份文件描述符不完整");
            if (backupZip != null) {
                copyFileToDescriptor(targetZip, backupZip);
                copyFileToDescriptor(targetNx, backupNx);
            }
            // Actual replacement is performed by a Shizuku server-side remote
            // process in the client. A UserService has uid 2000 but retains an
            // app_process scoped-storage mount and cannot move its own staged
            // files into another app's Android/data directory.
            return ok("NLS 原文件已校验并完成备份");
        } catch (Throwable t) {
            return fail(t.getClass().getSimpleName() + ": " + safeMessage(t));
        } finally {
            closeQuietly(sourceZip); closeQuietly(sourceNx);
            closeQuietly(backupZip); closeQuietly(backupNx);
        }
    }

    @Override
    public String restoreNls(ParcelFileDescriptor backupZip, ParcelFileDescriptor backupNx,
                             String zipName, String nxName) {
        try {
            validateNlsNames(zipName, nxName);
            if (backupZip == null || backupNx == null) return fail("找不到 NLS 自动备份文件");
            File targetZip = new File(XFILEZIP, zipName);
            File targetNx = new File(XPACKAGE, nxName);
            if (!targetZip.isFile() || !targetNx.isFile()) return fail("NLS 目标 ZIP/NX 不存在");
            String stopped = stopGame();
            if (stopped != null) return fail(stopped);
            replacePair(backupZip, backupNx, targetZip, targetNx);
            return ok("NLS ZIP/NX 已从 connector 私有备份还原");
        } catch (Throwable t) {
            return fail(t.getClass().getSimpleName() + ": " + safeMessage(t));
        } finally {
            closeQuietly(backupZip); closeQuietly(backupNx);
        }
    }

    @Override
    public String deleteNls(String zipName, String nxName) {
        try {
            validateNlsNames(zipName, nxName);
            String stopped = stopGame();
            if (stopped != null) return fail(stopped);
            File zip = new File(XFILEZIP, zipName);
            File nx = new File(XPACKAGE, nxName);
            CommandResult removed = runCommand("rm", "-f",
                    zip.getAbsolutePath(), nx.getAbsolutePath());
            if (removed.code != 0 || zip.exists() || nx.exists()) {
                return fail("删除 NLS ZIP/NX 失败，exit=" + removed.code
                        + suffix(removed.output));
            }
            return ok("NLS ZIP/NX 已删除；游戏后续可重新下载或展开官方资源");
        } catch (Throwable t) {
            return fail(t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    @Override
    public String restore(boolean deleteIl2cpp) {
        try {
            CommandResult stopped = runCommand("am", "force-stop", TARGET);
            if (stopped.code != 0) {
                return fail("无法停止游戏，exit=" + stopped.code + suffix(stopped.output));
            }

            if (deleteIl2cpp) {
                if (Os.getuid() != 0) return fail("删除 il2cpp 需要 Root 模式 Shizuku");
                File il2cpp = new File(IL2CPP);
                if (!il2cpp.exists()) return ok("il2cpp 目录已不存在，下次启动会自动重建");
                CommandResult removed = runCommand("rm", "-rf", IL2CPP);
                if (removed.code != 0 || il2cpp.exists()) {
                    return fail("删除 il2cpp 目录失败，exit=" + removed.code
                            + suffix(removed.output) + "；当前 Shizuku 身份可能无权访问 Android/data");
                }
                return ok("已删除 il2cpp 目录；下次启动游戏会自动重建");
            }

            File metadata = new File(META);
            if (context == null) return fail("Shizuku UserService 未获得客户端 Context");
            if (!metadata.isFile()) return fail("找不到 global-metadata.dat，可使用重建 il2cpp 后直接启动游戏");

            byte[] official2048 = Config.orig2048Bytes();
            byte[] official1024 = Config.orig1024Bytes();
            String result = writePair(metadata, OFF_2048, official2048, OFF_1024, official1024);
            if (!"ok".equals(result) && !"already".equals(result)) return fail(result);
            return ok("already".equals(result) ? "当前已经是官方 RSA" : "两处官方 RSA 已恢复");
        } catch (Throwable t) {
            return fail(t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    private static String writePair(File metadata, long off2048, byte[] desired2048,
                                    long off1024, byte[] desired1024) throws Exception {
        if (off2048 < 0 || off1024 < 0) return "RSA 偏移无效";
        if (desired2048 == null || desired1024 == null
                || desired2048.length != 480 || desired1024.length != 243
                || !looksLikeRsaBlock(desired2048) || !looksLikeRsaBlock(desired1024)) {
            return "RSA 数据格式或长度异常";
        }
        try (RandomAccessFile raf = new RandomAccessFile(metadata, "rws")) {
            long required = Math.max(off2048 + desired2048.length, off1024 + desired1024.length);
            if (raf.length() < required) return "metadata 长度不足，客户端版本可能已经变化";

            byte[] current2048 = readAt(raf, off2048, desired2048.length);
            byte[] current1024 = readAt(raf, off1024, desired1024.length);
            boolean desiredA = Arrays.equals(current2048, desired2048);
            boolean desiredB = Arrays.equals(current1024, desired1024);
            if (!desiredA && !looksLikeRsaBlock(current2048))
                return "2048 位公钥块格式异常，拒绝覆盖";
            if (!desiredB && !looksLikeRsaBlock(current1024))
                return "1024 位公钥块格式异常，拒绝覆盖";
            if (desiredA && desiredB) return "already";

            if (!desiredA) {
                raf.seek(off2048);
                raf.write(desired2048);
            }
            if (!desiredB) {
                raf.seek(off1024);
                raf.write(desired1024);
            }
            try { raf.getFD().sync(); } catch (Throwable ignored) {}

            if (!Arrays.equals(readAt(raf, off2048, desired2048.length), desired2048)
                    || !Arrays.equals(readAt(raf, off1024, desired1024.length), desired1024)) {
                return "写入后的回读校验失败";
            }
            return "ok";
        }
    }

    @Override
    public String restoreMetadataFromApk() {
        File staged = new File("/data/local/tmp/lyskps_metadata_"
                + Long.toUnsignedString(System.nanoTime()) + ".tmp");
        try {
            if (context == null) return fail("Shizuku UserService 未获得客户端 Context");
            File metadata = new File(META);
            if (!metadata.isFile()) return fail("找不到现有 global-metadata.dat，请先启动游戏完成解包");

            ApkMetadataSource source = findApkMetadata();
            long expectedSize = source.entry.getSize();
            if (expectedSize < 1024 * 1024L) {
                source.close();
                return fail("游戏 APK 内的 metadata 大小异常");
            }
            try {
                extractAndVerify(source, staged);
            } finally {
                source.close();
            }

            // 先验证现有文件可写，避免打开输出流后才发现权限不足。
            try (RandomAccessFile ignored = new RandomAccessFile(metadata, "rw")) {}
            String stopped = stopGame();
            if (stopped != null) return fail(stopped);

            try (InputStream input = new BufferedInputStream(new FileInputStream(staged), 1024 * 1024);
                 FileOutputStream fileOutput = new FileOutputStream(metadata, false);
                 OutputStream output = new BufferedOutputStream(fileOutput, 1024 * 1024)) {
                copy(input, output);
                output.flush();
                try { fileOutput.getFD().sync(); } catch (Throwable ignored) {}
            }
            if (metadata.length() != expectedSize) {
                return fail("写入后的 metadata 大小校验失败");
            }
            return ok("已从游戏 APK 重写 global-metadata.dat（"
                    + formatMiB(expectedSize) + " MiB）");
        } catch (Throwable t) {
            return fail(t.getClass().getSimpleName() + ": " + safeMessage(t));
        } finally {
            try { if (staged.exists()) staged.delete(); } catch (Throwable ignored) {}
        }
    }

    private ApkMetadataSource findApkMetadata() throws Exception {
        ApplicationInfo info = context.getPackageManager().getApplicationInfo(TARGET, 0);
        String[] paths;
        if (info.splitSourceDirs == null || info.splitSourceDirs.length == 0) {
            paths = new String[]{info.sourceDir};
        } else {
            paths = new String[info.splitSourceDirs.length + 1];
            paths[0] = info.sourceDir;
            System.arraycopy(info.splitSourceDirs, 0, paths, 1, info.splitSourceDirs.length);
        }
        for (String path : paths) {
            ZipFile zip = new ZipFile(path);
            ZipEntry entry = zip.getEntry(APK_METADATA);
            if (entry != null && !entry.isDirectory()) return new ApkMetadataSource(zip, entry);
            zip.close();
        }
        for (String path : paths) {
            ZipFile zip = new ZipFile(path);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith("/global-metadata.dat")) {
                    return new ApkMetadataSource(zip, entry);
                }
            }
            zip.close();
        }
        throw new IllegalStateException("游戏 APK 内找不到原始 global-metadata.dat");
    }

    private static void extractAndVerify(ApkMetadataSource source, File destination)
            throws Exception {
        CRC32 crc = new CRC32();
        long written = 0;
        try (InputStream input = new BufferedInputStream(
                source.zip.getInputStream(source.entry), 1024 * 1024);
             OutputStream output = new BufferedOutputStream(
                     new FileOutputStream(destination, false), 1024 * 1024)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                crc.update(buffer, 0, count);
                written += count;
            }
        }
        if (written != source.entry.getSize() || destination.length() != written) {
            throw new IllegalStateException("APK metadata 解压大小校验失败");
        }
        long expectedCrc = source.entry.getCrc();
        if (expectedCrc != -1 && crc.getValue() != expectedCrc) {
            throw new IllegalStateException("APK metadata CRC 校验失败");
        }
    }

    private static String formatMiB(long bytes) {
        return String.format(java.util.Locale.US, "%.1f", bytes / 1048576.0);
    }

    private static void validateNlsNames(String zipName, String nxName) {
        if (zipName == null || nxName == null
                || !zipName.matches("[0-9]+\\.zip") || !nxName.matches("[0-9]+\\.nx")
                || !zipName.substring(0, zipName.length() - 4)
                .equals(nxName.substring(0, nxName.length() - 3))) {
            throw new IllegalArgumentException("NLS ZIP/NX 文件名不匹配或不安全");
        }
    }

    private static String stopGame() throws Exception {
        CommandResult stopped = runCommand("am", "force-stop", TARGET);
        return stopped.code == 0 ? null
                : "无法停止游戏，exit=" + stopped.code + suffix(stopped.output);
    }

    private static void copyFileToDescriptor(File source, ParcelFileDescriptor destination)
            throws Exception {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination.getFileDescriptor())) {
            copy(input, output);
            output.flush();
        }
    }

    private static void replacePair(ParcelFileDescriptor sourceZip, ParcelFileDescriptor sourceNx,
                                    File targetZip, File targetNx) throws Exception {
        String token = Long.toUnsignedString(System.nanoTime());
        // Keep the names non-hidden. Android's emulated-storage layer applies
        // different policy to dot files for a Shizuku UserService even though
        // both paths are owned by uid 2000.
        File stagedZip = new File(SHARED_STAGE, "LYSKPS_" + token + "_" + targetZip.getName());
        File stagedNx = new File(SHARED_STAGE, "LYSKPS_" + token + "_" + targetNx.getName());
        removeStages(stagedZip, stagedNx);
        try {
            stageDescriptorWithShell(sourceZip, stagedZip);
            stageDescriptorWithShell(sourceNx, stagedNx);
            // The Java Os.rename call is denied by Android's FUSE policy in a
            // Shizuku UserService. The platform shell's mv command is allowed
            // to move the same shared-storage inode over the exact target; it
            // was also verified on the target device. Avoid a shell string so
            // no filename can be interpreted as command syntax.
            moveOrThrow(stagedZip, targetZip);
            moveOrThrow(stagedNx, targetNx);
            if (!targetZip.isFile() || !targetNx.isFile()
                    || targetZip.length() <= 0 || targetNx.length() <= 0) {
                throw new IllegalStateException("替换后的 NLS 文件大小异常");
            }
        } finally {
            removeStages(stagedZip, stagedNx);
        }
    }

    private static void stageDescriptorWithShell(ParcelFileDescriptor source, File destination)
            throws Exception {
        // Creating the file with Java places it in app_process' scoped-storage
        // view; a child toybox process then cannot even stat that pathname.
        // Let the shell child create it in its own view while the service feeds
        // the descriptor through stdin.
        Process process = new ProcessBuilder("sh", "-c", "cat > \"$1\"", "sh",
                destination.getAbsolutePath()).redirectErrorStream(true).start();
        try (InputStream input = new FileInputStream(source.getFileDescriptor());
             OutputStream output = process.getOutputStream()) {
            copy(input, output);
        }
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (InputStream output = process.getInputStream()) {
            copy(output, captured);
        }
        int code = process.waitFor();
        String message = new String(captured.toByteArray(), StandardCharsets.UTF_8).trim();
        if (code != 0) {
            throw new IllegalStateException("创建暂存文件失败，exit=" + code + suffix(message));
        }
        CommandResult chmod = runCommand("chmod", "660", destination.getAbsolutePath());
        if (chmod.code != 0) {
            throw new IllegalStateException("设置暂存文件权限失败，exit=" + chmod.code
                    + suffix(chmod.output));
        }
    }

    private static void removeStages(File first, File second) {
        try {
            runCommand("rm", "-f", first.getAbsolutePath(), second.getAbsolutePath());
        } catch (Throwable ignored) {}
    }

    private static void moveOrThrow(File source, File target) throws Exception {
        CommandResult moved = runCommand("mv", "-f",
                source.getAbsolutePath(), target.getAbsolutePath());
        if (moved.code != 0 || source.exists() || !target.isFile()) {
            throw new IllegalStateException("mv " + target.getName() + " 失败，exit="
                    + moved.code + suffix(moved.output));
        }
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[128 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try { descriptor.close(); } catch (Throwable ignored) {}
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

    private static final class ApkMetadataSource {
        final ZipFile zip;
        final ZipEntry entry;
        ApkMetadataSource(ZipFile zip, ZipEntry entry) {
            this.zip = zip;
            this.entry = entry;
        }
        void close() {
            try { zip.close(); } catch (Throwable ignored) {}
        }
    }
}
