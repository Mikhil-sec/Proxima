// LOCATE lane owns this file.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "mu.proxima.locate"
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
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.location)
    // UWB is API 31+; guard every call with DeviceCapabilities.hasUwb.
    // If the manifest merger complains about minSdk, use tools:overrideLibrary in this module's manifest.
    implementation(libs.androidx.core.uwb)

    testImplementation(libs.junit)
}
