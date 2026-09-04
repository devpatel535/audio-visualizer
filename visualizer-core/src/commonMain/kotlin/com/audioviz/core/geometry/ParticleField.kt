package com.audioviz.core.geometry

import com.audioviz.core.anim.VisualParams
import com.audioviz.core.util.TWO_PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Tuning for [ParticleField]. All distances are in reference-radius units. */
data class ParticleFieldConfig(
    /** Hard cap on live particles. This is the memory budget; arrays are sized to it. */
    val capacity: Int = 160,

    /** Particles per second at `emission == 1`. */
    val emissionRate: Float = 110f,

    val minLifeSeconds: Float = 0.8f,
    val maxLifeSeconds: Float = 2.1f,

    /** Initial outward speed range. */
    val minSpeed: Float = 0.12f,
    val maxSpeed: Float = 0.42f,

    /** Tangential speed as a fraction of the shape's angular velocity. */
    val swirl: Float = 0.55f,

    /** Exponential velocity damping per second. Higher = particles stop sooner. */
    val drag: Float = 1.6f,

    /** Acceleration from the shared noise field; ties particle drift to the shape. */
    val turbulenceAcceleration: Float = 0.9f,

    /** Radial offset from the emitter outline where particles are born. */
    val spawnOffset: Float = 0.02f,

    val minSize: Float = 0.009f,
    val maxSize: Float = 0.030f,
)

/**
 * A pooled particle simulation with no per-frame allocation.
 *
 * State lives in parallel `FloatArray`s (structure-of-arrays) rather than in
 * objects: the update loop then walks contiguous memory, which matters on
 * mobile far more than the code style suggests, and it means zero GC pressure
 * with hundreds of particles.
 *
 * The field is shape-agnostic — particles are emitted along whatever outline the
 * caller supplies as a [RadialShape], so the same system decorates a blob, a
 * polygon or a traced logo. Their drift is driven by the *same*
 * [com.audioviz.core.anim.MotionField] as the outline deformation, which is why
 * the particles look like they belong to the object rather than being sprinkled
 * on top of it.
 */
class ParticleField(
    config: ParticleFieldConfig = ParticleFieldConfig(),
    seed: Int = 4_242,
) {
    var config: ParticleFieldConfig = config
        private set

    private var capacity = config.capacity

    val positionX = FloatArray(capacity)
    val positionY = FloatArray(capacity)
    val velocityX = FloatArray(capacity)
    val velocityY = FloatArray(capacity)
    val age = FloatArray(capacity)
    val life = FloatArray(capacity)
    val size = FloatArray(capacity)

    /** Per-particle constant in `0..1`; use it to vary colour or alpha. */
    val variation = FloatArray(capacity)

    /** Number of live particles occupying the first [count] slots. */
    var count: Int = 0
        private set

    private var emissionAccumulator = 0f
    private var rng = (seed.toLong() and 0xFFFFFFFFL).let { if (it == 0L) 1L else it }

    /** Normalized remaining life of particle [i] in `0..1`; 1 at birth. */
    fun remainingLife(i: Int): Float {
        val l = life[i]
        return if (l <= 0f) 0f else (1f - age[i] / l).coerceIn(0f, 1f)
    }

    fun clear() {
        count = 0
        emissionAccumulator = 0f
    }

    /**
     * Advances the simulation by one frame.
     *
     * @param emitter outline particles are born on, in reference-radius units.
     */
    fun update(params: VisualParams, emitter: RadialShape) {
        val cfg = config
        val dt = params.deltaTime
        val field = params.field

        // ---- emit ----------------------------------------------------------
        emissionAccumulator += params.emission * cfg.emissionRate * dt
        while (emissionAccumulator >= 1f && count < capacity) {
            emissionAccumulator -= 1f
            spawn(params, emitter, cfg)
        }
        // Never let a backlog build up while the pool is full.
        if (count >= capacity) emissionAccumulator = 0f

        // ---- integrate -----------------------------------------------------
        val damping = exp(-cfg.drag * dt)
        val turbulence = cfg.turbulenceAcceleration * (0.35f + params.turbulence)

        var i = 0
        while (i < count) {
            age[i] += dt
            if (age[i] >= life[i]) {
                // Swap-with-last removal keeps the live set contiguous in O(1).
                val last = count - 1
                if (i != last) moveSlot(last, i)
                count = last
                continue
            }

            val px = positionX[i]
            val py = positionY[i]

            // Curl-ish acceleration sampled from the same field as the outline.
            val ax = field.plane(px * 1.6f, py * 1.6f, layer = 3.3f, octaves = 2) * turbulence
            val ay = field.plane(py * 1.6f + 5.1f, px * 1.6f - 2.7f, layer = 4.9f, octaves = 2) * turbulence

            var vx = velocityX[i] + ax * dt
            var vy = velocityY[i] + ay * dt
            vx *= damping
            vy *= damping

            velocityX[i] = vx
            velocityY[i] = vy
            positionX[i] = px + vx * dt
            positionY[i] = py + vy * dt
            i++
        }
    }

    private fun spawn(params: VisualParams, emitter: RadialShape, cfg: ParticleFieldConfig) {
        val i = count++
        val theta = nextFloat() * TWO_PI
        val radius = emitter.radiusAt(theta) + cfg.spawnOffset
        val cosT = cos(theta)
        val sinT = sin(theta)

        positionX[i] = cosT * radius
        positionY[i] = sinT * radius

        val speed = cfg.minSpeed + (cfg.maxSpeed - cfg.minSpeed) * nextFloat() *
            (0.4f + 0.6f * params.pulse + 0.3f * params.intensity)
        val tangential = params.rotationVelocity * cfg.swirl * radius

        velocityX[i] = cosT * speed - sinT * tangential
        velocityY[i] = sinT * speed + cosT * tangential

        age[i] = 0f
        life[i] = cfg.minLifeSeconds + (cfg.maxLifeSeconds - cfg.minLifeSeconds) * nextFloat()
        size[i] = cfg.minSize + (cfg.maxSize - cfg.minSize) * nextFloat()
        variation[i] = nextFloat()
    }

    private fun moveSlot(from: Int, to: Int) {
        positionX[to] = positionX[from]
        positionY[to] = positionY[from]
        velocityX[to] = velocityX[from]
        velocityY[to] = velocityY[from]
        age[to] = age[from]
        life[to] = life[from]
        size[to] = size[from]
        variation[to] = variation[from]
    }

    /** xorshift32; deterministic across every platform, allocation-free. */
    private fun nextFloat(): Float {
        var x = rng
        x = x xor (x shl 13) and 0xFFFFFFFFL
        x = x xor (x shr 17)
        x = x xor (x shl 5) and 0xFFFFFFFFL
        rng = x
        return (x.toDouble() / 0xFFFFFFFFL.toDouble()).toFloat()
    }
}
