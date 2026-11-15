package io.github.design_nexus.nyx.tv.plex

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** A sign-in code: shown on the TV, typed at plex.tv/link. */
data class PlexPin(val id: Long, val code: String, val expiresInSec: Int) {
    val linkUrl: String get() = "https://plex.tv/link"
}

/**
 * A server the account can use, from plex.tv. [token] is the server's own access token:
 * never log or back it up. [connections] are ordered local first, remote next, relay last.
 */
data class PlexServer(
    val id: String,
    val name: String,
    val owned: Boolean,
    val token: String,
    val connections: List<Connection>,
) {
    data class Connection(val url: HttpUrl, val local: Boolean, val relay: Boolean) {
        val priority: Int get() = (if (relay) 4 else 0) + (if (!local) 2 else 0) + (if (url.scheme != "https") 1 else 0)
    }

    override fun toString(): String = "PlexServer($name, $id, ${connections.size} connections)"
}

/** plex.tv: sign-in with a PIN and the account's servers. */
class PlexAccount(private val http: PlexHttp, private val base: HttpUrl = "https://plex.tv/".toHttpUrl(), private val clients: HttpUrl = "https://clients.plex.tv/".toHttpUrl()) {

    /** A short code for plex.tv/link (strong=false gives the four-character form). */
    fun createPin(): PlexPin {
        val url = base.newBuilder().addPathSegments("api/v2/pins").build()
        val req = http.request(url, null).post(FormBody.Builder().add("strong", "false").build()).build()
        val o = http.json(req) as? JSONObject ?: throw PlexBadResponse("Unexpected PIN response")
        val code = o.optString("code").ifEmpty { throw PlexBadResponse("No PIN code") }
        return PlexPin(o.optLong("id"), code, o.optInt("expiresIn", 900))
    }

    /** The account token once the code has been entered, else null. */
    fun checkPin(pin: PlexPin): String? {
        val url = base.newBuilder().addPathSegments("api/v2/pins/${pin.id}").build()
        val o = http.json(http.request(url, null).get().build()) as? JSONObject ?: return null
        return o.optString("authToken").ifEmpty { null }.takeUnless { it == "null" }
    }

    fun servers(accountToken: String): List<PlexServer> {
        val url = clients.newBuilder().addPathSegments("api/v2/resources")
            .addQueryParameter("includeHttps", "1").addQueryParameter("includeRelay", "1").addQueryParameter("includeIPv6", "0").build()
        val a = http.json(http.request(url, accountToken).get().build()) as? JSONArray ?: throw PlexBadResponse("Unexpected resources response")
        return parseResources(a)
    }

    data class User(val name: String?, val thumb: String?)

    /** The signed-in account's name and avatar URL (plex.tv thumb). */
    fun user(accountToken: String): User {
        val url = base.newBuilder().addPathSegments("api/v2/user").build()
        val o = http.json(http.request(url, accountToken).get().build()) as? JSONObject
            ?: return User(null, null)
        val name = o.optStr("username") ?: o.optStr("title")
        val thumb = o.optStr("thumb")?.takeIf { it.startsWith("https://") }
        return User(name, thumb)
    }

    private fun JSONObject.optStr(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() && it != "null" }

    /** The signed-in account's name, for Settings. */
    fun userName(accountToken: String): String? = user(accountToken).name

    companion object {
        private fun flag(o: JSONObject, key: String): Boolean = when (val v = o.opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() == 1
            is String -> v == "1" || v == "true"
            else -> false
        }

        fun parseResources(a: JSONArray): List<PlexServer> {
            val out = ArrayList<PlexServer>()
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                if (e.optString("provides").split(',').none { it.trim() == "server" }) continue
                val id = e.optString("clientIdentifier").ifEmpty { continue }
                val token = e.optString("accessToken").ifEmpty { continue }
                val httpsRequired = flag(e, "httpsRequired")
                val conns = ArrayList<PlexServer.Connection>()
                val ca = e.optJSONArray("connections") ?: JSONArray()
                for (j in 0 until ca.length()) {
                    val c = ca.optJSONObject(j) ?: continue
                    val url = PlexHttp.serverUrl(c.optString("uri")) ?: continue
                    if (httpsRequired && url.scheme != "https") continue
                    conns.add(PlexServer.Connection(url, flag(c, "local"), flag(c, "relay")))
                }
                val sorted = conns.distinctBy { it.url }.sortedBy { it.priority }.take(12)
                out.add(PlexServer(id, e.optString("name", "Plex server"), flag(e, "owned"), token, sorted))
            }
            return out.sortedBy { it.name.lowercase() }
        }
    }
}
