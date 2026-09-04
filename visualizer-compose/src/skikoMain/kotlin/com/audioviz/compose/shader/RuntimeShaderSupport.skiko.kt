package com.audioviz.compose.shader

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * Skia-backed runtime shaders, shared by the desktop (JVM) and web (Wasm)
 * targets.
 *
 * Compose Multiplatform's `Shader` type aliases to `org.jetbrains.skia.Shader`
 * on both, so a Skia shader drops straight into a [ShaderBrush].
 */
actual fun createShaderProgram(source: String): ShaderProgram? = try {
    SkiaShaderProgram(RuntimeShaderBuilder(RuntimeEffect.makeForShader(source)))
} catch (e: Throwable) {
    // A shader that fails to compile must degrade to the Canvas path, never
    // take the app down.
    null
}

actual fun shaderBackendName(): String = "Skia RuntimeEffect (SkSL)"

private class SkiaShaderProgram(
    private val builder: RuntimeShaderBuilder,
) : ShaderProgram {

    override fun setFloat(name: String, value: Float) {
        builder.uniform(name, value)
    }

    override fun setFloat2(name: String, x: Float, y: Float) {
        builder.uniform(name, x, y)
    }

    override fun setFloat4(name: String, x: Float, y: Float, z: Float, w: Float) {
        builder.uniform(name, x, y, z, w)
    }

    // Uniforms change every frame, so the shader has to be rebuilt every frame.
    // This is one small object per frame, against a full-screen effect that
    // would otherwise cost a real blur pass.
    override fun brush(): Brush = ShaderBrush(builder.makeShader())
}
