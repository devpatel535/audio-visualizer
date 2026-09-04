// ---------------------------------------------------------------------------
// Root build file.
//
// The Android Gradle Plugin is added to the buildscript classpath *only* when
// the Android target is enabled (see settings.gradle.kts). That keeps the
// desktop/web build completely free of any Android SDK or Google Maven
// requirement, while still allowing a full Android build on a dev machine
// that has the SDK installed.
// ---------------------------------------------------------------------------
buildscript {
    val androidEnabled = gradle.extra["viz.androidEnabled"] as Boolean
    repositories {
        mavenCentral()
        if (androidEnabled) google()
    }
    dependencies {
        // Keep in sync with `agp` in gradle/libs.versions.toml.
        if (androidEnabled) classpath("com.android.tools.build:gradle:8.7.3")
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}

val androidEnabled: Boolean by extra(gradle.extra["viz.androidEnabled"] as Boolean)

// `checkAll` compiles every non-Android target and runs the DSP/animation unit
// tests. `checkPortable` is the subset that resolves from Maven Central alone,
// for networks without access to Google's Maven.
tasks.register("checkPortable") {
    group = "verification"
    description = "Unit tests + every target that resolves from Maven Central alone (no Google Maven)."
    dependsOn(
        ":visualizer-core:jvmTest",
        ":visualizer-core:compileKotlinWasmJs",
        ":visualizer-audio:compileKotlinWasmJs",
        ":visualizer-compose:compileKotlinWasmJs",
        ":demo-app:compileKotlinWasmJs",
        // Rasterises the whole pipeline headlessly; a smoke test as well as a
        // way to eyeball a tuning change.
        ":preview-tool:renderPreview",
    )
}

tasks.register("checkAll") {
    group = "verification"
    description = "Compiles every enabled target and runs the JVM unit tests."
    dependsOn(
        "checkPortable",
        ":visualizer-audio:compileKotlinJvm",
        ":visualizer-compose:compileKotlinJvm",
        ":demo-app:compileKotlinJvm",
    )
}
