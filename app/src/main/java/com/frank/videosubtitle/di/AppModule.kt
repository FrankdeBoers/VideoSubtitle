package com.frank.videosubtitle.di

import com.frank.videosubtitle.util.DefaultDispatcherProvider
import com.frank.videosubtitle.util.DispatcherProvider
import org.koin.dsl.bind
import org.koin.dsl.module

val appModule = module {
    single { DefaultDispatcherProvider() } bind DispatcherProvider::class
}
