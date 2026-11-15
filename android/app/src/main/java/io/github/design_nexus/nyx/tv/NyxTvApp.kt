package io.github.design_nexus.nyx.tv

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.SystemClock
import android.provider.Settings
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.design_nexus.nyx.tv.data.AppRepository
import io.github.design_nexus.nyx.tv.data.ConfigStore
import io.github.design_nexus.nyx.tv.data.HomeRepository
import io.github.design_nexus.nyx.tv.data.MetadataCache
import io.github.design_nexus.nyx.tv.data.SecureSessionStore
import io.github.design_nexus.nyx.tv.playback.Media3Bridge
import io.github.design_nexus.nyx.tv.playback.PlaybackPrefs
import io.github.design_nexus.nyx.tv.playback.PlaybackRouter
import io.github.design_nexus.nyx.tv.playback.PlexAppBridge
import io.github.design_nexus.nyx.tv.plex.PlexClientInfo
import io.github.design_nexus.nyx.tv.plex.PlexHttp
import io.github.design_nexus.nyx.tv.plex.PlexProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.UUID

/** The launcher's singletons. Nothing here touches the network at startup. */
class NyxTvApp : Application() {
    val startedAt: Long = SystemClock.elapsedRealtime()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var config: ConfigStore private set
    lateinit var apps: AppRepository private set
    lateinit var plex: PlexProvider private set
    lateinit var cache: MetadataCache private set
    lateinit var home: HomeRepository private set
    lateinit var images: ImageLoader private set
    lateinit var playbackPrefs: PlaybackPrefs private set
    lateinit var playback: PlaybackRouter private set
    lateinit var http: OkHttpClient private set

    override fun onCreate() {
        super.onCreate()
        config = ConfigStore(this, scope)
        apps = AppRepository(this)
        cache = MetadataCache(this)
        playbackPrefs = PlaybackPrefs(this)
        val sessions = SecureSessionStore(this)
        val clientId = getSharedPreferences("device", MODE_PRIVATE).let { p ->
            p.getString("client_id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("client_id", it).apply() }
        }
        val deviceName = runCatching { Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull() ?: "Nyx TV"
        val info = PlexClientInfo(clientId = clientId, version = BuildConfig.VERSION_NAME, deviceName = deviceName)
        lateinit var provider: PlexProvider
        http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val token = if (req.header("X-Plex-Token") == null) provider.tokenFor(req.url) else null
                chain.proceed(if (token != null) req.newBuilder().header("X-Plex-Token", token).build() else req)
            }
            .build()
        provider = PlexProvider(PlexHttp(info, http), sessions, scope, localDiscovery = { Multicast.discover(this) })
        plex = provider
        home = HomeRepository(plex, cache, scope)
        playback = PlaybackRouter(playbackPrefs, Media3Bridge(this), PlexAppBridge(this, playbackPrefs))
        images = ImageLoader.Builder(this)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(MEMORY_CACHE_BYTES).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork").toOkioPath()).maxSizeBytes(DISK_CACHE_BYTES).build() }
            .crossfade(false)
            .apply { if (BuildConfig.DEBUG) logger(coil3.util.DebugLogger()) }
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val mem = images.memoryCache ?: return
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> mem.clear()
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> mem.trimToSize(mem.maxSize / 4)
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> mem.trimToSize(mem.maxSize / 2)
        }
    }

    companion object {
        const val MEMORY_CACHE_BYTES = 24L * 1024 * 1024
        const val DISK_CACHE_BYTES = 384L * 1024 * 1024
    }
}

/** Plex local discovery needs a multicast lock on Android. */
private object Multicast {
    fun discover(app: Application): List<io.github.design_nexus.nyx.tv.plex.Gdm.Found> {
        val wifi = app.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return emptyList()
        val lock = wifi.createMulticastLock("nyx-tv-gdm").apply { setReferenceCounted(false) }
        return try {
            lock.acquire()
            io.github.design_nexus.nyx.tv.plex.Gdm.discover()
        } finally {
            lock.release()
        }
    }
}
