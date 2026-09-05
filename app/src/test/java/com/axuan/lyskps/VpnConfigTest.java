package com.axuan.lyskps;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VpnConfigTest {
    private static VpnConfig config(String rules) {
        return VpnConfig.fromInput(VpnConfig.MODE_PROXY,"http://127.0.0.1:8888","http://127.0.0.1:8088",true,rules,"com.papegames.lysk.cn");
    }

    @Test public void exclusionsOverridePlainAndRegexIncludes() {
        VpnConfig c=config("papegames.com\n!hotupdate.papegames.com\nre:^api\\d+\\.paper\\.com$\n!re:^api13\\..*$");
        assertEquals(VpnConfig.DOMAIN_INCLUDE,c.domainDecision("www.papegames.com"));
        assertEquals(VpnConfig.DOMAIN_EXCLUDE,c.domainDecision("cdn.hotupdate.papegames.com"));
        assertEquals(VpnConfig.DOMAIN_INCLUDE,c.domainDecision("api12.paper.com"));
        assertEquals(VpnConfig.DOMAIN_EXCLUDE,c.domainDecision("api13.paper.com"));
        assertEquals(VpnConfig.DOMAIN_NONE,c.domainDecision("example.com"));
    }

    @Test public void builtInRulesExcludePatchCdn() {
        VpnConfig c=config(VpnConfig.DEFAULT_DOMAINS);
        assertEquals(VpnConfig.DOMAIN_EXCLUDE,c.domainDecision("x3cn-client-v8rjb14txuthb0.papegames.com"));
        assertEquals(VpnConfig.DOMAIN_INCLUDE,c.domainDecision("risk-api.papegames.com"));
        assertEquals(VpnConfig.DOMAIN_INCLUDE,c.domainDecision("www.papegames.cn"));
    }

    @Test public void migratesOnlyTheOldBuiltInList() {
        assertEquals(VpnConfig.DEFAULT_DOMAINS,VpnConfig.migrateDomains("papegames.com\npapegames.cn"));
        String custom="papegames.com\n!apm.papegames.com";
        assertEquals(custom,VpnConfig.migrateDomains(custom));
    }

    @Test public void normalizesHostAndKeepsLegacyDomainSyntax() {
        VpnConfig c=config("*.PAPEGAMES.COM, papegames.cn");
        assertTrue(c.matches("API.PAPEGAMES.COM.:443"));
        assertTrue(c.matches("papegames.cn"));
    }

    @Test public void rejectsInvalidRegex() {
        try { config("papegames.com\nre:["); fail("invalid regex accepted"); }
        catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("正则域名无效")); }
    }

    @Test public void internationalResourcesBypassWhileLoginAndGateAreIncluded() {
        VpnConfig c=config(VpnConfig.DEFAULT_DOMAINS);
        for(String region:new String[]{"cn","tw","jp","en","kr","sg"}) {
            for(String suffix:new String[]{"","-backup"}) {
                assertEquals(VpnConfig.DOMAIN_EXCLUDE,c.domainDecision("x3"+region+"-client-v8bci8bzmq8vqa"+suffix+".infoldgames.com"));
            }
        }
        assertEquals(VpnConfig.DOMAIN_INCLUDE,c.domainDecision("api.infoldgames.com:443"));
        assertEquals(VpnConfig.DOMAIN_INCLUDE,c.domainDecision("risk-api.infoldgames.com"));
        assertEquals(VpnConfig.DOMAIN_INCLUDE,c.domainDecision("x3asia-gatesvr.infoldgames.com"));
        assertEquals(VpnConfig.DOMAIN_NONE,c.domainDecision("api.infoldgames.com.evil.test"));
        assertEquals(VpnConfig.DOMAIN_INCLUDE,c.domainDecision("other-client.infoldgames.com"));
    }
    @Test public void migratesPreviousDefaultsAndKeepsCustomScopes() {
        assertEquals(VpnConfig.DEFAULT_DOMAINS,VpnConfig.migrateDomains("papegames.com\npapegames.cn\n!re:^x3cn-client-[a-z0-9]+\\.papegames\\.com$"));
        assertEquals(VpnConfig.DEFAULT_PACKAGES,VpnConfig.migratePackages("com.papegames.lysk.cn"));
        assertEquals("*",VpnConfig.migratePackages("*"));
        assertEquals("custom.package",VpnConfig.migratePackages("custom.package"));
        VpnConfig c=VpnConfig.fromInput(VpnConfig.MODE_PROXY,"http://127.0.0.1:8888","http://127.0.0.1:8088",true,VpnConfig.DEFAULT_DOMAINS,VpnConfig.DEFAULT_PACKAGES);
        assertEquals(5,c.packages.size());
        for(String region:new String[]{"cn","tw","jp","en","kr"})assertTrue(c.packages.contains("com.papegames.lysk."+region));
    }
}
