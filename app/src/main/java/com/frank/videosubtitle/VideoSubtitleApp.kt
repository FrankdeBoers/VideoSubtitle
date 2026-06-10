package com.frank.videosubtitle

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.frank.videosubtitle.data.repository.ModelRepository
import com.frank.videosubtitle.data.repository.TaskRepository
import com.frank.videosubtitle.di.APPLICATION_SCOPE
import com.frank.videosubtitle.di.appModule
import com.frank.videosubtitle.di.dataModule
import com.frank.videosubtitle.di.uiModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.core.qualifier.named
import timber.log.Timber

class VideoSubtitleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        DynamicColors.applyToActivitiesIfAvailable(this)
        startKoin {
            androidLogger(Level.INFO)
            androidContext(this@VideoSubtitleApp)
            modules(appModule, dataModule, uiModule)
        }

        // Rewind tasks left in flight by a process kill (Phase 6 §6.2).
        val appScope: CoroutineScope = get(qualifier = named(APPLICATION_SCOPE))
        val taskRepo: TaskRepository by inject()
        val modelRepo: ModelRepository by inject()
        appScope.launch {
            runCatching { taskRepo.recoverInterrupted() }
                .onFailure { Timber.e(it, "Recovery sweep failed") }
        }
        // Materialize bundled GGML weights (assets/models/*.bin) to filesDir so
        // first-run transcription works without a download. Runs on the IO
        // dispatcher of applicationScope; the first ViewModel isAvailable()
        // check will see the file already there.
        appScope.launch {
            runCatching { modelRepo.prefetchBundled() }
                .onFailure { Timber.e(it, "Bundled model prefetch failed") }
        }
    }
}
