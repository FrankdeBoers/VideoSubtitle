package com.frank.videosubtitle.di

import com.frank.videosubtitle.ui.home.HomeViewModel
import com.frank.videosubtitle.ui.progress.ProgressViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val uiModule = module {
    viewModel { HomeViewModel(get(), get()) }
    viewModel { (taskId: String) -> ProgressViewModel(taskId, get(), get()) }
}
