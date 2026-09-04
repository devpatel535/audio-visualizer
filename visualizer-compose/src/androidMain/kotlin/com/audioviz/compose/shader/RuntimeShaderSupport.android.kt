package com.audioviz.compose.shader

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush

/**
 * AGSL-backed runtime shaders on Android 13 (API 33) and later.
 *
 * Older releases return `null` and every bundled renderer falls back to its
 * Canvas path, which is why the shader layer is presented as an enhancement
 * rather than as the primary implementation.
 */
actual fun createShaderProgram(source: String): ShaderProgram? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return try {
        AndroidShaderProgram(RuntimeShader(source))
    } catch (e: Throwable) {
        null
    }
}

actual fun shaderBackendName(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        "Android RuntimeShader (AGSL)"
    } else {
        "unavailable below API 33"
    }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AndroidShaderProgram(
    private val shader: RuntimeShader,
) : ShaderProgram {

    override fun setFloat(name: String, value: Float) {
        shader.setFloatUniform(name, value)
    }

    override fun setFloat2(name: String, x: Float, y: Float) {
        shader.setFloatUniform(name, x, y)
    }

    override fun setFloat4(name: String, x: Float, y: Float, z: Float, w: Float) {
        shader.setFloatUniform(name, x, y, z, w)
    }

    // `RuntimeShader` is mutable, so the uniforms set above are already live;
    // a fresh brush each frame keeps Compose from reusing a cached paint.
    override fun brush(): Brush = ShaderBrush(shader)
}
