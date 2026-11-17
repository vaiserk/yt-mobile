plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val numeroBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "br.com.vaiserk.ytmobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.vaiserk.ytmobile"
        minSdk = 29
        targetSdk = 34
        versionCode = numeroBuild
        versionName = "1.0.$numeroBuild"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Chave fixa: assim cada APK novo instala por cima do anterior (atualiza sem desinstalar)
    signingConfigs {
        create("release") {
            storeFile = file("yt-mobile.keystore")
            storePassword = "ytmobile"
            keyAlias = "ytmobile"
            keyPassword = "ytmobile"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // yt-dlp/python/ffmpeg rodam como binários nativos: precisam ser extraídos no aparelho
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
