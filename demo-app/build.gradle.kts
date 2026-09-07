import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val androidEnabled = rootProject.extra["androidEnabled"] as Boolean
if (androidEnabled) {
    apply(from = rootProject.file("gradle/android-application.gradle.kts"))
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("demo")
        browser {
            commonWebpackConfig { outputFileName = "demo.js" }
        }
        binaries.executable()
    }

    if (androidEnabled) {
        androidTarget {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":visualizer-core"))
            implementation(project(":visualizer-audio"))
            implementation(project(":visualizer-compose"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
        if (androidEnabled) {
            androidMain.dependencies {
                implementation(libs.androidx.activity.compose)
                // ContextCompat.checkSelfPermission; declared rather than
                // inherited transitively from activity-compose.
                implementation(libs.androidx.core.ktx)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.audioviz.demo.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "AudioVisualizerDemo"
            packageVersion = "1.0.0"
        }
    }
}
