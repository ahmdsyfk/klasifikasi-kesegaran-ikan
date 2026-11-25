package id.fishfresh.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import id.fishfresh.data.CaptureRepositoryImpl
import id.fishfresh.data.storagePaths
import id.fishfresh.domain.SyncScheduler
import id.fishfresh.domain.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Satu putaran sinkronisasi. Dipakai oleh percobaan langsung (saat simpan / tombol Unggah) dan oleh
 * WorkManager (cadangan yang diulang otomatis). Mutex mencegah dua putaran berjalan bersamaan.
 */
class SyncRunner(private val gateway: SupabaseGateway, private val repo: CaptureRepositoryImpl) {
    private val mutex = Mutex()

    /** @return jumlah item yang gagal (0 = semua beres). */
    suspend fun run(): Int = mutex.withLock {
        if (!gateway.config.enabled) return 0
        var failed = 0
        for (d in repo.remoteDeletes()) {
            runCatching { gateway.deleteRemote(d.id, d.raw_path, d.eye_path) }
                .onSuccess { repo.doneRemoteDelete(d.id) }.onFailure { failed++ }
        }
        for (c in repo.pending()) {
            runCatching {
                if (c.syncState == SyncState.SYNCED) {
                    gateway.updateLabel(c.id, c.label.key)          // koreksi label setelah terunggah
                } else {
                    val (raw, eye) = storagePaths(c.dataset, c.fishCode, c.id)
                    gateway.upload(c, raw, eye)
                }
            }.onSuccess { repo.markSynced(c.id) }
                .onFailure { e -> failed++; repo.markFailed(c.id, e.message ?: e::class.simpleName ?: "gagal") }
        }
        failed
    }
}

class SyncSchedulerImpl(
    private val context: Context,
    private val gateway: SupabaseGateway,
    private val repo: CaptureRepositoryImpl,
    private val runner: SyncRunner,
) : SyncScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val configured: Boolean get() = gateway.config.enabled
    override fun observePending(): Flow<Int> = repo.observePending()
    override fun observeUploaded(): Flow<Int> = repo.observeUploaded()

    override fun requestSync() {
        if (!configured) return
        // 1) coba sekarang juga; 2) cadangan: WorkManager menunggu jaringan & mengulang dengan backoff.
        scope.launch { runCatching { runner.run() } }
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-captures", ExistingWorkPolicy.KEEP, req)
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters, private val runner: SyncRunner) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val failed = runner.run()
        return if (failed == 0) Result.success() else if (runAttemptCount < 8) Result.retry() else Result.failure()
    }
}
