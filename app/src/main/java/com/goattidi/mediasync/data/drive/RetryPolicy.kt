package com.goattidi.mediasync.data.drive

import kotlinx.coroutines.delay

/**
 * Exponential backoff for retryable Drive failures. [sleeper] is injectable so
 * tests can record delays instead of waiting.
 */
class RetryPolicy(
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 64_000,
    val sleeper: suspend (delayMs: Long) -> Unit = { delay(it) }
) {
    fun backoffMs(attempt: Int): Long =
        (baseDelayMs * (1L shl (attempt - 1).coerceIn(0, 20))).coerceAtMost(maxDelayMs)
}

/**
 * Retries [block] on rate limits (honoring Retry-After) and network errors.
 * Everything else — quota, auth, session expiry — propagates immediately.
 */
suspend fun <T> withRetry(policy: RetryPolicy, block: suspend () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: DriveException.RateLimited) {
            attempt++
            if (attempt >= policy.maxAttempts) throw e
            policy.sleeper(e.retryAfterSeconds?.times(1000) ?: policy.backoffMs(attempt))
        } catch (e: DriveException.Network) {
            attempt++
            if (attempt >= policy.maxAttempts) throw e
            policy.sleeper(policy.backoffMs(attempt))
        }
    }
}
