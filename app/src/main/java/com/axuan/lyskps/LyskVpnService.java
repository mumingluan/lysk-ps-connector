package com.axuan.lyskps;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.*;
import android.util.Log;
import android.widget.Toast;
import com.github.shadowsocks.bg.Tun2proxy;
import java.io.IOException;

/** 按应用接管的 VPN；TUN 数据面使用 MIT 许可的 tun2proxy。 */
public final class LyskVpnService extends VpnService {
    private static final String TAG="LYSK-PS-Connector.VPN", START="com.axuan.lyskps.START", STOP="com.axuan.lyskps.STOP";
    private static volatile boolean running;
    private ParcelFileDescriptor tun;
    private SelectiveProxyServer localProxy;
    private Thread nativeThread;

    public static boolean isRunning(){return running;}
    public static void start(Context c){Intent i=new Intent(c,LyskVpnService.class).setAction(START);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}
    public static void stop(Context c){c.startService(new Intent(c,LyskVpnService.class).setAction(STOP));}
    @Override public int onStartCommand(Intent intent,int flags,int id){VpnLog.init(this);if(intent!=null&&STOP.equals(intent.getAction())){VpnLog.i("VPN","用户停止");shutdown();stopSelf();return START_NOT_STICKY;}startForegroundNow("正在启动");if(!running)new Thread(this::boot,"lyskps-vpn-boot").start();return START_STICKY;}

    private synchronized void boot(){if(running)return;try{
        VpnConfig config=VpnConfig.load(getSharedPreferences(VpnConfig.PREFS,0));
        localProxy=new SelectiveProxyServer(this,config);localProxy.start();
        Builder b=new Builder().setSession("LYSK-PS-Connector").setMtu(1500).addAddress("10.55.0.2",30).addRoute("0.0.0.0",0).addDnsServer("198.18.0.1");
        b.addAddress("fd55:6c79:736b::2",126).addRoute("::",0);
        int allowed=0;StringBuilder missing=new StringBuilder();
        if(config.packages.contains("*")){b.addDisallowedApplication(getPackageName());allowed=1;VpnLog.i("VPN","作用范围：全部应用（排除客户端自身）");}
        else for(String pkg:config.packages){if(getPackageName().equals(pkg))continue;try{b.addAllowedApplication(pkg);allowed++;VpnLog.i("VPN","允许应用："+pkg);}catch(PackageManager.NameNotFoundException e){if(missing.length()>0)missing.append(", ");missing.append(pkg);}}
        if(allowed==0)throw new IllegalArgumentException("没有找到任何已安装的作用包："+missing);
        if(Build.VERSION.SDK_INT>=29)b.setMetered(false);
        b.setBlocking(true);tun=b.establish();if(tun==null)throw new IOException("系统拒绝建立 VPN");
        final int fd=tun.getFd(),port=localProxy.port();running=true;VpnLog.i("VPN","已建立 TUN，模式="+(config.mode==VpnConfig.MODE_PROXY?"代理":"重定向")+"，上游="+config.endpoint+(config.mode==VpnConfig.MODE_REDIRECT?"，HTTPS包装器="+(config.redirectTlsWrapper?"开":"关"):""));startForegroundNow("运行中 · 本地策略端口 "+port);
        nativeThread=new Thread(()->{String args="tun2proxy --tun-fd "+fd+" --close-fd-on-drop false --proxy http://127.0.0.1:"+port+" --dns virtual --dns-addr 1.1.1.1 --ipv6-enabled --verbosity info";int rc=Tun2proxy.run(args,(char)1500);Log.i(TAG,"tun2proxy exited rc="+rc);if(running){shutdown();stopSelf();}},"lyskps-tun2proxy");nativeThread.start();
    }catch(Throwable t){Log.e(TAG,"start failed",t);VpnLog.i("ERROR","VPN 启动失败："+t);getSharedPreferences(VpnConfig.PREFS,0).edit().putString("last_error",String.valueOf(t.getMessage())).apply();new Handler(Looper.getMainLooper()).post(()->Toast.makeText(this,"VPN 启动失败："+t.getMessage(),Toast.LENGTH_LONG).show());shutdown();stopSelf();}}

    private void startForegroundNow(String text){String channel="lyskps_vpn";NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);Notification.Builder nb;if(Build.VERSION.SDK_INT>=26){nm.createNotificationChannel(new NotificationChannel(channel,"LYSK-PS-Connector VPN",NotificationManager.IMPORTANCE_LOW));nb=new Notification.Builder(this,channel);}else nb=new Notification.Builder(this);PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,InfoActivity.class),PendingIntent.FLAG_IMMUTABLE);Notification n=nb.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("LYSK-PS-Connector VPN").setContentText(text).setContentIntent(pi).setOngoing(true).build();startForeground(13001,n);}
    private synchronized void shutdown(){running=false;ParcelFileDescriptor oldTun=tun;tun=null;if(oldTun!=null)try{oldTun.close();}catch(IOException ignored){}if(localProxy!=null){localProxy.close();localProxy=null;}Thread worker=nativeThread;if(worker!=null&&worker!=Thread.currentThread())try{worker.join(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}nativeThread=null;stopForeground(STOP_FOREGROUND_REMOVE);}
    @Override public void onRevoke(){shutdown();stopSelf();super.onRevoke();}
    @Override public void onDestroy(){shutdown();super.onDestroy();}
}
