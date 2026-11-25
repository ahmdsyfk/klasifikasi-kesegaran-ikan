package id.fishfresh.data.sync

import id.fishfresh.domain.Capture
import id.fishfresh.domain.Platform
import id.fishfresh.domain.Prediction
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** Konfigurasi dari BuildConfig aplikasi (kosong = fitur Supabase dimatikan, aplikasi tetap jalan offline). */
data class SupabaseConfig(val url: String, val publishableKey: String) {
    val enabled: Boolean get() = url.startsWith("https://") && publishableKey.isNotBlank()
}

@Serializable
data class CaptureDto(
    val id: String,
    @SerialName("dataset_id") val datasetId: String,
    @SerialName("fish_code") val fishCode: String,
    val species: String,
    val label: String,
    val market: String?,
    @SerialName("captured_at") val capturedAt: String,
    @SerialName("raw_path") val rawPath: String,
    @SerialName("eye_path") val eyePath: String,
    @SerialName("raw_width") val rawWidth: Int,
    @SerialName("raw_height") val rawHeight: Int,
    @SerialName("raw_sha256") val rawSha256: String,
    @SerialName("crop_cx") val cropCx: Float,
    @SerialName("crop_cy") val cropCy: Float,
    @SerialName("crop_r") val cropR: Float,
    @SerialName("crop_margin") val cropMargin: Float = 0.10f,
    @SerialName("crop_adjusted") val cropAdjusted: Boolean,
    @SerialName("sharpness_lapvar") val sharpness: Double?,
    @SerialName("brightness_mean") val brightness: Double?,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("app_version") val appVersion: String,
    val notes: String?,
)

@Serializable
data class PredictionDto(
    @SerialName("capture_id") val captureId: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("model_version") val modelVersion: String,
    val scenario: String,
    @SerialName("input_variant") val inputVariant: String,
    @SerialName("score_highly_fresh") val highlyFresh: Float,
    @SerialName("score_fresh") val fresh: Float,
    @SerialName("score_not_fresh") val notFresh: Float,
    @SerialName("preprocess_ms") val preprocessMs: Double,
    @SerialName("inference_ms") val inferenceMs: Double,
    val threads: Int,
    val quantization: String,
    @SerialName("shown_before_label") val shownBeforeLabel: Boolean = false,
)

@Serializable
data class ModelRow(
    val id: String,
    val scenario: String,
    @SerialName("scenario_name") val scenarioName: String,
    @SerialName("scenario_desc") val scenarioDesc: String? = null,
    val arch: String,
    @SerialName("arch_name") val archName: String,
    val seed: Int? = null,
    @SerialName("input_variant") val inputVariant: String,
    @SerialName("input_size") val inputSize: Int,
    val classes: List<String>,
    val quantization: String,
    val version: String,
    @SerialName("file_path") val filePath: String,
    @SerialName("file_mb") val fileMb: Double,
    @SerialName("file_sha256") val fileSha256: String,
    val metrics: JsonObject = JsonObject(emptyMap()),
    @SerialName("sort_order") val sortOrder: Int = 100,
    @SerialName("is_default") val isDefault: Boolean = false,
)

private fun isDuplicate(e: Throwable): Boolean {
    val m = (e.message ?: "") + ((e as? RestException)?.error ?: "")
    return listOf("duplicate", "already exists", "23505", "409").any { m.contains(it, ignoreCase = true) }
}

/** Satu-satunya pintu ke Supabase. Sesi anonim dibuat otomatis (tanpa layar login). */
class SupabaseGateway(val config: SupabaseConfig, private val platform: Platform) {
    private val mutex = Mutex()
    private val client: SupabaseClient? = if (!config.enabled) null else createSupabaseClient(config.url, config.publishableKey) {
        // Jaringan pasar sering lambat: beri waktu lebih panjang dari bawaan 10 detik.
        requestTimeout = 30.seconds
        install(Auth)
        install(Postgrest)
        install(Storage)
    }

    private suspend fun session(): SupabaseClient = mutex.withLock {
        val c = client ?: error("Supabase belum dikonfigurasi")
        c.auth.awaitInitialization()
        if (c.auth.currentSessionOrNull() == null) c.auth.signInAnonymously()
        c
    }

    suspend fun accessToken(): String = session().auth.currentAccessTokenOrNull() ?: error("Sesi tidak tersedia")

    suspend fun models(): List<ModelRow> =
        session().from("models").select().decodeList<ModelRow>()

    suspend fun upload(capture: Capture, rawPath: String, eyePath: String) {
        val c = session()
        val bucket = c.storage.from("fish-eyes")
        uploadOnce { bucket.upload(rawPath, File(capture.photo.path).readBytes()) { upsert = false; contentType = ContentType.Image.JPEG } }
        uploadOnce { bucket.upload(eyePath, File(capture.eyePath).readBytes()) { upsert = false; contentType = ContentType.Image.PNG } }
        uploadOnce { c.from("captures").insert(capture.toDto(rawPath, eyePath)) }
        capture.predictions.forEach { p -> uploadOnce { c.from("predictions").insert(p.toDto(capture.id)) } }
    }

    suspend fun updateLabel(id: String, label: String) {
        session().from("captures").update({ set("label", label) }) { filter { eq("id", id) } }
    }

    suspend fun deleteRemote(id: String, rawPath: String, eyePath: String) {
        val c = session()
        c.from("captures").delete { filter { eq("id", id) } }
        runCatching { c.storage.from("fish-eyes").delete(rawPath, eyePath) }
    }

    /** Unggah ulang setelah gagal di tengah jalan aman: baris/objek yang sudah ada dianggap berhasil. */
    private suspend fun uploadOnce(block: suspend () -> Unit) {
        try { block() } catch (e: Throwable) { if (!isDuplicate(e)) throw e }
    }

    private fun Capture.toDto(raw: String, eye: String) = CaptureDto(
        id = id, datasetId = dataset, fishCode = fishCode, species = species, label = label.key,
        market = market.ifBlank { null }, capturedAt = java.time.Instant.ofEpochMilli(capturedAt).toString(),
        rawPath = raw, eyePath = eye, rawWidth = photo.width, rawHeight = photo.height, rawSha256 = photo.sha256,
        cropCx = circle.cx, cropCy = circle.cy, cropR = circle.r, cropAdjusted = circleAdjusted,
        sharpness = sharpness, brightness = brightness, deviceModel = platform.deviceModel,
        appVersion = platform.appVersion, notes = notes.ifBlank { null },
    )

    private fun Prediction.toDto(captureId: String) = PredictionDto(
        captureId, modelId, modelVersion, scenario, inputVariant,
        scoreOf(id.fishfresh.domain.FreshnessClass.HIGHLY_FRESH), scoreOf(id.fishfresh.domain.FreshnessClass.FRESH),
        scoreOf(id.fishfresh.domain.FreshnessClass.NOT_FRESH), preprocessMs, inferenceMs, threads, quantization,
    )
}
