package io.github.design_nexus.nyx.tv.plex

import io.github.design_nexus.nyx.tv.media.MediaItem
import java.net.URLEncoder

/**
 * Links that might open a title in the official Plex app. Plex doesn't document a deep-link
 * interface for its Android TV app; its manifest accepts any `plex://` URI and a few
 * `https://` hosts (seen in `dumpsys package com.plexapp.android` on the TCL TV). Which of
 * these start playback, open the title, or do nothing is recorded in docs/PLEX.md as it's
 * tested; none is assumed to work.
 */
object PlexLinks {
    enum class Kind { PLEX_SERVER_URI, PLEX_PLAY_URI }

    data class Candidate(val kind: Kind, val uri: String)

    fun candidates(item: MediaItem, fromStart: Boolean): List<Candidate> {
        val server = item.server.id
        val key = "/library/metadata/${item.id}"
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        return listOf(
            Candidate(Kind.PLEX_SERVER_URI, "plex://server://$server/com.plexapp.plugins.library$key"),
            Candidate(Kind.PLEX_PLAY_URI, "plex://play/?metadataKey=${enc(key)}&server=${enc(server)}" + if (fromStart) "&offset=0" else ""),
        )
    }
}
