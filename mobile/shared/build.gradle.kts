plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    // Khusus Android. Struktur KMP dipertahankan (commonMain = domain + ViewModel + UI)
    // agar target lain bisa ditambah kelak tanpa memindah kode.
    android {
        namespace = "id.fishfresh.shared"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
        androidResources { enable = true }   // Compose Resources (font Plus Jakarta Sans)
    }
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.icons.extended)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.compose.resources)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.navigation.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.coil.compose)
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
            implementation(libs.activity.compose)
            implementation(libs.koin.workmanager)
            implementation(libs.litert)
            implementation(libs.camerax.core)
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.compose)
            implementation(libs.exifinterface)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.work.runtime)
            implementation(project.dependencies.platform(libs.supabase.bom))
            implementation(libs.supabase.auth)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.storage)
            implementation(libs.ktor.client.okhttp)
        }
    }
}

compose.resources {
    packageOfResClass = "id.fishfresh.resources"
    publicResClass = false
}

sqldelight {
    databases {
        create("IkanSegarDb") {
            packageName.set("id.fishfresh.db")
        }
    }
}
