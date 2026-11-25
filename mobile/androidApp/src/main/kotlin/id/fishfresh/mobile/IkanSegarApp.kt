package id.fishfresh.mobile

import android.app.Application
import id.fishfresh.data.sync.SupabaseConfig
import id.fishfresh.di.androidModule
import id.fishfresh.di.sharedModule
import id.fishfresh.domain.SyncScheduler
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

class IkanSegarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@IkanSegarApp)
            workManagerFactory()
            modules(sharedModule, androidModule(SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY)))
        }
        // Unggah sisa antrean dari sesi sebelumnya (berjalan saat ada internet).
        get<SyncScheduler>().requestSync()
    }
}
