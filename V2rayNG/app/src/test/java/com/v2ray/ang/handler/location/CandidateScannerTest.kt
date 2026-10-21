package com.v2ray.ang.handler.location

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A model of the service: one selected server, one running flag, strictly one active connection. */
private class FakeConnector(
    var selected: String? = "orig",
    var running: Boolean = true,
    private val outcomes: Map<String, ConnectOutcome> = emptyMap(),
    var snapshotAvailable: Boolean = true,
    var failRestore: Boolean = false,
    private val gate: CompletableDeferred<Unit>? = null,
    private val throwFor: Set<String> = emptySet(),
    private val hangFor: Set<String> = emptySet(),
) : CandidateConnector {
    val connectOrder = mutableListOf<String>()
    var concurrent = 0
    var maxConcurrent = 0
    var restoreCalls = 0

    override suspend fun snapshot(): ConnectionSnapshot? =
        if (snapshotAvailable) ConnectionSnapshot(selected, running) else null

    override suspend fun connectAndObserve(guid: String): ConnectOutcome {
        concurrent++
        maxConcurrent = maxOf(maxConcurrent, concurrent)
        try {
            connectOrder += guid
            selected = guid
            running = true
            if (guid in throwFor) throw IllegalStateException("boom")
            if (guid in hangFor) CompletableDeferred<Unit>().await() // never completes
            gate?.await()
            delay(1)
            return outcomes[guid] ?: ConnectOutcome.ConnectionFailed
        } finally {
            concurrent--
        }
    }

    override suspend fun restore(snapshot: ConnectionSnapshot): Boolean {
        restoreCalls++
        if (failRestore) return false
        selected = snapshot.selectedGuid
        running = snapshot.wasRunning
        return true
    }
}

private class FakeJournal : ScanJournal {
    var saved: ConnectionSnapshot? = null
    var saveCalls = 0
    var clearCalls = 0
    override fun save(snapshot: ConnectionSnapshot) { saved = snapshot; saveCalls++ }
    override fun clear() { saved = null; clearCalls++ }
    override fun hasPending() = saved != null
}

class CandidateScannerTest {

    private val desired = DesiredLocation("DE")
    private fun loc(country: String?) = ObservedLocation("p.test", 5L, "203.0.113.1", country, serverGuid = null)
    private val refs = listOf(CandidateRef("a", "A"), CandidateRef("b", "B"), CandidateRef("c", "C"))

    private fun scanner(c: FakeConnector, j: FakeJournal = FakeJournal(), max: Int = CandidateScanner.MAX_CANDIDATES) =
        CandidateScanner(c, j, desired, null, nowMillis = { 100L }, maxCandidates = max)

    @Test
    fun testsCandidatesOneAtATimeInOrderAndClassifiesEachHonestly(): Unit = runBlocking {
        val connector = FakeConnector(
            outcomes = mapOf(
                "a" to ConnectOutcome.Observed(loc("DE")),
                "b" to ConnectOutcome.Observed(loc("US")),
                "c" to ConnectOutcome.GeoUnavailable,
            )
        )
        var last = emptyList<CandidateResult>()
        val out = scanner(connector).scan(refs, onUpdate = { last = it })
        assertEquals(ScanOutcome.Completed, out)
        assertEquals(listOf("a", "b", "c"), connector.connectOrder)
        assertEquals(1, connector.maxConcurrent)
        assertEquals(
            listOf(CandidateState.VERIFIED_MATCH, CandidateState.NO_MATCH, CandidateState.GEO_UNAVAILABLE),
            last.map { it.state }
        )
        assertEquals(100L, last[0].checkedAtMillis)
    }

    @Test
    fun connectionFailuresAreNeverReportedAsMatchesAndDoNotStopTheScan(): Unit = runBlocking {
        val connector = FakeConnector(
            outcomes = mapOf("b" to ConnectOutcome.Observed(loc("DE")), "c" to ConnectOutcome.NotRunning),
            throwFor = setOf("a"),
        )
        var last = emptyList<CandidateResult>()
        scanner(connector).scan(refs, onUpdate = { last = it })
        assertEquals(
            listOf(CandidateState.CONNECTION_FAILED, CandidateState.VERIFIED_MATCH, CandidateState.CONNECTION_FAILED),
            last.map { it.state }
        )
        assertEquals(listOf("a", "b", "c"), connector.connectOrder)
    }

