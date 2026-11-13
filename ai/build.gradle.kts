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
    // Triage accuracy eval (ai/eval/README.md): off unless -PproximaEval=export|score is passed.
    testOptions.unitTests.all {
        it.systemProperty("proxima.eval", providers.gradleProperty("proximaEval").getOrElse(""))
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    // On-device LLM (see docs/lanes/AI.md decision log). LlmTriageEngine only talks to LlmBackend.
    // Primary: Gemma 4 E2B via LiteRT-LM, model file sideloaded (never in the APK).
    implementation(libs.litertlm.android)
    // Secondary: Gemini Nano via ML Kit Prompt API, used only where AICore offers it.
    implementation(libs.mlkit.genai.prompt)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
