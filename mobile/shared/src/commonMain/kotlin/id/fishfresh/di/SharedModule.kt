package id.fishfresh.di

import id.fishfresh.presentation.CaptureDetailViewModel
import id.fishfresh.presentation.CaptureSession
import id.fishfresh.presentation.CollectViewModel
import id.fishfresh.presentation.DataViewModel
import id.fishfresh.presentation.HistoryViewModel
import id.fishfresh.presentation.BackendViewModel
import id.fishfresh.presentation.ModelViewModel
import id.fishfresh.presentation.ResultViewModel
import id.fishfresh.presentation.ScanViewModel
import id.fishfresh.presentation.SettingsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val sharedModule = module {
    single { CaptureSession() }
    viewModelOf(::ModelViewModel)
    viewModelOf(::ScanViewModel)
    viewModelOf(::HistoryViewModel)
    viewModelOf(::CollectViewModel)
    viewModelOf(::DataViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::BackendViewModel)
    viewModel { (id: Long) -> ResultViewModel(id, get()) }
    viewModel { (id: String) -> CaptureDetailViewModel(id, get(), get()) }
}
