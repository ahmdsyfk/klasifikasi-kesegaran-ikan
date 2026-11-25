package id.fishfresh.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val progress: Float) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

interface ModelRegistry {
    /** Daftar model (salinan lokal dulu, lalu diperbarui dari Supabase di latar). */
    val models: StateFlow<List<ModelEntry>>
    val states: StateFlow<Map<String, ModelState>>
    suspend fun refresh(): Result<Unit>
    suspend fun ensureDownloaded(id: String): Result<Unit>
    fun delete(id: String)
}

interface Classifier {
    suspend fun classify(photo: StoredPhoto, circle: EyeCircle?, model: ModelEntry, threads: Int): Prediction
}

interface PhotoStore {
    /** Simpan foto JPEG mentah (dari kamera/galeri): luruskan EXIF, batasi sisi panjang. */
    suspend fun importJpeg(sourcePath: String, deleteSource: Boolean): StoredPhoto
    /** Buat crop mata 224 bermasker (PNG) dari foto + lingkaran; kembalikan path + metrik kualitas. */
    suspend fun makeEyeCrop(photo: StoredPhoto, circle: EyeCircle, id: String): EyeCropResult
    fun delete(path: String)
}

data class EyeCropResult(val path: String, val sharpness: Double, val brightness: Double)

interface CaptureRepository {
    fun observe(): Flow<List<Capture>>
    fun observeOne(id: String): Flow<Capture?>
    suspend fun pending(): List<Capture>
    suspend fun add(capture: Capture)
    suspend fun updateLabel(id: String, label: FreshnessClass)
    suspend fun markSynced(id: String)
    suspend fun markFailed(id: String, error: String)
    suspend fun delete(id: String)
    suspend fun species(): List<String>
}

interface ScanRepository {
    fun observe(): Flow<List<ScanRecord>>
    fun observeOne(id: Long): Flow<ScanRecord?>
    suspend fun add(record: ScanRecord): Long
    suspend fun delete(id: Long)
}

interface SettingsStore {
    val state: StateFlow<AppSettings>
    fun update(transform: (AppSettings) -> AppSettings)
}

interface SyncScheduler {
    val configured: Boolean
    fun requestSync()
    /** Jumlah foto yang belum terunggah (untuk lencana). */
    fun observePending(): Flow<Int>
}

interface Platform {
    val deviceModel: String
    val appVersion: String
    fun now(): Long
    fun uuid(): String
    fun today(): String
    fun formatTime(epochMs: Long): String
}
