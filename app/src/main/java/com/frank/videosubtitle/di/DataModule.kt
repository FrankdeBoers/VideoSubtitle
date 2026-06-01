package com.frank.videosubtitle.di

import androidx.room.Room
import com.frank.videosubtitle.data.engine.FFmpegKitEngine
import com.frank.videosubtitle.data.engine.WhisperJniEngine
import com.frank.videosubtitle.data.orchestrator.TaskOrchestrator
import com.frank.videosubtitle.data.repository.DefaultModelRepository
import com.frank.videosubtitle.data.repository.DefaultTaskRepository
import com.frank.videosubtitle.data.repository.DefaultVideoRepository
import com.frank.videosubtitle.data.repository.ModelRepository
import com.frank.videosubtitle.data.repository.TaskRepository
import com.frank.videosubtitle.data.repository.VideoRepository
import com.frank.videosubtitle.data.source.local.AppDatabase
import com.frank.videosubtitle.data.source.media.MediaStoreSaver
import com.frank.videosubtitle.data.source.media.UriResolver
import com.frank.videosubtitle.domain.engine.FFmpegEngine
import com.frank.videosubtitle.domain.engine.WhisperEngine
import com.frank.videosubtitle.domain.usecase.BurnSubtitlesUseCase
import com.frank.videosubtitle.domain.usecase.ExtractAudioUseCase
import com.frank.videosubtitle.domain.usecase.TranscribeAudioUseCase
import kotlinx.coroutines.CoroutineScope
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val dataModule = module {
    single {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, "video_subtitle.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
    single { get<AppDatabase>().taskDao() }

    single { UriResolver(androidContext(), get()) }
    single { DefaultTaskRepository(get()) } bind TaskRepository::class
    single { DefaultVideoRepository(get(), get()) } bind VideoRepository::class
    single { DefaultModelRepository(androidContext()) } bind ModelRepository::class

    single { FFmpegKitEngine() } bind FFmpegEngine::class
    single { WhisperJniEngine(get()) } bind WhisperEngine::class

    single { ExtractAudioUseCase(get()) }
    single { TranscribeAudioUseCase(get()) }
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
            burnSubtitles = get(),
            mediaStoreSaver = get(),
        )
    }
}
