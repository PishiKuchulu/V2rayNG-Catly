package com.v2ray.ang.handler.location

object CoordinateParser {
    sealed interface Result {
        data object Cleared : Result
        data object Invalid : Result
        data class Parsed(val latitude: Double, val longitude: Double) : Result
    }

    /**
     * Accepts "lat, lon", "lat lon" or "lat; lon" with a dot decimal separator. Blank input means
     * "clear". Anything that is not exactly two finite in-range numbers is Invalid.
     */
    fun parse(text: String): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.Cleared
        val parts = trimmed.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
        if (parts.size != 2) return Result.Invalid
        val lat = parts[0].toDoubleOrNull()
        val lon = parts[1].toDoubleOrNull()
        return if (LocationProfileValidator.isValidLatitude(lat) && LocationProfileValidator.isValidLongitude(lon)) {
            Result.Parsed(lat!!, lon!!)
        } else {
            Result.Invalid
        }
    }

    fun format(latitude: Double?, longitude: Double?): String =
        if (latitude == null || longitude == null) "" else "%.5f, %.5f".format(java.util.Locale.ROOT, latitude, longitude)
}
