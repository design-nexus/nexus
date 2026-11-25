package io.github.design_nexus.nexus.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON for [LauncherConfig], with migrations from every earlier version. Unknown keys are
 * ignored and bad values fall back to defaults, so a damaged file never stops the launcher.
 *
 * Versions:
 * - 0 (unversioned, development builds): `showHero`, and `appsAbove` instead of an apps row.
 * - 1: `heroEnabled`; rows without a `style`.
 * - 2: rows carry `style`; current.
 */
object ConfigCodec {
    fun encode(c: LauncherConfig): String = JSONObject().apply {
        put("version", LauncherConfig.CURRENT_VERSION)
        put("heroEnabled", c.heroEnabled)
        put("rows", JSONArray(c.rows.map { r ->
            JSONObject().apply {
                put("id", r.id); put("type", r.type.name); put("visible", r.visible); put("style", r.style.name); put("limit", r.limit)
                r.title?.let { put("title", it) }
                r.libraryId?.let { put("libraryId", it) }
                r.genre?.let { put("genre", it) }
                if (r.pinned.isNotEmpty()) put("pinned", JSONArray(r.pinned))
            }
        }))
        put("favorites", JSONArray(c.favorites))
        put("hiddenApps", JSONArray(c.hiddenApps.sorted()))
        put("appOrder", JSONArray(c.appOrder))
        put("folders", JSONArray(c.folders.map { f -> JSONObject().put("id", f.id).put("name", f.name).put("packages", JSONArray(f.packages)) }))
        put("theme", c.theme); put("accent", c.accent)
        put("clock24h", c.clock24h); put("showClock", c.showClock); put("showServerStatus", c.showServerStatus); put("reduceMotion", c.reduceMotion)
        put("ambient", JSONObject().put("enabled", c.ambient.enabled).put("idleMinutes", c.ambient.idleMinutes).put("source", c.ambient.source.name).put("showClock", c.ambient.showClock))
    }.toString()

    fun decode(text: String?): LauncherConfig {
        if (text.isNullOrBlank()) return LauncherConfig()
        val raw = try { JSONObject(text) } catch (_: Exception) { return LauncherConfig() }
        val o = migrate(raw)
        val d = LauncherConfig()
        return LauncherConfig(
            heroEnabled = o.optBoolean("heroEnabled", d.heroEnabled),
            rows = o.optJSONArray("rows")?.let(::rows)?.takeIf { it.isNotEmpty() } ?: d.rows,
            favorites = o.optJSONArray("favorites").strings(),
            hiddenApps = o.optJSONArray("hiddenApps").strings().toSet(),
            appOrder = o.optJSONArray("appOrder").strings(),
            folders = o.optJSONArray("folders")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val f = a.optJSONObject(i) ?: return@mapNotNull null
                    AppFolder(f.optString("id").ifEmpty { "folder$i" }, f.optString("name", "Folder"), f.optJSONArray("packages").strings())
                }
            } ?: emptyList(),
            theme = o.optString("theme", d.theme),
            accent = o.optString("accent", d.accent),
            clock24h = o.optBoolean("clock24h", d.clock24h),
            showClock = o.optBoolean("showClock", d.showClock),
            showServerStatus = o.optBoolean("showServerStatus", d.showServerStatus),
            reduceMotion = o.optBoolean("reduceMotion", d.reduceMotion),
            ambient = o.optJSONObject("ambient")?.let { a ->
                AmbientConfig(
                    enabled = a.optBoolean("enabled", false),
                    idleMinutes = a.optInt("idleMinutes", 10).coerceIn(1, 120),
                    source = enumOr(a.optString("source"), AmbientSource.ARTWORK),
                    showClock = a.optBoolean("showClock", true),
                )
            } ?: d.ambient,
        )
    }

    /** Brings any earlier version up to [LauncherConfig.CURRENT_VERSION]. */
    fun migrate(o: JSONObject): JSONObject {
        var v = o.optInt("version", 0)
        if (v == 0) {
            if (o.has("showHero") && !o.has("heroEnabled")) o.put("heroEnabled", o.optBoolean("showHero", true))
            o.remove("showHero")
            val rows = o.optJSONArray("rows")
            if (rows != null && o.has("appsAbove")) {
                val list = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.filter { it.optString("type") != "FAVORITE_APPS" }.toMutableList()
                val apps = JSONObject().put("id", "apps").put("type", "FAVORITE_APPS")
                if (o.optBoolean("appsAbove")) list.add(0, apps) else list.add(minOf(1, list.size), apps)
                o.put("rows", JSONArray(list))
            }
            o.remove("appsAbove")
            v = 1
        }
        if (v == 1) {
            o.optJSONArray("rows")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.optJSONObject(i) ?: continue
                    if (!r.has("style")) r.put("style", RowConfig.defaultStyle(enumOr(r.optString("type"), RowType.LIBRARY)).name)
                }
            }
            v = 2
        }
        if (v == 2) {
            // Insert Playlists after Collections (or at end) when missing, but only if there
            // is at least one valid row (otherwise damaged/empty configs fall back to defaults).
            val rows = o.optJSONArray("rows")
            val hasValidRow = rows != null && (0 until rows.length()).any { i ->
                enumOrNull<RowType>(rows.optJSONObject(i)?.optString("type")) != null
            }
            val has = rows != null && (0 until rows.length()).any { i ->
                rows.optJSONObject(i)?.optString("type") == "PLAYLISTS"
            }
            if (hasValidRow && !has) {
                val playlist = JSONObject()
                    .put("id", "playlists")
                    .put("type", "PLAYLISTS")
                    .put("visible", true)
                    .put("style", RowConfig.defaultStyle(RowType.PLAYLISTS).name)
                    .put("limit", 20)
                var insertAt = rows.length()
                for (i in 0 until rows.length()) {
                    if (rows.optJSONObject(i)?.optString("type") == "COLLECTIONS") {
                        insertAt = i + 1
                        break
                    }
                }
                val list = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.toMutableList()
                list.add(insertAt.coerceIn(0, list.size), playlist)
                o.put("rows", JSONArray(list))
            }
            v = 3
        }
        o.put("version", v)
        return o
    }

    private fun rows(a: JSONArray): List<RowConfig> = (0 until a.length()).mapNotNull { i ->
        val r = a.optJSONObject(i) ?: return@mapNotNull null
        val type = enumOrNull<RowType>(r.optString("type")) ?: return@mapNotNull null
        RowConfig(
            id = r.optString("id").ifEmpty { "row$i" },
            type = type,
            title = r.optString("title").ifEmpty { null },
            visible = r.optBoolean("visible", true),
            style = enumOr(r.optString("style"), RowConfig.defaultStyle(type)),
            limit = r.optInt("limit", 20).coerceIn(1, 100),
            libraryId = r.optString("libraryId").ifEmpty { null },
            genre = r.optString("genre").ifEmpty { null },
            pinned = r.optJSONArray("pinned").strings(),
        )
    }.distinctBy { it.id }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifEmpty { null } }

    private inline fun <reified E : Enum<E>> enumOrNull(s: String?): E? = enumValues<E>().firstOrNull { it.name == s }
    private inline fun <reified E : Enum<E>> enumOr(s: String?, d: E): E = enumOrNull<E>(s) ?: d
}
