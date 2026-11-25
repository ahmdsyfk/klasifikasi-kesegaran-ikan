package id.fishfresh.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.fishfresh.domain.AppSettings
import id.fishfresh.domain.BackendCheck
import id.fishfresh.domain.BackendConfig
import id.fishfresh.domain.BackendProbe
import id.fishfresh.domain.BackendSettings
import id.fishfresh.domain.Capture
import id.fishfresh.domain.CaptureRepository
import id.fishfresh.domain.Classifier
import id.fishfresh.domain.EyeCircle
import id.fishfresh.domain.FreshnessClass
import id.fishfresh.domain.ModelEntry
import id.fishfresh.domain.ModelRegistry
import id.fishfresh.domain.ModelState
import id.fishfresh.domain.PhotoStore
import id.fishfresh.domain.Platform
import id.fishfresh.domain.Prediction
import id.fishfresh.domain.ScanRecord
import id.fishfresh.domain.ScanRepository
import id.fishfresh.domain.Scenario
import id.fishfresh.domain.SettingsStore
import id.fishfresh.domain.StoredPhoto
import id.fishfresh.domain.SyncScheduler
import id.fishfresh.domain.scenarios
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Model aktif + model yang dijalankan untuk satu foto (1 model, atau 3 arsitektur saat dibandingkan). */
fun resolveModels(all: List<ModelEntry>, s: AppSettings): List<ModelEntry> {
    val selected = all.firstOrNull { it.id == s.modelId }
        ?: all.firstOrNull { it.isDefault && !it.demo }
        ?: all.firstOrNull { !it.demo } ?: all.firstOrNull()
        ?: return emptyList()
    return if (s.compareArchs) all.filter { it.scenario == selected.scenario && it.seed == selected.seed }
        .sortedBy { it.sortOrder }.ifEmpty { listOf(selected) } else listOf(selected)
}

suspend fun runModels(
    models: List<ModelEntry>, registry: ModelRegistry, classifier: Classifier,
    photo: StoredPhoto, circle: EyeCircle?, threads: Int,
): List<Prediction> = models.map { m ->
    registry.ensureUsable(m.id).getOrThrow()
    classifier.classify(photo, circle, m, threads)
}

// ------------------------------------------------------------------ Beranda + pemilih model
data class ModelUiState(
    val models: List<ModelEntry> = emptyList(),
    val scenarios: List<Scenario> = emptyList(),
    val active: List<ModelEntry> = emptyList(),
    val states: Map<String, ModelState> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    val refreshError: String? = null,
) {
    val selected: ModelEntry? get() = active.firstOrNull { it.id == settings.modelId } ?: active.firstOrNull()
    val isDemo: Boolean get() = active.isNotEmpty() && active.all { it.demo }
}

class ModelViewModel(
    private val registry: ModelRegistry,
    private val settings: SettingsStore,
) : ViewModel() {
    private val refreshError = MutableStateFlow<String?>(null)

    val state: StateFlow<ModelUiState> = combine(
        registry.models, registry.states, settings.state, refreshError,
    ) { models, states, s, err ->
        ModelUiState(models, models.scenarios(), resolveModels(models, s), states, s, err)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ModelUiState())

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        refreshError.value = registry.refresh().exceptionOrNull()?.let {
            "Daftar model belum bisa diperbarui (offline?). Memakai salinan di ponsel."
        }
    }

    fun select(id: String) {
        settings.update { it.copy(modelId = id) }
        viewModelScope.launch { registry.ensureUsable(id) }
    }

    fun setCompare(on: Boolean) {
        settings.update { it.copy(compareArchs = on) }
        if (on) viewModelScope.launch { state.value.active.forEach { registry.ensureUsable(it.id) } }
    }

    fun download(id: String) = viewModelScope.launch { registry.ensureDownloaded(id) }
    fun delete(id: String) = registry.delete(id)
}

