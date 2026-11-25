package id.fishfresh.data

import android.content.Context
import id.fishfresh.data.ml.LiteRtClassifier
import id.fishfresh.data.ml.ModelFiles
import id.fishfresh.data.ml.ModelSource
import id.fishfresh.domain.ModelEntry
import id.fishfresh.domain.ModelMetrics
import id.fishfresh.domain.ModelRegistry
import id.fishfresh.domain.ModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Registry model: model bawaan APK (assets/models) + daftar dari GitHub Releases repo model publik
 * (`registry.json` di rilis bertag `registry`, diterbitkan oleh fishfresh.export_mobile --publish).
 * Daftar disalin ke berkas agar bisa dipakai offline; berkas .tflite diunduh sekali dari aset rilis
 * lalu diverifikasi SHA-256. Tidak butuh akun atau token.
 */
class ModelRegistryImpl(
    private val context: Context,
    private val registryUrl: String,
) : ModelRegistry, ModelFiles {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val cacheFile = File(dir, "registry.json")
    // Harus diinisialisasi sebelum _states (computeStates membaca peta ini).
    private val installedFile = File(dir, "installed.json")
    private val installedSer = MapSerializer(String.serializer(), Installed.serializer())
    private val installed: MutableMap<String, Installed> =
        runCatching { json.decodeFromString(installedSer, installedFile.readText()).toMutableMap() }.getOrDefault(mutableMapOf())
    private val listSer = ListSerializer(ModelEntry.serializer())
    private val bundled: List<ModelEntry> = loadBundled()
    private val _models = MutableStateFlow(merge(loadCache()))
    private val _states = MutableStateFlow(computeStates(_models.value))
    private val locks = mutableMapOf<String, Mutex>()
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    var classifier: LiteRtClassifier? = null

    override val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()
    override val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    private fun merge(remote: List<ModelEntry>): List<ModelEntry> =
        (bundled + remote.filter { r -> bundled.none { it.id == r.id } }).sortedBy { it.sortOrder }

    private fun fileFor(m: ModelEntry) = File(dir, "${m.id}-${m.fileSha256.take(12)}.tflite")

    /** Versi yang terpasang per model (id -> berkas, versi, kuantisasi), agar versi lama tetap bisa dipakai. */
    @Serializable
    private data class Installed(val file: String, val sha256: String, val version: String, val quantization: String)


    private fun saveInstalled() = synchronized(installed) { installedFile.writeText(json.encodeToString(installedSer, installed.toMap())) }

    /** Berkas versi terpasang sebelumnya yang masih ada (dipakai sampai versi baru selesai diunduh). */
    private fun previous(m: ModelEntry): Installed? =
        installed[m.id]?.takeIf { it.sha256 != m.fileSha256 && File(dir, it.file).exists() }

    override fun locate(model: ModelEntry): ModelSource? = when {
        model.bundled -> ModelSource.Asset("models/${model.filePath}")
        fileFor(model).exists() -> ModelSource.Local(fileFor(model), model.version, model.quantization)
        else -> previous(model)?.let { ModelSource.Local(File(dir, it.file), it.version, it.quantization) }
    }

    private fun stateOf(m: ModelEntry): ModelState = when {
        m.bundled || fileFor(m).exists() -> ModelState.Ready
        else -> previous(m)?.let { ModelState.UpdateAvailable(it.version) } ?: ModelState.NotDownloaded
    }

    private fun computeStates(list: List<ModelEntry>) = list.associate { it.id to stateOf(it) }

    override suspend fun refresh(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!registryUrl.startsWith("https://")) return@runCatching
            val remote = fetchRegistry()
            cacheFile.writeText(json.encodeToString(listSer, remote))
            val merged = merge(remote)
            _models.value = merged
            _states.update { old -> computeStates(merged).mapValues { (id, s) -> (old[id] as? ModelState.Downloading) ?: s } }
            // Hapus hanya berkas model yang sudah tidak ada di registry. Versi lama dari model yang masih
            // ada TIDAK dihapus di sini: tetap dipakai sampai versi barunya selesai diunduh.
            val ids = merged.map { it.id }.toSet()
            dir.listFiles { f -> f.extension == "tflite" }?.forEach { f ->
                if (ids.none { f.name.startsWith("$it-") }) f.delete()
            }
            synchronized(installed) { installed.keys.retainAll(ids) }
            saveInstalled()
        }
    }

    private fun fetchRegistry(): List<ModelEntry> {
        val req = Request.Builder().url(registryUrl).header("Cache-Control", "no-cache").build()
        val text = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Registry model tidak bisa diambil (${resp.code})")
            resp.body?.string() ?: error("Registry kosong")
        }
        return json.decodeFromString(RegistryFile.serializer(), text).models.filter { it.active }.map { it.toEntry() }
    }

    override suspend fun ensureUsable(id: String): Result<Unit> {
        val m = _models.value.firstOrNull { it.id == id } ?: return Result.failure(IllegalStateException("Model tidak ada"))
        return if (locate(m) != null) Result.success(Unit) else ensureDownloaded(id)
    }

    override suspend fun ensureDownloaded(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val m = _models.value.firstOrNull { it.id == id } ?: return@withContext Result.failure(IllegalStateException("Model tidak ada"))
        if (m.bundled || fileFor(m).exists()) return@withContext Result.success(Unit)
        val lock = synchronized(locks) { locks.getOrPut(id) { Mutex() } }
        lock.withLock {
            if (fileFor(m).exists()) return@withLock Result.success(Unit)
            runCatching { download(m) }
                .onSuccess { _states.update { it + (id to ModelState.Ready) } }
                .onFailure { e ->
                    // Gagal memperbarui: versi lama tetap dipakai.
                    val back = previous(m)?.let { ModelState.UpdateAvailable(it.version) }
                    _states.update { it + (id to (back ?: ModelState.Failed(e.message ?: "Unduhan gagal"))) }
                }
        }
    }

    private suspend fun download(m: ModelEntry) {
        _states.update { it + (m.id to ModelState.Downloading(0f)) }
        // filePath = URL aset rilis GitHub (diarahkan ulang ke CDN; OkHttp mengikuti redirect).
        val req = Request.Builder().url(m.filePath).build()
        val tmp = File(dir, "${m.id}.part")
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Unduhan gagal (${resp.code})")
            val body = resp.body ?: error("Respons kosong")
            val total = body.contentLength().takeIf { it > 0 } ?: (m.fileMb * 1024 * 1024).toLong()
            val md = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L; var lastPct = -1
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        val pct = (100 * done / total).toInt()
                        if (pct != lastPct) { lastPct = pct; _states.update { it + (m.id to ModelState.Downloading(pct / 100f)) } }
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != m.fileSha256) { tmp.delete(); error("Berkas model rusak (SHA-256 tidak cocok)") }
        }
        tmp.renameTo(fileFor(m))
        // Versi baru lolos verifikasi: baru sekarang versi lama dihapus.
        classifier?.evict(m.id)
        dir.listFiles { f -> f.name.startsWith("${m.id}-") && f.extension == "tflite" && f.name != fileFor(m).name }
            ?.forEach { it.delete() }
        synchronized(installed) { installed[m.id] = Installed(fileFor(m).name, m.fileSha256, m.version, m.quantization) }
        saveInstalled()
    }

    override fun delete(id: String) {
        val m = _models.value.firstOrNull { it.id == id } ?: return
        if (m.bundled) return
        classifier?.evict(id)
        dir.listFiles { f -> f.name.startsWith("$id-") && f.extension == "tflite" }?.forEach { it.delete() }
        synchronized(installed) { installed.remove(id) }
        saveInstalled()
        _states.update { it + (id to ModelState.NotDownloaded) }
    }

    private fun loadCache(): List<ModelEntry> =
        runCatching { json.decodeFromString(listSer, cacheFile.readText()) }.getOrDefault(emptyList())

    /** Model bawaan: assets/models/<id>.json (format keluaran fishfresh.export_mobile). */
    private fun loadBundled(): List<ModelEntry> {
        val names = context.assets.list("models").orEmpty()
        return names.filter { it.endsWith(".json") }.mapNotNull { name ->
            runCatching {
                val o = json.parseToJsonElement(context.assets.open("models/$name").bufferedReader().readText()).jsonObject
                fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                val id = s("id")!!
                ModelEntry(
                    id = id, scenario = s("scenario") ?: "bawaan", scenarioName = s("scenario_name") ?: "Model bawaan",
                    scenarioDesc = s("scenario_desc") ?: "", arch = s("arch") ?: "?", archName = s("name") ?: id,
                    seed = o["seed"]?.jsonPrimitive?.int, inputVariant = s("input_variant") ?: "full",
                    inputSize = o["input_size"]!!.jsonPrimitive.int,
                    classes = o["classes"]!!.jsonArray.map { it.jsonPrimitive.content },
                    quantization = s("quantization") ?: "?", version = s("version") ?: s("exported_at") ?: "?",
                    filePath = "$id.tflite", fileMb = o["file_mb"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    fileSha256 = s("file_sha256") ?: "bundled",
                    metrics = (o["metrics"] as? JsonObject)?.toMetrics() ?: ModelMetrics(),
                    sortOrder = if (o["demo"]?.jsonPrimitive?.contentOrNull == "true") 999 else 0,
                    bundled = true, demo = o["demo"]?.jsonPrimitive?.contentOrNull == "true",
                )
            }.getOrNull()
        }
    }
}

