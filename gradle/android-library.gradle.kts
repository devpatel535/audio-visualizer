// Applied from a module build script only when the Android target is enabled.
// Kept in a separate script so that AGP types are never resolved (and the
// Android SDK is never required) on desktop/web-only builds.
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

apply(plugin = "com.android.library")

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val namespaceValue = project.extra["androidNamespace"] as String

configure<com.android.build.gradle.LibraryExtension> {
    namespace = namespaceValue
    compileSdk = libs.findVersion("androidCompileSdk").get().requiredVersion.toInt()
    defaultConfig {
        minSdk = libs.findVersion("androidMinSdk").get().requiredVersion.toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
