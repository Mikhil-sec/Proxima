// AI lane owns this file.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "mu.proxima.ai"
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
    // After the device spike, add exactly ONE on-device LLM runtime:
    // implementation(libs.litertlm.android)
    // implementation(libs.mediapipe.tasks.genai)

    // Device spike only (src/debug/ LlmProbeActivity). Debug builds only; remove after the runtime is picked.
    debugImplementation(libs.mlkit.genai.prompt)
    debugImplementation(libs.litertlm.android)

    testImplementation(libs.junit)
}
