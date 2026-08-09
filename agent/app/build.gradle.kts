import java.util.Properties
import java.io.FileInputStream

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
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screentamer.agent"
        minSdk = 23
        targetSdk = 33
        versionCode = (project.property("VERSION_CODE") as String).toInt()
        versionName = project.property("VERSION_NAME") as String
    }

    buildFeatures {
        buildConfig = true
    }

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        FileInputStream(localPropertiesFile).use { localProperties.load(it) }
    }

    val releaseKeystorePath = localProperties.getProperty("release.keystore.path")
    val releaseKeystorePassword = localProperties.getProperty("release.keystore.password")
    val releaseKeyAlias = localProperties.getProperty("release.key.alias")
    val releaseKeyPassword = localProperties.getProperty("release.key.password")

    val hasSigningConfig = releaseKeystorePath != null && releaseKeystorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
