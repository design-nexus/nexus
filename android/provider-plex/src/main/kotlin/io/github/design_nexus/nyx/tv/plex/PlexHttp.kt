package io.github.design_nexus.nyx.tv.plex

import io.github.design_nexus.nyx.tv.media.ProviderUnavailable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Who this client is, sent to Plex with every request. */
data class PlexClientInfo(
    val clientId: String,
    val product: String = "Nyx TV",
    val version: String = "0.1.0",
    val platform: String = "Android",
    val device: String = "Android TV",
    val deviceName: String = "Nyx TV",
)

class PlexAuthRequired : Exception("Plex sign-in is needed again")
class PlexBadResponse(message: String) : Exception(message)

/** HTTP for Plex. Errors never carry URLs, tokens or response bodies. */
class PlexHttp(val info: PlexClientInfo, base: OkHttpClient = OkHttpClient()) {
    val client: OkHttpClient = base.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    val probe: OkHttpClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    fun request(url: HttpUrl, token: String?): Request.Builder = Request.Builder().url(url).apply {
        header("Accept", "application/json")
        header("X-Plex-Product", info.product)
        header("X-Plex-Version", info.version)
        header("X-Plex-Platform", info.platform)
        header("X-Plex-Device", info.device)
        header("X-Plex-Device-Name", info.deviceName)
        header("X-Plex-Client-Identifier", info.clientId)
        if (token != null) header("X-Plex-Token", token)
    }

    fun json(request: Request, http: OkHttpClient = client): Any {
        val response = try { http.newCall(request).execute() } catch (e: IOException) { throw ProviderUnavailable("Plex server unreachable", e) }
        response.use { return parse(it) }
    }

    private fun parse(r: Response): Any {
        when (r.code) {
            in 200..299 -> {}
            401, 403 -> throw PlexAuthRequired()
            in 300..399 -> throw PlexBadResponse("Plex redirected the request")
            in 500..599 -> throw ProviderUnavailable("Plex server error ${r.code}")
            else -> throw PlexBadResponse("Plex answered ${r.code}")
        }
        val body = r.body.source()
        if (body.request(MAX_BODY + 1)) throw PlexBadResponse("Plex response too large")
        val text = body.readUtf8()
        if (text.isBlank()) return JSONObject()
        return try {
            if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
        } catch (_: Exception) {
            throw PlexBadResponse("Plex response wasn't JSON")
        }
    }

    companion object {
        const val MAX_BODY = 16L * 1024 * 1024

        /** A server address without credentials, path, query or fragment. */
        fun serverUrl(address: String): HttpUrl? {
            val u = address.trim().let { if ("://" in it) it else "http://$it" }.toHttpUrlOrNull() ?: return null
            if (u.username.isNotEmpty() || u.password.isNotEmpty() || u.query != null || u.fragment != null) return null
            if (u.encodedPath != "/" && u.encodedPath.isNotEmpty()) return null
            return u
        }

        /** A server-relative key: starts with one slash, no scheme, query or control characters. */
        fun relativeKey(path: String?): Boolean =
            path != null && path.startsWith('/') && !path.startsWith("//") && '?' !in path && '#' !in path && '\\' !in path &&
                "://" !in path && path.none { it.isISOControl() }
    }
}
