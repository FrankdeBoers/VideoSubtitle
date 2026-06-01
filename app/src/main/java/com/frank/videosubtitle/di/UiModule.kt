package com.frank.videosubtitle.di

import com.frank.videosubtitle.ui.editor.EditorViewModel
import com.frank.videosubtitle.ui.home.HomeViewModel
import com.frank.videosubtitle.ui.progress.ProgressViewModel
import com.frank.videosubtitle.ui.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val uiModule = module {
    viewModel { HomeViewModel(get(), get(), get()) }
    viewModel { (taskId: String) -> ProgressViewModel(taskId, get(), get(), get(), get()) }
    viewModel { (taskId: String) -> EditorViewModel(taskId, get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), androidContext().cacheDir) }
}
