package id.fishfresh.data

import android.content.Context
import id.fishfresh.data.ml.LiteRtClassifier
import id.fishfresh.data.ml.ModelFiles
import id.fishfresh.data.ml.ModelSource
import id.fishfresh.data.sync.ModelRow
import id.fishfresh.data.sync.SupabaseGateway
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
import kotlinx.serialization.builtins.ListSerializer
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
 * Registry model: model bawaan APK (berkas JSON di assets/models) + daftar dari tabel Supabase `models`.
 * Daftar disalin ke berkas agar bisa dipakai offline; berkas .tflite diunduh sekali lalu diverifikasi SHA-256.
 */
class ModelRegistryImpl(
    private val context: Context,
    private val gateway: SupabaseGateway,
) : ModelRegistry, ModelFiles {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val cacheFile = File(dir, "registry.json")
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

    override fun locate(model: ModelEntry): ModelSource? = when {
        model.bundled -> ModelSource.Asset("models/${model.filePath}")
        fileFor(model).exists() -> ModelSource.Local(fileFor(model))
        else -> null
    }

    private fun computeStates(list: List<ModelEntry>) =
        list.associate { it.id to if (locate(it) != null) ModelState.Ready else ModelState.NotDownloaded }

    override suspend fun refresh(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!gateway.config.enabled) return@runCatching
            val remote = gateway.models().map { it.toEntry() }
            cacheFile.writeText(json.encodeToString(listSer, remote))
            val merged = merge(remote)
            _models.value = merged
            _states.update { old -> computeStates(merged).mapValues { (id, s) -> (old[id] as? ModelState.Downloading) ?: s } }
            // Bersihkan berkas versi lama yang tidak lagi ada di registry.
            val keep = merged.map { fileFor(it).name }.toSet()
            dir.listFiles { f -> f.extension == "tflite" && f.name !in keep }?.forEach { it.delete() }
        }
    }

    override suspend fun ensureDownloaded(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val m = _models.value.firstOrNull { it.id == id } ?: return@withContext Result.failure(IllegalStateException("Model tidak ada"))
        if (locate(m) != null) return@withContext Result.success(Unit)
        val lock = synchronized(locks) { locks.getOrPut(id) { Mutex() } }
        lock.withLock {
            if (locate(m) != null) return@withLock Result.success(Unit)
            runCatching { download(m) }
                .onSuccess { _states.update { it + (id to ModelState.Ready) } }
                .onFailure { e -> _states.update { it + (id to ModelState.Failed(e.message ?: "Unduhan gagal")) } }
        }
    }

    private suspend fun download(m: ModelEntry) {
        _states.update { it + (m.id to ModelState.Downloading(0f)) }
        val token = gateway.accessToken()
        val req = Request.Builder()
            .url("${gateway.config.url}/storage/v1/object/authenticated/models/${m.filePath}")
            .header("apikey", gateway.config.publishableKey).header("Authorization", "Bearer $token").build()
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
    }

    override fun delete(id: String) {
        val m = _models.value.firstOrNull { it.id == id } ?: return
        if (m.bundled) return
        classifier?.evict(id)
        fileFor(m).delete()
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

fun ModelRow.toEntry() = ModelEntry(
    id = id, scenario = scenario, scenarioName = scenarioName, scenarioDesc = scenarioDesc ?: "", arch = arch,
    archName = archName, seed = seed, inputVariant = inputVariant, inputSize = inputSize, classes = classes,
    quantization = quantization, version = version, filePath = filePath, fileMb = fileMb, fileSha256 = fileSha256,
    metrics = metrics.toMetrics(), sortOrder = sortOrder, isDefault = isDefault,
)
