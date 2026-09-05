package com.axuan.lyskps;

import android.content.SharedPreferences;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 客户端 VPN 配置。 */
public final class VpnConfig {
    public static final String PREFS = "lysk_vpn_config";
    public static final int MODE_PROXY = 101, MODE_REDIRECT = 102;
    private static final String LEGACY_DEFAULT_DOMAINS = "papegames.com\npapegames.cn";
    private static final String PREVIOUS_DEFAULT_DOMAINS = LEGACY_DEFAULT_DOMAINS+"\n!re:^x3cn-client-[a-z0-9]+\\.papegames\\.com$";
    public static final String DEFAULT_DOMAINS = LEGACY_DEFAULT_DOMAINS+"\ninfoldgames.com"
            +"\n!re:^x3[a-z]+-client-[a-z0-9-]+\\.(?:papegames|infoldgames)\\.com$";
    private static final String LEGACY_DEFAULT_PACKAGES = "com.papegames.lysk.cn";
    public static final String DEFAULT_PACKAGES = LEGACY_DEFAULT_PACKAGES
            +"\ncom.papegames.lysk.tw\ncom.papegames.lysk.jp\ncom.papegames.lysk.en\ncom.papegames.lysk.kr";
    public final int mode; public final String proxyEndpoint, redirectEndpoint, endpoint, domainsText, packagesText;
    public final boolean redirectTlsWrapper;
    public final URI endpointUri; public final List<String> domains, packages;
    private final List<DomainRule> domainRules;
    private VpnConfig(int m,String pe,String re,boolean wrap,String d,String p,URI u,List<String> ds,List<String> ps,List<DomainRule> rules){mode=m;proxyEndpoint=pe;redirectEndpoint=re;redirectTlsWrapper=wrap;endpoint=m==MODE_PROXY?pe:re;domainsText=d;packagesText=p;endpointUri=u;domains=ds;packages=ps;domainRules=rules;}
    public static VpnConfig load(SharedPreferences sp) {
        int mode=sp.getInt("mode",MODE_REDIRECT);String legacy=sp.getString("endpoint","");
        String proxy=sp.getString("proxy_endpoint",mode==MODE_PROXY&&!legacy.isEmpty()?legacy:"http://127.0.0.1:8888");
        String redirect=sp.getString("redirect_endpoint",mode==MODE_REDIRECT&&!legacy.isEmpty()?legacy:"http://127.0.0.1:8088");
        String domains=migrateDomains(sp.getString("domains",DEFAULT_DOMAINS));
        return fromInput(mode,proxy,redirect,sp.getBoolean("redirect_tls_wrapper",true),domains,migratePackages(sp.getString("packages",DEFAULT_PACKAGES)));
    }
    static String migrateDomains(String domains){return domains!=null&&(LEGACY_DEFAULT_DOMAINS.equals(domains.trim())||PREVIOUS_DEFAULT_DOMAINS.equals(domains.trim()))?DEFAULT_DOMAINS:domains;}
    static String migratePackages(String packages){return packages!=null&&LEGACY_DEFAULT_PACKAGES.equals(packages.trim())?DEFAULT_PACKAGES:packages;}
    public static VpnConfig fromInput(int mode,String proxyEndpoint,String redirectEndpoint,boolean redirectTlsWrapper,String domains,String packages) {
        if (mode != MODE_PROXY && mode != MODE_REDIRECT) mode = MODE_REDIRECT;
        proxyEndpoint=proxyEndpoint.trim();redirectEndpoint=redirectEndpoint.trim();
        URI proxy=parseEndpoint(proxyEndpoint,true,"上游代理地址"),redirect=parseEndpoint(redirectEndpoint,false,"上游 HTTP 服务地址");
        URI uri=mode==MODE_PROXY?proxy:redirect;
        ParsedDomains parsed=parseDomains(domains);List<String> ds=parsed.texts, ps=lines(packages,false);
        boolean hasInclude=false;for(DomainRule rule:parsed.rules)if(!rule.exclude){hasInclude=true;break;}
        if(!hasInclude) throw new IllegalArgumentException("至少填写一个非排除的域名规则");
        if(ps.isEmpty()) throw new IllegalArgumentException("至少填写一个 VPN 作用包名");
        return new VpnConfig(mode,proxyEndpoint,redirectEndpoint,redirectTlsWrapper,join(ds),join(ps),uri,Collections.unmodifiableList(ds),Collections.unmodifiableList(ps),Collections.unmodifiableList(parsed.rules));
    }
    public void save(SharedPreferences sp){sp.edit().putInt("mode",mode).putString("proxy_endpoint",proxyEndpoint).putString("redirect_endpoint",redirectEndpoint).putBoolean("redirect_tls_wrapper",redirectTlsWrapper).remove("endpoint").remove("redirect_proxy_port").putString("domains",domainsText).putString("packages",packagesText).commit();}
    public int endpointPort(){int p=endpointUri.getPort();if(p>0)return p;return "https".equalsIgnoreCase(endpointUri.getScheme())?443:80;}
    private static URI parseEndpoint(String value,boolean proxy,String label){URI uri;try{uri=URI.create(value);}catch(Throwable t){throw new IllegalArgumentException(label+"格式无效");}String scheme=uri.getScheme();if(uri.getHost()==null||scheme==null||(!"http".equalsIgnoreCase(scheme)&&(!"https".equalsIgnoreCase(scheme)||proxy)))throw new IllegalArgumentException(label+(proxy?"须为 http://主机:端口":"须以 http:// 或 https:// 开头"));if(uri.getPort()>65535)throw new IllegalArgumentException(label+"端口无效");return uri;}
    public static final int DOMAIN_NONE=0,DOMAIN_INCLUDE=1,DOMAIN_EXCLUDE=2;
    public boolean matches(String host) { return domainDecision(host)==DOMAIN_INCLUDE; }
    public int domainDecision(String host) {
        host=normalizeHost(host);if(host==null)return DOMAIN_NONE;boolean included=false;
        for(DomainRule rule:domainRules)if(rule.matches(host)){if(rule.exclude)return DOMAIN_EXCLUDE;included=true;}
        return included?DOMAIN_INCLUDE:DOMAIN_NONE;
    }
    private static String normalizeHost(String host){if(host==null)return null;host=host.trim().toLowerCase(Locale.ROOT);int colon=host.lastIndexOf(':');if(colon>0&&host.indexOf(':')==colon)host=host.substring(0,colon);while(host.endsWith("."))host=host.substring(0,host.length()-1);return host.isEmpty()?null:host;}
    private static ParsedDomains parseDomains(String text){
        ArrayList<String> values=new ArrayList<>();ArrayList<DomainRule> rules=new ArrayList<>();
        for(String line:text.split("[\\r\\n]+")){String trimmed=line.trim();if(trimmed.isEmpty())continue;boolean regex=isRegexRule(trimmed);String[] entries=regex?new String[]{trimmed}:trimmed.split("[,;]+");
            for(String raw:entries){String value=raw.trim();if(value.isEmpty())continue;boolean exclude=value.startsWith("!");if(exclude)value=value.substring(1).trim();boolean isRegex=value.regionMatches(true,0,"re:",0,3);
                if(isRegex){String expression=value.substring(3);if(expression.isEmpty())throw new IllegalArgumentException("正则域名不能为空："+raw.trim());Pattern pattern;try{pattern=Pattern.compile(expression,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);}catch(PatternSyntaxException e){throw new IllegalArgumentException("正则域名无效："+raw.trim()+"（"+e.getDescription()+"）");}String normalized=(exclude?"!":"")+"re:"+expression;if(!values.contains(normalized)){values.add(normalized);rules.add(new DomainRule(exclude,null,pattern));}}
                else {String domain=value.toLowerCase(Locale.ROOT);if(domain.startsWith("*."))domain=domain.substring(2);else if(domain.startsWith("."))domain=domain.substring(1);if(domain.contains("://")||domain.contains("/")||!domain.contains("."))throw new IllegalArgumentException("过滤域名无效："+raw.trim());String normalized=(exclude?"!":"")+domain;if(!values.contains(normalized)){values.add(normalized);rules.add(new DomainRule(exclude,domain,null));}}
            }
        }
        return new ParsedDomains(values,rules);
    }
    private static boolean isRegexRule(String value){if(value.startsWith("!"))value=value.substring(1).trim();return value.regionMatches(true,0,"re:",0,3);}
    private static List<String> lines(String text, boolean domain) {
        ArrayList<String> out=new ArrayList<>(); for(String raw:text.split("[\\r\\n,;]+")) { String s=raw.trim().toLowerCase(Locale.ROOT); if(s.isEmpty())continue;
            if(domain){if(s.startsWith("*."))s=s.substring(2);else if(s.startsWith("."))s=s.substring(1);if(s.contains("://")||s.contains("/")||!s.contains("."))throw new IllegalArgumentException("过滤域名无效："+raw.trim());}
            else if(!"*".equals(s) && !s.matches("[a-z0-9_]+(\\.[a-z0-9_]+)+"))throw new IllegalArgumentException("包名无效："+raw.trim()); if(!out.contains(s))out.add(s); }
        if (!domain && out.contains("*")) { out.clear(); out.add("*"); }
        return out;
    }
    private static String join(List<String> values) { StringBuilder b=new StringBuilder(); for(String v:values){if(b.length()>0)b.append('\n');b.append(v);}return b.toString(); }
    private static final class ParsedDomains {final ArrayList<String> texts;final ArrayList<DomainRule> rules;ParsedDomains(ArrayList<String> t,ArrayList<DomainRule> r){texts=t;rules=r;}}
    private static final class DomainRule {final boolean exclude;final String domain;final Pattern pattern;DomainRule(boolean e,String d,Pattern p){exclude=e;domain=d;pattern=p;}boolean matches(String host){return pattern!=null?pattern.matcher(host).matches():host.equals(domain)||host.endsWith("."+domain);}}
}
