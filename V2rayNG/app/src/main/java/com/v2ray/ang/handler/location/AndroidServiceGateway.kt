package com.v2ray.ang.handler.location

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Production [ServiceGateway]: talks to the project's **existing** service through its existing
 * messages and launcher. It adds no networking of its own.
 *
 * Measurement route: `MSG_MEASURE_DELAY` runs inside the `:daemon` process and fetches the
 * IP-geolocation URL through the running core's own local proxy. This process never contacts the
 * provider and never assumes a port (a dynamic SOCKS port exists only in the daemon).
 * Caveat inherited from the core: if the user's routing rules send that request `direct`, the
 * observed IP is not the proxy's. The scan therefore flags several servers reporting one address.
 *
 * State is never cached; every [queryState] asks again. Silence is never read as "stopped".
 * Call [open] before use and [close] afterwards (it registers a broadcast receiver).
 */
class AndroidServiceGateway(private val app: Application) : ServiceGateway, AutoCloseable {

    private sealed interface Event {
        data object Running : Event
        data object NotRunning : Event
        data object StartSuccess : Event
        data object StartFailure : Event
        data class Measured(val requestId: String, val result: ConnectionTestResult?) : Event
        data class MeasureCancelled(val requestId: String) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val safe = intent ?: return
            val requestId = safe.getStringExtra(MessageHelper.EXTRA_REQUEST_ID).orEmpty()
            val event: Event = when (safe.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> Event.Running
                AppConfig.MSG_STATE_NOT_RUNNING -> Event.NotRunning
                AppConfig.MSG_STATE_START_SUCCESS -> Event.StartSuccess
                AppConfig.MSG_STATE_START_FAILURE -> Event.StartFailure
                AppConfig.MSG_MEASURE_DELAY_RESULT ->
                    Event.Measured(requestId, safe.serializable<ConnectionTestResult>("content"))

                AppConfig.MSG_MEASURE_DELAY_CANCEL -> Event.MeasureCancelled(requestId)
                else -> return
            }
            events.trySend(event)
        }
    }

    fun open() {
        if (registered) return
        // Same flags as the project's other activity-side receivers (exported on Android 13+). State events
        // carry no request id and could be spoofed by another app; measurements are tied to a random id.
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Utils.receiverFlags()
        )
        registered = true
    }

    override fun close() {
        if (!registered) return
        registered = false
        runCatching { app.unregisterReceiver(receiver) }
            .onFailure { LogUtil.e(AppConfig.TAG, "Service gateway: failed to unregister receiver", it) }
        events.close()
    }

    private fun drain() {
        while (events.tryReceive().getOrNull() != null) { /* discard stale events */ }
    }

    private suspend fun <T : Any> awaitEvent(timeoutMillis: Long, pick: (Event) -> T?): T? =
        withTimeoutOrNull(timeoutMillis) {
            var found: T? = null
            while (found == null) found = pick(events.receive())
            found
        }

    /** Whether the OS reports the `:daemon` process; null when that cannot be determined. */
    private fun daemonProcessAlive(): Boolean? = try {
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.runningAppProcesses?.any { it.processName == "${app.packageName}:daemon" }
    } catch (e: SecurityException) {
        null
    }

    override suspend fun queryState(): ServiceState {
        drain()
        var ipcFailed = false
        try {
            MessageHelper.sendMsg2Service(app, AppConfig.MSG_REGISTER_CLIENT, "")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Service gateway: state query could not be sent", e)
            ipcFailed = true
        }
        val reply = if (ipcFailed) null else awaitEvent(QUERY_TIMEOUT_MS) {
            when (it) {
                Event.Running -> DaemonReply.RUNNING
                Event.NotRunning -> DaemonReply.NOT_RUNNING
                else -> null
            }
        }
        return ServiceStateResolver.resolve(reply, daemonProcessAlive(), ipcFailed)
    }

    override fun selectedGuid(): String? = MmkvManager.getSelectServer()

    override fun select(guid: String) {
        MmkvManager.setSelectServer(guid)
    }

    override suspend fun startOrRestart(): StartResult {
        drain()
        try {
            LauncherManager.restartServiceOrStart(app) { LauncherManager.startService(app) }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Service gateway: start request failed", e)
            return StartResult.FAILED
        }
        val outcome = awaitEvent(START_TIMEOUT_MS) {
            when (it) {
                Event.StartSuccess -> StartResult.STARTED
                Event.StartFailure -> StartResult.FAILED
                else -> null
            }
        }
        return outcome ?: StartResult.TIMED_OUT
    }

    override suspend fun requestStop() {
        try {
            LauncherManager.stopService(app)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Service gateway: stop request failed", e)
        }
    }

    override suspend fun measure(): MeasureResult {
        repeat(2) { attempt ->
            val requestId = UUID.randomUUID().toString()
            val handled = CompletableDeferred<Boolean>()
            try {
                MessageHelper.sendMsg2ServiceForResult(app, AppConfig.MSG_MEASURE_DELAY, requestId) { handled.complete(it) }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Service gateway: measurement request failed", e)
                return MeasureResult.Failed
            }
            if (withTimeoutOrNull(QUERY_TIMEOUT_MS) { handled.await() } != true) return MeasureResult.NotRunning
            val answer = awaitEvent<Event>(MEASURE_TIMEOUT_MS) {
                when {
                    it is Event.Measured && it.requestId == requestId -> it
                    it is Event.MeasureCancelled && it.requestId == requestId -> it
                    else -> null
                }
            } ?: return MeasureResult.TimedOut
            if (answer is Event.Measured) {
                val r = answer.result ?: return MeasureResult.Failed
                return MeasureResult.Done(
                    RawMeasurement(
                        delayMillis = r.delayMillis,
                        country = r.country,
                        countryCode = r.countryCode,
                        ipAddress = r.ipAddress,
                        regionCode = r.regionCode,
                        regionName = r.regionName,
                        city = r.city,
                        latitude = r.latitude,
                        longitude = r.longitude,
                    )
                )
            }
            // Cancelled: the core is reloading, stopped, or another test superseded ours. Retry once.
            if (attempt == 0) delay(RETRY_DELAY_MS)
        }
        return MeasureResult.NotRunning
    }

    private companion object {
        const val QUERY_TIMEOUT_MS = 3_000L
        const val START_TIMEOUT_MS = 30_000L
        const val MEASURE_TIMEOUT_MS = 30_000L
        const val RETRY_DELAY_MS = 2_000L
    }
}
