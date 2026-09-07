# Performance

> **What is measured and what is not.** The tables below come from
> `./gradlew :preview-tool:benchmarkCore`, which times `visualizer-core` on the
> machine it runs on. They are real measurements, but on a **desktop JVM**, not a
> phone — read them as a budget *between stages* and as a regression guard, not as
> a device figure. Rasterisation is deliberately excluded: it happens in Skia on
> the GPU and is device-bound, so a JVM number for it would be actively
> misleading. Everything in "Design decisions" is verifiable by reading the source.

## Design decisions

### No allocation in the render loop

Steady-state allocation per frame is **zero** in the core and geometry layers:

- `AnimationController` reuses one `VisualParams` for its lifetime and hands it out by
  reference. Renderers see it through a read-only interface whose setters are `internal`.
- `PolarProfile` owns its radius array, its trig tables and its harmonic tables.
- `ParticleField` is a pooled structure-of-arrays with swap-with-last removal — no objects,
  no iterators, no garbage, and the update loop walks contiguous memory.
- Renderers allocate their `Path` objects and buffers once, and call `Path.reset()` per frame.

The two deliberate exceptions:

1. **One `AudioFeatures` per analysis frame** (~94/s at 48 kHz). Immutability is what makes
   the audio→render thread handoff a single volatile write with no locks and no possibility of
   a torn read. A few hundred bytes a second buys away an entire class of concurrency bug.
2. **Gradient brushes**, two or three per frame, because their colours change every frame.
   Compose allocates comparably for any painted gradient. The shader path removes even these.

### Precompute anything whose input does not change

The sample angles of a closed outline never change, so their sines and cosines — and those of
every band harmonic — are computed once in the constructor. Per frame, the pattern rotation
costs one `cos`/`sin` pair applied to the tables via the angle-addition identities, plus one
pair per band and one per rotated path.

For a 128-sample, 6-band profile drawn with a body path and two interior contours, that takes
the per-frame transcendental count from roughly **4,900 to about 20**. The base `RadialShape`
is also evaluated only when the shape *instance* changes, which matters for definitions like
the superformula that cost two `pow` and two trig calls per sample.

Elsewhere: FFT twiddles, the bit-reversal permutation and the analysis window are built in
`RealFft`'s constructor; the colour palette is baked into a 128-entry LUT so that sampling per
particle costs an index and one lerp instead of an Oklab conversion and two transfer
functions.

### Frame-rate independence, and what it costs

Every follower derives its coefficient from `1 − exp(−dt/tau)`. That is one `exp` per
follower per frame — about twenty per frame in the controller, which is nothing, and it is
what makes 30 fps, 60 fps and 120 fps produce the same motion. `VisualResponseTest` asserts
the means agree within 0.06 across that range.

The spring integrator sub-steps at a fixed 1/240 s with `dt` clamped to 100 ms, so a dropped
frame costs at most 24 sub-steps and can never make the integration diverge.

### Audio work stays off the UI thread

On Android and desktop the entire analysis chain runs on the capture thread, which is blocked
in `read()` most of the time anyway. The UI thread's only audio-related work is one volatile
read per frame.

