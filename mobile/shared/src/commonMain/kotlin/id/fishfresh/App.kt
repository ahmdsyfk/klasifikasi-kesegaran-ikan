package id.fishfresh

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import id.fishfresh.domain.Platform
import id.fishfresh.domain.StoredPhoto
import id.fishfresh.presentation.CaptureMode
import id.fishfresh.presentation.CaptureSession
import id.fishfresh.ui.screens.AboutScreen
import id.fishfresh.ui.screens.AnalyzeScreen
import id.fishfresh.ui.screens.CaptureDetailScreen
import id.fishfresh.ui.screens.CollectScreen
import id.fishfresh.ui.screens.DataScreen
import id.fishfresh.ui.screens.HistoryScreen
import id.fishfresh.ui.screens.HomeScreen
import id.fishfresh.ui.screens.ResultScreen
import id.fishfresh.ui.screens.ReviewScreen
import id.fishfresh.ui.screens.SettingsScreen
import id.fishfresh.ui.theme.IkanSegarTheme
import id.fishfresh.ui.theme.Palette
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable object HomeRoute
@Serializable object DataRoute
@Serializable object HistoryRoute
@Serializable object SettingsRoute
@Serializable object AboutRoute
@Serializable data class CameraRoute(val collect: Boolean)
@Serializable object ReviewRoute
@Serializable object AnalyzeRoute
@Serializable data class ResultRoute(val id: Long)
@Serializable object CollectRoute
@Serializable data class CaptureRoute(val id: String)

/** Kamera dengan overlay kepala ikan (implementasi Android: CameraX + izin runtime). */
@Composable
expect fun CameraCapture(mode: CaptureMode, onCaptured: (StoredPhoto) -> Unit, onGallery: () -> Unit, onClose: () -> Unit)

/** Photo Picker sistem (tanpa izin penyimpanan). Mengembalikan fungsi untuk membukanya. */
@Composable
expect fun rememberGalleryPicker(onPicked: (StoredPhoto) -> Unit): () -> Unit

