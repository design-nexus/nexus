package io.github.design_nexus.nyx.tv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.design_nexus.nyx.tv.plex.PlexSession
import io.github.design_nexus.nyx.tv.plex.PlexSessionStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Plex session (server and account tokens), encrypted with an AES-GCM key that lives in
 * the Android Keystore and never leaves it. The app has backups off, so this file isn't
 * copied anywhere either.
 */
class SecureSessionStore(context: Context) : PlexSessionStore {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)
    @Volatile private var cached: PlexSession? = null
    @Volatile private var read = false

    @Synchronized
    override fun load(): PlexSession? {
        if (read) return cached
        cached = prefs.getString(KEY, null)?.let { runCatching { decode(decrypt(it)) }.getOrNull() }
        read = true
        return cached
    }

    @Synchronized
    override fun save(session: PlexSession?) {
        cached = session
        read = true
        if (session == null) prefs.edit().remove(KEY).apply() else prefs.edit().putString(KEY, encrypt(encode(session))).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(text: String): String {
        val all = Base64.decode(text, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        return String(c.doFinal(all, 12, all.size - 12))
    }

    private fun encode(s: PlexSession) = JSONObject().apply {
        put("serverId", s.serverId); put("serverName", s.serverName); put("serverToken", s.serverToken)
        put("addresses", JSONArray(s.addresses)); s.lastGood?.let { put("lastGood", it) }
        s.accountToken?.let { put("accountToken", it) }; s.userName?.let { put("userName", it) }
    }.toString()

    private fun decode(t: String): PlexSession {
        val o = JSONObject(t)
        val a = o.optJSONArray("addresses") ?: JSONArray()
        return PlexSession(
            serverId = o.getString("serverId"),
            serverName = o.optString("serverName", "Plex"),
            serverToken = o.getString("serverToken"),
            addresses = (0 until a.length()).map { a.getString(it) },
            lastGood = o.optString("lastGood").ifEmpty { null },
            accountToken = o.optString("accountToken").ifEmpty { null },
            userName = o.optString("userName").ifEmpty { null },
        )
    }

    private companion object {
        const val KEY = "plex_session"
        const val ALIAS = "nyx_tv_secrets"
    }
}
