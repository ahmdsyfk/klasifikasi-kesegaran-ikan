package id.fishfresh.data.ml

import android.content.Context
import android.graphics.BitmapFactory
import id.fishfresh.data.toRgb
import id.fishfresh.domain.ClassScore
import id.fishfresh.domain.Classifier
import id.fishfresh.domain.EyeCircle
import id.fishfresh.domain.FreshnessClass
import id.fishfresh.domain.ImageMath
import id.fishfresh.domain.InputVariant
import id.fishfresh.domain.ModelEntry
import id.fishfresh.domain.Prediction
import id.fishfresh.domain.StoredPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Lokasi berkas model: aset APK (model bawaan) atau berkas unduhan. */
fun interface ModelFiles {
    fun locate(model: ModelEntry): ModelSource?
}

sealed interface ModelSource {
    data class Asset(val path: String) : ModelSource
    data class Local(val file: File) : ModelSource
}

/**
 * Inferensi LiteRT (TFLite) di CPU. Input float32 [1,S,S,3] RGB 0-255 (normalisasi ada di dalam model).
 * Praproses identik dengan pelatihan (lihat ImageMath), waktu praproses & inferensi diukur terpisah.
 */
class LiteRtClassifier(private val context: Context, private val files: ModelFiles) : Classifier {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, Interpreter>(4, 0.75f, true)

    override suspend fun classify(photo: StoredPhoto, circle: EyeCircle?, model: ModelEntry, threads: Int): Prediction =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val t0 = System.nanoTime()
                val input = preprocess(photo, circle, model)
                val t1 = System.nanoTime()
                val itp = interpreter(model, threads)
                val out = Array(1) { FloatArray(model.classes.size) }
                itp.run(input, out)
                val t2 = System.nanoTime()
                Prediction(
                    modelId = model.id, modelVersion = model.version, scenario = model.scenario,
                    scenarioName = model.scenarioName, archName = model.archName, inputVariant = model.inputVariant,
                    scores = model.classes.mapIndexed { i, k -> ClassScore(FreshnessClass.fromKey(k), out[0][i]) },
                    preprocessMs = (t1 - t0) / 1e6, inferenceMs = (t2 - t1) / 1e6, threads = threads,
                    quantization = model.quantization,
                )
            }
        }

    private fun preprocess(photo: StoredPhoto, circle: EyeCircle?, model: ModelEntry): ByteBuffer {
        val bmp = BitmapFactory.decodeFile(photo.path) ?: error("Foto tidak bisa dibaca")
        val rgb = bmp.toRgb()
        bmp.recycle()
        val s = model.inputSize
        val pixels = when (model.variant) {
            InputVariant.EYE_CROP -> ImageMath.toFloatHwc(
                ImageMath.cropEye(rgb, circle ?: error("Model crop mata butuh posisi mata"), s),
            )
            InputVariant.FULL -> ImageMath.resizeBilinearAntialiasFloat(rgb, s, s)
        }
        val buf = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(pixels)
        return buf
    }

    private fun interpreter(model: ModelEntry, threads: Int): Interpreter {
        val key = "${model.id}@${model.fileSha256}#$threads"
        cache[key]?.let { return it }
        val src = files.locate(model) ?: error("Model ${model.archName} belum diunduh")
        val itp = Interpreter(map(src), Interpreter.Options().setNumThreads(threads))
        cache[key] = itp
        // Batasi memori: maksimal 3 interpreter (cukup untuk mode bandingkan 3 arsitektur).
        while (cache.size > 3) cache.remove(cache.keys.first())?.close()
        return itp
    }

    fun evict(modelId: String) {
        cache.keys.filter { it.startsWith("$modelId@") }.forEach { cache.remove(it)?.close() }
    }

    private fun map(src: ModelSource): MappedByteBuffer = when (src) {
        is ModelSource.Asset -> context.assets.openFd(src.path).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        is ModelSource.Local -> FileInputStream(src.file).channel.use { ch ->
            ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
        }
    }
}
