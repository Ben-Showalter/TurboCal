import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Release signing comes from an untracked keystore.properties at the repo root
// (AGENTS.md §9). Without it, release builds are unsigned and debug builds are
// unaffected.
val keystoreProps: Properties? = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}
if (keystoreProps == null) {
    logger.warn("TurboCal: keystore.properties not found - release APKs will be unsigned (debug builds are fine).")
}

android {
    namespace = "com.turbocal.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.turbocal.app"
        // 24 covers the Android 7 sibling (E4610). Confirm the E4810 with
        // `adb shell getprop ro.build.version.sdk` and record it in the README.
        minSdk = 24
        targetSdk = 34
        // Bump both for every release; versionCode always increases (AGENTS.md §9).
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    packaging {
        resources {
            // ical4j's Apache Commons dependencies all ship these; ical4j's zoneinfo/ stays.
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        // No Play Store: target-SDK freshness warnings don't apply to sideloaded APKs.
        disable += setOf("OldTargetApi", "ExpiredTargetSdkVersion")
    }
}

// TurboCal-0.1.0.apk for releases, TurboCal-0.1.0-debug.apk for debug builds.
android.applicationVariants.all {
    val variant = this
    outputs.all {
        val suffix = if (variant.buildType.name == "release") "" else "-${variant.buildType.name}"
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
            "TurboCal-${variant.versionName}$suffix.apk"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.androidx.core.ktx)
    coreLibraryDesugaring(libs.desugar)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
}
