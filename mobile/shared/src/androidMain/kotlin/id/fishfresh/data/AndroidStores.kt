package id.fishfresh.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import id.fishfresh.domain.AppSettings
import id.fishfresh.domain.BackendConfig
import id.fishfresh.domain.BackendSettings
import id.fishfresh.domain.EyeCircle
import id.fishfresh.domain.EyeCropResult
import id.fishfresh.domain.ImageMath
import id.fishfresh.domain.PhotoStore
import id.fishfresh.domain.Platform
import id.fishfresh.domain.Rgb
import id.fishfresh.domain.SettingsStore
import id.fishfresh.domain.StoredPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SettingsStoreImpl(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(load())
    override val state: StateFlow<AppSettings> = flow.asStateFlow()

    private fun load() = AppSettings(
        modelId = prefs.getString("modelId", null),
        compareArchs = prefs.getBoolean("compareArchs", false),
        dataset = prefs.getString("dataset", "primer_1")!!,
        market = prefs.getString("market", "")!!,
        lastSpecies = prefs.getString("lastSpecies", "")!!,
        fishCounter = prefs.getInt("fishCounter", 1),
        fishCodeDate = prefs.getString("fishCodeDate", "")!!,
        threads = prefs.getInt("threads", 4),
        onboarded = prefs.getBoolean("onboarded", false),
        historyGrid = prefs.getBoolean("historyGrid", false),
    )

    @Synchronized
    override fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(flow.value)
        prefs.edit()
            .putString("modelId", s.modelId).putBoolean("compareArchs", s.compareArchs)
            .putString("dataset", s.dataset).putString("market", s.market).putString("lastSpecies", s.lastSpecies)
            .putInt("fishCounter", s.fishCounter).putString("fishCodeDate", s.fishCodeDate)
            .putInt("threads", s.threads).putBoolean("onboarded", s.onboarded)
            .putBoolean("historyGrid", s.historyGrid).apply()
        flow.value = s
    }
}

class AndroidPlatform(context: Context) : Platform {
    override val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
    override val appVersion: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
    override fun now(): Long = System.currentTimeMillis()
    override fun uuid(): String = UUID.randomUUID().toString()
    override fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    override fun formatTime(epochMs: Long): String =
        SimpleDateFormat("d MMM yyyy, HH.mm", Locale.forLanguageTag("id-ID")).format(Date(epochMs))
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun Bitmap.toRgb(): Rgb {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    for (i in px.indices) px[i] = px[i] and 0xFFFFFF
    return Rgb(width, height, px)
}

fun Rgb.toBitmap(): Bitmap {
    val px = IntArray(px.size) { this.px[it] or (0xFF shl 24) }
    return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * Foto disimpan TEGAK (rotasi EXIF diterapkan, setara ImageOps.exif_transpose) sebagai JPEG q95,
 * sisi panjang maks. [MAX_SIDE] px. Foto inilah yang diunggah sebagai foto asli (raw).
 */
class PhotoStoreImpl(private val context: Context) : PhotoStore {
    private val photoDir = File(context.filesDir, "photos").apply { mkdirs() }
    private val eyeDir = File(context.filesDir, "eyes").apply { mkdirs() }

    override suspend fun importJpeg(sourcePath: String, deleteSource: Boolean): StoredPhoto = withContext(Dispatchers.IO) {
        val src = File(sourcePath)
        val bmp = decodeUpright(src)
        val out = File(photoDir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val stored = StoredPhoto(out.absolutePath, bmp.width, bmp.height, sha256(out.readBytes()))
        bmp.recycle()
        if (deleteSource) src.delete()
        stored
    }

    override suspend fun makeEyeCrop(photo: StoredPhoto, circle: EyeCircle, id: String): EyeCropResult =
        withContext(Dispatchers.Default) {
            val bmp = BitmapFactory.decodeFile(photo.path) ?: error("Foto tidak bisa dibaca")
            val crop = ImageMath.cropEye(bmp.toRgb(), circle, 224)
            bmp.recycle()
            val (sharp, bright) = ImageMath.quality(crop)
            val out = File(eyeDir, "$id.png")
            FileOutputStream(out).use { crop.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            EyeCropResult(out.absolutePath, sharp, bright)
        }

    override fun delete(path: String) {
        val f = File(path)
        if (f.absolutePath.startsWith(context.filesDir.absolutePath)) f.delete()
    }

    private fun decodeUpright(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val raw = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Berkas bukan gambar yang didukung")
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val longSide = maxOf(raw.width, raw.height)
        if (longSide > MAX_SIDE) m.postScale(MAX_SIDE.toFloat() / longSide, MAX_SIDE.toFloat() / longSide)
        if (m.isIdentity) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }

    companion object { const val MAX_SIDE = 2048 }
}

/** Konfigurasi Supabase yang diubah pengguna; kosong = memakai nilai bawaan build. */
class BackendSettingsImpl(context: Context, override val defaults: BackendConfig) : BackendSettings {
    private val prefs = context.getSharedPreferences("backend", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(load())
    override val state: StateFlow<BackendConfig> = flow.asStateFlow()

    /** URL proyek pemilik sesi anonim yang tersimpan. */
    var sessionUrl: String?
        get() = prefs.getString("sessionUrl", null) ?: defaults.url.ifBlank { null }
        set(v) { prefs.edit().putString("sessionUrl", v).apply() }

    private fun load() = BackendConfig(
        url = prefs.getString("url", null) ?: defaults.url,
        publishableKey = prefs.getString("key", null) ?: defaults.publishableKey,
    )

    override fun save(config: BackendConfig) {
        prefs.edit().putString("url", config.url.trim().trimEnd('/')).putString("key", config.publishableKey.trim()).apply()
        flow.value = load()
    }

    override fun reset() {
        prefs.edit().remove("url").remove("key").apply()
        flow.value = load()
    }
}
