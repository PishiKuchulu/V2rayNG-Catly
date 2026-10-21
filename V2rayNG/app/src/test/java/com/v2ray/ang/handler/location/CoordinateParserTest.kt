package com.v2ray.ang.handler.location

import com.v2ray.ang.handler.location.CoordinateParser.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoordinateParserTest {
    @Test
    fun parsesCommonSeparators() {
        assertEquals(Result.Parsed(40.7128, -74.006), CoordinateParser.parse("40.7128, -74.006"))
        assertEquals(Result.Parsed(40.7128, -74.006), CoordinateParser.parse(" 40.7128 -74.006 "))
        assertEquals(Result.Parsed(1.0, 2.0), CoordinateParser.parse("1;2"))
    }

    @Test
    fun blankMeansClearAndGarbageIsInvalid() {
        assertEquals(Result.Cleared, CoordinateParser.parse("   "))
        for (bad in listOf("abc", "1", "1,2,3", "91,0", "0,181", "NaN,0", "1,Infinity", "40,7128, -74", "1,2x")) {
            assertEquals(Result.Invalid, CoordinateParser.parse(bad), bad)
        }
    }

    @Test
    fun formatIsLocaleIndependentAndRoundTrips() {
        assertEquals("40.71280, -74.00600", CoordinateParser.format(40.7128, -74.006))
        assertEquals("", CoordinateParser.format(null, 1.0))
        assertEquals(Result.Parsed(40.7128, -74.006), CoordinateParser.parse(CoordinateParser.format(40.7128, -74.006)))
    }
}