    @Test
    fun originalSelectionAndRunningStateAreRestoredAndJournalCleared(): Unit = runBlocking {
        val connector = FakeConnector(selected = "orig", running = true)
        val journal = FakeJournal()
        var restored: Boolean? = null
        scanner(connector, journal).scan(refs, {}, { restored = it })
        assertEquals("orig", connector.selected)
        assertTrue(connector.running)
        assertEquals(true, restored)
        assertNull(journal.saved)
        assertEquals(1, journal.saveCalls)
        assertEquals(1, journal.clearCalls)
    }

    @Test
    fun aScanStartedWhileDisconnectedLeavesItDisconnected(): Unit = runBlocking {
        val connector = FakeConnector(selected = "orig", running = false)
        scanner(connector).scan(refs, {})
        assertFalse(connector.running)
        assertEquals("orig", connector.selected)
    }

    @Test
    fun journalIsWrittenBeforeTheFirstConnectionChange(): Unit = runBlocking {
        val journal = FakeJournal()
        val connector = object : CandidateConnector {
            var savedBeforeFirstConnect: Boolean? = null
            override suspend fun snapshot() = ConnectionSnapshot("orig", true)
            override suspend fun connectAndObserve(guid: String): ConnectOutcome {
                if (savedBeforeFirstConnect == null) savedBeforeFirstConnect = journal.saved != null
                return ConnectOutcome.ConnectionFailed
            }
            override suspend fun restore(snapshot: ConnectionSnapshot) = true
        }
        CandidateScanner(connector, journal, desired, null).scan(refs, {})
        assertEquals(true, connector.savedBeforeFirstConnect)
    }

    @Test
    fun failedRestoreKeepsTheJournalSoRecoveryCanBeOffered(): Unit = runBlocking {
        val journal = FakeJournal()
        var restored: Boolean? = null
        scanner(FakeConnector(failRestore = true), journal).scan(refs, {}, { restored = it })
        assertEquals(false, restored)
        assertNotNull(journal.saved)
        assertEquals(0, journal.clearCalls)
    }

    @Test
    fun cancellationStillRestoresTheOriginalStateAndResetsInFlightRows(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>() // never completed: candidate "a" hangs
        val connector = FakeConnector(gate = gate)
        val journal = FakeJournal()
        var last = emptyList<CandidateResult>()
        var restored: Boolean? = null
        val job = launch {
            scanner(connector, journal).scan(refs, { last = it }, { restored = it })
        }
        delay(50)
        assertEquals("a", connector.selected) // scan really changed the selection...
        job.cancelAndJoin()
        assertEquals("orig", connector.selected) // ...and cancelling put it back
        assertTrue(connector.running)
        assertEquals(true, restored)
        assertTrue(last.none { it.state == CandidateState.CHECKING })
        assertNull(journal.saved)
    }

    @Test
    fun aSecondScanWhileOneIsRunningIsRefusedNotInterleaved(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val connector = FakeConnector(gate = gate)
        val s = scanner(connector)
        val first = async { s.scan(refs, {}) }
        delay(50)
        assertTrue(s.isRunning)
        assertEquals(ScanOutcome.Refused(RefusalReason.ALREADY_RUNNING), s.scan(refs, {}))
        gate.complete(Unit)
        first.await()
        assertFalse(s.isRunning)
        assertEquals(1, connector.maxConcurrent)
    }

    @Test
    fun duplicatesAreCollapsedAndTheCandidateCountIsCapped(): Unit = runBlocking {
        val many = (1..50).map { CandidateRef("g${it % 40}", "S$it") }
        val connector = FakeConnector()
        scanner(connector, max = 5).scan(many, {})
        assertEquals(5, connector.connectOrder.size)
        assertEquals(connector.connectOrder.toSet().size, connector.connectOrder.size)
    }

    @Test
    fun refusesWhenThereIsNothingToTestOrTheStateIsUnknownAndChangesNothing(): Unit = runBlocking {
        val journal = FakeJournal()
        val c1 = FakeConnector()
        assertEquals(ScanOutcome.Refused(RefusalReason.NO_CANDIDATES), scanner(c1, journal).scan(emptyList(), {}))
        val c2 = FakeConnector(snapshotAvailable = false)
        assertEquals(ScanOutcome.Refused(RefusalReason.STATE_UNKNOWN), scanner(c2, journal).scan(refs, {}))
        assertTrue(c1.connectOrder.isEmpty() && c2.connectOrder.isEmpty())
        assertEquals(0, journal.saveCalls)
        assertEquals(0, c2.restoreCalls)
    }

