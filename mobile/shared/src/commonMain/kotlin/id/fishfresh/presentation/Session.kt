package id.fishfresh.presentation

import id.fishfresh.domain.EyeCircle
import id.fishfresh.domain.StoredPhoto
import id.fishfresh.ui.components.GuideGeometry

/** Mode kamera: memindai (melihat skor) atau mengumpulkan data primer berlabel. */
enum class CaptureMode { SCAN, COLLECT }

/** Foto yang baru diambil, dibagi antar-layar dalam satu alur (pola sesi seperti Lensa Herbal). */
class CaptureSession {
    var mode: CaptureMode = CaptureMode.SCAN
    var photo: StoredPhoto? = null
    var circle: EyeCircle? = null
    var adjusted: Boolean = false
    var fromGallery: Boolean = false

    fun set(photo: StoredPhoto, fromGallery: Boolean) {
        this.photo = photo
        this.fromGallery = fromGallery
        this.adjusted = false
        this.circle = defaultCircle(photo.width, photo.height, fromGallery)
    }

    fun clear() {
        photo = null; circle = null; adjusted = false; fromGallery = false
    }

    companion object {
        /**
         * Kamera: pratinjau 3:4 = foto 3:4, jadi posisi lingkaran panduan langsung dipetakan ke foto.
         * Galeri: lingkaran diletakkan di tengah dan pengguna diminta menyesuaikannya.
         */
        fun defaultCircle(w: Int, h: Int, fromGallery: Boolean): EyeCircle =
            if (fromGallery) {
                EyeCircle(w / 2f, h / 2f, 0.2f * minOf(w, h) * GuideGeometry.CROP_MARGIN)
            } else {
                EyeCircle(
                    GuideGeometry.EYE_X * w, GuideGeometry.EYE_Y * h,
                    GuideGeometry.EYE_R * w * GuideGeometry.CROP_MARGIN,
                )
            }
    }
}
