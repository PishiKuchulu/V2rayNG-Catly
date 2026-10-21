package com.v2ray.ang.handler.location

import java.net.URI

enum class AuditSeverity { INFO, WARNING }

enum class AuditFindingId {
    /** Proxy-only mode: Android does not route other apps through the tunnel. */
    PROXY_ONLY_MODE,

    /** VPN mode with IPv6 off: no IPv6 route is installed, so IPv6 traffic may bypass the tunnel. */
    IPV6_NOT_ROUTED,

    /** "Prefer IPv6" is on while IPv6 is disabled; the two settings contradict each other. */
    PREFER_IPV6_WITHOUT_IPV6,

    /** System Private DNS (DNS-over-TLS) is active; its interaction with the tunnel is not verified. */
    PRIVATE_DNS_ACTIVE,

    /** Always-on VPN / lockdown state cannot be read by this app; the user must check system settings. */
    ALWAYS_ON_NOT_READABLE,

    /** Tells the user which third-party hosts the optional exit check contacts. */
    EXTERNAL_CHECK_DISCLOSURE,

    /** A configured check URL does not use HTTPS, so its answer could be read or altered in transit. */
    EXTERNAL_CHECK_NOT_HTTPS,
}

data class AuditFinding(
    val id: AuditFindingId,
    val severity: AuditSeverity,
    /** Non-secret display arguments (for example host names). */
    val args: List<String> = emptyList(),
)

enum class ProxyScope { VPN, PROXY_ONLY, ROOT }

data class PrivacyAuditInput(
    val scope: ProxyScope,
    val ipv6Enabled: Boolean,
    val preferIpv6: Boolean,
    /** Null when the platform could not tell (API level or no active network). */
    val privateDnsActive: Boolean?,
    val delayTestUrl: String?,
    val ipApiUrl: String?,
)

/**
 * Static review of the *current configuration*. It does not send traffic and therefore cannot prove
 * the absence of a leak; the UI says so. Findings only state what the configuration implies.
 */
object PrivacyAudit {

    fun run(input: PrivacyAuditInput): List<AuditFinding> {
        val out = mutableListOf<AuditFinding>()
        if (input.scope == ProxyScope.PROXY_ONLY) {
            out += AuditFinding(AuditFindingId.PROXY_ONLY_MODE, AuditSeverity.WARNING)
        }
        if (input.scope == ProxyScope.VPN && !input.ipv6Enabled) {
            out += AuditFinding(AuditFindingId.IPV6_NOT_ROUTED, AuditSeverity.WARNING)
        }
        if (input.preferIpv6 && !input.ipv6Enabled) {
            out += AuditFinding(AuditFindingId.PREFER_IPV6_WITHOUT_IPV6, AuditSeverity.WARNING)
        }
        if (input.privateDnsActive == true) {
            out += AuditFinding(AuditFindingId.PRIVATE_DNS_ACTIVE, AuditSeverity.INFO)
        }
        if (input.scope == ProxyScope.VPN) {
            out += AuditFinding(AuditFindingId.ALWAYS_ON_NOT_READABLE, AuditSeverity.INFO)
        }
        val hosts = listOfNotNull(hostOf(input.delayTestUrl), hostOf(input.ipApiUrl)).distinct()
        if (hosts.isNotEmpty()) {
            out += AuditFinding(AuditFindingId.EXTERNAL_CHECK_DISCLOSURE, AuditSeverity.INFO, hosts)
        }
        val insecure = listOf(input.delayTestUrl, input.ipApiUrl)
            .filter { !it.isNullOrBlank() && !isHttps(it) }
            .mapNotNull { hostOf(it) }.distinct()
        if (insecure.isNotEmpty()) {
            out += AuditFinding(AuditFindingId.EXTERNAL_CHECK_NOT_HTTPS, AuditSeverity.WARNING, insecure)
        }
        return out
    }

    fun isHttps(url: String?): Boolean = url?.trim()?.startsWith("https://", ignoreCase = true) == true

    /**
     * Host only. Never returns the path, query or user-info, which can carry tokens or credentials
     * in user-configured URLs.
     */
    fun hostOf(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return try {
            URI(trimmed).host?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}
