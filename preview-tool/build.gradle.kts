import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// A JVM-only developer tool. It depends on `visualizer-core` and nothing else —
// no Compose, no Android SDK, no Google Maven — so it renders anywhere a JDK
// runs, including headless CI.
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":visualizer-core"))
        }
    }
}

fun previewTask(name: String, outputDir: java.io.File, describe: String) =
    tasks.register<JavaExec>(name) {
        group = "verification"
        description = describe
        mainClass.set("com.audioviz.preview.MainKt")
        classpath = files(
            kotlin.jvm().compilations.getByName("main").output.allOutputs,
            kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles,
        )
        args = listOf(outputDir.absolutePath)
        systemProperty("java.awt.headless", "true")
        dependsOn("jvmMainClasses")
    }

previewTask(
    "renderPreview",
    project.layout.buildDirectory.dir("preview").get().asFile,
    "Renders the visualizer's geometry to PNG contact sheets (headless).",
)

tasks.register<JavaExec>("benchmarkCore") {
    group = "verification"
    description = "Measures visualizer-core throughput on this machine."
    mainClass.set("com.audioviz.preview.BenchmarkKt")
    classpath = files(
        kotlin.jvm().compilations.getByName("main").output.allOutputs,
        kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles,
    )
    dependsOn("jvmMainClasses")
}

previewTask(
    "updateDocImages",
    rootProject.file("docs/images"),
    "Regenerates the contact sheets committed under docs/images.",
)
