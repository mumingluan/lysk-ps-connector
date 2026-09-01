package com.axuan.lyskps;

import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Log;
import android.widget.Toast;

/** 不创建 VPN/TUN，仅提供本机 HTTP CONNECT/转发代理。 */
public final class HttpProxyService extends Service {
    private static final String TAG="LYSK-PS-Connector.HttpProxy";
    private static final String START="com.axuan.lyskps.HTTP_PROXY_START", STOP="com.axuan.lyskps.HTTP_PROXY_STOP";
    public static final int PORT=8899;
    private static volatile boolean running;
    private SelectiveProxyServer proxy;

    public static boolean isRunning(){return running;}
    public static void start(Context c){Intent i=new Intent(c,HttpProxyService.class).setAction(START);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}
    public static void stop(Context c){c.startService(new Intent(c,HttpProxyService.class).setAction(STOP));}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        VpnLog.init(this);
        if(intent!=null&&STOP.equals(intent.getAction())){VpnLog.i("HTTP-PROXY","用户停止独立 HTTP 代理");shutdown();stopSelf();return START_NOT_STICKY;}
        startForegroundNow("正在启动 127.0.0.1:"+PORT);
        if(!running)new Thread(this::boot,"lyskps-http-proxy-boot").start();
        return START_STICKY;
    }

    private synchronized void boot(){if(running)return;try{
        VpnConfig config=VpnConfig.load(getSharedPreferences(VpnConfig.PREFS,0));
        proxy=new SelectiveProxyServer(this,config,null,PORT);proxy.start();running=true;
        VpnLog.i("HTTP-PROXY","独立代理已监听 127.0.0.1:"+PORT+"，模式="+(config.mode==VpnConfig.MODE_PROXY?"代理":"重定向"));
        startForegroundNow("监听 127.0.0.1:"+PORT);
    }catch(Throwable t){Log.e(TAG,"start failed",t);VpnLog.i("ERROR","独立 HTTP 代理启动失败："+t);new Handler(Looper.getMainLooper()).post(()->Toast.makeText(this,"HTTP 代理启动失败："+t.getMessage(),Toast.LENGTH_LONG).show());shutdown();stopSelf();}}

    private void startForegroundNow(String text){String channel="lyskps_http_proxy";NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);Notification.Builder nb;if(Build.VERSION.SDK_INT>=26){nm.createNotificationChannel(new NotificationChannel(channel,"LYSK-PS-Connector HTTP Proxy",NotificationManager.IMPORTANCE_LOW));nb=new Notification.Builder(this,channel);}else nb=new Notification.Builder(this);PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,InfoActivity.class),PendingIntent.FLAG_IMMUTABLE);Notification n=nb.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("LYSK-PS-Connector HTTP Proxy").setContentText(text).setContentIntent(pi).setOngoing(true).build();startForeground(13002,n);}
    private synchronized void shutdown(){running=false;if(proxy!=null){proxy.close();proxy=null;}stopForeground(STOP_FOREGROUND_REMOVE);}
    @Override public void onDestroy(){shutdown();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
