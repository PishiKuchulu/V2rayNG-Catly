package com.v2ray.ang.handler.location

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GeoIpResponseParserTest {

    private fun parse(json: String?) = GeoIpResponseParser.parse(json)

    @Test
    fun parsesIpSbShape() {
        val r = parse(
            """{"organization":"X","longitude":-74.006,"city":"New York","timezone":"America/New_York",
                "region":"New York","asn":1,"country":"United States","ip":"203.0.113.5",
                "latitude":40.7128,"country_code":"US","region_code":"NY"}"""
        )!!
        assertEquals("203.0.113.5", r.ip)
        assertEquals("US", r.countryCode)
        assertEquals("NY", r.regionCodeRaw)
        assertEquals("New York", r.regionName)
        assertEquals("New York", r.city)
        assertEquals(40.7128, r.latitude)
        assertEquals(-74.006, r.longitude)
    }

    @Test
    fun parsesIpApiComShapeWhereRegionIsACode() {
        val r = parse("""{"query":"198.51.100.1","countryCode":"DE","region":"BY","regionName":"Bavaria","city":"Munich","lat":48.1,"lon":11.5}""")!!
        assertEquals("DE", r.countryCode)
        assertEquals("BY", r.regionCodeRaw)
        assertEquals("Bavaria", r.regionName)
        assertEquals(48.1, r.latitude)
    }

    @Test
    fun parsesIpinfoShapeWithLocString() {
        val r = parse("""{"ip":"192.0.2.9","city":"Berlin","region":"Berlin","country":"DE","loc":"52.5200,13.4050"}""")!!
        assertEquals("DE", r.countryCode)
        assertEquals("Berlin", r.regionName)
        assertNull(r.regionCodeRaw)
        assertEquals(52.52, r.latitude)
        assertEquals(13.405, r.longitude)
    }

    @Test
    fun countryNameIsMappedToIsoCodeOnlyWhenUnambiguous() {
        assertEquals("DE", parse("""{"ip":"1.1.1.1","country":"Germany"}""")!!.countryCode)
        assertEquals("US", parse("""{"ip":"1.1.1.1","country_name":"united states"}""")!!.countryCode)
        assertNull(parse("""{"ip":"1.1.1.1","country":"Atlantis"}""")!!.countryCode)
        assertNull(parse("""{"ip":"1.1.1.1","country":"USA"}""")!!.countryCode)
    }

    @Test
    fun cloudflareMetaNestedLocationIsUsed() {
        val r = parse("""{"clientIp":"203.0.113.77","location":{"country_code":"JP"}}""")!!
        assertEquals("JP", r.countryCode)
        assertEquals("203.0.113.77", r.ip)
    }

    @Test
    fun malformedOrHostileInputYieldsNullNotAnException() {
        assertNull(parse(null))
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("not json"))
        assertNull(parse("[1,2,3]"))
        assertNull(parse("\"string\""))
        assertNull(parse("{\"ip\":"))
        assertNull(parse("x".repeat(GeoIpResponseParser.MAX_INPUT_CHARS + 1)))
    }

    @Test
    fun wrongTypesAndMissingFieldsYieldEmptyFieldsNotCrashes() {
        val r = parse("""{"ip":5,"country_code":["US"],"city":{"a":1},"latitude":"abc","longitude":9}""")
        assertNotNull(r)
        assertNull(r!!.ip)
        assertNull(r.countryCode)
        assertNull(r.city)
        assertNull(r.latitude)
        assertNull(r.longitude)
        val empty = parse("{}")!!
        assertNull(empty.countryCode)
    }

    @Test
    fun outOfRangeCoordinatesAreDiscardedAsAPair() {
        val r = parse("""{"ip":"1.1.1.1","country_code":"US","latitude":123.0,"longitude":10.0}""")!!
        assertNull(r.latitude)
        assertNull(r.longitude)
    }

    @Test
    fun textIsSanitizedAndBounded() {
        val noisy = "Ber\u0000lin\n" + "x".repeat(500)
        val r = parse("""{"ip":"1.1.1.1","country_code":"DE","city":${com.google.gson.Gson().toJson(noisy)}}""")!!
        assertEquals(100, r.city!!.length)
        assertEquals(false, r.city!!.any { it.isISOControl() })
    }

    @Test
    fun parserIsDeterministicWhenSeveralCountryKeysDisagree() {
        // country_code wins over country, always; there is no "convenient" choice between them.
        val r = parse("""{"ip":"1.1.1.1","country_code":"FR","country":"Germany"}""")!!
        assertEquals("FR", r.countryCode)
    }
}
