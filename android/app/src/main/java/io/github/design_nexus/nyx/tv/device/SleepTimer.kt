package io.github.design_nexus.nyx.tv.device

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * In-app sleep countdown. On fire it starts Ambient black — a third-party app cannot
 * put the TV into standby without privileged APIs.
 */
object SleepTimer {
    val CHOICES_MIN = listOf(30, 60, 90, 120)

    private val handler = Handler(Looper.getMainLooper())
    private var fireAtElapsed: Long = 0L
    private var pending: Runnable? = null
    private var onFire: (() -> Unit)? = null

    /** Minutes left, or null when idle. */
    @Synchronized
    fun remainingMinutes(): Int? {
        if (pending == null || fireAtElapsed == 0L) return null
        val ms = fireAtElapsed - SystemClock.elapsedRealtime()
        if (ms <= 0) return null
        return ((ms + 59_999) / 60_000).toInt().coerceAtLeast(1)
    }

    @Synchronized
    fun isActive(): Boolean = pending != null

    @Synchronized
    fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        fireAtElapsed = 0L
        onFire = null
    }

    /**
     * Schedule [minutes] from now. [fire] runs on the main thread when the timer ends
     * (typically start Ambient black). Replaces any previous timer.
     */
    @Synchronized
    fun schedule(minutes: Int, fire: () -> Unit) {
        cancel()
        if (minutes <= 0) return
        onFire = fire
        fireAtElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L
        val r = Runnable {
            val cb = synchronized(this) {
                pending = null
                fireAtElapsed = 0L
                val c = onFire
                onFire = null
                c
            }
            cb?.invoke()
        }
        pending = r
        handler.postDelayed(r, minutes * 60_000L)
    }

    fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "$h hr $m min"
            h > 0 -> "$h hr"
            else -> "$m min"
        }
    }

    fun subtitle(): String {
        val left = remainingMinutes() ?: return "Off"
        return "${formatDuration(left)} left"
    }
}
