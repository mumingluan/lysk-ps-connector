package com.axuan.lyskps;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** 深色 MD3 风格的模块配置、VPN 控制与实时日志页。 */
@SuppressLint({"SetTextI18n","ClickableViewAccessibility"})
public class InfoActivity extends Activity {
    private static final int REQ_VPN = 1001;
    private static final int REQ_CA_CERT=2001,REQ_CA_KEY=2002,REQ_LEAF_CERT=2003,REQ_LEAF_KEY=2004;
    private static final int REQ_EXPORT_CRT=2101,REQ_EXPORT_HASH=2102;
    private static final int BG=0xff121318, SURFACE=0xff1e1f25, TEXT=0xffe6e1e5;
    private static final int MUTED=0xffcac4d0, PRIMARY=0xffd0bcff, ON_PRIMARY=0xff381e72, OUTLINE=0xff49454f;
    private SharedPreferences prefs;
    private RadioGroup modes;
    private EditText proxyEndpoint, redirectEndpoint, domains, packages;
    private Switch tlsWrapper;
    private LinearLayout tlsIdentityPanel;
    private TextView state, logText, certStatus, protocolWarning;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateState();
            String text=VpnLog.snapshot();
            if(!text.equals(logText.getText().toString())) { boolean bottom=isLogAtBottom();int oldY=logText.getScrollY();logText.setText(text);logText.post(()->{if(bottom)scrollLogBottom();else logText.scrollTo(0,oldY);}); }
            handler.postDelayed(this,700);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        VpnLog.init(this);
        prefs=getSharedPreferences(VpnConfig.PREFS,0);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18),dp(18),dp(18),dp(28)); root.setBackgroundColor(BG);
        TextView title=label("LYSK-PS",28,TEXT); title.setTypeface(null,Typeface.BOLD); root.addView(title);
        root.addView(label("RSA + Selective VPN  ·  v1.5",13,PRIMARY));
        root.addView(label("要使用RSA替换功能，请在LSPosed启用此模块，或使用LSPatch将模块集成到游戏。",13,MUTED));

        root.addView(section("过滤范围"));
        root.addView(label("过滤域名（每行一个，同时匹配其所有子域名）",12,MUTED));domains=edit(true);domains.setMinLines(3);root.addView(domains);
        root.addView(label("VPN 作用包名（每行一个；填 * 代表除模块自身外的全部应用）",12,MUTED));packages=edit(true);packages.setMinLines(2);root.addView(packages);

        root.addView(section("工作模式"));
        modes=new RadioGroup(this); modes.setOrientation(RadioGroup.HORIZONTAL);
        modes.addView(radio("HTTP 代理",VpnConfig.MODE_PROXY)); modes.addView(radio("Web 重定向",VpnConfig.MODE_REDIRECT)); root.addView(modes);
        root.addView(label("上游代理地址",12,MUTED));proxyEndpoint=edit(false);proxyEndpoint.setHint("http://代理IP:端口");root.addView(proxyEndpoint);
        root.addView(label("上游 HTTP 服务地址",12,MUTED));redirectEndpoint=edit(false);redirectEndpoint.setHint("http://服务IP:8088 或 https://服务地址");root.addView(redirectEndpoint);
        tlsWrapper=new Switch(this);tlsWrapper.setText("启用内置 HTTPS 包装器");tlsWrapper.setTextColor(TEXT);tlsWrapper.setPadding(dp(4),dp(4),0,dp(8));tlsWrapper.setOnCheckedChangeListener((b,v)->modeUi());root.addView(tlsWrapper);
        protocolWarning=label("",12,0xffffb4ab);protocolWarning.setBackground(round(0xff2b1b1b,12,0xff8c4a45));protocolWarning.setPadding(dp(12),dp(9),dp(12),dp(9));root.addView(protocolWarning);

        tlsIdentityPanel=new LinearLayout(this);tlsIdentityPanel.setOrientation(LinearLayout.VERTICAL);
        tlsIdentityPanel.addView(section("TLS 包装器身份"));
        certStatus=label("正在初始化随机身份…",12,MUTED);certStatus.setTextIsSelectable(true);tlsIdentityPanel.addView(certStatus);
        LinearLayout caRow=new LinearLayout(this);caRow.addView(importButton("导入 CA 证书",REQ_CA_CERT),weightParams());caRow.addView(importButton("导入 CA 私钥",REQ_CA_KEY),weightParams());tlsIdentityPanel.addView(caRow);
        LinearLayout leafRow=new LinearLayout(this);leafRow.addView(importButton("导入 Leaf 证书",REQ_LEAF_CERT),weightParams());leafRow.addView(importButton("导入 Leaf 私钥",REQ_LEAF_KEY),weightParams());tlsIdentityPanel.addView(leafRow);
        LinearLayout exportRow=new LinearLayout(this);Button exportCrt=button("导出 CA .crt",true);exportCrt.setOnClickListener(v->createExport(false));Button exportHash=button("导出 xxxxxxxx.0",false);exportHash.setOnClickListener(v->createExport(true));exportRow.addView(exportCrt,weightParams());exportRow.addView(exportHash,weightParams());tlsIdentityPanel.addView(exportRow);
        Button regenerate=button("重新随机生成 CA / Leaf",false);regenerate.setOnClickListener(v->regenerateIdentity());tlsIdentityPanel.addView(regenerate);
        tlsIdentityPanel.addView(label("新版 Android：先导出 .crt，然后前往“设置 → 安全/密码与安全 → 更多安全设置 → 加密与凭据 → 安装证书 → CA 证书”选择该文件。不同系统的菜单名称可能略有不同。",12,MUTED));
        tlsIdentityPanel.addView(label("root 用户亦可将证书 .0 文件添加到系统 CA + Conscrypt APEX 中，但游戏默认信任用户证书，因此不必这么做。",12,PRIMARY));
        root.addView(tlsIdentityPanel);
        state=label("",13,PRIMARY);root.addView(state);

        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button start=button("保存并启动",true),stop=button("停止 VPN",false);
        start.setOnClickListener(v->requestStart());stop.setOnClickListener(v->LyskVpnService.stop(this));
        actions.addView(start,weightParams());actions.addView(stop,weightParams());root.addView(actions);

        root.addView(section("实时分流日志"));
        root.addView(label("DIRECT / PROXY / REDIRECT-HTTP / REDIRECT-TLS-WRAP / RAW 会逐连接显示。",12,MUTED));
        logText=label("",11,0xffe2e2e9);logText.setTypeface(Typeface.MONOSPACE);logText.setPadding(dp(12),dp(10),dp(12),dp(10));logText.setBackground(round(0xff18191e,16,OUTLINE));logText.setVerticalScrollBarEnabled(true);logText.setMovementMethod(new ScrollingMovementMethod());logText.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);logText.setOnTouchListener((v,e)->{if(e.getAction()==android.view.MotionEvent.ACTION_DOWN)v.getParent().requestDisallowInterceptTouchEvent(true);else if(e.getAction()==android.view.MotionEvent.ACTION_UP||e.getAction()==android.view.MotionEvent.ACTION_CANCEL)v.getParent().requestDisallowInterceptTouchEvent(false);return false;});root.addView(logText,new LinearLayout.LayoutParams(-1,dp(230)));
        Button clear=button("清空日志",false);clear.setOnClickListener(v->{VpnLog.clear();logText.setText("");});root.addView(clear);
        root.addView(label("重定向模式由模块内置 TLS/HTTP 包装器解密命中流量，再送入配置的明文 Web 服务。未命中域名由系统网络直接连接。",12,MUTED));

        ScrollView page=new ScrollView(this);page.setFillViewport(true);page.addView(root);setContentView(page);load();refreshIdentity();
    }

    private void load(){VpnConfig c=VpnConfig.load(prefs);domains.setText(c.domainsText);packages.setText(c.packagesText);proxyEndpoint.setText(c.proxyEndpoint);redirectEndpoint.setText(c.redirectEndpoint);modes.check(c.mode);tlsWrapper.setChecked(c.redirectTlsWrapper);modes.setOnCheckedChangeListener((g,id)->modeUi());redirectEndpoint.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){modeUi();}public void afterTextChanged(Editable e){}});modeUi();updateState();}
    private void modeUi(){boolean redirect=modes.getCheckedRadioButtonId()==VpnConfig.MODE_REDIRECT;tlsWrapper.setVisibility(redirect?View.VISIBLE:View.GONE);if(tlsIdentityPanel!=null)tlsIdentityPanel.setVisibility(redirect&&tlsWrapper.isChecked()?View.VISIBLE:View.GONE);String address=redirectEndpoint.getText().toString().trim().toLowerCase(Locale.ROOT);if(redirect&&!address.startsWith("https://")){protocolWarning.setVisibility(View.VISIBLE);if(tlsWrapper.isChecked()){protocolWarning.setText("提示：当前服务地址不是 https://。命中的 HTTPS 将由内置包装器终止 TLS 后转为 HTTP。此配置可用。");protocolWarning.setTextColor(0xffffd8a8);}else{protocolWarning.setText("警告：服务地址不是 https:// 且内置包装器已关闭。原始 TLS 会进入明文 HTTP 服务，通常会连接失败。");protocolWarning.setTextColor(0xffffb4ab);}}else protocolWarning.setVisibility(View.GONE);}
    private boolean save(){try{VpnConfig.fromInput(modes.getCheckedRadioButtonId(),proxyEndpoint.getText().toString(),redirectEndpoint.getText().toString(),tlsWrapper.isChecked(),domains.getText().toString(),packages.getText().toString()).save(prefs);return true;}catch(Throwable e){Toast.makeText(this,e.getMessage()==null?"配置无效":e.getMessage(),Toast.LENGTH_LONG).show();return false;}}
    private void requestStart(){if(!save())return;Intent i=VpnService.prepare(this);if(i!=null)startActivityForResult(i,REQ_VPN);else startVpn();}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQ_VPN){if(resultCode==RESULT_OK)startVpn();else Toast.makeText(this,"未授予 VPN 权限",Toast.LENGTH_LONG).show();return;}if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;if(requestCode==REQ_EXPORT_CRT||requestCode==REQ_EXPORT_HASH){new Thread(()->{try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"w")){TlsIdentityStore store=TlsIdentityStore.get(this);out.write(requestCode==REQ_EXPORT_HASH?store.caPemBytes():store.caBytes());out.flush();runOnUiThread(()->Toast.makeText(this,"CA 证书已导出",Toast.LENGTH_LONG).show());}catch(Throwable e){runOnUiThread(()->Toast.makeText(this,"导出失败："+e.getMessage(),Toast.LENGTH_LONG).show());}},"lyskps-export").start();return;}int kind=requestCode==REQ_CA_CERT?TlsIdentityStore.CA_CERT:requestCode==REQ_CA_KEY?TlsIdentityStore.CA_KEY:requestCode==REQ_LEAF_CERT?TlsIdentityStore.LEAF_CERT:TlsIdentityStore.LEAF_KEY;new Thread(()->{try(InputStream in=getContentResolver().openInputStream(data.getData())){String result=TlsIdentityStore.get(this).importPart(kind,in);runOnUiThread(()->{LyskVpnService.stop(this);Toast.makeText(this,result+"；VPN 已停止，请重新启动",Toast.LENGTH_LONG).show();refreshIdentity();});}catch(Throwable e){runOnUiThread(()->Toast.makeText(this,"导入失败："+e.getMessage(),Toast.LENGTH_LONG).show());}},"lyskps-import").start();}
    private void startVpn(){VpnLog.i("UI","保存配置并启动 VPN");LyskVpnService.start(this);}
    private void updateState(){if(state!=null)state.setText("状态  ·  "+(LyskVpnService.isRunning()?"VPN 运行中":"已停止"));}
    private boolean isLogAtBottom(){if(logText==null||logText.getLayout()==null)return true;int content=logText.getLayout().getHeight()+logText.getPaddingTop()+logText.getPaddingBottom();return logText.getScrollY()+logText.getHeight()>=content-dp(12);}
    private void scrollLogBottom(){if(logText.getLayout()==null)return;int content=logText.getLayout().getHeight()+logText.getPaddingTop()+logText.getPaddingBottom();logText.scrollTo(0,Math.max(0,content-logText.getHeight()));}
    private Button importButton(String text,int request){Button b=button(text,false);b.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,request);});return b;}
    private void refreshIdentity(){new Thread(()->{try{String s=TlsIdentityStore.get(this).status();runOnUiThread(()->certStatus.setText(s));}catch(Throwable e){runOnUiThread(()->certStatus.setText("初始化失败："+e.getMessage()));}},"lyskps-identity").start();}
    private void createExport(boolean hash){new Thread(()->{try{String name=hash?TlsIdentityStore.get(this).androidHashFileName():"LYSK-PS-CA.crt";runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(hash?"application/x-pem-file":"application/x-x509-ca-cert");i.putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(i,hash?REQ_EXPORT_HASH:REQ_EXPORT_CRT);});}catch(Throwable e){runOnUiThread(()->Toast.makeText(this,"准备导出失败："+e.getMessage(),Toast.LENGTH_LONG).show());}},"lyskps-export-prepare").start();}
    private void regenerateIdentity(){new android.app.AlertDialog.Builder(this).setTitle("重新生成 TLS 身份？").setMessage("旧 CA 将立即失效，需要重新安装并信任新 CA。").setPositiveButton("重新生成",(d,w)->new Thread(()->{try{TlsIdentityStore.get(this).regenerate();runOnUiThread(()->{LyskVpnService.stop(this);refreshIdentity();Toast.makeText(this,"已生成并停止 VPN，请安装新的 CA",Toast.LENGTH_LONG).show();});}catch(Throwable e){runOnUiThread(()->Toast.makeText(this,"生成失败："+e.getMessage(),Toast.LENGTH_LONG).show());}},"lyskps-regenerate").start()).setNegativeButton("取消",null).show();}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}

    private RadioButton radio(String text,int id){RadioButton b=new RadioButton(this);b.setText(text);b.setTextColor(TEXT);b.setId(id);b.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{PRIMARY,MUTED}));return b;}
    private EditText edit(boolean multiline){EditText e=new EditText(this);e.setSingleLine(!multiline);e.setTextColor(TEXT);e.setHintTextColor(0xff938f99);e.setBackground(round(SURFACE,14,OUTLINE));e.setPadding(dp(14),dp(10),dp(14),dp(10));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(3),0,dp(12));e.setLayoutParams(lp);return e;}
    private Button button(String text,boolean primary){Button b=new Button(this);b.setText(text);b.setTextColor(primary?ON_PRIMARY:PRIMARY);b.setAllCaps(false);b.setTypeface(null,Typeface.BOLD);GradientDrawable shape=round(primary?PRIMARY:SURFACE,24,primary?PRIMARY:OUTLINE);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),shape,null));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(4),dp(8),dp(4),dp(8));b.setLayoutParams(lp);return b;}
    private LinearLayout.LayoutParams weightParams(){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(56),1);lp.setMargins(dp(4),dp(6),dp(4),dp(6));return lp;}
    private GradientDrawable round(int color,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));g.setStroke(dp(1),stroke);return g;}
    private TextView section(String text){TextView v=label(text,18,TEXT);v.setTypeface(null,Typeface.BOLD);v.setPadding(0,dp(20),0,dp(7));return v;}
    private TextView label(String text,int size,int color){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(4),0,dp(4));return v;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
