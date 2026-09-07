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

// The manifest and resources live at AGP's own default location, `src/main`,
// so the Android source sets need no configuration. Kotlin sources stay in
// `src/androidMain/kotlin` where the Kotlin Multiplatform plugin puts them.
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
