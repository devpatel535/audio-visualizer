package com.audioviz.compose

import com.audioviz.compose.shader.AURA_SHADER_SOURCE
import com.audioviz.compose.shader.createShaderProgram
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Compiles the bundled shader through the real Skia compiler.
 *
 * A shader is a second language inside a Kotlin string: the Kotlin compiler
 * will happily ship a typo in it, and on Android the only symptom is a layer
 * that silently never appears because [createShaderProgram] returned null. This
 * test turns that into a build failure.
 *
 * The desktop target uses the same `RuntimeEffect` compiler that the web target
 * does, and AGSL is the same SkSL dialect, so passing here covers all three
 * backends for syntax and type errors.
 */
class ShaderCompilationTest {

    @Test
    fun auraShaderCompiles() {
        assertNotNull(
            createShaderProgram(AURA_SHADER_SOURCE),
            "the aura shader failed to compile; every platform would fall back to the Canvas path",
        )
    }

    @Test
    fun uniformsCanBeSetWithoutThrowing() {
        val program = assertNotNull(createShaderProgram(AURA_SHADER_SOURCE))
        program.setFloat2("uResolution", 1080f, 1920f)
        program.setFloat2("uCenter", 540f, 960f)
        program.setFloat("uRadius", 300f)
        program.setFloat("uGlow", 0.6f)
        program.setFloat("uTurbulence", 0.4f)
        program.setFloat("uTime", 12.5f)
        program.setFloat4("uInner", 0.2f, 0.5f, 1f, 1f)
        program.setFloat4("uOuter", 0.05f, 0.1f, 0.3f, 0f)
        // Building the brush is what actually validates the uniform names and
        // types against the compiled program.
        program.brush()
    }

    @Test
    fun aBrokenShaderDegradesInsteadOfThrowing() {
        assertNull(
            createShaderProgram("half4 main(float2 p) { return notAFunction(p); }"),
            "a bad shader must return null so callers fall back, not crash the app",
        )
    }
}
