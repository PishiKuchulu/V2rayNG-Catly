package com.v2ray.ang.handler.location

import kotlinx.coroutines.delay

/** Plain copy of the core's connection-test answer, so the sequencing logic stays free of Android types. */
data class RawMeasurement(
    val delayMillis: Long,
    val country: String?,
    val countryCode: String?,
    val ipAddress: String?,
    val regionCode: String?,
    val regionName: String?,
    val city: String?,
    val latitude: Double?,
    val longitude: Double?,
)

sealed interface MeasureResult {
    data class Done(val measurement: RawMeasurement) : MeasureResult

    /** The core answered that it is not running (or is reloading) and no result will come. */
    data object NotRunning : MeasureResult
    data object TimedOut : MeasureResult
    data object Failed : MeasureResult
}

enum class StartResult { STARTED, FAILED, TIMED_OUT }

/**
 * The thin Android-facing seam. Every method must be honest about uncertainty and must never throw:
 * IPC problems become UNKNOWN / FAILED / TIMED_OUT. Nothing here caches state; each call asks again.
 */
interface ServiceGateway {
    suspend fun queryState(): ServiceState
    fun selectedGuid(): String?
    fun select(guid: String)

    /** Requests a (re)start of the core on the currently selected server and waits for the confirmation event. */
    suspend fun startOrRestart(): StartResult
    suspend fun requestStop()

    /** Asks the running core to test through its own route and report the exit observed there. */
    suspend fun measure(): MeasureResult
}

data class ConnectorTiming(
    val settleMillis: Long = 1_000,
    val stopPolls: Int = 8,
    val stopPollIntervalMillis: Long = 1_000,
)

object ObservationReconciler {
    /**
     * Turns a raw measurement into an outcome. An answer is only usable as evidence when it is
     * self-consistent: a successful delay test, an ISO country that does not contradict the provider's
     * other country field, and a syntactically valid IP address.
     */
    fun reconcile(raw: RawMeasurement, source: String, nowMillis: Long, serverGuid: String?): ConnectOutcome {
        if (raw.delayMillis < 0) return ConnectOutcome.ConnectionFailed
        val fromCode = LocationProfileValidator.normalizeCountry(raw.countryCode)
        val fromName = LocationProfileValidator.normalizeCountry(raw.country)
        if (fromCode != null && fromName != null && fromCode != fromName) {
            return ConnectOutcome.GeoUnavailable // conflicting fields: inconclusive, never "convenient"
        }
        val country = fromCode ?: fromName ?: return ConnectOutcome.GeoUnavailable
        if (IpFamily.of(raw.ipAddress) == IpFamily.UNKNOWN) return ConnectOutcome.GeoUnavailable
        return ConnectOutcome.Observed(
            ObservedLocation(
                source = source,
                observedAtMillis = nowMillis,
                ipAddress = raw.ipAddress,
                countryCode = country,
                regionCodeRaw = raw.regionCode,
                regionName = raw.regionName,
                cityName = raw.city,
                latitude = raw.latitude,
                longitude = raw.longitude,
                serverGuid = serverGuid,
            )
        )
    }
}

/**
 * Connection sequencing over a [ServiceGateway]. Pure Kotlin: every decision about unknown state,
 * confirmation and selection read-back is unit-tested.
 *
 * It does not claim more than it can know. The daemon starts whichever server is selected *at start
 * time* and does not report which one it runs, so "this server was used" is supported by selection
 * read-backs before and after (and the observation is discarded if they disagree), not proven.
 */
class GatewayConnector(
    private val gateway: ServiceGateway,
    private val providerHost: () -> String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val timing: ConnectorTiming = ConnectorTiming(),
) : CandidateConnector {

    override suspend fun snapshot(): ConnectionSnapshot? {
        val state = gateway.queryState()
        val selected = gateway.selectedGuid()
        return when (state) {
            ServiceState.RUNNING -> ConnectionSnapshot(selected, true)
            ServiceState.STOPPED -> ConnectionSnapshot(selected, false)
            ServiceState.UNKNOWN -> null // never guess
        }
    }

    override suspend fun connectAndObserve(guid: String): ConnectOutcome {
        // The state is re-read every time; nothing from an earlier step is trusted.
        if (gateway.queryState() == ServiceState.UNKNOWN) return ConnectOutcome.StateUnknown
        gateway.select(guid)
        if (gateway.selectedGuid() != guid) return ConnectOutcome.ConnectionFailed
        if (gateway.startOrRestart() != StartResult.STARTED) return ConnectOutcome.ConnectionFailed
        // A start callback is not proof of a working route or of the intended profile: confirm.
        when (gateway.queryState()) {
            ServiceState.RUNNING -> Unit
            ServiceState.STOPPED -> return ConnectOutcome.ConnectionFailed
            ServiceState.UNKNOWN -> return ConnectOutcome.StateUnknown
        }
        if (gateway.selectedGuid() != guid) return ConnectOutcome.ConnectionFailed // changed by someone else
        delay(timing.settleMillis)
        return observe(expectedGuid = guid)
    }

    /** Observes the exit of whatever the core is connected to. Starts nothing. */
    suspend fun observeActive(): ConnectOutcome {
        when (gateway.queryState()) {
            ServiceState.STOPPED -> return ConnectOutcome.NotRunning
            ServiceState.UNKNOWN -> return ConnectOutcome.StateUnknown
            ServiceState.RUNNING -> Unit
        }
        return observe(expectedGuid = null)
    }

    private suspend fun observe(expectedGuid: String?): ConnectOutcome {
        val guidBefore = gateway.selectedGuid()
        val outcome = when (val m = gateway.measure()) {
            is MeasureResult.Done -> ObservationReconciler.reconcile(m.measurement, providerHost(), nowMillis(), guidBefore)
            MeasureResult.NotRunning -> ConnectOutcome.NotRunning
            MeasureResult.TimedOut, MeasureResult.Failed -> ConnectOutcome.ConnectionFailed
        }
        if (outcome is ConnectOutcome.Observed) {
            val after = gateway.selectedGuid()
            // The selection moved during the measurement: this answer may describe a different server.
            if (after != guidBefore || (expectedGuid != null && after != expectedGuid)) {
                return ConnectOutcome.ConnectionFailed
            }
        }
        return outcome
    }

    /**
     * Puts selection and running state back. Returns true **only** when the target state was confirmed
     * by a fresh query; any doubt is false so the caller reports it.
     */
    override suspend fun restore(snapshot: ConnectionSnapshot): Boolean {
        val wanted = snapshot.selectedGuid
        if (wanted != null) {
            gateway.select(wanted)
            if (gateway.selectedGuid() != wanted) return false
        }
        if (snapshot.wasRunning) {
            if (gateway.startOrRestart() != StartResult.STARTED) return false
            return gateway.queryState() == ServiceState.RUNNING && (wanted == null || gateway.selectedGuid() == wanted)
        }
        gateway.requestStop()
        repeat(timing.stopPolls) {
            delay(timing.stopPollIntervalMillis)
            if (gateway.queryState() == ServiceState.STOPPED) return wanted == null || gateway.selectedGuid() == wanted
        }
        return false
    }
}
