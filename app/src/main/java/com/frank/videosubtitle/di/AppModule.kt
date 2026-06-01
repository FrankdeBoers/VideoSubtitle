package com.frank.videosubtitle.di

import com.frank.videosubtitle.util.DefaultDispatcherProvider
import com.frank.videosubtitle.util.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

const val APPLICATION_SCOPE = "applicationScope"

val appModule = module {
    single { DefaultDispatcherProvider() } bind DispatcherProvider::class
    single<CoroutineScope>(named(APPLICATION_SCOPE)) {
        CoroutineScope(SupervisorJob() + get<DispatcherProvider>().io)
    }
}
