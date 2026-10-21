package com.v2ray.ang.handler.location

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager

/** Reads the *current* settings and system state into a [PrivacyAuditInput]. No network traffic. */
object PrivacyAuditSource {

    fun read(context: Context): PrivacyAuditInput = PrivacyAuditInput(
        scope = when {
            SettingsManager.isRootMode() -> ProxyScope.ROOT
            SettingsManager.isVpnMode() -> ProxyScope.VPN
            else -> ProxyScope.PROXY_ONLY
        },
        ipv6Enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED, false),
        preferIpv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6, false),
        privateDnsActive = privateDnsActive(context),
        delayTestUrl = SettingsManager.getDelayTestUrl(),
        ipApiUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL).takeIf { !it.isNullOrBlank() }
            ?: AppConfig.IP_API_URL,
    )

    /** Null when the platform cannot say (API < 28, no active network, or access refused). */
    private fun privateDnsActive(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val network = cm.activeNetwork ?: return null
            cm.getLinkProperties(network)?.isPrivateDnsActive
        } catch (e: SecurityException) {
            null
        }
    }
}
