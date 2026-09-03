package com.axuan.lyskps;

import android.content.SharedPreferences;
import android.util.Base64;

/** Shizuku 文件操作使用的 RSA 配置，存储在客户端 SharedPreferences 中。 */
public final class Config {
    public static final String PREFS = "lysk_ps_config";

    public static volatile String off2048 = "22aee2f";
    public static volatile String off1024 = "22af00f";

    // 默认 RSA 块：Base64 只用于避免 Java 源文件中的 XML/换行破坏字符串。
    static final String DEFAULT_REPLACE_2048 = "PFJTQUtleVZhbHVlPjxSU0FLZXlWYWx1ZT48TW9kdWx1cz4KICAgICAgICAgICAgdnQ2VW56NCt3S0dnY1ppTVl6Y3A4dFJRRzR3emRXQVdPQVZTRm9zK3VtdXdMZlVuWmtkVDNpaitSbHk2N2FkOWs2dGlPZ2JxZGljUEhKZVgzcmZaMUVPNXNibTdxajRIZzg3Qko3ZC9nNFdVTVhQanMxanIvT0JJYmpWaUw3U1dnKzRUYklaWk1zRFRWdWVyVTBzeGtnaVFmZUR5N3NOWWtECiAgICAgICAgICAgIDZpSkZSTDB3M1dyemxuMnk1UlozOEhLdE4zTUd6a1orZWx2eUZRVm9jMHI2TWN4cnVsb1hTR1ByNjlobnc4cTUvcWRSR2VxUDdFaTZrZ096aDZEMnIrWFVaaEsxMWkyNEprWGs2QUh0T0ZlVmJMSDA0NXFtekVyTQogICAgICAgICAgICBwZXBnTWpIVml0N2FTUlBZeSs5K1lpTGhyYnJMKzFYejBHWktEUWhrb3ZaY2trWmx6TmtjUWtoZG55eFE9PTwvTW9kdWx1cz48RXhwb25lbnQ+QVFBQjwvRXhwb25lbnQ+CiAgICAgICAgICAgIDwvUlNBS2V5VmFsdWU+";
    static final String DEFAULT_REPLACE_1024 = "PFJTQUtleVZhbHVlPjxNb2R1bHVzPnN0VUNZYVVLVXV5TFBNWEhjcHBrVkVhUXNwaGwxRkppZzF2TlZtcWVaaVk1SXhwNDk1VGVBY2JGQXVkYTdqQW13blNRY3BmMHNZVlZrZG5nL0dhMmplN0NOZjFlbkhzbDBMS3I2VU94YUxDUUl5TkZzTlN4cDBZSWZzVXVZVHdNS0t2TUVzeUJ4Q2xNeGdUbXp4TGkxZmtNNTkrcXdWeW5wOVBMSDZrLzlMcz08L01vZHVsdXM+PEV4cG9uZW50PkFRQUI8L0V4cG9uZW50PjwvUlNBS2V5VmFsdWU+";
    private static final String DEFAULT_ORIG_2048 = "PFJTQUtleVZhbHVlPjxSU0FLZXlWYWx1ZT48TW9kdWx1cz4KICAgICAgICAgICAgeEs1WHJvaVQ3eFMralpoOUNyOU1Db2xCYlMvaHF5WW9hVC9vWVBjVjMxeDVOdi8vd1Q4VXRBemU5VkxUeERZODU4U3F3T0pTNFlwcmhya2Jib3FSdXFpRzd1WDZTdm9YbmQ4UTNLV1lPZW5kaWE5SW5md3RDbEVyZkdYeHpOeFM4Tnh3c2c3aVNNeWk3QTg1dTA3M25pTVY0YU1iSlVZQm00CiAgICAgICAgICAgIFZiYUx2OUpGZ0I3V1h2THpsdlFTaVBwYUpqbUpRQzdWa3JJSXhVaklQdnF5VVJvckN2aW5mWDdtR1lDdDFSVU1KclZSeTNUMXM3cjdSRURZRkJYNnorSFprZkFTZVhnM2QrUm9pT1NFWmFTYTJsOTFzUjBIMUpXLwogICAgICAgICAgICA0T280WWgxVkczTTFWMnJYaVJtWERHTk14cW5DWE1IbFVnZWlNN21STW9vYi9lSnFhZllpTmJjUkRXUVE9PTwvTW9kdWx1cz48RXhwb25lbnQ+QVFBQjwvRXhwb25lbnQ+CiAgICAgICAgICAgIDwvUlNBS2V5VmFsdWU+";
    private static final String DEFAULT_ORIG_1024 = "PFJTQUtleVZhbHVlPjxNb2R1bHVzPnJjMjhqU2phVzFkcndMWXY0ZlNoU0IrM2FlTy94L1hrVERlR1FoQklFZC9vdy8xeGhJcy9OeUI5S1ltZ3Y3UTN2K2RkbmR1Z3NwVmo1SVZvU251RXdNbGdBRDRvQkJ0aUE1MWF4cS9wek14UTJIaTNZZmVRdHVyNkh3WVBFbnRPb2RhRVVWOUUvMDJ5OGp6VHZOMG5RMWR0bDg5cnZKR3NtOGFvSkVBVElQRT08L01vZHVsdXM+PEV4cG9uZW50PkFRQUI8L0V4cG9uZW50PjwvUlNBS2V5VmFsdWU+";

