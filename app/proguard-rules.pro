# JNI entry points are resolved by their Java class and method names.
-keep class com.github.shadowsocks.bg.Tun2proxy {
    public static native <methods>;
}

# Shizuku exposes process APIs that this app reaches through reflection.
-keep class rikka.shizuku.** { *; }

# Shizuku starts this UserService in a separate app_process and instantiates it
# reflectively. R8 cannot see that entry point and otherwise removes its public
# constructors, causing ServiceStarter to fail with InstantiationException.
-keep class com.axuan.lyskps.ShizukuRsaService { *; }

# Keep runtime annotations used by Android and Compose-generated code.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod
