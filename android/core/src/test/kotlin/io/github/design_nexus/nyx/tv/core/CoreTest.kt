package io.github.design_nexus.nyx.tv.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ConfigCodecTest {
    @Test fun roundTrip() {
        val c = LauncherConfig(
            heroEnabled = false,
            favorites = listOf("com.plexapp.android", "com.netflix.ninja"),
            hiddenApps = setOf("com.tcl.tv"),
            folders = listOf(AppFolder("f1", "Streaming", listOf("a", "b"))),
            theme = "latte", accent = "blue", clock24h = true,
            ambient = AmbientConfig(enabled = true, idleMinutes = 15, source = AmbientSource.BLACK),
        ).moveRow("apps", -1).updateRow("collections") { it.copy(pinned = listOf("123"), visible = false) }
        assertEquals(c, ConfigCodec.decode(ConfigCodec.encode(c)))
    }

    @Test fun damagedInputFallsBackToDefaults() {
        assertEquals(LauncherConfig(), ConfigCodec.decode("{not json"))
        assertEquals(LauncherConfig(), ConfigCodec.decode(null))
        val c = ConfigCodec.decode("""{"version":2,"rows":[{"type":"NOPE"}],"ambient":{"idleMinutes":9999}}""")
        assertEquals(LauncherConfig.defaultRows(), c.rows)
        assertEquals(120, c.ambient.idleMinutes)
    }

    @Test fun migratesUnversioned() {
        val v0 = """{"showHero":false,"appsAbove":true,"rows":[{"id":"continue","type":"CONTINUE_WATCHING"},{"id":"m","type":"RECENT_MOVIES"}]}"""
        val c = ConfigCodec.decode(v0)
        assertFalse(c.heroEnabled)
        assertEquals(listOf("apps", "continue", "m"), c.rows.map { it.id })
        assertEquals(CardStyle.APP_TILE, c.rows[0].style)
    }

    @Test fun migratesV1RowStyles() {
        val o = ConfigCodec.migrate(JSONObject("""{"version":1,"rows":[{"id":"n","type":"NEXT_EPISODES"},{"id":"g","type":"GENRE","genre":"Drama"}]}"""))
        assertEquals(LauncherConfig.CURRENT_VERSION, o.getInt("version"))
        val c = ConfigCodec.decode(o.toString())
        assertEquals(CardStyle.LANDSCAPE, c.rows[0].style)
        assertEquals(CardStyle.POSTER, c.rows[1].style)
        assertEquals("Drama", c.rows[1].genre)
    }

    @Test fun rowAndFavoriteMoves() {
        val c = LauncherConfig().moveRow("collections", -10)
        assertEquals("collections", c.rows.first().id)
        val f = LauncherConfig(favorites = listOf("a", "b", "c")).moveFavorite("c", -1)
        assertEquals(listOf("a", "c", "b"), f.favorites)
        assertEquals(listOf("a", "b"), LauncherConfig(favorites = listOf("a", "b", "c")).setHidden("c", true).favorites)
    }
}

class BackoffTest {
    @Test fun growsAndIsBounded() {
        val b = Backoff(baseMs = 1000, maxMs = 8000, random = Random(1))
        val delays = List(8) { b.next() }
        assertTrue(delays[0] in 800..1000)
        assertTrue(delays[2] in 3200..4000)
        assertTrue(delays.all { it <= 8000 })
        assertTrue(delays.last() >= 6400)
        b.reset()
        assertTrue(b.next() <= 1000)
    }
}

class RedactTest {
    @Test fun removesTokens() {
        val s = Redact.text("GET http://10.0.0.2:32400/library?X-Plex-Token=abcDEF123&x=1 {\"authToken\":\"zzz\"} Authorization: Bearer qqq https://u:pw@host/")
        assertFalse(s.contains("abcDEF123"))
        assertFalse(s.contains("zzz"))
        assertFalse(s.contains("qqq"))
        assertFalse(s.contains("pw@"))
        assertTrue(s.contains("&x=1"))
    }
}

class FocusMemoryTest {
    @Test fun restoresAndClamps() {
        val f = FocusMemory()
        f.remember("continue", 7)
        f.remember("movies", 3)
        assertEquals(5, f.positionIn("continue", 6))
        assertEquals(3, f.positionIn("movies", 20))
        assertEquals("movies", f.rowToFocus(listOf("continue", "movies")))
        assertEquals("continue", f.rowToFocus(listOf("continue")))
        f.forget("movies")
        assertEquals(0, f.positionIn("movies", 5))
    }
}
