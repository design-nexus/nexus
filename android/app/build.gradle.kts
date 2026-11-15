import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.design_nexus.nyx.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.design_nexus.nyx.tv"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Release builds are signed with the key named in ~/.config/nyx-tv/signing.properties
        // (storeFile, storePassword, keyAlias, keyPassword); without it they fall back to debug.
        val props = file("${System.getProperty("user.home")}/.config/nyx-tv/signing.properties")
        if (props.exists()) {
            val p = Properties().apply { props.inputStream().use { load(it) } }
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Nyx TV (debug)")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "Nyx TV")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        resValues = true
    }
    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin", "META-INF/versions/**")
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":provider-plex"))
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.core)
    implementation(libs.coil.okhttp)
    implementation(libs.recyclerview)
    implementation(libs.datastore)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
}
