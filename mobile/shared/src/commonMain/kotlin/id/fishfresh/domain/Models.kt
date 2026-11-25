package id.fishfresh.domain

import kotlinx.serialization.Serializable

@Serializable
enum class FreshnessClass(val key: String, val labelId: String) {
    HIGHLY_FRESH("highly_fresh", "Sangat Segar"),
    FRESH("fresh", "Segar"),
    NOT_FRESH("not_fresh", "Tidak Segar");

    companion object {
        fun fromKey(key: String): FreshnessClass =
            entries.firstOrNull { it.key == key } ?: error("Kelas tidak dikenal: $key")
    }
}

enum class InputVariant(val key: String) {
    EYE_CROP("eye_crop"), FULL("full");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: FULL
    }
}

@Serializable
data class ModelMetrics(
    val accMfed: Double? = null,
    val f1Mfed: Double? = null,
    val f1Ffe: Double? = null,
    val deltaF1: Double? = null,
    val latencyMsCpu4: Double? = null,
    val params: Long? = null,
)

/** Satu model di registry: skenario x arsitektur x seed. */
@Serializable
data class ModelEntry(
    val id: String,
    val scenario: String,
    val scenarioName: String,
    val scenarioDesc: String = "",
    val arch: String,
    val archName: String,
    val seed: Int? = null,
    val inputVariant: String,
    val inputSize: Int,
    val classes: List<String>,
    val quantization: String,
    val version: String,
    val filePath: String,
    val fileMb: Double,
    val fileSha256: String,
    val metrics: ModelMetrics = ModelMetrics(),
    val sortOrder: Int = 100,
    val isDefault: Boolean = false,
    val bundled: Boolean = false,
    val demo: Boolean = false,
) {
    val variant: InputVariant get() = InputVariant.fromKey(inputVariant)
}

data class Scenario(val key: String, val name: String, val desc: String, val models: List<ModelEntry>)

fun List<ModelEntry>.scenarios(): List<Scenario> =
    sortedBy { it.sortOrder }.groupBy { it.scenario }.map { (k, ms) ->
        Scenario(k, ms.first().scenarioName, ms.first().scenarioDesc, ms)
    }

@Serializable
data class ClassScore(val cls: FreshnessClass, val score: Float)

/** Hasil satu model untuk satu foto. [scores] memuat SEMUA kelas (softmax mentah). */
@Serializable
data class Prediction(
    val modelId: String,
    val modelVersion: String,
    val scenario: String,
    val scenarioName: String,
    val archName: String,
    val inputVariant: String,
    val scores: List<ClassScore>,
    val preprocessMs: Double,
    val inferenceMs: Double,
    val threads: Int,
    val quantization: String,
) {
    val sorted: List<ClassScore> get() = scores.sortedByDescending { it.score }
    val top: ClassScore get() = sorted.first()
    /** Selisih skor kelas teratas dan kedua: makin kecil = model makin ragu. */
    val margin: Float get() = sorted.let { if (it.size > 1) it[0].score - it[1].score else it[0].score }
    fun scoreOf(cls: FreshnessClass): Float = scores.firstOrNull { it.cls == cls }?.score ?: 0f
}

/**
 * Lingkaran crop dalam piksel foto asli (setelah EXIF transpose). Sama dengan
 * eye_roi.crop_eye: persegi bersisi 2*r berpusat (cx, cy); r SUDAH termasuk margin 1,10.
 */
@Serializable
data class EyeCircle(val cx: Float, val cy: Float, val r: Float)

/** Foto yang sudah disimpan aplikasi (tegak, maks. sisi panjang dibatasi). */
@Serializable
data class StoredPhoto(val path: String, val width: Int, val height: Int, val sha256: String)

enum class SyncState { PENDING, SYNCED, FAILED }

/** Satu foto DATA PRIMER berlabel. Label dari pengumpul, bukan dari model. */
data class Capture(
    val id: String,
    val dataset: String,
    val fishCode: String,
    val species: String,
    val label: FreshnessClass,
    val market: String,
    val notes: String,
    val capturedAt: Long,
    val photo: StoredPhoto,
    val eyePath: String,
    val circle: EyeCircle,
    val circleAdjusted: Boolean,
    val sharpness: Double?,
    val brightness: Double?,
    val predictions: List<Prediction>,
    val syncState: SyncState = SyncState.PENDING,
    val syncError: String? = null,
)

data class ScanRecord(
    val id: Long,
    val photo: StoredPhoto,
    val eyePath: String?,
    val circle: EyeCircle?,
    val createdAt: Long,
    val predictions: List<Prediction>,
)

data class AppSettings(
    val modelId: String? = null,
    val compareArchs: Boolean = false,
    val dataset: String = "primer_1",
    val market: String = "",
    val lastSpecies: String = "",
    val fishCounter: Int = 1,
    val fishCodeDate: String = "",
    val threads: Int = 4,
    val onboarded: Boolean = false,
    val historyGrid: Boolean = false,
)

/** Hasil praproses satu foto untuk satu model. */
class ModelInput(val pixels: FloatArray, val size: Int, val preprocessMs: Double)
