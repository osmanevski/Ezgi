plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

import java.util.Properties

// Kalıcı imza: imza/keystore.properties (git dışı, Masaüstü yedeğiyle Drive'a gider).
// Anahtar kaybolursa telefondaki Ezgi güncellenemez; sil-kur gerekir, listeler gider.
val signing = Properties().apply {
    rootProject.file("imza/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.osmanevski.ezgi"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.osmanevski.ezgi"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "1.1"
    }

    signingConfigs {
        if (signing.isNotEmpty()) create("ezgi") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Play'e gitmiyor; APK elle kurulur. Anahtar dosyası yoksa yayın derlemesi imzasız kalır.
            signingConfig = signingConfigs.findByName("ezgi")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor java.nio kullanıyor; minSdk 33 altında desugaring şart (README'si).
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material.icons.ext)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
    implementation(libs.reorderable)
    coreLibraryDesugaring(libs.desugar.nio)
    testImplementation(libs.junit)
}
