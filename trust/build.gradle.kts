// TRUST lane owns this file.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "mu.proxima.trust"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // TrustEngineContractTest lives in src/testFixtures so the SAME security spec runs on the JVM
    // (in-memory keysets) and on a phone (Android Keystore). A source dir cannot be shared directly (AGP 7.2+).
    testFixtures { enable = true }
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

    testFixturesApi(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Print each test result: `build/` is deny-listed for the agents, so the console is the only report we read.
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        // Without this a failure prints only its name, and the reason is in build/ where we cannot look.
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = false
        showStandardStreams = false
    }
}
