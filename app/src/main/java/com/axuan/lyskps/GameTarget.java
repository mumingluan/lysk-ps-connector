package com.axuan.lyskps;
import android.content.Context;
import android.content.ContextWrapper;
import java.io.File;

final class GameTarget {
 static final String[] PACKAGES={"com.papegames.lysk.cn","com.papegames.lysk.tw","com.papegames.lysk.jp","com.papegames.lysk.en","com.papegames.lysk.kr"};
 static final String[] LABELS={"国服","台服","日服","英服","韩服"};
 static String validate(String pkg){for(String p:PACKAGES)if(p.equals(pkg))return p;throw new IllegalArgumentException("不支持的目标客户端");}
 static String selected(Context c){if(c instanceof Frozen)return ((Frozen)c).pkg;return validate(c.getSharedPreferences(VpnConfig.PREFS,0).getString("game_package",PACKAGES[0]));}
 static Context freeze(Context c){return c instanceof Frozen?c:new Frozen(c.getApplicationContext(),selected(c));}
 static String files(String pkg){return "/storage/emulated/0/Android/data/"+validate(pkg)+"/files";}
 static String prefs(Context c,String base){String pkg=selected(c);return pkg.equals(PACKAGES[0])?base:base+"_"+pkg;}
 static File backup(Context c,String kind){String pkg=selected(c);return new File(c.getFilesDir(),"backups/"+kind+(pkg.equals(PACKAGES[0])?"":"/"+pkg));}
 private static final class Frozen extends ContextWrapper {final String pkg;Frozen(Context c,String p){super(c);pkg=p;}@Override public Context getApplicationContext(){return this;}}
}
