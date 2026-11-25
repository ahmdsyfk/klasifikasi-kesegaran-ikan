package id.fishfresh.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import id.fishfresh.db.IkanSegarDb
import id.fishfresh.db.Remote_delete
import id.fishfresh.domain.Capture
import id.fishfresh.domain.CaptureRepository
import id.fishfresh.domain.EyeCircle
import id.fishfresh.domain.FreshnessClass
import id.fishfresh.domain.PhotoStore
import id.fishfresh.domain.Prediction
import id.fishfresh.domain.ScanRecord
import id.fishfresh.domain.ScanRepository
import id.fishfresh.domain.StoredPhoto
import id.fishfresh.domain.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import id.fishfresh.db.Capture as CaptureRow
import id.fishfresh.db.Scan as ScanRow

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val predList = ListSerializer(Prediction.serializer())

fun CaptureRow.toDomain() = Capture(
    id = id, dataset = dataset, fishCode = fish_code, species = species, label = FreshnessClass.fromKey(label),
    market = market, notes = notes, capturedAt = captured_at,
    photo = StoredPhoto(photo_path, width.toInt(), height.toInt(), sha256), eyePath = eye_path,
    circle = EyeCircle(cx.toFloat(), cy.toFloat(), r.toFloat()), circleAdjusted = adjusted != 0L,
    sharpness = sharpness, brightness = brightness, predictions = json.decodeFromString(predList, predictions_json),
    syncState = SyncState.valueOf(sync_state), syncError = sync_error,
)

private fun Capture.toRow() = CaptureRow(
    id, dataset, fishCode, species, label.key, market, notes, capturedAt, photo.path, photo.width.toLong(),
    photo.height.toLong(), photo.sha256, eyePath, circle.cx.toDouble(), circle.cy.toDouble(), circle.r.toDouble(),
    if (circleAdjusted) 1 else 0, sharpness, brightness, json.encodeToString(predList, predictions),
    syncState.name, syncError, 0,
)

/** Path objek di bucket fish-eyes: <dataset>/<fish_code>/<id>_raw.jpg dan _eye.png */
fun storagePaths(dataset: String, fishCode: String, id: String) =
    "$dataset/$fishCode/${id}_raw.jpg" to "$dataset/$fishCode/${id}_eye.png"

class CaptureRepositoryImpl(private val db: IkanSegarDb, private val photos: PhotoStore) : CaptureRepository {
    private val q = db.ikanSegarQueries
    private val io = Dispatchers.Default

    override fun observe(): Flow<List<Capture>> =
        q.allCaptures().asFlow().mapToList(io).map { l -> l.map { it.toDomain() } }

    override fun observeOne(id: String): Flow<Capture?> =
        q.captureById(id).asFlow().mapToOneOrNull(io).map { it?.toDomain() }

    fun observePending(): Flow<Int> = q.countPending().asFlow().mapToOne(io).map { it.toInt() }
    fun observeUploaded(): Flow<Int> = q.countSynced().asFlow().mapToOne(io).map { it.toInt() }

    override suspend fun pending(): List<Capture> = withContext(io) { q.pendingCaptures().executeAsList().map { it.toDomain() } }
    override suspend fun add(capture: Capture) = withContext(io) { q.upsertCapture(capture.toRow()); Unit }
    override suspend fun updateLabel(id: String, label: FreshnessClass) = withContext(io) { q.updateLabel(label.key, id); Unit }
    override suspend fun markSynced(id: String) = withContext(io) { q.markSynced(id); Unit }
    override suspend fun markFailed(id: String, error: String) = withContext(io) { q.markFailed(error.take(300), id); Unit }
    override suspend fun species(): List<String> = withContext(io) { q.recentSpecies().executeAsList() }

    override suspend fun delete(id: String) = withContext(io) {
        val c = q.captureById(id).executeAsOneOrNull() ?: return@withContext
        db.transaction {
            if (c.sync_state == SyncState.SYNCED.name) {
                val (raw, eye) = storagePaths(c.dataset, c.fish_code, c.id)
                q.queueRemoteDelete(Remote_delete(id, raw, eye))
            }
            q.deleteCapture(id)
        }
        photos.delete(c.photo_path); photos.delete(c.eye_path)
    }

    suspend fun remoteDeletes(): List<Remote_delete> = withContext(io) { q.remoteDeletes().executeAsList() }
    suspend fun doneRemoteDelete(id: String) = withContext(io) { q.doneRemoteDelete(id); Unit }
}

class ScanRepositoryImpl(private val db: IkanSegarDb, private val photos: PhotoStore) : ScanRepository {
    private val q = db.ikanSegarQueries
    private val io = Dispatchers.Default

    private fun ScanRow.toDomain() = ScanRecord(
        id, StoredPhoto(photo_path, width.toInt(), height.toInt(), sha256), eye_path,
        if (cx != null && cy != null && r != null) EyeCircle(cx.toFloat(), cy.toFloat(), r.toFloat()) else null,
        created_at, json.decodeFromString(predList, predictions_json),
    )

    override fun observe(): Flow<List<ScanRecord>> = q.allScans().asFlow().mapToList(io).map { l -> l.map { it.toDomain() } }
    override fun observeOne(id: Long): Flow<ScanRecord?> = q.scanById(id).asFlow().mapToOneOrNull(io).map { it?.toDomain() }

    override suspend fun add(record: ScanRecord): Long = withContext(io) {
        db.transactionWithResult {
            q.insertScan(
                record.photo.path, record.photo.width.toLong(), record.photo.height.toLong(), record.photo.sha256,
                record.eyePath, record.circle?.cx?.toDouble(), record.circle?.cy?.toDouble(), record.circle?.r?.toDouble(),
                record.createdAt, json.encodeToString(predList, record.predictions),
            )
            q.lastInsertId().executeAsOne()
        }
    }

    override suspend fun delete(id: Long) = withContext(io) {
        val s = q.scanById(id).executeAsOneOrNull() ?: return@withContext
        q.deleteScan(id)
        photos.delete(s.photo_path); s.eye_path?.let(photos::delete)
    }
}