In the browser there is no worker thread to use, so `pump()` runs on the animation frame — but
the expensive part (the FFT the browser's `AnalyserNode` performs) already happens on the
browser's own audio thread, and what remains is the shared Kotlin analysis chain at the cost
estimated below.

---

## Measured cost

`./gradlew :preview-tool:benchmarkCore`, JDK 21 / x86-64, median of seven trials
after 20,000 warm-up iterations:

| Stage | Per call | Rate | Share of one core |
|---|---|---|---|
| Analyzer — 1024/512, FFT, 6 bands | 13.38 µs | 93.8 /s | 0.125 % |
| Analyzer — `LevelOnly` (no FFT, no overlap) | 3.20 µs | 93.8 /s | 0.030 % |
| Animation controller | 0.69 µs | 60 /s | 0.004 % |
| Polar profile, 128 samples | 25.77 µs | 60 /s | 0.155 % |
| Polar profile, 64 samples | 12.18 µs | 60 /s | 0.073 % |
| Polar profile, 128, **dynamic** shape | 51.77 µs | 60 /s | 0.311 % |
| Particle field, 160 particles | 18.23 µs | 60 /s | 0.109 % |
| Palette sample (LUT) | 0.01 µs | — | ~0 % |

**Render frame** (controller + 128-sample profile + 160 particles): **44.7 µs**, or
0.27 % of a 16.67 ms frame. **Audio and render together: 0.39 % of one core.**

### What the measurements changed

Three things were not what the operation counts suggested, and the benchmark is
the only reason they are known:

**The geometry costs twice what the DSP does.** The polar profile at 128 samples
is 25.8 µs against the analyzer's 13.4 µs. Audio analysis is *not* where a
visualizer becomes slow, and the instinct to reach for a smaller FFT first is
wrong — `sampleCount` and `turbulenceOctaves` are the CPU levers that matter.
Each sample runs six Perlin evaluations, so the cost is linear in both.

**A dynamic shape costs exactly double.** 51.8 µs against 25.8 µs, which is the
predicted result of re-sampling the base outline every frame instead of caching
it, now confirmed rather than assumed.

**`LevelOnly` was barely cheaper than the full analyzer.** It dropped the FFT but
kept a half-window hop, which doubled the analysis rate and cancelled most of the
saving: 0.51 % of a core against the full analyzer's 0.62 %. Removing the overlap
as well took it to 0.030 %. The preset was fixed; without a measurement it would
have shipped as a performance option that did not improve performance.

A fourth finding was about the benchmark itself: the first version generated its
test signal *inside* the timed loop, so 80 % of the reported analyzer cost was the
eight-harmonic synthetic voice, not the analyzer. The corpus is now pre-generated.

## The dials, in order of impact

CPU and GPU costs are separate problems and the orderings are different. Measure
before choosing; on a phone the GPU list is usually the one that matters.

**CPU** — measured above, in descending order:

| Dial | Default | Cheaper | Measured effect | What you lose |
|---|---|---|---|---|
| Dynamic vs static `shape` | static | static | 51.8 → 25.8 µs | An outline that morphs |
| `PolarProfileConfig.sampleCount` | 128 | 64 | 25.8 → 12.2 µs | Outline smoothness — the spline hides most of it |
| `ParticleFieldConfig.capacity` | 160 | 48 or 0 | 18.2 µs → ~5 µs or 0 | Sparks |
| `PolarProfileConfig.turbulenceOctaves` | 4 | 2 | ~ −7 µs | Fine surface detail |
| `AnalyzerConfig.LevelOnly` | full | level-only | 13.4 → 3.2 µs | Bands, brightness, flux onsets |
| `BlobRendererConfig.innerContours` | 2 | 0 | ~ −2 µs each | Interior secondary motion |

**GPU** — not measured here, and dominant on mobile:

| Dial | Default | Cheaper | What you lose |
|---|---|---|---|
| `BlobRendererConfig.glowLayers` | 3 | 1 | Halo smoothness. **The biggest GPU saving** — each layer is a full overdraw of a large circle |
| `ParticleAuraConfig` particle count | 160 | 48 or 0 | Sparks; each is a draw call |
| `BlobRendererConfig.drawSpecular` | true | false | The wet-surface highlight; one gradient circle |
| `BlobRendererConfig.innerContours` | 2 | 0 | Interior motion; each is a stroked 128-segment path |

Two ready-made tiers:

```kotlin
// low-end
OrganicBlobRenderer(BlobRendererConfig.Economy)      // 64 samples, 1 glow layer, no contours
rememberVisualizerState(source, analyzerConfig = AnalyzerConfig.LevelOnly)

// hero surface
CompositeRenderer(
    ShaderGlowRenderer(),
    ParticleAuraRenderer.followingOutline(blob, ParticleAuraConfig.Rich),
    OrganicBlobRenderer(BlobRendererConfig.Rich),
)
```

---

## Where the GPU actually helps

Only in one place: **the aura**.

A convincing bloom is a wide, smooth falloff evaluated per pixel. On the CPU there are two
options and both are bad. A real blur is a full-screen read-modify-write — the most
bandwidth-expensive thing a mobile 2D renderer can do, and it scales with screen area, so it
gets *worse* on the devices with the most pixels and the least memory bandwidth. A stack of
overlapping gradient circles is cheap but bands visibly on a dark background, and the layer
count caps how smooth it can get.

`ShaderGlowRenderer` replaces the stack with one full-screen SkSL pass: two exponentials, an
`atan`, three sines and a `mix` per pixel. No bandwidth cost beyond the single write, no
banding, and the angular wobble that makes the halo follow the deformed body comes free
instead of costing another layer.

Outline geometry is **not** worth moving to the GPU. It is 128 points a frame; the real cost
is tessellation and rasterisation, which Skia already performs on the GPU however the path was
built.

The shader path is an enhancement, never a requirement: `createShaderProgram` returns `null`
on Android before API 33 and on any driver that refuses the program, and the renderer falls
back to the gradient stack with no caller involvement.

---

## Practical notes

- **Compose invalidation.** The frame loop bumps an `Int` state that only the *draw* lambda
  reads, so a new frame repaints without recomposing or re-laying-out anything. Putting the
  parameters in a `State` instead would recompose the subtree 60–120 times a second.
- **`withFrameNanos`, not a wall clock.** `dt` then matches the interval the compositor will
  present, which removes the micro-judder a `System.nanoTime` loop produces.
- **Stop capture when you leave the screen.** `rememberVisualizerState` does this from its
  `DisposableEffect`, but if you drive `VisualizerState` yourself, call `stop()` — a running
  `AudioRecord` keeps the microphone indicator lit and costs battery.
- **Overdraw is the thing to measure first** on Android. Glow layers plus the background fill
  plus the body means 5–6× overdraw in the centre of the screen at default settings. Drop
  `glowLayers` before anything else.
- **Watch the frame counter, not the average.** The demo shows a live fps readout; a mean of
  60 with periodic dips to 40 is a GC pause or a layout invalidation, not a throughput problem.
