// TRUST lane owns this file.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "mu.proxima.trust"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tink.android)
    implementation(libs.zxing.core)
    // QR scanner (add when building the scan screen component):
    // implementation(libs.androidx.camera.camera2)
    // implementation(libs.androidx.camera.lifecycle)
    // implementation(libs.androidx.camera.view)
    // implementation(libs.mlkit.barcode.scanning)

    testImplementation(libs.junit)
}

// Print each test result: `build/` is deny-listed for the agents, so the console is the only report we read.
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
