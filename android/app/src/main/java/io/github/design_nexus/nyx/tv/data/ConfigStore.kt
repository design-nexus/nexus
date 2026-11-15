package io.github.design_nexus.nyx.tv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.design_nexus.nyx.tv.core.ConfigCodec
import io.github.design_nexus.nyx.tv.core.LauncherConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.store by preferencesDataStore("launcher")
private val CONFIG = stringPreferencesKey("config")

/** The launcher's settings: read once at start, then kept in memory and written on change. */
class ConfigStore(private val context: Context, private val scope: CoroutineScope) {
    private val _config = MutableStateFlow(LauncherConfig())
    val config: StateFlow<LauncherConfig> = _config
    @Volatile var loaded = false
        private set

    suspend fun load(): LauncherConfig {
        if (loaded) return _config.value
        val text = withContext(Dispatchers.IO) { context.store.data.first()[CONFIG] }
        _config.value = ConfigCodec.decode(text)
        loaded = true
        return _config.value
    }

    fun update(f: (LauncherConfig) -> LauncherConfig) {
        val next = f(_config.value)
        if (next == _config.value) return
        _config.value = next
        scope.launch(Dispatchers.IO) { context.store.edit { it[CONFIG] = ConfigCodec.encode(next) } }
    }

    /** For backups: everything except credentials (the config holds none). */
    fun export(): String = ConfigCodec.encode(_config.value)

    fun import(text: String) = update { ConfigCodec.decode(text) }
}
