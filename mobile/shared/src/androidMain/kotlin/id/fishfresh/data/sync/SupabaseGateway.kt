package id.fishfresh.data.sync

import id.fishfresh.domain.BackendCheck
import id.fishfresh.domain.BackendConfig
import id.fishfresh.domain.BackendProbe
import id.fishfresh.data.BackendSettingsImpl
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

/** Konfigurasi aktif (bawaan build atau diubah di menu Supabase). Kosong = unggah dimatikan, aplikasi tetap jalan offline. */
typealias SupabaseConfig = BackendConfig

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

private fun isDuplicate(e: Throwable): Boolean {
    val m = (e.message ?: "") + ((e as? RestException)?.error ?: "")
    return listOf("duplicate", "already exists", "23505", "409").any { m.contains(it, ignoreCase = true) }
}

/** Satu-satunya pintu ke Supabase. Sesi anonim dibuat otomatis (tanpa layar login). */
class SupabaseGateway(private val settings: BackendSettingsImpl, private val platform: Platform) : BackendProbe {
    private val mutex = Mutex()
    val config: SupabaseConfig get() = settings.state.value

    /** Klien dibuat ulang bila konfigurasi diubah. */
    private var built: Pair<SupabaseConfig, SupabaseClient?>? = null

    private fun build(cfg: SupabaseConfig): SupabaseClient? = if (!cfg.enabled) null else createSupabaseClient(cfg.url, cfg.publishableKey) {
        // Jaringan pasar sering lambat: beri waktu lebih panjang dari bawaan 10 detik.
        requestTimeout = 30.seconds
        install(Auth)
        install(Postgrest)
        install(Storage)
    }

    private suspend fun session(): SupabaseClient = mutex.withLock {
        val cfg = config
        val cur = built?.takeIf { it.first == cfg } ?: (cfg to build(cfg)).also { built = it }
        val c = cur.second ?: error("Supabase belum dikonfigurasi")
        c.auth.awaitInitialization()
        // Sesi anonim tersimpan milik proyek lain tidak berlaku di proyek baru.
        if (settings.sessionUrl != cfg.url) {
            runCatching { c.auth.clearSession() }
            settings.sessionUrl = cfg.url
        }
        if (c.auth.currentSessionOrNull() == null) c.auth.signInAnonymously()
        c
    }

    override suspend fun test(): List<BackendCheck> {
        if (!config.enabled) return listOf(BackendCheck("Konfigurasi", false, "URL atau key kosong"))
        val out = mutableListOf<BackendCheck>()
        val c = try {
            session().also { out += BackendCheck("Masuk anonim", true, "sesi aktif") }
        } catch (e: Throwable) {
            return out + BackendCheck("Masuk anonim", false, e.short())
        }
        out += check("Tabel captures") { c.from("captures").select { limit(1) }; "dapat diakses" }
        out += check("Bucket fish-eyes") { c.storage.from("fish-eyes").list(); "dapat diakses" }
        return out
    }

    private suspend fun check(name: String, block: suspend () -> String): BackendCheck =
        try { BackendCheck(name, true, block()) } catch (e: Throwable) { BackendCheck(name, false, e.short()) }

    private fun Throwable.short() = (message ?: this::class.simpleName ?: "galat").lineSequence().first().take(120)

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
