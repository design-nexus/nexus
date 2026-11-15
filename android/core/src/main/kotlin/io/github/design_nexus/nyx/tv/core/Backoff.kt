package io.github.design_nexus.nyx.tv.core

import kotlin.random.Random

/**
 * Bounded exponential backoff for reconnecting to a server: 5 s, 10 s, 20 s … capped at
 * [maxMs], with up to 20 % jitter so several TVs don't retry in step.
 */
class Backoff(
    private val baseMs: Long = 5_000,
    private val maxMs: Long = 10 * 60_000,
    private val random: Random = Random.Default,
) {
    var attempt: Int = 0
        private set

    fun next(): Long {
        val shift = attempt.coerceAtMost(20)
        val exp = if (baseMs <= 0L) 0L
        else if (baseMs > maxMs / (1L shl shift)) maxMs
        else baseMs * (1L shl shift)
        val capped = exp.coerceAtMost(maxMs)
        attempt++
        val jitter = (capped * 0.2 * random.nextDouble()).toLong()
        return (capped - jitter).coerceAtLeast(baseMs / 2)
    }

    fun reset() {
        attempt = 0
    }
}