fun JsonObject.toMetrics(): ModelMetrics {
    fun d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    return ModelMetrics(d("acc_mfed"), d("f1_mfed"), d("f1_ffe"), d("delta_f1"), d("latency_ms_cpu4"),
        (this["params"] as? JsonPrimitive)?.longOrNull)
}

/** Isi registry.json di rilis `registry`. */
@Serializable
data class RegistryFile(val schema: Int = 1, val updated: String? = null, val models: List<ModelRow> = emptyList())

/** Satu baris registry; file_url = URL unduhan aset rilis. */
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
    @SerialName("file_url") val fileUrl: String,
    @SerialName("file_mb") val fileMb: Double,
    @SerialName("file_sha256") val fileSha256: String,
    val metrics: JsonObject = JsonObject(emptyMap()),
    @SerialName("sort_order") val sortOrder: Int = 100,
    @SerialName("is_default") val isDefault: Boolean = false,
    val active: Boolean = true,
)

fun ModelRow.toEntry() = ModelEntry(
    id = id, scenario = scenario, scenarioName = scenarioName, scenarioDesc = scenarioDesc ?: "", arch = arch,
    archName = archName, seed = seed, inputVariant = inputVariant, inputSize = inputSize, classes = classes,
    quantization = quantization, version = version, filePath = fileUrl, fileMb = fileMb, fileSha256 = fileSha256,
    metrics = metrics.toMetrics(), sortOrder = sortOrder, isDefault = isDefault,
)