// ------------------------------------------------------------------ Pindai
sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class Working(val message: String) : ScanUiState
    data class Done(val scanId: Long) : ScanUiState
    data class Failed(val message: String) : ScanUiState
}

class ScanViewModel(
    private val session: CaptureSession,
    private val registry: ModelRegistry,
    private val classifier: Classifier,
    private val scans: ScanRepository,
    private val photos: PhotoStore,
    private val settings: SettingsStore,
    private val platform: Platform,
) : ViewModel() {
    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val state = _state.asStateFlow()

    fun analyze() {
        val photo = session.photo ?: return run { _state.value = ScanUiState.Failed("Foto tidak ditemukan.") }
        val circle = session.circle
        viewModelScope.launch {
            val s = settings.state.value
            val models = resolveModels(registry.models.value, s)
            if (models.isEmpty()) {
                _state.value = ScanUiState.Failed("Belum ada model. Buka Setelan untuk memperbarui daftar model.")
                return@launch
            }
            _state.value = ScanUiState.Working(
                if (models.size > 1) "Menjalankan ${models.size} model di ponsel…" else "Menganalisis mata ikan…",
            )
            runCatching {
                val preds = runModels(models, registry, classifier, photo, circle, s.threads)
                val eye = circle?.let { photos.makeEyeCrop(photo, it, platform.uuid()).path }
                scans.add(ScanRecord(0, photo, eye, circle, platform.now(), preds))
            }.onSuccess { id ->
                settings.update { it.copy(onboarded = true) }   // panduan langkah di beranda tidak perlu lagi
                _state.value = ScanUiState.Done(id)
            }
                .onFailure { _state.value = ScanUiState.Failed(it.message ?: "Analisis gagal.") }
        }
    }

    fun reset() { _state.value = ScanUiState.Idle }
}

class ResultViewModel(
    private val scanId: Long,
    private val scans: ScanRepository,
) : ViewModel() {
    val record: StateFlow<ScanRecord?> = scans.observeOne(scanId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun delete() = viewModelScope.launch { scans.delete(scanId) }
}

class HistoryViewModel(private val scans: ScanRepository, private val settings: SettingsStore) : ViewModel() {
    val items: StateFlow<List<ScanRecord>?> = scans.observe().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** Mode tampilan Riwayat (kartu atau list) diingat di setelan. */
    val grid: StateFlow<Boolean> = settings.state.map { it.historyGrid }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.state.value.historyGrid)
    fun setGrid(on: Boolean) = settings.update { it.copy(historyGrid = on) }
}

// ------------------------------------------------------------------ Pengumpulan data primer
enum class CollectStep { LABEL, DETAILS, SAVING, SAVED }

data class CollectUiState(
    val step: CollectStep = CollectStep.LABEL,
    val photo: StoredPhoto? = null,
    val circle: EyeCircle? = null,
    val eyePreview: String? = null,
    val label: FreshnessClass? = null,
    val species: String = "",
    val market: String = "",
    val notes: String = "",
    val fishCode: String = "",
    val speciesSuggestions: List<String> = emptyList(),
    val saved: Capture? = null,
    val error: String? = null,
) {
    val canSave: Boolean get() = label != null && species.isNotBlank() && photo != null
}