private data class Tab(val route: Any, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, "Pindai", Icons.Outlined.CameraAlt),
    Tab(DataRoute, "Data Primer", Icons.Outlined.PhotoLibrary),
    Tab(HistoryRoute, "Riwayat", Icons.Outlined.History),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun App() = IkanSegarTheme {
    val nav = rememberNavController()
    val session: CaptureSession = koinInject()
    val platform: Platform = koinInject()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val onTab = tabs.any { t -> dest?.hasRoute(t.route::class) == true }
    val title = when {
        dest?.hasRoute(SettingsRoute::class) == true -> "Setelan"
        dest?.hasRoute(AboutRoute::class) == true -> "Tentang"
        dest?.hasRoute(ReviewRoute::class) == true -> "Periksa foto"
        dest?.hasRoute(ResultRoute::class) == true -> "Hasil"
        dest?.hasRoute(CollectRoute::class) == true -> "Data primer"
        dest?.hasRoute(CaptureRoute::class) == true -> "Detail foto"
        dest?.hasRoute(AnalyzeRoute::class) == true -> "Menganalisis"
        else -> null
    }
    val fullScreen = dest?.hasRoute(CameraRoute::class) == true

    val openGallery = rememberGalleryPicker { photo ->
        session.mode = CaptureMode.SCAN
        session.set(photo, fromGallery = true)
        nav.navigate(ReviewRoute)
    }

    Scaffold(
        containerColor = Palette.Background,
        topBar = {
            if (title != null && !fullScreen) TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Kembali") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Background),
            )
        },
        bottomBar = {
            if (onTab) ShortNavigationBar(containerColor = Palette.Background) {
                tabs.forEach { t ->
                    val selected = dest?.hasRoute(t.route::class) == true
                    ShortNavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        // Ikon polos: indikator berbentuk pil dihilangkan, status aktif cukup lewat warna.
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                        colors = ShortNavigationBarItemDefaults.colors(
                            selectedIndicatorColor = Color.Transparent,
                            selectedIconColor = Palette.Primary, selectedTextColor = Palette.Primary,
                            unselectedIconColor = Palette.TextMuted, unselectedTextColor = Palette.TextMuted,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().background(Palette.Background).padding(if (fullScreen) androidx.compose.foundation.layout.PaddingValues() else pad)) {
            AppNavHost(nav, session, platform, openGallery)
        }
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, session: CaptureSession, platform: Platform, openGallery: () -> Unit) {
    val fmt: (Long) -> String = platform::formatTime
    fun openCamera(collect: Boolean) {
        session.mode = if (collect) CaptureMode.COLLECT else CaptureMode.SCAN
        nav.navigate(CameraRoute(collect))
    }
    NavHost(nav, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(koinViewModel(), onCamera = { openCamera(false) }, onGallery = openGallery,
                onSettings = { nav.navigate(SettingsRoute) }, onAbout = { nav.navigate(AboutRoute) })
        }
        composable<DataRoute> {
            DataScreen(koinViewModel(), fmt, onCollect = { openCamera(true) }, onOpen = { nav.navigate(CaptureRoute(it)) })
        }
        composable<HistoryRoute> { HistoryScreen(koinViewModel(), fmt) { nav.navigate(ResultRoute(it)) } }
        composable<SettingsRoute> { SettingsScreen(koinViewModel(), koinViewModel(), onAbout = { nav.navigate(AboutRoute) }) }
        composable<AboutRoute> { AboutScreen() }
        composable<CameraRoute> {
            CameraCapture(
                session.mode,
                onCaptured = { photo ->
                    session.set(photo, fromGallery = false)
                    nav.navigate(ReviewRoute) { popUpTo<CameraRoute> { inclusive = true } }
                },
                onGallery = { nav.popBackStack(); openGallery() },
                onClose = { nav.popBackStack() },
            )
        }
        composable<ReviewRoute> {
            ReviewScreen(
                session,
                onRetake = { nav.navigate(CameraRoute(session.mode == CaptureMode.COLLECT)) { popUpTo<ReviewRoute> { inclusive = true } } },
                onContinue = {
                    if (session.mode == CaptureMode.SCAN) nav.navigate(AnalyzeRoute) { popUpTo<ReviewRoute> { inclusive = true } }
                    else if (!nav.popBackStack<CollectRoute>(inclusive = false)) {
                        nav.navigate(CollectRoute) { popUpTo<ReviewRoute> { inclusive = true } }
                    }
                },
            )
        }
        composable<AnalyzeRoute> {
            AnalyzeScreen(koinViewModel(), onDone = { id ->
                nav.navigate(ResultRoute(id)) { popUpTo<AnalyzeRoute> { inclusive = true } }
            }, onBack = { nav.popBackStack() })
        }
        composable<ResultRoute> { e ->
            val id = e.toRoute<ResultRoute>().id
            ResultScreen(
                koinViewModel { parametersOf(id) }, fmt,
                onScanAgain = { nav.popBackStack(); openCamera(false) },
                onSaveAsData = { r ->
                    // Pakai foto yang sama sebagai data primer -> langsung ke pelabelan.
                    session.mode = CaptureMode.COLLECT
                    session.photo = r.photo
                    session.circle = r.circle ?: CaptureSession.defaultCircle(r.photo.width, r.photo.height, true)
                    session.adjusted = false
                    nav.navigate(CollectRoute)
                },
            )
        }
        composable<CollectRoute> {
            CollectScreen(
                koinViewModel(),
                onAdjust = { nav.navigate(ReviewRoute) },
                onAgain = { _ ->
                    nav.popBackStack<CollectRoute>(inclusive = true)
                    openCamera(true)
                },
                onDone = {
                    nav.navigate(DataRoute) { popUpTo(nav.graph.findStartDestination().id); launchSingleTop = true }
                },
            )
        }
        composable<CaptureRoute> { e ->
            val id = e.toRoute<CaptureRoute>().id
            CaptureDetailScreen(koinViewModel { parametersOf(id) }, fmt, onDeleted = { nav.popBackStack() })
        }
    }
}
