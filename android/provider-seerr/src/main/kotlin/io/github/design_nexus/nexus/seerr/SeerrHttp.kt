package io.github.design_nexus.nexus.seerr

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class SeerrHttp(baseClient: OkHttpClient) {
    val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val probeClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun get(url: HttpUrl, auth: SeerrAuthHeaders? = null, probe: Boolean = false): Any {
        val req = Request.Builder().url(url).get().applyAuth(auth).build()
        return executeJson(req, if (probe) probeClient else client).first
    }

    fun post(url: HttpUrl, body: JSONObject, auth: SeerrAuthHeaders? = null): Pair<Any, String?> {
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .applyAuth(auth)
            .build()
        return executeJson(req, client)
    }

    fun delete(url: HttpUrl, auth: SeerrAuthHeaders? = null) {
        val req = Request.Builder().url(url).delete().applyAuth(auth).build()
        executeJson(req, client)
    }

    private fun Request.Builder.applyAuth(auth: SeerrAuthHeaders?): Request.Builder {
        header("Accept", "application/json")
        auth?.cookie?.let { header("Cookie", it) }
        auth?.apiKey?.let { header("X-Api-Key", it) }
        return this
    }

    private fun executeJson(req: Request, http: OkHttpClient): Pair<Any, String?> {
        val res: Response = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw SeerrUnavailableException("Cannot reach Seerr server: ${e.message}", e)
        }
        res.use { r ->
            val raw = r.body.string()
            val setCookie = r.headers("Set-Cookie").firstOrNull { it.startsWith("connect.sid=") }
                ?.substringBefore(';')
            when (r.code) {
                in 200..299 -> {
                    if (raw.isBlank()) return JSONObject() to setCookie
                    val parsed = parseJson(raw)
                        ?: throw SeerrUnavailableException("Unexpected response from Seerr")
                    return parsed to setCookie
                }
                401 -> throw SeerrAuthExpiredException(extractMessage(raw) ?: "Invalid or expired Seerr session")
                403 -> throw SeerrPermissionException(extractMessage(raw) ?: "Insufficient Seerr permissions")
                409 -> throw SeerrDuplicateRequestException(extractMessage(raw) ?: "Request already exists")
                400 -> throw SeerrBadRequestException(extractMessage(raw) ?: "Invalid request")
                404 -> throw SeerrBadRequestException(extractMessage(raw) ?: "Title not found on Seerr")
                else -> throw SeerrUnavailableException(extractMessage(raw) ?: "Seerr returned HTTP ${r.code}")
            }
        }
    }

    private fun parseJson(raw: String): Any? {
        val t = raw.trim()
        return try {
            when {
                t.startsWith("{") -> JSONObject(t)
                t.startsWith("[") -> JSONArray(t)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractMessage(raw: String): String? {
        val obj = parseJson(raw) as? JSONObject ?: return null
        return obj.optString("message").ifEmpty { obj.optString("error").ifEmpty { null } }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * Normalizes user-supplied server URLs (LAN IPs, HTTPS, Tailscale hostnames/IPs).
         * Preserves explicit `http://` or `https://`; defaults bare hosts to `http://` if
         * IP/port or `.local`/`.ts.net`? Bare hosts without scheme default to `https://` unless
         * they look like a local IP or include `:5055`.
         */
        fun normalizeServerUrl(raw: String): HttpUrl? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else {
                val lower = trimmed.lowercase()
                val isLocal = lower.startsWith("192.168.") ||
                    lower.startsWith("10.") ||
                    lower.startsWith("100.") ||
                    lower.startsWith("127.") ||
                    lower.startsWith("localhost") ||
                    ".local" in lower ||
                    ":5055" in lower
                if (isLocal) "http://$trimmed" else "https://$trimmed"
            }
            val parsed = withScheme.toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "http" && parsed.scheme != "https") return null
            return parsed.newBuilder().encodedPath("/").query(null).fragment(null).build()
        }
    }
}

data class SeerrAuthHeaders(val cookie: String? = null, val apiKey: String? = null)
