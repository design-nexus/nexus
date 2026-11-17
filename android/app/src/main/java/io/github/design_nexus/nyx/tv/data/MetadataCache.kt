package io.github.design_nexus.nyx.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.MediaKind
import io.github.design_nexus.nyx.tv.media.Rating
import io.github.design_nexus.nyx.tv.media.ServerRef
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last rows each provider returned, so Home draws at once on start and stays usable
 * while the server is unreachable. One small table: row key to JSON. No credentials.
 */
class MetadataCache(context: Context) : SQLiteOpenHelper(context, "metadata.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE rows (key TEXT PRIMARY KEY, json TEXT NOT NULL, updated INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS rows")
        onCreate(db)
    }

    fun get(key: String): List<MediaItem>? =
        readableDatabase.rawQuery("SELECT json FROM rows WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) runCatching { MediaJson.decode(c.getString(0)) }.getOrNull() else null
        }

    fun put(key: String, items: List<MediaItem>) {
        writableDatabase.insertWithOnConflict("rows", null, ContentValues().apply {
            put("key", key); put("json", MediaJson.encode(items)); put("updated", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clear() {
        writableDatabase.delete("rows", null, null)
    }
}

object MediaJson {
    fun encode(items: List<MediaItem>): String = JSONArray(items.map { i ->
        JSONObject().apply {
            put("p", i.server.provider); put("s", i.server.id); put("id", i.id); put("k", i.kind.name); put("t", i.title)
            i.year?.let { put("y", it) }; put("sum", i.summary); put("tag", i.tagline); put("g", JSONArray(i.genres))
            i.contentRating?.let { put("cr", it) }; put("d", i.durationMs); put("vo", i.viewOffsetMs); put("w", i.watched)
            i.unwatchedCount?.let { put("u", it) }; i.childCount?.let { put("cc", it) }
            i.seriesTitle?.let { put("st", it) }; i.seasonNumber?.let { put("sn", it) }; i.episodeNumber?.let { put("en", it) }
            i.parentId?.let { put("pid", it) }; i.seriesId?.let { put("sid", it) }
            i.poster?.let { put("po", it) }; i.backdrop?.let { put("bd", it) }; i.thumb?.let { put("th", it) }
            put("r", JSONArray(i.ratings.map { r -> JSONObject().put("s", r.source).put("v", r.value).put("x", r.scale) }))
            put("a", i.addedAt); i.libraryId?.let { put("lib", it) }
            i.tmdbId?.let { put("tmdb", it) }; i.tvdbId?.let { put("tvdb", it) }; i.imdbId?.let { put("imdb", it) }
        }
    }).toString()

    fun decode(text: String): List<MediaItem> {
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { n ->
            val o = a.optJSONObject(n) ?: return@mapNotNull null
            val kind = MediaKind.entries.firstOrNull { it.name == o.optString("k") } ?: return@mapNotNull null
            fun s(k: String) = o.optString(k).ifEmpty { null }
            fun i(k: String) = if (o.has(k)) o.optInt(k) else null
            val g = o.optJSONArray("g") ?: JSONArray()
            val r = o.optJSONArray("r") ?: JSONArray()
            MediaItem(
                server = ServerRef(o.optString("p"), o.optString("s")), id = o.optString("id"), kind = kind, title = o.optString("t"),
                year = i("y"), summary = o.optString("sum"), tagline = o.optString("tag"), genres = (0 until g.length()).map { g.optString(it) },
                contentRating = s("cr"), durationMs = o.optLong("d"), viewOffsetMs = o.optLong("vo"), watched = o.optBoolean("w"),
                unwatchedCount = i("u"), childCount = i("cc"), seriesTitle = s("st"), seasonNumber = i("sn"), episodeNumber = i("en"),
                parentId = s("pid"), seriesId = s("sid"), poster = s("po"), backdrop = s("bd"), thumb = s("th"),
                ratings = (0 until r.length()).mapNotNull { r.optJSONObject(it) }.map { Rating(it.optString("s"), it.optDouble("v"), it.optDouble("x")) },
                addedAt = o.optLong("a"), libraryId = s("lib"),
                tmdbId = i("tmdb"), tvdbId = i("tvdb"), imdbId = s("imdb"),
            )
        }
    }
}
