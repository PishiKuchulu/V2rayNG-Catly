package com.v2ray.ang.handler.location

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class FakeGateway(var state: ServiceState = ServiceState.RUNNING, var selected: String? = "orig") : ServiceGateway {
    val calls = mutableListOf<String>()
    val script = ArrayDeque<ServiceState>()
    var startResult = StartResult.STARTED
    var measureResult: MeasureResult = MeasureResult.Failed
    var selectIgnored = false
    var stopWorks = true
    var onStart: () -> Unit = {}
    var onMeasure: () -> Unit = {}

    override suspend fun queryState(): ServiceState {
        calls += "query"
        return script.removeFirstOrNull() ?: state
    }

    override fun selectedGuid() = selected
    override fun select(guid: String) {
        calls += "select:$guid"
        if (!selectIgnored) selected = guid
    }

    override suspend fun startOrRestart(): StartResult {
        calls += "start"
        if (startResult == StartResult.STARTED) state = ServiceState.RUNNING
        onStart()
        return startResult
    }

    override suspend fun requestStop() {
        calls += "stop"
        if (stopWorks) state = ServiceState.STOPPED
    }

    override suspend fun measure(): MeasureResult {
        calls += "measure"
        onMeasure()
        return measureResult
    }
}

class GatewayConnectorTest {
    private val timing = ConnectorTiming(settleMillis = 0, stopPolls = 3, stopPollIntervalMillis = 0)
    private fun raw(
        delay: Long = 42, country: String? = "DE", code: String? = "DE", ip: String? = "203.0.113.5",
        region: String? = null,
    ) = RawMeasurement(delay, country, code, ip, region, null, null, null, null)

    private fun connector(g: FakeGateway) = GatewayConnector(g, { "provider.test" }, { 7L }, timing)

    private fun good(g: FakeGateway) {
        g.measureResult = MeasureResult.Done(raw())
    }

    // ---- snapshot: running / stopped / unknown ------------------------------------------------

    @Test
    fun snapshotReportsRunningAndStoppedFromPositiveState() = runBlocking {
        assertEquals(ConnectionSnapshot("orig", true), connector(FakeGateway(ServiceState.RUNNING)).snapshot())
        assertEquals(ConnectionSnapshot("orig", false), connector(FakeGateway(ServiceState.STOPPED)).snapshot())
    }

    @Test
    fun snapshotIsNullWhenTheStateIsUnknownSoNothingIsAssumed() = runBlocking {
        assertNull(connector(FakeGateway(ServiceState.UNKNOWN)).snapshot())
    }

    @Test
    fun anUnknownStateRefusesAScanBeforeAnythingIsChanged() = runBlocking {
        val g = FakeGateway(ServiceState.UNKNOWN)
        val journal = object : ScanJournal {
            var saves = 0
            override fun save(snapshot: ConnectionSnapshot) { saves++ }
            override fun clear() {}
            override fun hasPending() = false
        }
        val out = CandidateScanner(connector(g), journal, DesiredLocation("DE"), null)
            .scan(listOf(CandidateRef("a", "A")), {})
        assertEquals(ScanOutcome.Refused(RefusalReason.STATE_UNKNOWN), out)
        assertEquals(0, journal.saves)
        assertTrue(g.calls.none { it == "start" || it == "stop" || it.startsWith("select") })
    }

    // ---- connectAndObserve ---------------------------------------------------------------------

    @Test
    fun successfulCandidateIsObservedForTheSelectedServerAfterConfirmedStart() = runBlocking {
        val g = FakeGateway(ServiceState.RUNNING); good(g)
        val out = connector(g).connectAndObserve("cand")
        val obs = (out as ConnectOutcome.Observed).location
        assertEquals("cand", obs.serverGuid)
        assertEquals("DE", obs.countryCode)
        assertEquals("provider.test", obs.source)
        // order: ask state -> select -> start -> confirm state -> measure
        assertEquals(listOf("query", "select:cand", "start", "query", "measure"), g.calls)
    }

