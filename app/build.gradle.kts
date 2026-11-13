// UI lane owns this file.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "mu.proxima.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "mu.proxima.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Hackathon: release APK signed with the debug key so judges can sideload it.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":mesh"))
    implementation(project(":ai"))
    implementation(project(":locate"))
    implementation(project(":trust"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

// ui-android design rules (the skill's `detektDesign` regexes; detekt itself is waived: no Kotlin 2.4 support).
// Fails `check` on arbitrary dp/sp/colour literals, banned gradients, filler copy, buzzwords and looping
// decoration anywhere outside ui/theme. See DESIGN_RULES.md.
val designCheck by tasks.registering {
    group = "verification"
    description = "Blocks UI slop patterns outside ui/theme (DESIGN_RULES.md)."
    val sources = fileTree("src/main/java") {
        include("**/*.kt")
        exclude("**/ui/theme/**")
    }
    inputs.files(sources)
    doLast {
        val patterns = mapOf(
            "dp literal outside theme (use MaterialTheme.spacing/sizes)" to Regex("""(?<![\w.])(?!0\.dp\b)\d+(\.\d+)?\.dp\b"""),
            "sp literal outside Type.kt (use MaterialTheme.typography)" to Regex("""(?<![\w.])\d+(\.\d+)?\.sp\b"""),
            "Color literal outside Color.kt" to Regex("""Color\(0x[0-9A-Fa-f]+\)"""),
            "Named colour constant (use colorScheme roles)" to Regex("""Color\.(Red|Green|Blue|Yellow|Magenta|Cyan|White|Black|Gray|DarkGray|LightGray)\b"""),
            "Inline RoundedCornerShape size (use MaterialTheme.shapes)" to Regex("""RoundedCornerShape\(\s*\d"""),
            "Gradient brush" to Regex("""Brush\.(linear|radial|sweep|horizontal|vertical)Gradient"""),
            "Looping decoration (rememberInfiniteTransition)" to Regex("""rememberInfiniteTransition"""),
            "Filler copy" to Regex("""(?i)(Lorem ipsum|Lightning fast|Blazing fast|Powered by AI|Built for the modern|Get started in seconds|Beautiful and intuitive|Seamlessly integrate|Effortlessly|The future of|Reimagined|Unleash the power of)"""),
            "Generic CTA" to Regex(""""(Get Started|Learn More|Click Here|Submit)""""),
            "Buzzword in copy" to Regex("""(?i)"[^"\n]*\b(clean|modern|professional|sleek|premium|beautiful|elegant)\b[^"\n]*""""),
            "Inline font family" to Regex("""FontFamily\("""),
        )
        val violations = mutableListOf<String>()
        sources.forEach { f ->
            val lines = f.readLines()
            lines.forEachIndexed { i, line ->
                if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
                patterns.forEach { (label, regex) ->
                    regex.find(line)?.let { violations += "${f.relativeTo(projectDir)}:${i + 1}  [$label]  ${it.value}" }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("ui-android design rule violations (${violations.size}):\n" + violations.joinToString("\n"))
        }
        println("designCheck: 0 violations")
    }
}
tasks.named("check") { dependsOn(designCheck) }
