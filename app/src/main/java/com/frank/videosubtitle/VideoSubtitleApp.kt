package com.frank.videosubtitle

import android.app.Application
import com.frank.videosubtitle.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import timber.log.Timber

class VideoSubtitleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        startKoin {
            androidLogger(Level.INFO)
            androidContext(this@VideoSubtitleApp)
            modules(appModule)
        }
    }
}
