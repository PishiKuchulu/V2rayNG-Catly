package com.v2ray.ang.handler.location

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil

/**
 * Reports when any internet-capable network appears or disappears (Wi-Fi/cellular switch, VPN
 * reconnect). Used only to expire a previous exit observation: after such a change the exit may be
 * different, so showing the old result as "confirmed" would be wrong. Callbacks arrive on a
 * binder thread; the owner must hop to its own scope.
 */
class NetworkChangeWatcher(context: Context, private val onChange: () -> Unit) {

    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChange()
        override fun onLost(network: Network) = onChange()
    }

    fun start() {
        val cm = manager ?: return
        if (registered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "Network watcher: registration refused", e)
        } catch (e: RuntimeException) {
            LogUtil.e(AppConfig.TAG, "Network watcher: registration failed", e)
        }
    }

    fun stop() {
        val cm = manager ?: return
        if (!registered) return
        registered = false
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
    }
}