    private static volatile String replace2048;
    private static volatile String replace1024;
    private static volatile String orig2048;
    private static volatile String orig1024;

    public static long parseHex(String s) {
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        return Long.parseLong(s, 16);
    }

    /** 首次读取时补齐默认值；之后所有 RSA 操作均从客户端配置读取。 */
    public static synchronized void load(SharedPreferences sp) {
        boolean missing = !sp.contains("off_2048") || !sp.contains("off_1024")
                || !sp.contains("replace_2048_b64") || !sp.contains("replace_1024_b64")
                || !sp.contains("orig_2048_b64") || !sp.contains("orig_1024_b64");
        off2048 = sp.getString("off_2048", off2048);
        off1024 = sp.getString("off_1024", off1024);

        replace2048 = sp.getString("replace_2048_b64", DEFAULT_REPLACE_2048);
        replace1024 = sp.getString("replace_1024_b64", DEFAULT_REPLACE_1024);
        orig2048 = sp.getString("orig_2048_b64", DEFAULT_ORIG_2048);
        orig1024 = sp.getString("orig_1024_b64", DEFAULT_ORIG_1024);
        if (missing) edit(sp).commit();
        // 旧/损坏 prefs 回退到代码内置默认值，不再出现“资产缺失”。
        validateOrReset(sp);
    }

    private static void validateOrReset(SharedPreferences sp) {
        try {
            validate(replace2048, 480); validate(replace1024, 243);
            validate(orig2048, 480); validate(orig1024, 243);
        } catch (Throwable bad) {
            replace2048 = DEFAULT_REPLACE_2048; replace1024 = DEFAULT_REPLACE_1024;
            orig2048 = DEFAULT_ORIG_2048; orig1024 = DEFAULT_ORIG_1024;
            edit(sp).commit();
        }
    }

    private static void validate(String value, int expectedLength) {
        byte[] data = Base64.decode(value, Base64.DEFAULT);
        if (data.length != expectedLength
                || !starts(data, "<RSAKeyValue>".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                || !ends(data, "</RSAKeyValue>".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("invalid RSA block");
    }

    public static byte[] replace2048Bytes() { return decode(valueOrDefault(replace2048, DEFAULT_REPLACE_2048)); }
    public static byte[] replace1024Bytes() { return decode(valueOrDefault(replace1024, DEFAULT_REPLACE_1024)); }
    public static byte[] orig2048Bytes() { return decode(valueOrDefault(orig2048, DEFAULT_ORIG_2048)); }
    public static byte[] orig1024Bytes() { return decode(valueOrDefault(orig1024, DEFAULT_ORIG_1024)); }
    public static String replace2048B64() { return valueOrDefault(replace2048, DEFAULT_REPLACE_2048); }
    public static String replace1024B64() { return valueOrDefault(replace1024, DEFAULT_REPLACE_1024); }

    public static synchronized void setRsaBlocks(SharedPreferences sp, String r2048, String r1024) {
        validate(r2048, 480); validate(r1024, 243);
        replace2048 = r2048.trim(); replace1024 = r1024.trim();
        sp.edit().putString("replace_2048_b64", replace2048)
                .putString("replace_1024_b64", replace1024).apply();
    }

    public static synchronized void restoreDefaultRsaBlocks(SharedPreferences sp) {
        replace2048 = DEFAULT_REPLACE_2048;
        replace1024 = DEFAULT_REPLACE_1024;
        sp.edit().putString("replace_2048_b64", replace2048)
                .putString("replace_1024_b64", replace1024).apply();
    }

    public static SharedPreferences.Editor edit(SharedPreferences sp) {
        return sp.edit()
                .putString("off_2048", off2048)
                .putString("off_1024", off1024)
                .putString("replace_2048_b64", replace2048 == null ? DEFAULT_REPLACE_2048 : replace2048)
                .putString("replace_1024_b64", replace1024 == null ? DEFAULT_REPLACE_1024 : replace1024)
                .putString("orig_2048_b64", orig2048 == null ? DEFAULT_ORIG_2048 : orig2048)
                .putString("orig_1024_b64", orig1024 == null ? DEFAULT_ORIG_1024 : orig1024);
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static byte[] decode(String value) { return Base64.decode(value, Base64.DEFAULT); }
    private static boolean starts(byte[] a, byte[] p) {
        if (a.length < p.length) return false;
        for (int i = 0; i < p.length; i++) if (a[i] != p[i]) return false;
        return true;
    }
    private static boolean ends(byte[] a, byte[] p) {
        if (a.length < p.length) return false;
        int off = a.length - p.length;
        for (int i = 0; i < p.length; i++) if (a[off + i] != p[i]) return false;
        return true;
    }

    private Config() {}
}
