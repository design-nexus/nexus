package io.github.design_nexus.nexus.core

enum class RowType { CONTINUE_WATCHING, FAVORITE_APPS, RECENT_MOVIES, RECENT_SHOWS, NEXT_EPISODES, COLLECTIONS, PLAYLISTS, LIBRARY, GENRE }

enum class CardStyle { LANDSCAPE, POSTER, SQUARE, APP_TILE }

enum class AmbientSource { ARTWORK, BLACK }

data class RowConfig(
    val id: String,
    val type: RowType,
    val title: String? = null,
    val visible: Boolean = true,
    val style: CardStyle = defaultStyle(type),
    val limit: Int = 20,
    val libraryId: String? = null,
    val genre: String? = null,
    /** Collection ids pinned to the front of a Collections row. */
    val pinned: List<String> = emptyList(),
) {
    companion object {
        fun defaultStyle(type: RowType): CardStyle = when (type) {
            RowType.CONTINUE_WATCHING, RowType.NEXT_EPISODES -> CardStyle.LANDSCAPE
            RowType.FAVORITE_APPS -> CardStyle.APP_TILE
            else -> CardStyle.POSTER
        }
    }
}

data class AppFolder(val id: String, val name: String, val packages: List<String>)

data class AmbientConfig(
    val enabled: Boolean = false,
    val idleMinutes: Int = 10,
    val source: AmbientSource = AmbientSource.ARTWORK,
    val showClock: Boolean = true,
)

/** Everything the launcher remembers. No credentials: the Plex token lives in [SecureStore]. */
data class LauncherConfig(
    val version: Int = CURRENT_VERSION,
    val heroEnabled: Boolean = true,
    val rows: List<RowConfig> = defaultRows(),
    val favorites: List<String> = emptyList(),
    val hiddenApps: Set<String> = emptySet(),
    val appOrder: List<String> = emptyList(),
    val folders: List<AppFolder> = emptyList(),
    val theme: String = "mocha",
    val accent: String = "mauve",
    val clock24h: Boolean = false,
    val showClock: Boolean = true,
    val showServerStatus: Boolean = true,
    val reduceMotion: Boolean = false,
    val ambient: AmbientConfig = AmbientConfig(),
) {
    fun moveRow(id: String, by: Int): LauncherConfig {
        val from = rows.indexOfFirst { it.id == id }
        if (from < 0) return this
        val to = (from + by).coerceIn(0, rows.lastIndex)
        if (to == from) return this
        val list = rows.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(rows = list)
    }

    fun updateRow(id: String, f: (RowConfig) -> RowConfig): LauncherConfig = copy(rows = rows.map { if (it.id == id) f(it) else it })

    fun toggleFavorite(pkg: String): LauncherConfig =
        copy(favorites = if (pkg in favorites) favorites - pkg else favorites + pkg)

    fun moveFavorite(pkg: String, by: Int): LauncherConfig {
        val from = favorites.indexOf(pkg)
        if (from < 0) return this
        val to = (from + by).coerceIn(0, favorites.lastIndex)
        val list = favorites.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(favorites = list)
    }

    fun setHidden(pkg: String, hidden: Boolean): LauncherConfig =
        copy(hiddenApps = if (hidden) hiddenApps + pkg else hiddenApps - pkg, favorites = if (hidden) favorites - pkg else favorites)

    companion object {
        const val CURRENT_VERSION = 3

        fun defaultRows(): List<RowConfig> = listOf(
            RowConfig("continue", RowType.CONTINUE_WATCHING),
            RowConfig("apps", RowType.FAVORITE_APPS, limit = 12),
            RowConfig("recent-movies", RowType.RECENT_MOVIES),
            RowConfig("recent-shows", RowType.RECENT_SHOWS),
            RowConfig("next", RowType.NEXT_EPISODES, limit = 12),
            RowConfig("collections", RowType.COLLECTIONS),
            RowConfig("playlists", RowType.PLAYLISTS),
        )

        /** Apps a new install puts in Favorites when they're installed, in this order. */
        val DEFAULT_FAVORITES = listOf(
            "com.plexapp.android",
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.hulu.livingroomplus",
        )
    }
}
