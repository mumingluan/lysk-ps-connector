# JNI entry points are resolved by their Java class and method names.
-keep class com.github.shadowsocks.bg.Tun2proxy {
    public static native <methods>;
}

# Shizuku exposes process APIs that this app reaches through reflection.
-keep class rikka.shizuku.** { *; }

# Keep runtime annotations used by Android and Compose-generated code.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod
