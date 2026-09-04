package com.audioviz.compose.shader

import androidx.compose.ui.graphics.Brush

/**
 * A compiled runtime-shader program with settable float uniforms.
 *
 * Deliberately tiny: only scalar and vector float uniforms, and one way to get
 * a [Brush] out. Nothing about textures, child shaders or blend modes, because
 * those are where the AGSL and SkSL dialects diverge and the abstraction would
 * start to leak.
 */
interface ShaderProgram {
    fun setFloat(name: String, value: Float)
    fun setFloat2(name: String, x: Float, y: Float)
    fun setFloat4(name: String, x: Float, y: Float, z: Float, w: Float)

    /** A brush that paints with the shader at its current uniform values. */
    fun brush(): Brush
}

/**
 * Compiles [source] as a runtime shader, or returns `null` when the platform
 * cannot run one.
 *
 * ### Where the backends differ
 * | Target        | API                                | Availability            |
 * |---------------|------------------------------------|-------------------------|
 * | Android       | `android.graphics.RuntimeShader`   | API 33 (Android 13)+    |
 * | Desktop / Web | `org.jetbrains.skia.RuntimeEffect` | wherever Skia runs      |
 *
 * Both consume the same SkSL dialect (AGSL *is* SkSL), so one shader string
 * serves every target. Returning `null` rather than throwing is intentional:
 * callers are expected to have a Canvas fallback, and roughly half of Android
 * devices in service still predate API 33.
 */
expect fun createShaderProgram(source: String): ShaderProgram?

/** Human-readable backend name, for diagnostics and the demo's status line. */
expect fun shaderBackendName(): String
