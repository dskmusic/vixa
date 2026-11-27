package com.dskmusic.vixa

import android.app.Application
import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import com.dskmusic.vixa.ai.AiJobs
import com.dskmusic.vixa.ai.ModelStore
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.data.ProjectStore
import com.dskmusic.vixa.data.SettingsStore
import com.dskmusic.vixa.data.online.OnlineRepository
import com.dskmusic.vixa.engine.Exporter
import com.dskmusic.vixa.engine.Fonts
import com.dskmusic.vixa.media.Proxies
import com.dskmusic.vixa.media.Thumbs
import com.dskmusic.vixa.media.Waveforms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Contenedor de dependencias de la app (inyección manual, sin Hilt). */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context, appScope)
    val projects = ProjectStore(context)
    val exporter = Exporter(context)
    val proxies = Proxies(context)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cache(Cache(File(context.cacheDir, "http"), 64L * 1024 * 1024))
        .build()
    val online = OnlineRepository(context, http, settings)
    // Las descargas de modelos son grandes: sin caché HTTP y con más margen de lectura.
    val models = ModelStore(context, http.newBuilder().cache(null).readTimeout(60, TimeUnit.SECONDS).build(), settings)
    val aiJobs = AiJobs(context, appScope)
}

class VixaApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        ErrorLog.init(this)
        com.dskmusic.vixa.engine.ShapeLib.init(this)
        Fonts.init(this)
        Thumbs.init(this)
        Waveforms.init(this)
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(container.http))
                add(VideoFrameDecoder.Factory())
                if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) Thumbs.clearMemory()
    }
}
