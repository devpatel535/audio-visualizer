# Architecture

## The rule everything follows

> Each layer knows the layer below it by an interface, and knows nothing about the layer
> above it.

That is what makes the visualizer shape-agnostic. It is enforced by the module graph rather
than by convention: `visualizer-compose` does not depend on `visualizer-audio`, so a renderer
*cannot* reach the microphone even by accident.

```
                    ┌──────────────────────────────────────────┐
                    │            visualizer-core               │
                    │  pure Kotlin, no Compose, no platform    │
                    │                                          │
   AudioSource ◄────┤  capture/   the interface only            │
   (interface)      │  dsp/       filters, FFT, envelopes       │
                    │  analysis/  AudioAnalyzer → AudioFeatures │
                    │  anim/      AnimationController → Params  │
                    │  geometry/  shapes, profiles, particles   │
                    │  color/     Oklab, palettes               │
                    └───────▲──────────────────────┬────────────┘
                            │                      │
          implements        │                      │  consumes
                            │                      │
        ┌───────────────────┴──────┐   ┌───────────▼──────────────────┐
        │    visualizer-audio      │   │     visualizer-compose        │
        │  AudioRecord / JavaSound │   │  ShapeRenderer + renderers    │
        │  / Web Audio             │   │  AudioVisualizer composable   │
        └───────────────────┬──────┘   └───────────┬──────────────────┘
                            │                      │
                            └────────┬─────────────┘
                                     │
                              ┌──────▼───────┐
                              │   demo-app    │
                              └──────────────┘
```

`demo-app` is the only module that sees both a microphone and a renderer, and all it does is
hand one to the other.

---

## The four layers

### 1. Audio capture — `AudioSource`

```kotlin
interface AudioSource {
    val status: AudioSourceStatus
    val sampleRate: Int
    fun start(sink: AudioFrameSink)
    fun pump() {}          // pull-based sources deliver here; push-based no-op
    fun stop()
}
```

Two delivery models sit behind one interface because the platforms genuinely differ:

- **Push** (Android `AudioRecord`, JVM `TargetDataLine`) — a dedicated capture thread calls
  the sink as blocks arrive. `read` blocks until the device has data, so the loop paces
  itself and never spins.
- **Pull** (Web Audio) — the browser gives no worker thread to block on and an `AudioWorklet`
  would mean shipping a separate JavaScript file next to the Wasm bundle. Instead an
  `AnalyserNode` keeps a rolling window that the browser's own audio thread fills, and the
  host reads from it once per animation frame.

The host calls `pump()` unconditionally every frame, so nothing above this layer knows which
model it is talking to.

Failures are reported through `status`, never thrown: permission refusal, a missing device
and a mid-session device loss are ordinary UI states, not exceptions.

Two implementations ship in `visualizer-core` itself and need no platform at all:
`SyntheticAudioSource` (a speech-like generator) and `SilentAudioSource`. They are what the
tests drive, and what the demo falls back to when there is no microphone.

### 2. Audio analysis — `AudioAnalyzer`

Consumes arbitrary-length PCM blocks, produces `AudioFeatures` snapshots. Fully described in
[AUDIO_PIPELINE.md](AUDIO_PIPELINE.md).

The important structural decision is **re-framing**. Device buffer sizes vary wildly — Android
hands out anything from 240 to 4096 samples depending on the device and the audio path — so
the analyzer maintains its own fixed sliding window (1024 samples, 512 hop) and every
internal time constant is expressed against that stable frame rate. Without this, the same
`releaseTau` would mean different things on different phones.

### 3. Animation control — `AnimationController`

Consumes `AudioFeatures`, produces `VisualParams`. Fully described in [MAPPING.md](MAPPING.md).

It runs on the **render** thread at display rate, deliberately decoupled from the audio rate.
Three consequences, all of them wanted:

