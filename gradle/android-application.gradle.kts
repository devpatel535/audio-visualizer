// Applied from demo-app only when the Android target is enabled. See
// gradle/android-library.gradle.kts for why this lives in its own script.
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

apply(plugin = "com.android.application")

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<com.android.build.gradle.internal.dsl.BaseAppModuleExtension> {
    namespace = "com.audioviz.demo"
    compileSdk = libs.findVersion("androidCompileSdk").get().requiredVersion.toInt()
    defaultConfig {
        applicationId = "com.audioviz.demo"
        minSdk = libs.findVersion("androidMinSdk").get().requiredVersion.toInt()
        targetSdk = libs.findVersion("androidTargetSdk").get().requiredVersion.toInt()
        versionCode = 1
        versionName = "1.0.0"
    }
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}
