package id.fishfresh

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Rational
import android.view.Surface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.fishfresh.domain.PhotoStore
import id.fishfresh.domain.StoredPhoto
import id.fishfresh.presentation.CaptureMode
import id.fishfresh.ui.components.FishHeadGuide
import id.fishfresh.ui.components.PrimaryButton
import id.fishfresh.ui.components.SecondaryButton
import id.fishfresh.ui.theme.Palette
import id.fishfresh.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private enum class CamPermission { GRANTED, ASK, DENIED_FOREVER }

/** Izin CAMERA (dangerous) diminta saat runtime; ditolak permanen -> arahkan ke Setelan aplikasi. */
@Composable
private fun rememberCameraPermission(): Pair<CamPermission, () -> Unit> {
    val context = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var state by remember { mutableStateOf(if (granted()) CamPermission.GRANTED else CamPermission.ASK) }
    var askedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        askedOnce = true
        state = when {
            ok -> CamPermission.GRANTED
            context.activity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true -> CamPermission.ASK
            else -> CamPermission.DENIED_FOREVER
        }
    }
    // Kembali dari Setelan: periksa ulang.
    LifecycleResumeEffect(Unit) {
        if (granted()) state = CamPermission.GRANTED
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { if (state == CamPermission.ASK && !askedOnce) launcher.launch(Manifest.permission.CAMERA) }
    val request: () -> Unit = {
        if (state == CamPermission.DENIED_FOREVER) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else launcher.launch(Manifest.permission.CAMERA)
    }
    return state to request
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
actual fun CameraCapture(mode: CaptureMode, onCaptured: (StoredPhoto) -> Unit, onGallery: () -> Unit, onClose: () -> Unit) {
    val (perm, request) = rememberCameraPermission()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (perm == CamPermission.GRANTED) {
            CameraContent(mode, onCaptured, onGallery, onClose)
        } else {
            PermissionRationale(perm == CamPermission.DENIED_FOREVER, request, onGallery, onClose)
        }
    }
}

@Composable
private fun PermissionRationale(forever: Boolean, onRequest: () -> Unit, onGallery: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding().padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.l, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.NoPhotography, null, Modifier.size(56.dp), tint = Palette.TextMuted)
        Text("Izin kamera diperlukan", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            "Kamera dipakai untuk memotret mata ikan. Foto diproses di ponsel; untuk data primer, foto diunggah " +
                "ke server penelitian hanya setelah Anda menyimpannya.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted, textAlign = TextAlign.Center,
        )
        if (forever) Text("Izin pernah ditolak. Aktifkan di Setelan > Izin > Kamera.",
            style = MaterialTheme.typography.bodySmall, color = Palette.Warning, textAlign = TextAlign.Center)
        PrimaryButton(if (forever) "Buka Setelan" else "Izinkan kamera", onRequest, icon = Icons.Outlined.PhotoCamera)
        SecondaryButton("Pilih dari galeri saja", onGallery, icon = Icons.Outlined.PhotoLibrary)
        SecondaryButton("Tutup", onClose)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CameraContent(mode: CaptureMode, onCaptured: (StoredPhoto) -> Unit, onGallery: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val photos: PhotoStore = koinInject()
    val scope = rememberCoroutineScope()
    val surface = remember { MutableStateFlow<SurfaceRequest?>(null) }
    val request by surface.collectAsState()
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
    val preview = remember { Preview.Builder().setResolutionSelector(ratio).build().apply { setSurfaceProvider { surface.value = it } } }
    val capture = remember {
        ImageCapture.Builder().setResolutionSelector(ratio)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        // ViewPort 3:4 -> area foto tersimpan = area pratinjau, sehingga lingkaran panduan
        // dapat dipetakan langsung ke koordinat foto.
        val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)
            .setViewPort(ViewPort.Builder(Rational(3, 4), Surface.ROTATION_0).build()).build()
        provider.unbindAll()
        camera = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, group)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Tutup", tint = Color.White) }
            Text(
                if (mode == CaptureMode.COLLECT) "Data primer" else "Pindai", Modifier.weight(1f),
                color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            IconButton(onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) }) {
                Icon(if (torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, "Senter", tint = Color.White)
            }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            request?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
            FishHeadGuide(Modifier.fillMaxSize())
        }
        Text(
            "Arahkan kepala ikan sesuai garis, masukkan mata ke lingkaran putih",
            Modifier.fillMaxWidth().padding(Space.m), color = Color.White,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().padding(bottom = Space.xl, start = Space.xl, end = Space.xl),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onGallery, Modifier.size(56.dp)) { Icon(Icons.Outlined.PhotoLibrary, "Galeri", tint = Color.White) }
            Box(
                Modifier.size(80.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                    .background(if (busy) Palette.PrimaryPressed else Palette.Primary, CircleShape)
                    .clickable(enabled = !busy) {
                        busy = true
                        val tmp = File(context.cacheDir, "capture-${System.currentTimeMillis()}.jpg")
                        capture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(tmp).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                                    scope.launch {
                                        runCatching { photos.importJpeg(tmp.absolutePath, deleteSource = true) }
                                            .onSuccess(onCaptured)
                                            .onFailure { Toast.makeText(context, "Gagal menyimpan foto", Toast.LENGTH_SHORT).show() }
                                        busy = false
                                    }
                                }
                                override fun onError(e: ImageCaptureException) {
                                    busy = false
                                    Toast.makeText(context, "Kamera gagal: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) ContainedLoadingIndicator(Modifier.size(40.dp))
                else Icon(Icons.Outlined.PhotoCamera, "Potret", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.size(56.dp))
        }
    }
}

/** Photo Picker sistem: tidak butuh READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE. */
@Composable
actual fun rememberGalleryPicker(onPicked: (StoredPhoto) -> Unit): () -> Unit {
    val context = LocalContext.current
    val photos: PhotoStore = koinInject()
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val tmp = File(context.cacheDir, "pick-${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                photos.importJpeg(tmp.absolutePath, deleteSource = true)
            }.onSuccess(onPicked).onFailure {
                Toast.makeText(context, "Foto tidak bisa dibuka", Toast.LENGTH_SHORT).show()
            }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}
