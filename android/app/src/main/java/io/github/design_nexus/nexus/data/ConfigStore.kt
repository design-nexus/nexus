package io.github.design_nexus.nexus.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.design_nexus.nexus.core.ConfigCodec
import io.github.design_nexus.nexus.core.LauncherConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.store by preferencesDataStore("launcher")
private val CONFIG = stringPreferencesKey("config")

/** The launcher's settings: read once at start, then kept in memory and written on change. */
class ConfigStore(private val context: Context, private val scope: CoroutineScope) {
    private val _config = MutableStateFlow(LauncherConfig())
    val config: StateFlow<LauncherConfig> = _config
    private val loadMutex = Mutex()
    private val writeMutex = Mutex()
    private val stateLock = Any()
    @Volatile var loaded = false
        private set

    suspend fun load(): LauncherConfig {
        if (loaded) return _config.value
        return loadMutex.withLock {
            if (loaded) return@withLock _config.value
            val text = withContext(Dispatchers.IO) { context.store.data.first()[CONFIG] }
            synchronized(stateLock) {
                if (!loaded) {
                    _config.value = ConfigCodec.decode(text)
                    loaded = true
                }
            }
            _config.value
        }
    }

    fun update(f: (LauncherConfig) -> LauncherConfig) {
        val next = synchronized(stateLock) {
            val n = f(_config.value)
            if (n == _config.value) return
            _config.value = n
            loaded = true
            n
        }
        scope.launch(Dispatchers.IO) { persist(next) }
    }

    suspend fun updateAndAwait(f: (LauncherConfig) -> LauncherConfig) {
        val next = synchronized(stateLock) {
            val n = f(_config.value)
            _config.value = n
            loaded = true
            n
        }
        withContext(Dispatchers.IO) { persist(next) }
    }

    private suspend fun persist(c: LauncherConfig) {
        writeMutex.withLock {
            val snapshot = synchronized(stateLock) { _config.value }
            context.store.edit { it[CONFIG] = ConfigCodec.encode(snapshot.takeIf { it == c } ?: snapshot) }
        }
    }

    /** For backups: everything except credentials (the config holds none). */
    fun export(): String = ConfigCodec.encode(_config.value)

    fun import(text: String) = update { ConfigCodec.decode(text) }
}
