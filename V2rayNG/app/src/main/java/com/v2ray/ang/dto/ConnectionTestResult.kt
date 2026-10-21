package com.v2ray.ang.dto

import java.io.Serializable

/** Locale-neutral result sent from the daemon for presentation by the UI process. */
data class ConnectionTestResult(
    val delayMillis: Long,
    val errorMessage: String = "",
    val country: String? = null,
    val ipAddress: String? = null,
    /** Normalized ISO country code, null when the provider's answer could not be mapped to one. */
    val countryCode: String? = null,
    val regionCode: String? = null,
    val regionName: String? = null,
    val city: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) : Serializable
