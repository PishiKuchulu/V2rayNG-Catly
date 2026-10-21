package com.v2ray.ang.handler.location

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ServiceStateTest {
    private fun r(reply: DaemonReply?, alive: Boolean?, ipcFailed: Boolean = false) =
        ServiceStateResolver.resolve(reply, alive, ipcFailed)

    @Test
    fun aDaemonReplyIsTakenAtFaceValue() {
        assertEquals(ServiceState.RUNNING, r(DaemonReply.RUNNING, true))
        assertEquals(ServiceState.STOPPED, r(DaemonReply.NOT_RUNNING, true))
    }

    @Test
    fun timeoutWithAnExistingDaemonProcessIsUnknownNeverStopped() {
        assertEquals(ServiceState.UNKNOWN, r(null, alive = true))
    }

    @Test
    fun missingResponseWithUndeterminedProcessStateIsUnknown() {
        assertEquals(ServiceState.UNKNOWN, r(null, alive = null))
    }

    @Test
    fun ipcFailureIsUnknownEvenIfAStaleReplyWasSeen() {
        assertEquals(ServiceState.UNKNOWN, r(null, alive = false, ipcFailed = true))
        assertEquals(ServiceState.UNKNOWN, r(DaemonReply.RUNNING, alive = true, ipcFailed = true))
        assertEquals(ServiceState.UNKNOWN, r(DaemonReply.NOT_RUNNING, alive = false, ipcFailed = true))
    }

    @Test
    fun stoppedNeedsPositiveEvidenceFromTheOperatingSystemWhenTheDaemonIsSilent() {
        assertEquals(ServiceState.STOPPED, r(null, alive = false))
    }

    @Test
    fun runningReplyWinsOverProcessHeuristics() {
        assertEquals(ServiceState.RUNNING, r(DaemonReply.RUNNING, alive = null))
        assertEquals(ServiceState.RUNNING, r(DaemonReply.RUNNING, alive = false))
    }
}
