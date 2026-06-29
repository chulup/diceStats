plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "xyz.chulup.dicestats"
    compileSdk = 35

    defaultConfig {
        applicationId = "xyz.chulup.dicestats"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    buildToolsVersion = "36.1.0"

    testOptions {
        unitTests.all {
            // Surface the recognition sweep's per-photo log (println) in test output.
            it.testLogging { showStandardStreams = true }
        }
    }

    packaging {
        resources {
            // Each bytedeco native jar (opencv/openblas/javacpp) carries identical GraalVM
            // native-image config (unused on Android) and a copy of the javacpp helper lib;
            // drop the former and keep one of the latter to avoid merge conflicts.
            excludes += "/META-INF/native-image/**"
            pickFirsts += "/org/bytedeco/**"
        }
    }

    // OpenCV (bytedeco/JavaCPP) bundles large native libs; keep the per-ABI split so the APK
    // carries only one architecture's libs. bytedeco ships arm64-v8a natives only (see above).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
}

// bytedeco/JavaCPP native artifacts (carry a per-platform classifier, so they're declared
// here rather than in the version catalog) and the Android ABIs the app ships.
val bytedecoNatives = listOf(
    "opencv" to libs.versions.bytedecoOpencv.get(),
    "javacpp" to libs.versions.javacpp.get(),
    "openblas" to libs.versions.bytedecoOpenblas.get(),
)
// bytedeco 4.13.0-1.5.13 ships only 64-bit Android natives; we target arm64-v8a devices.
val androidAbis = listOf("android-arm64")

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.coil.compose)

    implementation(libs.kotlinx.coroutines.android)

    // OpenCV (bytedeco/JavaCPP): Java API + per-ABI Android natives. The opencv artifact
    // pulls javacpp + openblas (Java) transitively; their native jars are added per ABI
    // (version-catalog entries can't carry a classifier). The matching desktop natives for
    // JVM unit tests are added as testRuntimeOnly below.
    implementation(libs.bytedeco.opencv)
    bytedecoNatives.forEach { (lib, version) ->
        androidAbis.forEach { abi -> implementation("org.bytedeco:$lib:$version:$abi") }
    }

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.gson)
    // Desktop (linux-x86_64) OpenCV natives so the recognition sweep runs the real PipCounter
    // (pip counting) in JVM unit tests on the dev machine.
    bytedecoNatives.forEach { (lib, version) ->
        testRuntimeOnly("org.bytedeco:$lib:$version:linux-x86_64")
    }

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.gson)

    debugImplementation(libs.androidx.ui.tooling)
}