class CollectViewModel(
    private val session: CaptureSession,
    private val repo: CaptureRepository,
    private val photos: PhotoStore,
    private val registry: ModelRegistry,
    private val classifier: Classifier,
    private val settings: SettingsStore,
    private val sync: SyncScheduler,
    private val platform: Platform,
) : ViewModel() {
    private val _state = MutableStateFlow(CollectUiState())
    val state = _state.asStateFlow()
    private val captureId = platform.uuid()

    init {
        val s = settings.state.value
        _state.value = CollectUiState(
            photo = session.photo, circle = session.circle, species = s.lastSpecies, market = s.market,
            fishCode = fishCode(s),
        )
        viewModelScope.launch { _state.update { it.copy(speciesSuggestions = repo.species()) } }
        refreshPreview()
    }

    private fun fishCode(s: AppSettings): String {
        val today = platform.today()
        val n = if (s.fishCodeDate == today) s.fishCounter else 1
        val prefix = s.dataset.uppercase().replace("PRIMER_", "P")
        return "$prefix-$today-${n.toString().padStart(3, '0')}"
    }

    /** Pratinjau crop mata persis seperti yang akan dipakai model & disimpan. */
    fun refreshPreview() {
        val photo = session.photo ?: return
        val circle = session.circle ?: return
        _state.update { it.copy(circle = circle) }
        viewModelScope.launch {
            runCatching { photos.makeEyeCrop(photo, circle, captureId) }
                .onSuccess { r -> _state.update { it.copy(eyePreview = r.path) } }
        }
    }

    fun chooseLabel(l: FreshnessClass) = _state.update { it.copy(label = l, step = CollectStep.DETAILS) }
    fun back() = _state.update { it.copy(step = CollectStep.LABEL) }
    fun setSpecies(v: String) = _state.update { it.copy(species = v) }
    fun setMarket(v: String) = _state.update { it.copy(market = v) }
    fun setNotes(v: String) = _state.update { it.copy(notes = v.take(500)) }

    fun save() {
        val st = _state.value
        val photo = st.photo ?: return
        val circle = st.circle ?: return
        val label = st.label ?: return
        _state.update { it.copy(step = CollectStep.SAVING, error = null) }
        viewModelScope.launch {
            runCatching {
                val crop = photos.makeEyeCrop(photo, circle, captureId)
                val s = settings.state.value
                // Prediksi dihitung SETELAH label dipilih -> tidak menjangkarkan penilaian pengumpul.
                val preds = runCatching {
                    runModels(resolveModels(registry.models.value, s), registry, classifier, photo, circle, s.threads)
                }.getOrDefault(emptyList())
                val c = Capture(
                    id = captureId, dataset = s.dataset, fishCode = st.fishCode, species = st.species.trim(),
                    label = label, market = st.market.trim(), notes = st.notes.trim(), capturedAt = platform.now(),
                    photo = photo, eyePath = crop.path, circle = circle, circleAdjusted = session.adjusted,
                    sharpness = crop.sharpness, brightness = crop.brightness, predictions = preds,
                )
                repo.add(c)
                settings.update {
                    it.copy(lastSpecies = c.species, market = c.market, fishCodeDate = platform.today(),
                        fishCounter = if (it.fishCodeDate == platform.today()) it.fishCounter else 1)
                }
                sync.requestSync()
                c
            }.onSuccess { c -> _state.update { it.copy(step = CollectStep.SAVED, saved = c) } }
                .onFailure { e -> _state.update { it.copy(step = CollectStep.DETAILS, error = e.message ?: "Gagal menyimpan") } }
        }
    }

    /** Foto berikutnya dari ikan yang sama (fish_code sama) atau ikan baru (nomor naik). */
    fun finish(nextFish: Boolean) {
        if (nextFish) settings.update { it.copy(fishCounter = it.fishCounter + 1, fishCodeDate = platform.today()) }
        session.clear()
    }
}

data class DataUiState(
    val captures: List<Capture>? = null,
    val pending: Int = 0,
    val syncConfigured: Boolean = false,
    val settings: AppSettings = AppSettings(),
) {
    val counts: Map<FreshnessClass, Int>
        get() = FreshnessClass.entries.associateWith { c -> captures.orEmpty().count { it.label == c } }
    val fishCount: Int get() = captures.orEmpty().map { it.fishCode }.distinct().size
}

class DataViewModel(
    private val repo: CaptureRepository,
    private val sync: SyncScheduler,
    private val settings: SettingsStore,
) : ViewModel() {
    val state: StateFlow<DataUiState> = combine(repo.observe(), sync.observePending(), settings.state) { c, p, s ->
        DataUiState(c.filter { it.dataset == s.dataset }, p, sync.configured, s)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DataUiState())

    fun syncNow() = sync.requestSync()
    fun nextFish() = settings.update { it.copy(fishCounter = it.fishCounter + 1) }
}

