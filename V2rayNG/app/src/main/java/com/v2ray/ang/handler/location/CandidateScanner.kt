package com.v2ray.ang.handler.location

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean

/** Which server was selected and whether the core was running, before a scan changed anything. */
data class ConnectionSnapshot(val selectedGuid: String?, val wasRunning: Boolean)

sealed interface ConnectOutcome {
    /** The route works and the provider returned a location for the IP seen through it. */
    data class Observed(val location: ObservedLocation) : ConnectOutcome
    data object ConnectionFailed : ConnectOutcome

    /** Nothing is running, so nothing could be observed (and nothing was sent). */
    data object NotRunning : ConnectOutcome

    /** The route works but the geolocation provider returned nothing usable. */
    data object GeoUnavailable : ConnectOutcome

    /** Whether the service is running could not be established; continuing would be unsafe. */
    data object StateUnknown : ConnectOutcome
}

/**
 * The only seam to the real connection lifecycle. The production implementation drives the existing
 * service (select server, start/restart, wait for start, ask the *running core* for the exit
 * location). Everything else in the scan logic is plain Kotlin and unit-tested.
 */
interface CandidateConnector {
    /** Null when the current state cannot be determined; a scan is then refused rather than guessed. */
    suspend fun snapshot(): ConnectionSnapshot?

    /** Selects [guid], (re)starts the core on it, waits for it, and observes the exit through it. */
    suspend fun connectAndObserve(guid: String): ConnectOutcome

    /** Puts selection and running state back as in [snapshot]. Returns false if that could not be confirmed. */
    suspend fun restore(snapshot: ConnectionSnapshot): Boolean
}

/** Persistent record of "a scan changed the user's connection", used to offer recovery after a crash. */
interface ScanJournal {
    fun save(snapshot: ConnectionSnapshot)
    fun clear()

    /** True while an earlier operation left a record that has not been restored or dismissed. */
    fun hasPending(): Boolean
}

data class CandidateRef(val guid: String, val label: String)

enum class RefusalReason {
    ALREADY_RUNNING, NO_CANDIDATES, STATE_UNKNOWN,

    /** Nothing is selected, so there is no previous server that could be restored afterwards. */
    NO_SELECTED_SERVER,

    /** An earlier interrupted operation's restore record exists; overwriting it would lose the original state. */
    INTERRUPTED_SCAN_PENDING,
}

/** Why a scan ended early. Cleanup and restoration still ran. */
enum class StopReason { STATE_UNKNOWN, DEADLINE }

sealed interface ScanOutcome {
    data object Completed : ScanOutcome
    data class Stopped(val reason: StopReason) : ScanOutcome
    data class Refused(val reason: RefusalReason) : ScanOutcome
}

/**
 * Tests candidates strictly one at a time. It never runs two connections at once, never switches
 * silently (callers must obtain consent first), and always restores the original state in `finally`,
 * including on cancellation.
 */
