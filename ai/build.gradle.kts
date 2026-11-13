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

    // Provisional runtime: Gemini Nano via ML Kit Prompt API (see docs/lanes/AI.md decision log).
    // LlmTriageEngine only talks to the LlmBackend interface, so a Gemma backend can replace it.
    implementation(libs.mlkit.genai.prompt)
    // Device spike only (src/debug/ LlmProbeActivity) for the Gemma comparison. Debug builds only.
    debugImplementation(libs.litertlm.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
