package io.github.design_nexus.nyx.tv.plex

import io.github.design_nexus.nyx.tv.media.MediaDetails
import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.MediaKind
import io.github.design_nexus.nyx.tv.media.Person
import io.github.design_nexus.nyx.tv.media.Rating
import io.github.design_nexus.nyx.tv.media.ServerRef
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Plex JSON (`MediaContainer.Metadata[]`) to the launcher's model. */
object PlexParser {
    fun container(o: Any?): JSONObject = (o as? JSONObject)?.optJSONObject("MediaContainer") ?: JSONObject()

    fun items(o: Any?, server: ServerRef): List<MediaItem> = container(o).optJSONArray("Metadata").objects().mapNotNull { item(it, server) }

    fun kind(type: String?): MediaKind? = when (type) {
        "movie" -> MediaKind.MOVIE
        "show" -> MediaKind.SHOW
        "season" -> MediaKind.SEASON
        "episode" -> MediaKind.EPISODE
        "collection" -> MediaKind.COLLECTION
        "playlist" -> MediaKind.PLAYLIST
        else -> null
    }

    /**
     * Server-relative artwork path. Accepts relative keys, strips query/fragment, and
     * peels absolute same-host URLs down to their path (Plex sometimes returns those).
     */
    fun artPath(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var p = raw.trim()
        if ("://" in p) {
            val u = p.toHttpUrlOrNull() ?: return null
            p = u.encodedPath
        }
        val cut = p.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) p.length else it }
        p = p.substring(0, cut)
        if (!PlexHttp.relativeKey(p)) return null
        if (!p.startsWith("/library/") && !p.startsWith("/playlists/")) return null
        return p
    }

    fun item(v: JSONObject, server: ServerRef): MediaItem? {
        val kind = kind(v.optString("type")) ?: return null
        val id = key(v, "ratingKey") ?: return null
        fun art(field: String): String? = artPath(v.optString(field).ifEmpty { null })
        val leaf = v.optInt("leafCount", -1)
        val viewed = v.optInt("viewedLeafCount", -1)
        val episode = kind == MediaKind.EPISODE
        val season = kind == MediaKind.SEASON
        val stamp = v.optLong("updatedAt", 0).takeIf { it > 0 }
            ?: v.optLong("addedAt", 0).takeIf { it > 0 }
            ?: 1L
        val cover = imageCover(v)
        val composite = when (kind) {
            MediaKind.COLLECTION -> "/library/collections/$id/composite/$stamp"
            MediaKind.PLAYLIST -> "/playlists/$id/composite/$stamp"
            else -> null
        }
        return MediaItem(
            server = server,
            id = id,
            kind = kind,
            title = v.optStr("title") ?: "Untitled",
            year = v.optInt("year", 0).takeIf { it > 0 },
            summary = v.optStr("summary") ?: "",
            tagline = v.optStr("tagline") ?: "",
            genres = v.optJSONArray("Genre").objects().mapNotNull { it.optStr("tag") },
            contentRating = v.optStr("contentRating"),
            durationMs = v.optLong("duration", 0),
            viewOffsetMs = v.optLong("viewOffset", 0),
            watched = when (kind) {
                MediaKind.SHOW, MediaKind.SEASON -> leaf > 0 && viewed >= leaf
                else -> v.optInt("viewCount", 0) > 0 && v.optLong("viewOffset", 0) == 0L
            },
            unwatchedCount = if ((kind == MediaKind.SHOW || season) && leaf >= 0 && viewed >= 0) (leaf - viewed).coerceAtLeast(0) else null,
            childCount = when (kind) {
                MediaKind.SHOW -> v.optInt("childCount", -1).takeIf { it >= 0 }
                MediaKind.SEASON -> leaf.takeIf { it >= 0 }
                MediaKind.COLLECTION -> v.optInt("childCount", -1).takeIf { it >= 0 }
                MediaKind.PLAYLIST -> leaf.takeIf { it >= 0 }
                    ?: v.optInt("childCount", -1).takeIf { it >= 0 }
                else -> null
            },
            seriesTitle = when {
                episode -> v.optStr("grandparentTitle")
                season -> v.optStr("parentTitle")
                else -> null
            },
            seasonNumber = when {
                season -> v.optInt("index", -1).takeIf { it >= 0 }
                episode -> v.optInt("parentIndex", -1).takeIf { it >= 0 }
                else -> null
            },
            episodeNumber = if (episode) v.optInt("index", -1).takeIf { it >= 0 } else null,
            parentId = key(v, "parentRatingKey"),
            seriesId = if (episode) key(v, "grandparentRatingKey") else if (season) key(v, "parentRatingKey") else null,
            poster = when {
                episode -> art("grandparentThumb") ?: art("parentThumb")
                kind == MediaKind.COLLECTION || kind == MediaKind.PLAYLIST ->
                    art("thumb") ?: cover ?: composite
                else -> art("thumb") ?: cover
            },
            backdrop = when {
                episode -> art("grandparentArt") ?: art("art")
                season -> art("art") ?: art("parentArt")
                else -> art("art")
            },
            thumb = if (episode) art("thumb") else null,
            ratings = ratings(v),
            addedAt = v.optLong("addedAt", 0),
            libraryId = key(v, "librarySectionID"),
        )
    }

    /** Newer PMS payloads put posters in `Image[]` (`coverPoster`) instead of `thumb`. */
    private fun imageCover(v: JSONObject): String? {
        for (img in v.optJSONArray("Image").objects()) {
            val type = img.optString("type")
            if (type != "coverPoster" && type != "poster") continue
            artPath(img.optString("url").ifEmpty { null })?.let { return it }
        }
        return null
    }

    fun details(v: JSONObject, server: ServerRef, children: List<MediaItem>): MediaDetails? {
        val item = item(v, server) ?: return null
        val related = v.optJSONObject("Related")?.optJSONArray("Hub").objects()
            .flatMap { hub -> hub.optJSONArray("Metadata").objects().mapNotNull { item(it, server) } }
            .distinctBy { it.id }.filter { it.id != item.id }.take(20)
        return MediaDetails(
            item = item,
            cast = v.optJSONArray("Role").objects().mapNotNull { r ->
                val name = r.optString("tag").ifEmpty { return@mapNotNull null }
                Person(name, r.optString("role").ifEmpty { null }, r.optString("thumb").ifEmpty { null }?.takeIf { it.startsWith("https://") })
            }.take(20),
            directors = v.optJSONArray("Director").objects().mapNotNull { it.optString("tag").ifEmpty { null } },
            studio = v.optString("studio").ifEmpty { null },
            children = children,
            related = related,
        )
    }

    /** Ratings from the `Rating` array (newer agents) or the flat fields (older ones). */
    fun ratings(v: JSONObject): List<Rating> {
        val out = ArrayList<Rating>()
        for (r in v.optJSONArray("Rating").objects()) {
            val source = source(r.optString("image"), r.optString("type")) ?: continue
            val value = r.optDouble("value", Double.NaN)
            if (!value.isNaN()) out.add(Rating(source, value, 10.0))
        }
        if (out.isEmpty()) {
            source(v.optString("ratingImage"), "critic")?.let { s -> v.optDouble("rating", Double.NaN).takeIf { !it.isNaN() }?.let { out.add(Rating(s, it, 10.0)) } }
            source(v.optString("audienceRatingImage"), "audience")?.let { s -> v.optDouble("audienceRating", Double.NaN).takeIf { !it.isNaN() }?.let { out.add(Rating(s, it, 10.0)) } }
        }
        return out.distinctBy { it.source }
    }

    private fun source(image: String, type: String): String? = when {
        image.startsWith("rottentomatoes://") -> if (type == "audience" || "upright" in image || "spilled" in image) "rt_audience" else "rt_critic"
        image.startsWith("imdb://") -> "imdb"
        image.startsWith("themoviedb://") -> "tmdb"
        else -> null
    }

    private fun key(v: JSONObject, field: String): String? {
        val raw = v.opt(field) ?: return null
        val s = when (raw) {
            is Number -> raw.toLong().toString()
            is String -> raw
            else -> return null
        }
        return s.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    private fun JSONObject.optStr(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() && it != "null" }

    fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
