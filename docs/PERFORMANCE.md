# Performance

> **Scope note.** The figures below are *operation counts derived from the code*, not device
> benchmarks — no phone was available while this was built. They are useful for reasoning
> about relative cost and for sizing the configurable dials; they are not a substitute for
> profiling on your target hardware. Everything in "Design decisions" is verifiable by
> reading the source.

---

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

## Estimated cost

### Audio, per analysis frame

Default configuration: 1024-sample window, 512 hop, 6 bands, 513 bins, 93.75 frames/s at
48 kHz.

| Stage | Approximate operations |
|---|---|
| Windowing | 1,024 multiplies |
| Real FFT (512-point complex) | ~2,300 butterflies, ~23,000 flops |
| Real-input unpacking | ~8,000 flops |
| RMS + peak | 1,024 multiply-adds |
| Band summation + 6 normalizers | ~600 flops |
| Spectral centroid | ~1,000 flops |
| Spectral flux | ~1,500 flops |
| Envelopes, gate, normalizer | ~50 flops |
| **Total** | **~36,000 flops per frame ≈ 3.4 Mflop/s** |

That is on the order of a tenth of a percent of one modern phone core. Audio analysis is not
where a visualizer becomes slow.

`AnalyzerConfig.LevelOnly` removes the FFT, bands, centroid and flux, leaving ~2,000 flops per
frame — worth having for the lowest tier of device, at the cost of band-driven deformation and
flux-based onset detection.

### Render, per frame

Default `OrganicBlobRenderer`: 128 samples, 2 + 4 field octaves, 6 bands, 2 interior contours.

| Stage | Approximate operations |
|---|---|
| Polar profile: 6 Perlin evaluations × 128 samples | ~54,000 flops |
| Band harmonics (from tables) | ~2,300 flops |
| Body spline: 128 cubic segments | ~2,000 flops |
| 2 interior contours (1 Perlin each + spline) | ~22,000 flops |
| Transcendental calls | ~20 |
| **Total geometry** | **~80,000 flops per frame ≈ 4.8 Mflop/s at 60 fps** |

Beyond that the cost is **rasterisation**, which is device- and GPU-dependent and dominates:
3 filled or stroked paths, 3–5 radial-gradient circles, and up to 160 small circles if the
particle layer is enabled. Overdraw from the glow layers is the single largest GPU cost in the
default configuration.

---

## The dials, in order of impact

| Dial | Default | Cheaper | What you lose |
|---|---|---|---|
| `BlobRendererConfig.glowLayers` | 3 | 1 | Halo smoothness — **the biggest GPU saving** (each layer is a full overdraw of a large circle) |
| `ParticleAuraConfig.field.capacity` | 160 | 48 or 0 | Sparks; each particle is a draw call |
| `BlobRendererConfig.innerContours` | 2 | 0 | Interior secondary motion; each is a full profile + spline |
| `PolarProfileConfig.turbulenceOctaves` | 4 | 2 | Fine surface detail; linear in the profile loop |
| `BlobRendererConfig.sampleCount` | 128 | 64 | Outline smoothness — the spline hides most of it |
| `AnalyzerConfig.enableSpectrum` | true | false | Band deformation, brightness, flux onsets |
| `AnalyzerConfig.fftSize` / `hopSize` | 1024 / 512 | 512 / 256 | Frequency resolution (and *halves* the hop, so raise the hop too if saving CPU) |
| `BlobRendererConfig.drawSpecular` | true | false | The wet-surface highlight; one gradient circle |

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
