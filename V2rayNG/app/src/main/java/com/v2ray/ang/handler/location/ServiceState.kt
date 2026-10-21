package com.v2ray.ang.handler.location

/**
 * What is positively known about the VPN/core service. UNKNOWN is a first-class answer and is never
 * folded into STOPPED: a timeout, a missing reply or a failed IPC call proves nothing.
 */
enum class ServiceState { RUNNING, STOPPED, UNKNOWN }

/** A reply the daemon process actually sent to the state query. */
enum class DaemonReply { RUNNING, NOT_RUNNING }

object ServiceStateResolver {
    /**
     * @param reply the daemon's answer, or null when none arrived in time.
     * @param daemonProcessAlive whether the OS reports the `:daemon` process as existing; null when that
     *   could not be determined.
     * @param ipcFailed true when sending the query itself failed.
     *
     * STOPPED is reported only on positive evidence: the daemon said "not running", or it said nothing
     * *and* the operating system confirms the daemon process does not exist (the service cannot run
     * without its process). A silent but existing daemon is UNKNOWN.
     */
    fun resolve(reply: DaemonReply?, daemonProcessAlive: Boolean?, ipcFailed: Boolean): ServiceState = when {
        ipcFailed -> ServiceState.UNKNOWN
        reply == DaemonReply.RUNNING -> ServiceState.RUNNING
        reply == DaemonReply.NOT_RUNNING -> ServiceState.STOPPED
        daemonProcessAlive == false -> ServiceState.STOPPED
        else -> ServiceState.UNKNOWN
    }
}
