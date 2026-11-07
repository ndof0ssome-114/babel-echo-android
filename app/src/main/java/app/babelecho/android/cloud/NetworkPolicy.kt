package app.babelecho.android.cloud

/** Pure network rules shared by every provider call; kept free of Android APIs for JVM tests. */
object NetworkPolicy {
    private val retryStatus = setOf(408, 425, 429, 500, 502, 503, 504)
    const val MAX_ATTEMPTS = 3
    private const val MAX_RETRY_DELAY_MS = 30_000L

    /**
     * Plain HTTP is only for engines on the user's own device or network (Ollama, LM Studio,
     * whisper.cpp). Anything else would carry audio, transcripts and keys unencrypted.
     */
    fun isLocalNetworkHost(hostname: String): Boolean {
        val host = hostname.lowercase().removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) return false
        if (!host.contains('.') && !host.contains(':')) return true // single-label LAN name
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".lan")) return true
        val v4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(host)
        if (v4 != null) {
            val a = v4.groupValues[1].toInt()
            val b = v4.groupValues[2].toInt()
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        if (host.contains(':')) {
            return host == "::1" || Regex("^f[cd][0-9a-f]{2}:").containsMatchIn(host) ||
                Regex("^fe[89ab][0-9a-f]:").containsMatchIn(host)
        }
        return false
    }

    /**
     * Delay before retrying an HTTP failure, or null when it must not be retried. Only explicit
     * retryable statuses are retried: a timeout may already have been billed upstream.
     */
    fun retryDelayMs(status: Int, retryAfter: String?, attempt: Int, nowMs: Long = System.currentTimeMillis()): Long? {
        if (status !in retryStatus || attempt >= MAX_ATTEMPTS - 1) return null
        val header = retryAfter?.trim().orEmpty()
        val fromHeader = header.toLongOrNull()?.times(1_000)
            ?: runCatching { java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs }.getOrNull()
        val fallback = 1_000L * (attempt + 1)
        return (fromHeader?.takeIf { it > 0 } ?: fallback).coerceAtMost(MAX_RETRY_DELAY_MS)
    }
}