    @Test
    fun unknownStateBeforeTheSwitchChangesNothing() = runBlocking {
        val g = FakeGateway(ServiceState.UNKNOWN)
        assertEquals(ConnectOutcome.StateUnknown, connector(g).connectAndObserve("cand"))
        assertEquals(listOf("query"), g.calls)
    }

    @Test
    fun startFailureAndStartTimeoutAreConnectionFailuresNeverMatches() = runBlocking {
        for (r in listOf(StartResult.FAILED, StartResult.TIMED_OUT)) {
            val g = FakeGateway(); g.startResult = r; good(g)
            assertEquals(ConnectOutcome.ConnectionFailed, connector(g).connectAndObserve("cand"), r.name)
            assertFalse(g.calls.contains("measure"), r.name)
        }
    }

    @Test
    fun aStartCallbackIsNotProofThatTheServiceRuns() = runBlocking {
        val stopped = FakeGateway(); good(stopped); stopped.script.addAll(listOf(ServiceState.RUNNING, ServiceState.STOPPED))
        assertEquals(ConnectOutcome.ConnectionFailed, connector(stopped).connectAndObserve("cand"))
        val unknown = FakeGateway(); good(unknown); unknown.script.addAll(listOf(ServiceState.RUNNING, ServiceState.UNKNOWN))
        assertEquals(ConnectOutcome.StateUnknown, connector(unknown).connectAndObserve("cand"))
        assertFalse(unknown.calls.contains("measure"))
    }

    @Test
    fun aSelectionThatDidNotTakeEffectMeansNoStartAtAll() = runBlocking {
        val g = FakeGateway(); g.selectIgnored = true; good(g)
        assertEquals(ConnectOutcome.ConnectionFailed, connector(g).connectAndObserve("cand"))
        assertFalse(g.calls.contains("start"))
    }

    @Test
    fun selectionChangedByAnotherActorAfterStartInvalidatesTheCandidate() = runBlocking {
        val g = FakeGateway(); good(g)
        g.onStart = { g.selected = "someone-else" }
        assertEquals(ConnectOutcome.ConnectionFailed, connector(g).connectAndObserve("cand"))
        assertFalse(g.calls.contains("measure"))
    }

    @Test
    fun selectionChangedDuringTheMeasurementDiscardsTheObservation() = runBlocking {
        val g = FakeGateway(); good(g)
        g.onMeasure = { g.selected = "someone-else" }
        assertEquals(ConnectOutcome.ConnectionFailed, connector(g).connectAndObserve("cand"))
    }

    @Test
    fun measurementFailuresMapToHonestOutcomes() = runBlocking {
        fun outcome(m: MeasureResult): ConnectOutcome {
            val g = FakeGateway(); g.measureResult = m
            return runBlocking { connector(g).connectAndObserve("cand") }
        }
        assertEquals(ConnectOutcome.NotRunning, outcome(MeasureResult.NotRunning))
        assertEquals(ConnectOutcome.ConnectionFailed, outcome(MeasureResult.TimedOut))
        assertEquals(ConnectOutcome.ConnectionFailed, outcome(MeasureResult.Failed))
        assertEquals(ConnectOutcome.ConnectionFailed, outcome(MeasureResult.Done(raw(delay = -1))))
    }

    @Test
    fun unusableOrConflictingProviderAnswersAreInconclusiveNotMatches() = runBlocking {
        fun outcome(r: RawMeasurement): ConnectOutcome {
            val g = FakeGateway(); g.measureResult = MeasureResult.Done(r)
            return runBlocking { connector(g).connectAndObserve("cand") }
        }
        assertEquals(ConnectOutcome.GeoUnavailable, outcome(raw(country = null, code = null)))
        assertEquals(ConnectOutcome.GeoUnavailable, outcome(raw(country = "FR", code = "DE"))) // conflicting fields
        assertEquals(ConnectOutcome.GeoUnavailable, outcome(raw(ip = null)))                    // no address to tie it to
        assertEquals(ConnectOutcome.GeoUnavailable, outcome(raw(ip = "not-an-ip")))
        assertTrue(outcome(raw(country = null, code = "DE")) is ConnectOutcome.Observed)
        assertTrue(outcome(raw(country = "DE", code = null)) is ConnectOutcome.Observed)
    }

