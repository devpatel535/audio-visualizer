// Applied from a module build script only when the Android target is enabled.
//
// Two things force this into a separate script rather than an `if` block inside
// the module's own build file:
//
//  1. The Kotlin DSL type-checks a whole build script even where a branch will
//     never run, so a reference to an AGP type in a dead `if (androidEnabled)`
//     block still fails to compile when AGP is absent. Desktop and web builds
//     would then require the Android SDK and Google's Maven, which is exactly
//     what this arrangement exists to avoid.
//
//  2. A script applied with `apply(from = ...)` does NOT inherit the root
//     project's buildscript classpath, so it needs its own `buildscript` block
//     to see AGP's types. Without it the script fails to compile with
//     "Unresolved reference: android" — the classes are on the *runtime*
//     classpath but not the *compile* one.
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Keep in sync with `agp` in gradle/libs.versions.toml and with the
        // root build script. Version catalogs are not visible inside a
        // buildscript block, so this cannot reference the catalog directly.
        classpath("com.android.tools.build:gradle:8.7.3")
    }
}

apply(plugin = "com.android.library")

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val namespaceValue = project.extra["androidNamespace"] as String

// `com.android.build.api.dsl.LibraryExtension` rather than the older
// `com.android.build.gradle.LibraryExtension`: the api.dsl types are AGP's
// supported surface, while the other lives under an `internal` package that has
// changed shape between AGP versions.
extensions.configure<LibraryExtension>("android") {
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
