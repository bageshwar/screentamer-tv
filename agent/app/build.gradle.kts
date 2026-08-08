plugins {
    id("com.android.application")
}

fun gitHash(): String = try {
    ProcessBuilder("git", "rev-parse", "--short=7", "HEAD")
        .start().inputStream.bufferedReader().readLine().trim()
} catch (e: Exception) {
    "unknown"
}

android {
    namespace = "com.screentamer.agent"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.screentamer.agent"
        minSdk = 23
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.${gitHash()}"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
