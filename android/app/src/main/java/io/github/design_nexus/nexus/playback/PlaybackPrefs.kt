package io.github.design_nexus.nexus.playback

import android.content.Context

/** Playback preferences shared by the bridge and the player. */
class PlaybackPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("playback", Context.MODE_PRIVATE)

    /** Prefer the built-in Media3 player over opening the Plex app. */
    var useBuiltIn: Boolean
        get() = prefs.getBoolean("use_builtin", true)
        set(v) = prefs.edit().putBoolean("use_builtin", v).apply()

    /**
     * Audio sync offset in milliseconds. Negative advances audio (typical fix when dialogue
     * lags by ~500 ms); positive delays it. Applied in the Media3 audio pipeline.
     */
    var audioOffsetMs: Int
        get() = prefs.getInt("audio_offset_ms", 0).coerceIn(MIN_OFFSET, MAX_OFFSET)
        set(v) = prefs.edit().putInt("audio_offset_ms", v.coerceIn(MIN_OFFSET, MAX_OFFSET)).apply()

    var directLinks: Boolean
        get() = prefs.getBoolean("direct_links", false)
        set(v) = prefs.edit().putBoolean("direct_links", v).apply()

    /** After credits / end of an episode, auto-start the next one. Off by default — end stops. */
    var autoplayNext: Boolean
        get() = prefs.getBoolean("autoplay_next", false)
        set(v) = prefs.edit().putBoolean("autoplay_next", v).apply()

    /** Automatically skip intro markers after [autoSkipDelaySec]. */
    var autoSkipIntro: Boolean
        get() = prefs.getBoolean("auto_skip_intro", false)
        set(v) = prefs.edit().putBoolean("auto_skip_intro", v).apply()

    /** Automatically skip credits markers (when Up Next is not shown) after [autoSkipDelaySec]. */
    var autoSkipCredits: Boolean
        get() = prefs.getBoolean("auto_skip_credits", false)
        set(v) = prefs.edit().putBoolean("auto_skip_credits", v).apply()

    /** Countdown in seconds before auto-skipping intro/credits (0 = instant). */
    var autoSkipDelaySec: Int
        get() = prefs.getInt("auto_skip_delay_sec", 5).coerceIn(0, 30)
        set(v) = prefs.edit().putInt("auto_skip_delay_sec", v.coerceIn(0, 30)).apply()

    /** Countdown in seconds before Up Next auto-plays the next episode. */
    var upNextDelaySec: Int
        get() = prefs.getInt("up_next_delay_sec", 15).coerceIn(3, 60)
        set(v) = prefs.edit().putInt("up_next_delay_sec", v.coerceIn(3, 60)).apply()

    /** Prefer direct play; fall back to convert when the TV can't decode. */
    var preferDirectPlay: Boolean
        get() = prefs.getBoolean("prefer_direct", true)
        set(v) = prefs.edit().putBoolean("prefer_direct", v).apply()

    companion object {
        const val MIN_OFFSET = -2000
        const val MAX_OFFSET = 2000
        const val NUDGE_MS = 50
    }
}