class CaptureDetailViewModel(
    private val id: String,
    private val repo: CaptureRepository,
    private val sync: SyncScheduler,
) : ViewModel() {
    val capture: StateFlow<Capture?> = repo.observeOne(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun relabel(l: FreshnessClass) = viewModelScope.launch { repo.updateLabel(id, l); sync.requestSync() }
    fun delete() = viewModelScope.launch { repo.delete(id); sync.requestSync() }
}

class SettingsViewModel(
    private val store: SettingsStore,
    private val sync: SyncScheduler,
    val platform: Platform,
) : ViewModel() {
    val settings = store.state
    val syncConfigured get() = sync.configured
    val pending = sync.observePending().map { it }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    fun setDataset(v: String) = store.update { it.copy(dataset = v, fishCounter = 1) }
    fun setThreads(v: Int) = store.update { it.copy(threads = v) }
    fun setMarket(v: String) = store.update { it.copy(market = v) }
}

// ------------------------------------------------------------------ Konfigurasi Supabase
data class BackendUiState(
    val config: BackendConfig,
    val isDefault: Boolean,
    val testing: Boolean = false,
    val checks: List<BackendCheck> = emptyList(),
    val error: String? = null,
    val savedMessage: String? = null,
)

class BackendViewModel(
    private val settings: BackendSettings,
    private val probe: BackendProbe,
    private val sync: SyncScheduler,
    private val registry: ModelRegistry,
) : ViewModel() {
    private val _ui = MutableStateFlow(uiFor(settings.state.value))
    val ui: StateFlow<BackendUiState> = _ui.asStateFlow()
    val pending = sync.observePending().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val uploaded = sync.observeUploaded().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val defaults get() = settings.defaults

    private fun uiFor(c: BackendConfig) = BackendUiState(c, c == settings.defaults)

    /** Key kosong = tetap memakai key yang aktif. */
    fun save(url: String, key: String): Boolean {
        val u = url.trim().trimEnd('/')
        val k = key.trim().ifBlank { settings.state.value.publishableKey }
        val err = when {
            !u.startsWith("https://") -> "URL harus diawali https://"
            k.isBlank() -> "Key belum diisi"
            isSecretKey(k) -> "Ini secret/service key. Pakai publishable atau anon key saja."
            else -> null
        }
        if (err != null) { _ui.update { it.copy(error = err) }; return false }
        settings.save(BackendConfig(u, k))
        applied("Konfigurasi disimpan")
        return true
    }

    fun reset() { settings.reset(); applied("Kembali ke konfigurasi bawaan") }

    fun clearError() = _ui.update { it.copy(error = null) }

    fun syncNow() = sync.requestSync()

    fun test() {
        if (_ui.value.testing) return
        _ui.update { it.copy(testing = true, checks = emptyList()) }
        viewModelScope.launch {
            val r = runCatching { probe.test() }.getOrElse { listOf(BackendCheck("Koneksi", false, it.message ?: "galat")) }
            _ui.update { it.copy(testing = false, checks = r) }
        }
    }

    private fun applied(msg: String) {
        _ui.value = uiFor(settings.state.value).copy(savedMessage = msg)
        sync.requestSync()
        viewModelScope.launch { registry.refresh() }
    }

    companion object {
        /** Secret key Supabase baru (sb_secret_) atau JWT lama dengan role service_role. */
        fun isSecretKey(k: String): Boolean {
            if (k.startsWith("sb_secret_")) return true
            val parts = k.split('.')
            if (parts.size != 3) return false
            return runCatching {
                @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT_OPTIONAL)
                    .decode(parts[1]).decodeToString().contains("service_role")
            }.getOrDefault(false)
        }

        fun mask(k: String): String = if (k.length <= 16) "••••" else k.take(12) + "••••" + k.takeLast(4)
    }
}