- The visual is smooth at 120 fps even though analysis produces ~94 frames a second.
- When audio stops arriving entirely — a device change, a browser tab throttling its audio
  graph — the visual keeps breathing rather than freezing.
- Passing the *same* `AudioFeatures` snapshot to consecutive calls is normal and correct.

### 4. Rendering — `ShapeRenderer`

```kotlin
interface ShapeRenderer {
    fun onSurfaceChanged(size: Size, density: Float) {}
    fun DrawScope.render(frame: VisualFrame)
}
```

`VisualFrame` carries the parameters, the resolved palette, the canvas geometry and the
shared noise field. Nothing else. See [ADDING_A_SHAPE.md](ADDING_A_SHAPE.md).

---

## Threading and data flow

```
 capture thread                                     render thread
 ──────────────                                     ─────────────
 read PCM block
     │
     ├─► AudioAnalyzer.onAudioFrame
     │      re-frame, DC block, RMS
     │      normalize, gate, envelopes
     │      FFT, bands, onset
     │      build AudioFeatures  (immutable)
     │            │
     │            └──── @Volatile write ────►  read once per frame
     │                                              │
     │                                    AnimationController.update(features, dt)
     │                                              │
     │                                    VisualParams (reused object)
     │                                              │
     │                                    ShapeRenderer.render
```

**The entire synchronisation contract is one volatile reference to an immutable object.**
One writer, many readers, no locks on either side, and a reader can never observe a
half-updated frame. The cost is one small allocation per analysis frame — about 94 a second
at 48 kHz — which is a rounding error next to what a single rendered frame costs, and buys
away an entire class of tearing and lock-contention bugs.

Everything downstream of that boundary is allocation-free in steady state: the controller
reuses one `VisualParams`, the profile reuses its radius arrays, the particle system is a
pooled structure-of-arrays, and the renderers reuse their `Path` objects.

### Why the frame counter exists

Compose repaints when a snapshot state that the **draw phase** reads changes. The frame loop
bumps an `Int` state; the `Canvas` lambda reads it. That invalidates only the draw phase — no
recomposition, no layout, no new composition scopes, 60–120 times a second. Storing the
parameters themselves in a `State` would recompose the whole subtree every frame.

```kotlin
LaunchedEffect(state) {
    while (isActive) { withFrameNanos { nanos -> state.advance(nanos) } }
}
Canvas(modifier) {
    state.frameTick          // subscribes the draw phase to the frame clock
    with(renderer) { render(frame) }
}
```

`withFrameNanos` rather than a wall clock: `dt` then matches the interval the compositor will
actually present, which is what keeps motion free of the micro-judder a `System.nanoTime`
loop produces.

---

## Where the GPU fits

The shader layer is an **enhancement with a fallback**, never a requirement.

`createShaderProgram(source)` returns `null` when the platform cannot run a runtime shader —
Android before API 33, or a driver that refuses the program — and every renderer that uses
one has a Canvas path. `ShaderGlowRenderer` is the worked example: SkSL on the GPU when
available, a stack of radial gradients when not.

The GPU is worth reaching for in exactly one place here: **the aura**. A convincing bloom is
a wide, smooth falloff evaluated per pixel. On the CPU the options are a real blur (a
full-screen read-modify-write, the most bandwidth-expensive thing a mobile 2D renderer can
do) or a stack of overlapping gradient circles — cheap, but the banding shows on a dark
background and the layer count caps how smooth it can get. On the GPU the same falloff is a
handful of ALU instructions per pixel with no bandwidth cost, and the angular wobble that
makes the halo follow the deformed body is free rather than another layer.

Outline geometry is *not* worth moving to the GPU. It is 128 points a frame; the cost is in
tessellation and rasterisation, which Skia already does on the GPU whichever way the path was
built.

AGSL is SkSL, so one shader string serves all three backends. `ShaderCompilationTest`
compiles it with the real Skia compiler during `check`, which turns a shader typo into a
build failure instead of a silently missing layer.