    @Test
    fun noMatchingServerIsReportedAsSuchAndNothingIsSilentlySelected(): Unit = runBlocking {
        val connector = FakeConnector(
            selected = "orig",
            outcomes = mapOf("a" to ConnectOutcome.Observed(loc("US")), "b" to ConnectOutcome.Observed(loc("FR")),
                "c" to ConnectOutcome.ConnectionFailed)
        )
        var last = emptyList<CandidateResult>()
        scanner(connector).scan(refs, { last = it })
        assertTrue(last.none { it.state.isSelectable })
        assertEquals("orig", connector.selected)
    }

    @Test
    fun finerLocationVerdictsFlowThroughTheScan(): Unit = runBlocking {
        val catalog = GeoCatalogParser.parse(
            """{"schema":1,"regions":{"DE":[["DE-BY","Bayern"]]},"cities":{}}"""
        )
        val want = DesiredLocation("DE", regionCode = "DE-BY")
        val connector = FakeConnector(
            outcomes = mapOf(
                "a" to ConnectOutcome.Observed(loc("DE").copy(regionCodeRaw = "BY")),
                "b" to ConnectOutcome.Observed(loc("DE")),
                "c" to ConnectOutcome.Observed(loc("DE").copy(regionCodeRaw = "BE")),
            )
        )
        var last = emptyList<CandidateResult>()
        val cat2 = GeoCatalogParser.parse(
            """{"schema":1,"regions":{"DE":[["DE-BY","Bayern"],["DE-BE","Berlin"]]},"cities":{}}"""
        )
        CandidateScanner(connector, FakeJournal(), want, cat2, { 1L }).scan(refs, { last = it })
        assertEquals(
            listOf(CandidateState.VERIFIED_MATCH, CandidateState.REGION_CITY_UNVERIFIED, CandidateState.COUNTRY_MATCH_ONLY),
            last.map { it.state }
        )
        assertNotNull(catalog)
    }

    @Test
    fun storedResultsDoNotRetainTheIpAddress() = runBlocking {
        val connector = FakeConnector(outcomes = mapOf("a" to ConnectOutcome.Observed(loc("DE"))))
        var last = emptyList<CandidateResult>()
        scanner(connector).scan(listOf(CandidateRef("a", "A")), { last = it })
        assertNull(last.single().observed!!.ipAddress)
    }

    @Test
    fun changingTheRequestReclassifiesStoredObservationsWithoutRescanning() {
        val observed = ObservedLocation("p", 1L, null, "DE")
        val stored = CandidateResult("a", "A", CandidateState.VERIFIED_MATCH, observed, null, 1L)
        assertEquals(CandidateState.VERIFIED_MATCH,
            CandidateEvaluation.reclassify(stored, DesiredLocation("DE"), null).state)
        assertEquals(CandidateState.NO_MATCH,
            CandidateEvaluation.reclassify(stored, DesiredLocation("FR"), null).state)
        // A finer request the provider cannot confirm downgrades the old "verified" verdict.
        assertEquals(CandidateState.REGION_CITY_UNVERIFIED,
            CandidateEvaluation.reclassify(stored, DesiredLocation("DE", regionCode = "DE-BY"), null).state)
        assertEquals(CandidateState.NOT_CHECKED, CandidateEvaluation.reclassify(stored, null, null).state)
        val failed = CandidateResult("b", "B", CandidateState.CONNECTION_FAILED)
        assertEquals(failed, CandidateEvaluation.reclassify(failed, DesiredLocation("DE"), null))
    }

    @Test
    fun scanIsRefusedWhenNothingIsSelectedBecauseNothingCouldBeRestored() = runBlocking {
        val connector = FakeConnector(selected = null, running = false)
        val journal = FakeJournal()
        assertEquals(ScanOutcome.Refused(RefusalReason.NO_SELECTED_SERVER), scanner(connector, journal).scan(refs, {}))
        assertTrue(connector.connectOrder.isEmpty())
        assertEquals(0, journal.saveCalls)
    }

    @Test
    fun aHungCandidateIsTimeBoxedAndTheScanContinuesWithTheNext() = runBlocking {
        val connector = FakeConnector(
            hangFor = setOf("a"),
            outcomes = mapOf("b" to ConnectOutcome.Observed(loc("DE")))
        )
        var last = emptyList<CandidateResult>()
        val s = CandidateScanner(connector, FakeJournal(), desired, null, { 100L }, perCandidateMillis = 50)
        assertEquals(ScanOutcome.Completed, s.scan(refs.take(2), { last = it }))
        assertEquals(listOf(CandidateState.CONNECTION_FAILED, CandidateState.VERIFIED_MATCH), last.map { it.state })
        assertEquals("orig", connector.selected) // and the state was restored afterwards
    }

