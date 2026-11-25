package id.fishfresh.di

import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import id.fishfresh.data.AndroidPlatform
import id.fishfresh.data.BackendSettingsImpl
import id.fishfresh.domain.BackendProbe
import id.fishfresh.domain.BackendSettings
import id.fishfresh.data.CaptureRepositoryImpl
import id.fishfresh.data.ModelRegistryImpl
import id.fishfresh.data.PhotoStoreImpl
import id.fishfresh.data.ScanRepositoryImpl
import id.fishfresh.data.SettingsStoreImpl
import id.fishfresh.data.ml.LiteRtClassifier
import id.fishfresh.data.sync.SupabaseConfig
import id.fishfresh.data.sync.SupabaseGateway
import id.fishfresh.data.sync.SyncRunner
import id.fishfresh.data.sync.SyncSchedulerImpl
import id.fishfresh.data.sync.SyncWorker
import id.fishfresh.db.IkanSegarDb
import id.fishfresh.domain.CaptureRepository
import id.fishfresh.domain.Classifier
import id.fishfresh.domain.ModelRegistry
import id.fishfresh.domain.PhotoStore
import id.fishfresh.domain.Platform
import id.fishfresh.domain.ScanRepository
import id.fishfresh.domain.SettingsStore
import id.fishfresh.domain.SyncScheduler
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.dsl.bind
import org.koin.dsl.module

fun androidModule(supabase: SupabaseConfig, modelRegistryUrl: String) = module {
    single { BackendSettingsImpl(androidContext(), supabase) } bind BackendSettings::class
    single<Platform> { AndroidPlatform(androidContext()) }
    single<SettingsStore> { SettingsStoreImpl(androidContext()) }
    single<PhotoStore> { PhotoStoreImpl(androidContext()) }
    single { IkanSegarDb(AndroidSqliteDriver(IkanSegarDb.Schema, androidContext(), "ikansegar.db")) }
    single { CaptureRepositoryImpl(get(), get()) } bind CaptureRepository::class
    single<ScanRepository> { ScanRepositoryImpl(get(), get()) }
    single { SupabaseGateway(get(), get()) } bind BackendProbe::class
    single { ModelRegistryImpl(androidContext(), modelRegistryUrl) } bind ModelRegistry::class
    single<Classifier> {
        val registry: ModelRegistryImpl = get()
        LiteRtClassifier(androidContext(), registry).also { registry.classifier = it }
    }
    single { SyncRunner(get(), get()) }
    single<SyncScheduler> { SyncSchedulerImpl(androidContext(), get(), get(), get()) }
    workerOf(::SyncWorker)
}
