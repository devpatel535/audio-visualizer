package com.audioviz.core

import com.audioviz.core.anim.MotionField
import com.audioviz.core.anim.VisualParams

/** A hand-set [VisualParams] for tests that need one specific value. */
class FakeParams(
    override val emission: Float = 0f,
    override val deltaTime: Float = 1f / 60f,
    override val intensity: Float = 0.5f,
    override val pulse: Float = 0.3f,
    override val turbulence: Float = 0.3f,
    override val rotationVelocity: Float = 0.1f,
) : VisualParams {
    override val energy = 0f
    override val idle = 0f
    override val breath = 0.5f
    override val scale = 1f
    override val deformation = 0.3f
    override val detail = 0.3f
    override val waveAmplitude = 0.2f
    override val rotation = 0f
    override val driftX = 0f
    override val driftY = 0f
    override val glow = 0.5f
    override val opacity = 1f
    override val blur = 0.1f
    override val colorMix = 0.5f
    override val brightness = 0.3f
    override val level = 0.5f
    override val gate = 1f
    override val spectralBrightness = 0.4f
    override val bandCount = 0
    override fun band(index: Int) = 0f
    override val lowEnergy = 0f
    override val midEnergy = 0f
    override val highEnergy = 0f
    override val time = 1f
    override val field = MotionField()
}