class CandidateScanner(
    private val connector: CandidateConnector,
    private val journal: ScanJournal,
    private val desired: DesiredLocation,
    private val catalog: GeoCatalog?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxCandidates: Int = MAX_CANDIDATES,
    /** Upper bound for one candidate (start + settle + measurement). Exceeding it fails that candidate. */
    private val perCandidateMillis: Long = PER_CANDIDATE_MILLIS,
    /** Upper bound for the whole scan; remaining candidates stay unchecked. */
    private val totalMillis: Long = TOTAL_MILLIS,
) {
    companion object {
        const val MAX_CANDIDATES = 30
        const val PER_CANDIDATE_MILLIS = 150_000L
        const val TOTAL_MILLIS = 25 * 60_000L
    }

    private val running = AtomicBoolean(false)

    /** Random per-scan salt: fingerprints cannot be reversed to an address and do not link scans. */
    private val salt: ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }

    private fun fingerprint(ip: String?): String? {
        if (ip.isNullOrBlank()) return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(ip.trim().lowercase().toByteArray(Charsets.UTF_8))
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    val isRunning: Boolean get() = running.get()

    /**
     * @param onUpdate called with the full list whenever a result changes.
     * @param onRestoreFinished called once the original state has been (attempted to be) restored.
     */
    suspend fun scan(
        candidates: List<CandidateRef>,
        onUpdate: (List<CandidateResult>) -> Unit,
        onRestoreFinished: (restored: Boolean) -> Unit = {},
    ): ScanOutcome {
        if (!running.compareAndSet(false, true)) return ScanOutcome.Refused(RefusalReason.ALREADY_RUNNING)
        try {
            val unique = candidates.distinctBy { it.guid }.take(maxCandidates)
            if (unique.isEmpty()) return ScanOutcome.Refused(RefusalReason.NO_CANDIDATES)
            if (journal.hasPending()) return ScanOutcome.Refused(RefusalReason.INTERRUPTED_SCAN_PENDING)
            val snapshot = connector.snapshot() ?: return ScanOutcome.Refused(RefusalReason.STATE_UNKNOWN)
            if (snapshot.selectedGuid == null) return ScanOutcome.Refused(RefusalReason.NO_SELECTED_SERVER)

            val results = unique.map { CandidateResult(it.guid, it.label) }.toMutableList()
            onUpdate(results.toList())
            // Written before the first change so a crash or kill can be recovered from.
            journal.save(snapshot)
            var stopped: StopReason? = null
            val startedAt = nowMillis()
            try {
                for ((index, candidate) in unique.withIndex()) {
                    yield() // cancellation point between candidates
                    if (nowMillis() - startedAt > totalMillis) {
                        stopped = StopReason.DEADLINE
                        break
                    }
                    results[index] = results[index].copy(state = CandidateState.CHECKING)
                    onUpdate(results.toList())
                    val outcome = try {
                        // A hung connector cannot stall the scan: every candidate is time-boxed.
                        withTimeoutOrNull(perCandidateMillis) { connector.connectAndObserve(candidate.guid) }
                            ?: ConnectOutcome.ConnectionFailed
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A failing connector must not abort the scan or skip the restore below.
                        ConnectOutcome.ConnectionFailed
                    }
                    results[index] = toResult(candidate, outcome)
                    onUpdate(results.toList())
                    if (outcome == ConnectOutcome.StateUnknown) {
                        // The service state is no longer known: stop testing, go straight to cleanup.
                        stopped = StopReason.STATE_UNKNOWN
                        break
                    }
                }
            } finally {
                val restored = withContext(NonCancellable) {
                    for (i in results.indices) {
                        if (results[i].state == CandidateState.CHECKING) {
                            results[i] = results[i].copy(state = CandidateState.NOT_CHECKED)
                        }
                    }
                    onUpdate(results.toList())
                    val ok = try {
                        connector.restore(snapshot)
                    } catch (e: Exception) {
                        false
                    }
                    if (ok) journal.clear()
                    ok
                }
                onRestoreFinished(restored)
            }
            return stopped?.let { ScanOutcome.Stopped(it) } ?: ScanOutcome.Completed
        } finally {
            running.set(false)
        }
    }

    private fun toResult(candidate: CandidateRef, outcome: ConnectOutcome): CandidateResult {
        val base = CandidateResult(candidate.guid, candidate.label, checkedAtMillis = nowMillis())
        return when (outcome) {
            // The IP is used for nothing after this point, so the stored result does not keep it.
            is ConnectOutcome.Observed ->
                CandidateEvaluation.reclassify(
                    base.copy(
                        observed = outcome.location.copy(ipAddress = null),
                        exitFingerprint = fingerprint(outcome.location.ipAddress),
                    ),
                    desired, catalog
                )

            ConnectOutcome.GeoUnavailable -> base.copy(state = CandidateState.GEO_UNAVAILABLE)
            ConnectOutcome.ConnectionFailed, ConnectOutcome.NotRunning, ConnectOutcome.StateUnknown ->
                base.copy(state = CandidateState.CONNECTION_FAILED)
        }
    }
}

object CandidateEvaluation {
    /**
     * True when two or more tested servers reported the same exit address. That is legitimate for servers
     * of one provider, but it is also what happens when the geolocation request is not routed through the
     * servers at all (for example a `direct` routing rule), so the UI warns instead of trusting silently.
     */
    fun sharedExit(results: Collection<CandidateResult>): Boolean =
        results.mapNotNull { it.exitFingerprint }.groupingBy { it }.eachCount().any { it.value >= 2 }

    fun stateOf(verdict: MatchVerdict): CandidateState = when (verdict) {
        MatchVerdict.VERIFIED_MATCH -> CandidateState.VERIFIED_MATCH
        MatchVerdict.COUNTRY_MATCH_ONLY -> CandidateState.COUNTRY_MATCH_ONLY
        MatchVerdict.REGION_CITY_UNVERIFIED -> CandidateState.REGION_CITY_UNVERIFIED
        MatchVerdict.NO_MATCH -> CandidateState.NO_MATCH
        MatchVerdict.GEO_UNAVAILABLE -> CandidateState.GEO_UNAVAILABLE
    }

    /**
     * Re-evaluates a stored observation against the *current* desired location/policy, so changing
     * the request does not need a rescan and an old verdict can never outlive the request it answered.
     * Results without an observation (not checked, connection failed, ...) are returned unchanged.
     */
    fun reclassify(result: CandidateResult, desired: DesiredLocation?, catalog: GeoCatalog?): CandidateResult {
        val observed = result.observed ?: return result
        if (desired == null) return result.copy(state = CandidateState.NOT_CHECKED, match = null)
        val match = LocationMatcher.evaluate(desired, observed, catalog)
        return result.copy(state = stateOf(match.verdict), match = match)
    }
}
