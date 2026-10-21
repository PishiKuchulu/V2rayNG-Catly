package com.v2ray.ang.handler.location

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrivacyAuditTest {

    private fun input(
        scope: ProxyScope = ProxyScope.VPN,
        ipv6: Boolean = true,
        prefer: Boolean = false,
        privateDns: Boolean? = null,
        delay: String? = null,
        ipApi: String? = null,
    ) = PrivacyAuditInput(scope, ipv6, prefer, privateDns, delay, ipApi)

    private fun ids(i: PrivacyAuditInput) = PrivacyAudit.run(i).map { it.id }

    @Test
    fun ipv4OnlyVpnConfigurationWarnsThatIpv6MayBypassTheTunnel() {
        val findings = PrivacyAudit.run(input(ipv6 = false))
        val f = findings.first { it.id == AuditFindingId.IPV6_NOT_ROUTED }
        assertEquals(AuditSeverity.WARNING, f.severity)
    }

    @Test
    fun ipv6EnabledVpnConfigurationHasNoIpv6Warning() {
        assertFalse(ids(input(ipv6 = true)).contains(AuditFindingId.IPV6_NOT_ROUTED))
    }

    @Test
    fun proxyOnlyModeIsFlaggedAndSkipsVpnOnlyFindings() {
        val found = ids(input(scope = ProxyScope.PROXY_ONLY, ipv6 = false))
        assertTrue(found.contains(AuditFindingId.PROXY_ONLY_MODE))
        assertFalse(found.contains(AuditFindingId.IPV6_NOT_ROUTED))
        assertFalse(found.contains(AuditFindingId.ALWAYS_ON_NOT_READABLE))
    }

    @Test
    fun rootModeHasNoProxyOnlyWarning() {
        assertFalse(ids(input(scope = ProxyScope.ROOT)).contains(AuditFindingId.PROXY_ONLY_MODE))
    }

    @Test
    fun contradictoryIpv6SettingsAreFlagged() {
        assertTrue(ids(input(ipv6 = false, prefer = true)).contains(AuditFindingId.PREFER_IPV6_WITHOUT_IPV6))
        assertFalse(ids(input(ipv6 = true, prefer = true)).contains(AuditFindingId.PREFER_IPV6_WITHOUT_IPV6))
    }

    @Test
    fun privateDnsIsReportedOnlyWhenKnownToBeActive() {
        assertTrue(ids(input(privateDns = true)).contains(AuditFindingId.PRIVATE_DNS_ACTIVE))
        assertFalse(ids(input(privateDns = false)).contains(AuditFindingId.PRIVATE_DNS_ACTIVE))
        assertFalse(ids(input(privateDns = null)).contains(AuditFindingId.PRIVATE_DNS_ACTIVE)) // unknown != active
    }

    @Test
    fun alwaysOnStateIsNeverClaimedOnlyFlaggedAsUnreadableInVpnMode() {
        val f = PrivacyAudit.run(input()).first { it.id == AuditFindingId.ALWAYS_ON_NOT_READABLE }
        assertEquals(AuditSeverity.INFO, f.severity)
    }

    @Test
    fun externalCheckDisclosureListsHostsOnlyNeverCredentialsOrQueries() {
        val findings = PrivacyAudit.run(
            input(
                delay = "https://www.example.com/generate_204",
                ipApi = "https://user:hunter2@api.example.org/geoip?token=SECRET#frag",
            )
        )
        val d = findings.first { it.id == AuditFindingId.EXTERNAL_CHECK_DISCLOSURE }
        assertEquals(listOf("www.example.com", "api.example.org"), d.args)
        val rendered = findings.toString()
        assertFalse(rendered.contains("hunter2"))
        assertFalse(rendered.contains("SECRET"))
    }

    @Test
    fun hostExtractionHandlesMissingAndMalformedUrls() {
        assertNull(PrivacyAudit.hostOf(null))
        assertNull(PrivacyAudit.hostOf("   "))
        assertNull(PrivacyAudit.hostOf("not a url"))
        assertNull(PrivacyAudit.hostOf("/relative/path"))
        assertEquals("a.b", PrivacyAudit.hostOf("http://a.b:8080/x"))
        assertTrue(ids(input(delay = null, ipApi = "::bad")).none { it == AuditFindingId.EXTERNAL_CHECK_DISCLOSURE })
    }

    @Test
    fun duplicateHostsAreListedOnce() {
        val d = PrivacyAudit.run(input(delay = "https://x.example/a", ipApi = "https://x.example/b"))
            .first { it.id == AuditFindingId.EXTERNAL_CHECK_DISCLOSURE }
        assertEquals(listOf("x.example"), d.args)
    }

    @Test
    fun nonHttpsCheckUrlsAreFlaggedWithHostOnly() {
        val f = PrivacyAudit.run(input(delay = "http://plain.example/204", ipApi = "https://secure.example/geo?token=SECRET"))
            .first { it.id == AuditFindingId.EXTERNAL_CHECK_NOT_HTTPS }
        assertEquals(AuditSeverity.WARNING, f.severity)
        assertEquals(listOf("plain.example"), f.args)
        assertFalse(f.toString().contains("SECRET"))
    }

    @Test
    fun httpsOnlyConfigurationHasNoTransportWarning() {
        val found = ids(input(delay = "https://a.example/x", ipApi = "HTTPS://b.example/y"))
        assertFalse(found.contains(AuditFindingId.EXTERNAL_CHECK_NOT_HTTPS))
    }
}
