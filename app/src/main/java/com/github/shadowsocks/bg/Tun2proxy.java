package com.github.shadowsocks.bg;
/** JNI 名称由上游 tun2proxy Android API 固定。 */
public final class Tun2proxy {
    static { System.loadLibrary("tun2proxy"); }
    public static native int run(String args, char mtu);
    public static native int stop();
    private Tun2proxy() {}
}
