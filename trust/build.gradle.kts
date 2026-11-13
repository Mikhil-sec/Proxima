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

// One-off organiser keypair for verified roles (#38). Public half -> assets (commit); private half -> OUTSIDE the
// repo (~/.proxima), pushed to the organiser phone with adb, never committed. See tools/GenerateOrganiserKey.kt.
tasks.register<JavaExec>("generateOrganiserKey") {
    group = "proxima"
    description = "Generates the organiser Ed25519 keypair for verified roles. Run once."
    val unitTest = tasks.named<Test>("testDebugUnitTest")
    dependsOn(unitTest.map { it.taskDependencies })
    classpath = files(unitTest.map { it.classpath })
    mainClass.set("mu.proxima.trust.tools.GenerateOrganiserKeyKt")
    args(
        file("src/main/assets/data/organiser_public.json").absolutePath,
        File(System.getProperty("user.home"), ".proxima/organiser_private.json").absolutePath,
    )
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
