// Applied from demo-app only when the Android target is enabled.
// See gradle/android-common.gradle.kts for why the configuration is dynamic.
import org.gradle.api.artifacts.VersionCatalogsExtension

apply(plugin = "com.android.application")

fun Any.node(name: String): Any = withGroovyBuilder { getProperty(name) }!!
fun Any.assign(name: String, value: Any?) { withGroovyBuilder { setProperty(name, value) } }

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun version(alias: String) = libs.findVersion(alias).get().requiredVersion.toInt()

val android = extensions.getByName("android")
android.assign("namespace", "com.audioviz.demo")
android.assign("compileSdk", version("androidCompileSdk"))

// The manifest and resources stay in `src/androidMain`. The Kotlin
// Multiplatform plugin already points AGP's main source set there, so no source
// set configuration is needed -- and moving them to AGP's own `src/main`
// default is actively wrong, because KMP's path wins and the build then fails
// with "property 'mainManifest' specifies file ... which doesn't exist".
android.node("defaultConfig").let {
    it.assign("applicationId", "com.audioviz.demo")
    it.assign("minSdk", version("androidMinSdk"))
    it.assign("targetSdk", version("androidTargetSdk"))
    it.assign("versionCode", 1)
    it.assign("versionName", "1.0.0")
}
android.node("compileOptions").let {
    it.assign("sourceCompatibility", JavaVersion.VERSION_17)
    it.assign("targetCompatibility", JavaVersion.VERSION_17)
}
// kotlinx-coroutines ships duplicate licence files that break packaging.
@Suppress("UNCHECKED_CAST")
(android.node("packaging").node("resources").node("excludes") as MutableSet<String>)
    .add("/META-INF/{AL2.0,LGPL2.1}")
