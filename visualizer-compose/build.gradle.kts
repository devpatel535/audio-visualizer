import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val androidEnabled = rootProject.extra["androidEnabled"] as Boolean
if (androidEnabled) {
    extra["androidNamespace"] = "com.audioviz.compose"
    apply(from = rootProject.file("gradle/android-library.gradle.kts"))
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    if (androidEnabled) {
        androidTarget {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // `skikoMain` holds the Skia-backed GPU shader implementation shared by
        // the desktop (JVM) and web (Wasm) targets. Android has its own AGSL
        // actual under `androidMain`.
        val skikoMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(skikoMain)
        wasmJsMain.get().dependsOn(skikoMain)

        commonMain.dependencies {
            api(project(":visualizer-core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // Pulls in Skia, so the bundled shader is compiled by the real
            // SkSL compiler during `check` rather than at runtime on a user's
            // device. Requires Google's Maven for the androidx artifacts that
            // Compose's JVM variant depends on.
            implementation(compose.desktop.currentOs)
        }
    }
}
