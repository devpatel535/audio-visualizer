# Replacing the shape

Four levels of effort, depending on how different the new geometry is.

---

## Level 0 — you do not need a named shape at all

The default is already a free-form abstract outline. If you just want *a different one*,
change the seed:

```kotlin
OrganicBlobRenderer(BlobRendererConfig(shape = RadialShapes.organic(seed = 4812)))
```

`organic` builds the outline from a random harmonic series,

```
r(theta) = 1 + irregularity * sum_k a_k * cos(k*theta + phi_k)
```

with amplitudes falling as `1/k` and phases drawn from the seed. The `1/k` falloff is what
makes the result read as organic rather than as noise: large lobes dominate and fine detail
stays subordinate, which is the spectral shape natural silhouettes have. Every seed is a
different form and none of them is anything you have a word for.

| Parameter | Default | Effect |
|---|---|---|
| `seed` | — | Selects the form. Deterministic across platforms and runs. |
| `harmonics` | 5 | How many angular components. More = busier. |
| `irregularity` | 0.38 | 0 is exactly a circle; 0.6 is markedly amoeboid. Clamped below 0.85 so the radius can never approach zero. |
| `lowestHarmonic` | 2 | The slowest component. 2 gives a broad two-lobed asymmetry; raise it for a rounder, busier form. |

### A shape with no fixed identity

```kotlin
OrganicBlobRenderer(BlobRendererConfig(shape = RadialShapes.driftingOrganic()))
```

`driftingOrganic` (and the general `morphing(forms, secondsPerForm)`) returns a
[`DynamicRadialShape`] — an outline that redefines itself every frame, travelling
continuously between forms with a quintic ease so the hand-over has no perceptible corner. It
is the strongest statement the architecture makes: the outline is not a circle, not a star,
not even one abstract form, and the analyzer, controller and renderer are all unchanged and
unaware.

`PolarProfile` detects a dynamic shape and re-samples it every frame instead of caching it.
That costs `sampleCount` extra evaluations per frame — real, and paid only when you opt in.

```kotlin
// Anything can be dynamic, not just the bundled morph.
class PulsingShape : DynamicRadialShape {
    private var squash = 1f
    override fun advance(params: VisualParams) {
        squash = 1f + 0.2f * params.breath
    }
    override fun radiusAt(theta: Float) = 1f / (1f + (squash - 1f) * abs(sin(theta)))
}
```

---

## Level 1 — a different closed outline (one line)

If the new shape can be written as *radius over angle*, it is a `RadialShape` and the entire
deformation pipeline applies to it unchanged.

```kotlin
fun interface RadialShape {
    /** Base radius at [theta] radians, as a multiple of the reference radius. */
    fun radiusAt(theta: Float): Float
}
```

```kotlin
OrganicBlobRenderer(BlobRendererConfig(shape = RadialShapes.polygon(6)))
```

Built in and ready to use:

| Shape | Call |
|---|---|
| Free-form (default) | `RadialShapes.Organic`, `RadialShapes.organic(seed)` |
| Morphing, no fixed form | `RadialShapes.driftingOrganic()`, `RadialShapes.morphing(forms)` |
| Circle | `RadialShapes.Circle` |
| Squircle / superellipse | `RadialShapes.superellipse(exponent = 4f)` |
| Regular polygon | `RadialShapes.polygon(sides = 6, cornerSoftness = 0.12f)` |
| Star | `RadialShapes.star(points = 5, innerRatio = 0.55f)` |
| Gielis superformula | `RadialShapes.superformula(m = 5f, n1 = 2f, n2 = 7f, n3 = 7f)` |
| Arbitrary sampled outline | `RadialShapes.sampled(radii)` |
| Morph between two | `RadialShapes.blend(from, to, amount)` |

Or write your own — it is a SAM interface:

```kotlin
val teardrop = RadialShape { theta ->
    1f - 0.35f * cos(theta) + 0.12f * cos(2f * theta)
}
```

Requirements: **2π-periodic, strictly positive, and peaking at exactly 1.**

The maximum is what makes shapes interchangeable — every built-in touches 1 at its widest
point, so swapping one for another keeps the visual the same size on screen without retuning
`radiusFraction`. If your definition has no convenient closed-form maximum, wrap it:

```kotlin
val mine = RadialShapes.normalized(RadialShape { theta -> /* anything */ })
```

`normalized` samples 1,440 angles once at construction and scales by the peak. The
superellipse, the superformula and `sampled` all use it internally, so you rarely need it
directly.

`GeometryTest.everyBuiltInShapePeaksAtOne` and `everyBuiltInShapeIsPeriodicAndPositive` check
this for the built-ins; both are worth extending for your own. They are not academic — the
superformula shipped un-normalised at first and drew at 2.4× the size of every other shape,
which only became obvious once the contact sheets were rendered.

### Importing an SVG path or a traced logo

Sample it once into a radius table and hand that to `RadialShapes.sampled`:

```kotlin
fun outlineToRadii(path: List<Offset>, samples: Int = 256): FloatArray {
    val cx = path.map { it.x }.average().toFloat()
    val cy = path.map { it.y }.average().toFloat()
    val radii = FloatArray(samples)
    val counts = IntArray(samples)
    for (p in path) {
        val dx = p.x - cx
        val dy = p.y - cy
        val bucket = ((atan2(dy, dx) + TWO_PI) % TWO_PI / TWO_PI * samples).toInt() % samples
        radii[bucket] += hypot(dx, dy)
        counts[bucket]++
    }
    val mean = radii.indices.filter { counts[it] > 0 }
        .map { radii[it] / counts[it] }.average().toFloat()
    for (i in radii.indices) {
        radii[i] = if (counts[i] > 0) radii[i] / counts[i] / mean else 1f
    }
    return radii
}
```

