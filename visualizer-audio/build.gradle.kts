import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

val androidEnabled = rootProject.extra["androidEnabled"] as Boolean
if (androidEnabled) {
    extra["androidNamespace"] = "com.audioviz.audio"
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

    sourceSets {
        commonMain.dependencies {
            api(project(":visualizer-core"))
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