    // ---- observeActive ---------------------------------------------------------------------------

    @Test
    fun observingTheActiveConnectionNeverStartsOrSwitchesAnything() = runBlocking {
        val running = FakeGateway(ServiceState.RUNNING); good(running)
        assertTrue(connector(running).observeActive() is ConnectOutcome.Observed)
        assertTrue(running.calls.none { it == "start" || it == "stop" || it.startsWith("select") })

        val stopped = FakeGateway(ServiceState.STOPPED); good(stopped)
        assertEquals(ConnectOutcome.NotRunning, connector(stopped).observeActive())
        assertFalse(stopped.calls.contains("measure")) // nothing is sent when nothing runs

        val unknown = FakeGateway(ServiceState.UNKNOWN); good(unknown)
        assertEquals(ConnectOutcome.StateUnknown, connector(unknown).observeActive())
        assertFalse(unknown.calls.contains("measure"))
    }

    // ---- restore ----------------------------------------------------------------------------------

    @Test
    fun restoreOfARunningConnectionIsConfirmedByAFreshStateQuery() = runBlocking {
        val g = FakeGateway(ServiceState.RUNNING, selected = "cand")
        assertTrue(connector(g).restore(ConnectionSnapshot("orig", true)))
        assertEquals("orig", g.selected)
        assertEquals("query", g.calls.last())
    }

    @Test
    fun restoreIsNotClaimedWhenTheRestartFailsOrCannotBeConfirmed() = runBlocking {
        val failed = FakeGateway(); failed.startResult = StartResult.FAILED
        assertFalse(connector(failed).restore(ConnectionSnapshot("orig", true)))
        val timedOut = FakeGateway(); timedOut.startResult = StartResult.TIMED_OUT
        assertFalse(connector(timedOut).restore(ConnectionSnapshot("orig", true)))
        val unconfirmed = FakeGateway(); unconfirmed.script.add(ServiceState.UNKNOWN)
        assertFalse(connector(unconfirmed).restore(ConnectionSnapshot("orig", true)))
        val wrongSelection = FakeGateway(); wrongSelection.selectIgnored = true
        assertFalse(connector(wrongSelection).restore(ConnectionSnapshot("other", true)))
    }

    @Test
    fun restoreOfAStoppedStateWaitsForConfirmedStopAndGivesUpBoundedly() = runBlocking {
        val ok = FakeGateway(ServiceState.RUNNING, selected = "cand")
        assertTrue(connector(ok).restore(ConnectionSnapshot("orig", false)))
        assertEquals("orig", ok.selected)

        val stuck = FakeGateway(ServiceState.RUNNING); stuck.stopWorks = false
        assertFalse(connector(stuck).restore(ConnectionSnapshot("orig", false)))
        assertEquals(timing.stopPolls, stuck.calls.count { it == "query" }) // bounded: exactly stopPolls queries

        val silent = FakeGateway(ServiceState.UNKNOWN); silent.stopWorks = false
        assertFalse(connector(silent).restore(ConnectionSnapshot("orig", false))) // unknown is never "stopped"
    }

    @Test
    fun fullScanThroughTheGatewayRestoresTheOriginalRunningServer() = runBlocking {
        val g = FakeGateway(ServiceState.RUNNING, selected = "orig"); good(g)
        var restored: Boolean? = null
        val out = CandidateScanner(connector(g), object : ScanJournal {
            override fun save(snapshot: ConnectionSnapshot) {}
            override fun clear() {}
            override fun hasPending() = false
        }, DesiredLocation("DE"), null).scan(listOf(CandidateRef("a", "A"), CandidateRef("b", "B")), {}, { restored = it })
        assertEquals(ScanOutcome.Completed, out)
        assertEquals(true, restored)
        assertEquals("orig", g.selected)
        assertEquals(ServiceState.RUNNING, g.state)
    }
}