`RadialShapes.sampled` normalises for you, so the table can be in any units.

This only works for outlines that are *star-shaped about their centroid* — every ray from the
centre crosses the boundary once. Most logos and glyphs qualify. Anything with a concave
notch deep enough to be crossed twice needs Level 2.

Tuning note: shapes with flat sides show deformation much more than a circle does, so reduce
`deformationStrength` when you switch. The demo's hexagon and star entries use 0.16 and 0.15
against the circle's 0.22 for exactly this reason.

---

## Level 2 — a genuinely different renderer

Implement `ShapeRenderer`. There is no radial assumption anywhere in the interface.

```kotlin
interface ShapeRenderer {
    fun onSurfaceChanged(size: Size, density: Float) {}
    fun DrawScope.render(frame: VisualFrame)
}
```

A complete example — a bar spectrum, which shares nothing structurally with a blob:

```kotlin
class BarsRenderer(private val bars: Int = 24) : ShapeRenderer {
    private var density = 1f

    override fun onSurfaceChanged(size: Size, density: Float) {
        this.density = density
    }

    override fun DrawScope.render(frame: VisualFrame) {
        val p = frame.params
        val width = size.width / bars
        for (i in 0 until bars) {
            val u = i / (bars - 1f)

            // Band energy, interpolated across the bar index.
            val bandIndex = (u * (p.bandCount - 1)).toInt().coerceAtLeast(0)
            val energy = p.band(bandIndex)

            // Shared motion field: this bar moves with the rest of the system.
            val wobble = frame.field.line(u * 2f, layer = 1.3f, octaves = 2)

            val height = size.height * 0.5f * (
                0.06f +                                  // never fully collapsed
                0.55f * energy +
                0.25f * p.pulse +
                0.10f * wobble * p.turbulence
            ) * p.scale

            drawRect(
                color = if (i % 4 == 0) frame.palette.highlight else frame.palette.core,
                topLeft = Offset(i * width, frame.center.y - height * 0.5f),
                size = Size(width * 0.66f, height),
                alpha = p.opacity * (0.35f + 0.65f * p.glow),
            )
        }
    }
}
```

Nothing upstream changes. Swap it in:

```kotlin
AudioVisualizer(renderer = remember { BarsRenderer() }, state = state)
```

### Rules for a renderer

- **Allocate in the constructor or in `onSurfaceChanged`, never in `render`.** Reuse `Path`
  objects, arrays and buffers; the loop runs 60–120 times a second.
- **Never smooth anything yourself.** Every value in `VisualParams` is already filtered,
  clamped and frame-rate compensated. Another filter only adds lag.
- **Sample `frame.field`, don't invent your own noise.** The shared field is the reason the
  parts of the visual look like parts of one object.
- **Use `frame.referenceRadius`, not raw pixels**, so the visual is identical on a watch and
  on a monitor.
- **Treat `VisualParams` as read-only.** Its setters are `internal` to the core module, so the
  compiler enforces this from outside.

### Which parameters to use

You do not have to use them all. Pick the ones that mean something for your geometry:

| If your shape has… | Drive it with |
|---|---|
| an outline | `deformation`, `turbulence`, band energies |
| an interior | `detail`, `midEnergy` |
| an overall size | `scale` — but keep it a *small* contribution |
| light or colour | `glow`, `colorMix`, `brightness` |
| discrete elements | `emission`, `pulse` |
| an orientation | `rotation`, `rotationVelocity` |
| a position | `driftX`, `driftY` |
| an amplitude | `waveAmplitude`, `level` |
| nothing happening | `idle`, `breath` — keep it alive |

---

## Level 3 — a fragment shader

Write SkSL and drive it with uniforms. `ShaderGlowRenderer` is the worked example; copy its
shape.

```kotlin
class MyShaderRenderer : ShapeRenderer {
    private val program = createShaderProgram(MY_SHADER)   // null if unsupported

    override fun DrawScope.render(frame: VisualFrame) {
        val p = program ?: return drawCanvasFallback(frame)
        p.setFloat2("uCenter", frame.center.x, frame.center.y)
        p.setFloat("uRadius", frame.referenceRadius)
        p.setFloat("uDeform", frame.params.deformation)
        p.setFloat("uTime", frame.params.time)
        drawRect(brush = p.brush())
    }
}
```

**Always provide a fallback.** `createShaderProgram` returns `null` on Android before API 33
and on any driver that refuses the program — that is a large fraction of devices in service,
not an edge case.

Add a test alongside `ShaderCompilationTest` so a typo in the shader string is a build
failure rather than a silently missing layer:

```kotlin
@Test fun myShaderCompiles() = assertNotNull(createShaderProgram(MY_SHADER))
```

---

## Combining renderers

`CompositeRenderer` draws several back to front. Because every layer reads the same
`VisualParams` and samples the same `MotionField`, they move together:

```kotlin
val blob = OrganicBlobRenderer(BlobRendererConfig.Rich)
CompositeRenderer(
    ShaderGlowRenderer(),                              // GPU aura, behind
    ParticleAuraRenderer.followingOutline(blob),       // sparks off the deformed edge
    blob,                                              // the body
)
```

`followingOutline` wires the particle emitter to the blob's live deformed outline, so sparks
leave the edge that is actually on screen rather than a static circle.
