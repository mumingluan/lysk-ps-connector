package com.axuan.lyskps;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** 进程内环形日志，同时落盘，供模块页实时显示。 */
public final class VpnLog {
    private static final int MAX_LINES = 300;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
    private static File file;
    private static boolean loaded;

    public static synchronized void init(Context c) {
        if (file == null) file = new File(c.getFilesDir(), "vpn.log");
        if (loaded) return;
        loaded = true;
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String s; while ((s = r.readLine()) != null) addMemory(s);
        } catch (IOException ignored) {}
    }

    public static synchronized void i(String tag, String message) {
        String line = CLOCK.format(new Date()) + "  " + tag + "  " + message;
        addMemory(line);
        Log.i("LYSK-PS." + tag, message);
        if (file != null) {
            try (FileWriter w = new FileWriter(file, true)) { w.write(line); w.write('\n'); }
            catch (IOException ignored) {}
            if (file.length() > 256 * 1024) rewrite();
        }
    }

    public static synchronized String snapshot() {
        StringBuilder b = new StringBuilder();
        for (String s : lines) { if (b.length() > 0) b.append('\n'); b.append(s); }
        return b.toString();
    }

    public static synchronized void clear() {
        lines.clear();
        if (file != null && file.exists()) file.delete();
    }

    private static void addMemory(String s) {
        lines.addLast(s);
        while (lines.size() > MAX_LINES) lines.removeFirst();
    }
    private static void rewrite() {
        try (FileWriter w = new FileWriter(file, false)) { for (String s : lines) { w.write(s); w.write('\n'); } }
        catch (IOException ignored) {}
    }
    private VpnLog() {}
}