    @Test
    fun theWholeScanHasADeadlineAfterWhichRemainingCandidatesStayUnchecked() = runBlocking {
        var clock = 0L
        val connector = FakeConnector(outcomes = refs.associate { it.guid to ConnectOutcome.Observed(loc("DE")) })
        val journal = FakeJournal()
        var last = emptyList<CandidateResult>()
        var restored: Boolean? = null
        val s = CandidateScanner(connector, journal, desired, null, { clock.also { clock += 10 } }, totalMillis = 25)
        val out = s.scan(refs, { last = it }, { restored = it })
        assertEquals(ScanOutcome.Stopped(StopReason.DEADLINE), out)
        assertTrue(last.any { it.state == CandidateState.NOT_CHECKED })
        assertTrue(last.none { it.state == CandidateState.CHECKING })
        assertEquals(true, restored)
        assertNull(journal.saved)
    }

    @Test
    fun unknownServiceStateStopsTheScanImmediatelyAndStillRestores() = runBlocking {
        val connector = FakeConnector(outcomes = mapOf("a" to ConnectOutcome.StateUnknown))
        var last = emptyList<CandidateResult>()
        var restored: Boolean? = null
        val out = scanner(connector).scan(refs, { last = it }, { restored = it })
        assertEquals(ScanOutcome.Stopped(StopReason.STATE_UNKNOWN), out)
        assertEquals(listOf("a"), connector.connectOrder) // b and c were never attempted
        assertEquals(CandidateState.CONNECTION_FAILED, last[0].state)
        assertEquals(CandidateState.NOT_CHECKED, last[1].state)
        assertEquals(1, connector.restoreCalls)
        assertEquals(true, restored)
    }

    @Test
    fun sharedExitAddressesAreDetectedWithoutKeepingTheAddress() = runBlocking {
        fun at(ip: String, country: String = "DE") =
            ConnectOutcome.Observed(ObservedLocation("p", 1L, ip, country))
        val connector = FakeConnector(
            outcomes = mapOf("a" to at("203.0.113.5"), "b" to at("203.0.113.5"), "c" to at("198.51.100.9"))
        )
        var last = emptyList<CandidateResult>()
        scanner(connector).scan(refs, { last = it })
        assertEquals(last[0].exitFingerprint, last[1].exitFingerprint)
        assertTrue(last[0].exitFingerprint != last[2].exitFingerprint)
        assertTrue(CandidateEvaluation.sharedExit(last))
        assertTrue(last.none { it.observed!!.ipAddress != null })
        assertTrue(last.none { it.toString().contains("203.0.113.5") })
    }

    @Test
    fun distinctExitAddressesAreNotFlaggedAndFingerprintsDoNotLinkScans() = runBlocking {
        val outcomes = mapOf(
            "a" to ConnectOutcome.Observed(ObservedLocation("p", 1L, "203.0.113.5", "DE")),
            "b" to ConnectOutcome.Observed(ObservedLocation("p", 1L, "198.51.100.9", "DE")),
        )
        var first = emptyList<CandidateResult>()
        var second = emptyList<CandidateResult>()
        scanner(FakeConnector(outcomes = outcomes)).scan(refs.take(2), { first = it })
        scanner(FakeConnector(outcomes = outcomes)).scan(refs.take(2), { second = it })
        assertFalse(CandidateEvaluation.sharedExit(first))
        assertTrue(first[0].exitFingerprint != second[0].exitFingerprint) // different salt per scan
        assertFalse(CandidateEvaluation.sharedExit(listOf(CandidateResult("x", "X"))))
    }

    @Test
    fun anUnresolvedInterruptedScanBlocksANewOneSoTheOriginalRecordIsNeverOverwritten() = runBlocking {
        val journal = FakeJournal().also { it.saved = ConnectionSnapshot("the-original", true) }
        val connector = FakeConnector()
        val out = scanner(connector, journal).scan(refs, {})
        assertEquals(ScanOutcome.Refused(RefusalReason.INTERRUPTED_SCAN_PENDING), out)
        assertEquals(ConnectionSnapshot("the-original", true), journal.saved)
        assertEquals(0, journal.saveCalls)
        assertTrue(connector.connectOrder.isEmpty())
    }
}
