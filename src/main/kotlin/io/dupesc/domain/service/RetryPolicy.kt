package io.dupesc.domain.service

import kotlin.math.min
import kotlin.random.Random

class RetryPolicy(
    private val baseMs: Long,
    private val capMs: Long,
    private val maxAttempts: Int,
    private val random: Random = Random.Default,
) {
    fun atrasoMs(attempt: Int): Long {
        val exponencial = min(baseMs * (1L shl (attempt - 1)), capMs)
        val jitter = 0.7 + random.nextDouble() * 0.6
        return (exponencial * jitter).toLong()
    }

    fun esgotou(attempt: Int) = attempt >= maxAttempts
}
