import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun secret(name: String): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: "").replace("\"", "")

android {
    namespace = "id.fishfresh.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "id.fishfresh.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${secret("SUPABASE_PUBLISHABLE_KEY")}\"")
        // Registry model di GitHub Releases repo publik (lihat fishfresh.export_mobile --publish).
        val modelsRepo = secret("IKANSEGAR_MODELS_REPO").ifBlank { "ahmdsyfk/klasifikasi-kesegaran-ikan" }
        buildConfigField("String", "MODEL_REGISTRY_URL", "\"https://github.com/$modelsRepo/releases/download/registry/registry.json\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Model .tflite harus tidak terkompresi agar bisa di-mmap oleh LiteRT.
        noCompress += "tflite"
    }

    // Penandatanganan release: path & kata sandi dari environment (kata sandi disimpan di Keychain,
    // tidak pernah di repo). Tanpa variabel ini, build release tidak ditandatangani.
    val ksPath = System.getenv("IKANSEGAR_KEYSTORE")
    val ksPass = System.getenv("IKANSEGAR_KEYSTORE_PASSWORD")
    signingConfigs {
        if (ksPath != null && ksPass != null) create("release") {
            storeFile = file(ksPath)
            storePassword = ksPass
            keyAlias = "ikansegar"
            keyPassword = ksPass
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.activity.compose)
    implementation(libs.splashscreen)
    implementation(libs.compose.material3)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.workmanager)
    implementation(libs.work.runtime)
}
