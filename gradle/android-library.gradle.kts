// Applied from a module build script only when the Android target is enabled.
// See gradle/android-common.gradle.kts for why the configuration is dynamic.
import org.gradle.api.artifacts.VersionCatalogsExtension

apply(plugin = "com.android.library")

fun Any.node(name: String): Any = withGroovyBuilder { getProperty(name) }!!
fun Any.assign(name: String, value: Any?) { withGroovyBuilder { setProperty(name, value) } }

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun version(alias: String) = libs.findVersion(alias).get().requiredVersion.toInt()

val android = extensions.getByName("android")
android.assign("namespace", project.extra["androidNamespace"] as String)
android.assign("compileSdk", version("androidCompileSdk"))
android.node("defaultConfig").assign("minSdk", version("androidMinSdk"))
android.node("compileOptions").let {
    it.assign("sourceCompatibility", JavaVersion.VERSION_17)
    it.assign("targetCompatibility", JavaVersion.VERSION_17)
}
