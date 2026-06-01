package com.frank.videosubtitle

import android.app.Application
import com.frank.videosubtitle.di.appModule
import com.frank.videosubtitle.di.dataModule
import com.frank.videosubtitle.di.uiModule
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
            modules(appModule, dataModule, uiModule)
        }
    }
}
