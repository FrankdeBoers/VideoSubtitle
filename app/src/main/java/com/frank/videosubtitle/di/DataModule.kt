package com.frank.videosubtitle.di

import androidx.room.Room
import com.frank.videosubtitle.data.engine.BaiduTranslationEngine
import com.frank.videosubtitle.data.engine.FFmpegKitEngine
import com.frank.videosubtitle.data.engine.Media3TransformerEngine
import com.frank.videosubtitle.data.engine.MicrosoftTranslationEngine
import com.frank.videosubtitle.data.engine.MlKitTranslationEngine
import com.frank.videosubtitle.data.engine.RouterTranslationEngine
import com.frank.videosubtitle.data.engine.RoutingMediaEngine
import com.frank.videosubtitle.data.engine.TencentTranslationEngine
import com.frank.videosubtitle.data.engine.WhisperJniEngine
import com.frank.videosubtitle.data.engine.YoudaoTranslationEngine
import com.frank.videosubtitle.data.orchestrator.TaskOrchestrator
import com.frank.videosubtitle.data.repository.DefaultModelRepository
import com.frank.videosubtitle.data.repository.DefaultSettingsRepository
import com.frank.videosubtitle.data.repository.DefaultTaskRepository
import com.frank.videosubtitle.data.repository.DefaultVideoRepository
import com.frank.videosubtitle.data.repository.ModelRepository
import com.frank.videosubtitle.data.repository.SettingsRepository
import com.frank.videosubtitle.data.repository.TaskRepository
import com.frank.videosubtitle.data.repository.VideoRepository
import com.frank.videosubtitle.data.source.local.AppDatabase
import com.frank.videosubtitle.data.source.local.SettingsDataStore
import com.frank.videosubtitle.data.source.local.TranslationCredentialsStore
import com.frank.videosubtitle.data.source.media.MediaStoreSaver
import com.frank.videosubtitle.data.source.media.UriResolver
import com.frank.videosubtitle.domain.engine.FFmpegEngine
import com.frank.videosubtitle.domain.engine.TranslationEngine
import com.frank.videosubtitle.domain.engine.WhisperEngine
import com.frank.videosubtitle.domain.usecase.BurnSubtitlesUseCase
import com.frank.videosubtitle.domain.usecase.ExtractAudioUseCase
import com.frank.videosubtitle.domain.usecase.TranscribeAudioUseCase
import com.frank.videosubtitle.domain.usecase.TranslateSubtitleUseCase
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val dataModule = module {
    single {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, "video_subtitle.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
    single { get<AppDatabase>().taskDao() }

    single {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    single { UriResolver(androidContext(), get()) }
    single { DefaultTaskRepository(get()) } bind TaskRepository::class
    single { DefaultVideoRepository(get(), get()) } bind VideoRepository::class
    single { DefaultModelRepository(androidContext(), get()) } bind ModelRepository::class

    single { SettingsDataStore(androidContext()) }
    single { DefaultSettingsRepository(get()) } bind SettingsRepository::class
    single { TranslationCredentialsStore(androidContext()) }

    single { FFmpegKitEngine() }
    single { Media3TransformerEngine(androidContext()) }
    single {
        RoutingMediaEngine(
            ffmpeg = get(),
            media3 = get(),
            settings = get(),
        )
    } bind FFmpegEngine::class
    single { WhisperJniEngine(get()) } bind WhisperEngine::class

    single { MlKitTranslationEngine(get()) }
    single { BaiduTranslationEngine(get(), get(), get()) }
    single { YoudaoTranslationEngine(get(), get(), get()) }
    single { TencentTranslationEngine(get(), get(), get()) }
    single { MicrosoftTranslationEngine(get(), get(), get()) }
    single<TranslationEngine> {
        RouterTranslationEngine(
            settings = get(),
            credentials = get(),
            mlkit = get(),
            baidu = get(),
            youdao = get(),
            tencent = get(),
            microsoft = get(),
        )
    }

    single { ExtractAudioUseCase(get()) }
    single { TranscribeAudioUseCase(get()) }
    single { TranslateSubtitleUseCase(get()) }
    single { BurnSubtitlesUseCase(get()) }

    single { MediaStoreSaver(androidContext()) }

    single {
        TaskOrchestrator(
            context = androidContext(),
            appScope = get<CoroutineScope>(named(APPLICATION_SCOPE)),
            dispatchers = get(),
            taskRepository = get(),
            modelRepository = get(),
            extractAudio = get(),
            transcribeAudio = get(),
            translateSubtitle = get(),
            burnSubtitles = get(),
            mediaStoreSaver = get(),
            settingsRepository = get(),
        )
    }
}
