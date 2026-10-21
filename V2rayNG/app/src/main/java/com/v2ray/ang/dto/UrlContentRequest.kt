package com.v2ray.ang.dto

data class UrlContentRequest(
    val url: String?,
    val timeout: Int = 15000,
    val httpPort: Int = 0,
    val proxyUsername: String? = null,
    val proxyPassword: String? = null,
    val userAgent: String? = null,
    val requestHeaders: String? = null,
    /** Reject responses larger than this many bytes. 0 keeps the previous unbounded behavior. */
    val maxBytes: Long = 0L
)