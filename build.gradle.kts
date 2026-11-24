/*
 * Noctorium Lite: the whole app is this one module, with no library but Kotlin's own standard library -- no
 * AndroidX, no Compose, no Media3, no image loader. Android's own views, MediaPlayer, media session and JSON
 * do the work, and R8 strips what is left, so the APK stays well under a megabyte and runs on Android 5.
 *
 * The music comes from the hosted web player's API (music.noctorium.xyz/api/music), which already
 * reads YouTube Music and SoundCloud; only a YouTube song's audio address is asked for on the phone, because
 * YouTube gives it out for the device that asks.
 */
plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android") version "2.1.21"
}

/** The newest release tag, as the other Noctorium apps read theirs, for a build nobody handed a version to. */
val latestReleaseTag: String? = runCatching {
    providers.exec {
        commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.startsWith("v") }
}.getOrNull()

val appVersion: String = (findProperty("appVersion") as String?)?.trim()?.removePrefix("v")?.takeIf { it.isNotBlank() }
    ?: latestReleaseTag?.removePrefix("v")
    ?: "0.40.0"

android {
    namespace = "app.noctorium.lite"
    compileSdk = 35

    // Signed with Noctorium's key when the release workflow hands it over, as every Noctorium APK is.
    val keystorePath: String? = System.getenv("NOCTORIUM_KEYSTORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("NOCTORIUM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NOCTORIUM_KEY_ALIAS")
                keyPassword = System.getenv("NOCTORIUM_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "app.noctorium.lite"
        minSdk = 21
        targetSdk = 35
        val parts = appVersion.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        versionCode = (parts.getOrElse(0) { 0 } * 10_000) + (parts.getOrElse(1) { 0 } * 100) + parts.getOrElse(2) { 0 }
        versionName = appVersion
        // English only: every other language Android would otherwise pack in is dead weight here.
        resourceConfigurations += "en"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources.excludes += listOf("META-INF/**", "kotlin/**", "DebugProbesKt.bin")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // No null checks on every parameter in the shipped app: smaller and a little faster.
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

/** The APK as the release names it: Noctorium-Lite-<version>.apk. */
val liteApkName = "Noctorium-Lite-$appVersion.apk"
tasks.register<Copy>("liteApk") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk") }
    into(layout.buildDirectory.dir("dist"))
    // A plain string, not the script's own value: the configuration cache keeps no reference to the script.
    val name = liteApkName
    rename { name }
}
