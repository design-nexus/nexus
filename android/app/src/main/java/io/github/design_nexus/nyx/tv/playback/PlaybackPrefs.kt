package io.github.design_nexus.nyx.tv.playback

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
