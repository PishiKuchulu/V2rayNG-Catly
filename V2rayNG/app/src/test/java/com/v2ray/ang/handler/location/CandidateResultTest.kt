package com.v2ray.ang.handler.location

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CandidateResultTest {
    private val t0 = 1_000_000L
    private fun r(guid: String, label: String, state: CandidateState, at: Long? = t0) =
        CandidateResult(guid, label, state, checkedAtMillis = at)

    @Test
    fun settledResultsBecomeStaleAfterTheTtlAndOnClockSkew() {
        val v = r("a", "A", CandidateState.VERIFIED_MATCH)
        assertEquals(CandidateState.VERIFIED_MATCH, v.effectiveState(t0 + CandidateResult.RESULT_TTL_MILLIS))
        assertEquals(CandidateState.STALE, v.effectiveState(t0 + CandidateResult.RESULT_TTL_MILLIS + 1))
        assertEquals(CandidateState.STALE, v.effectiveState(t0 - 1))
        assertFalse(v.effectiveState(t0 + CandidateResult.RESULT_TTL_MILLIS + 1).isSelectable)
    }

    @Test
    fun uncheckedAndCheckingNeverBecomeStale() {
        val far = t0 + 10 * CandidateResult.RESULT_TTL_MILLIS
        assertEquals(CandidateState.NOT_CHECKED, r("a", "A", CandidateState.NOT_CHECKED, null).effectiveState(far))
        assertEquals(CandidateState.CHECKING, r("a", "A", CandidateState.CHECKING).effectiveState(far))
    }

    @Test
    fun onlyMatchingStatesAreSelectable() {
        val selectable = CandidateState.entries.filter { it.isSelectable }.toSet()
        assertEquals(
            setOf(CandidateState.VERIFIED_MATCH, CandidateState.REGION_CITY_UNVERIFIED, CandidateState.COUNTRY_MATCH_ONLY),
            selectable
        )
        // Notably: unknown, failed, stale and no-match servers can never be chosen as a "match".
        assertTrue(listOf(CandidateState.NO_MATCH, CandidateState.CONNECTION_FAILED, CandidateState.GEO_UNAVAILABLE,
            CandidateState.STALE, CandidateState.NOT_CHECKED, CandidateState.CHECKING).none { it.isSelectable })
    }

    @Test
    fun rankingPutsVerifiedFirstThenFinerUnverifiedThenCountryOnlyAndFailuresLast() {
        val sorted = CandidateRanking.sorted(
            listOf(
                r("1", "nomatch", CandidateState.NO_MATCH),
                r("2", "failed", CandidateState.CONNECTION_FAILED),
                r("3", "country", CandidateState.COUNTRY_MATCH_ONLY),
                r("4", "unverified", CandidateState.REGION_CITY_UNVERIFIED),
                r("5", "geo", CandidateState.GEO_UNAVAILABLE),
                r("6", "ok", CandidateState.VERIFIED_MATCH),
                r("7", "old", CandidateState.VERIFIED_MATCH, at = t0 - 10 * CandidateResult.RESULT_TTL_MILLIS),
            ),
            nowMillis = t0
        )
        assertEquals(listOf("6", "4", "3", "7", "5", "2", "1"), sorted.map { it.guid })
    }

    @Test
    fun equalRanksAreOrderedStablyByLabelThenGuid() {
        val sorted = CandidateRanking.sorted(
            listOf(r("2", "b", CandidateState.VERIFIED_MATCH), r("9", "A", CandidateState.VERIFIED_MATCH),
                r("1", "a", CandidateState.VERIFIED_MATCH)),
            t0
        )
        assertEquals(listOf("1", "9", "2"), sorted.map { it.guid })
    }
}
