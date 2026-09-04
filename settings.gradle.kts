// ---------------------------------------------------------------------------
// Optional Android target.
//
// The Android target needs the Android SDK *and* network access to Google's
// Maven repository. Desktop (Windows/macOS/Linux) and Web (Wasm) need neither.
// To keep the repository buildable for everyone, the Android target is opt-in:
//
//   viz.android=auto   (default) enable it only when an SDK is detected
//   viz.android=true             force-enable
//   viz.android=false            force-disable
//
// Set it in gradle.properties or pass -Pviz.android=true on the command line.
//
// NOTE: `pluginManagement` is hoisted by Gradle and executes before the rest of
// this script, so the detection lives inside it and publishes the result on
// `gradle.extra` for everything that runs afterwards.
// ---------------------------------------------------------------------------
pluginManagement {
    val androidRequest = providers.gradleProperty("viz.android").orNull?.trim()?.lowercase() ?: "auto"

    val androidSdkDir: String? = run {
        val localProps = rootDir.resolve("local.properties")
        val fromLocal = if (localProps.isFile) {
            java.util.Properties().apply { localProps.inputStream().use { p -> load(p) } }.getProperty("sdk.dir")
        } else {
            null
        }
        fromLocal ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    }

    val androidEnabled = when (androidRequest) {
        "true", "on", "yes", "1" -> true
        "false", "off", "no", "0" -> false
        else -> androidSdkDir?.let { java.io.File(it).isDirectory } == true
    }
    gradle.extra["viz.androidEnabled"] = androidEnabled

    repositories {
        gradlePluginPortal()
        mavenCentral()
        if (androidEnabled || providers.gradleProperty("viz.googleRepo").orNull?.lowercase() != "false") google()
    }
}

rootProject.name = "audio-reactive-visualizer"

val androidEnabled = gradle.extra["viz.androidEnabled"] as Boolean
if (!androidEnabled) {
    logger.lifecycle(
        "[audio-visualizer] Android target DISABLED (no SDK detected). " +
            "Building desktop + web only. Enable with -Pviz.android=true once the SDK is installed."
    )
}

// Google's Maven hosts the `androidx.*` artifacts that Compose Multiplatform's
// JVM/desktop and Android variants depend on, so it is enabled by default.
// Sandboxed CI that can only reach Maven Central can pass -Pviz.googleRepo=false;
// in that configuration only the Wasm (web) target resolves.
val googleRepoEnabled = providers.gradleProperty("viz.googleRepo").orNull?.lowercase() != "false"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        if (googleRepoEnabled) google()
    }
}

include(":visualizer-core")
include(":visualizer-audio")
include(":visualizer-compose")
include(":demo-app")
