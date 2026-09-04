# Testing

## Manual: speech at three levels

The core check the design has to pass. Run it on each target you ship to.

```bash
./gradlew :demo-app:run                            # desktop
./gradlew :demo-app:wasmJsBrowserDevelopmentRun    # web
./gradlew :demo-app:installDebug -Pviz.android=true # Android
```

The demo exposes renderer, palette, character preset, sensitivity and gate threshold live, so
the whole matrix can be walked without a rebuild. The level meter along the top of the control
panel shows the normalized level with the gate state as its opacity — check it first whenever
something looks wrong.

### 1. Silence — 30 seconds, say nothing

| Expect | Fails if |
|---|---|
| The object keeps its form and turns slowly (~1 revolution every 2 minutes) | It freezes, or the outline stops moving |
| A gentle, non-repeating breathing motion | The breathing is an obvious loop, or absent |
| Colour sits at the calm end and stays there | Colour creeps upward — AGC is on, or `gateOpenMarginDb` is too low |
| The level meter reads zero and does not flicker | Flicker means the gate is chattering: raise the open/close gap |

Then **wait five minutes** with the room quiet. Nothing should drift. This is the test that
catches automatic gain control, which raises the mic gain during silence until room tone looks
like speech.

### 2. Quiet speech — talk softly, arm's length away

| Expect | Fails if |
|---|---|
| Visibly more active than silence within ~200 ms of the first word | Nothing happens → raise `sensitivity`, or lower `gateOpenMarginDb` |
| Individual syllables are discernible in the motion | Motion is one smooth swell → lower `slowReleaseTau` |
| Still clearly below "normal" in scale, glow and colour | It looks the same as loud speech → normalization has over-adapted; try `NormalizationMode.Fixed` to confirm |

### 3. Normal speech — read a paragraph at conversational volume

| Expect | Fails if |
|---|---|
| The shape looks like it is listening: deformation tracks the phrase, glow flares on emphasis | It pulses uniformly regardless of what you say |
| Colour has moved to roughly the middle of the palette | It has slammed to the bright end → lower the `colorFrom*` weights |
| It settles smoothly during pauses instead of collapsing | Collapse → raise `gateHoldSeconds` |
| No frame-to-frame flicker anywhere | Flicker → raise `jitterDeadband` |

### 4. Loud speech — project, then shout

| Expect | Fails if |
|---|---|
| Clearly more energetic, but the shape has **not** simply inflated | Growth beyond ~15% means the scale contributions are too high |
| Deformation, turbulence and glow all still respond to *changes* while loud | They pin at maximum → lower the `…From*` weights or `summationKnee` |
| No strobing, no colour flashing | If either happens, the slew limiters have been raised too far |
| After you stop, it returns to the calm state over a couple of seconds | Instant snap → raise `sustainedReleaseTau` |

### 5. Edge cases worth ten minutes

- **Cover the microphone mid-sentence.** Should decay gracefully, not cut out.
- **Tap the device / knock the desk.** One transient, then recovery — not a lasting change.
- **Play music instead of speech.** Bass should drive the broad lobes, cymbals the fine
  turbulence and the rim.
- **Background noise** (fan, café recording). Raise `gateOpenMarginDb` until the visual is
  calm, then check speech still opens it.
- **Two microphones with very different gain**, or one device held at 15 cm and then at 1 m.
  After ~10 seconds of speech both should drive the visual comparably — that is the adaptive
  normalization working.
- **Background the app and return.** No jump; `maxDeltaSeconds` clamps the first frame.
- **Web only:** reload without granting permission. The demo should offer the synthetic
  source rather than showing a dead canvas.

---

## Automated

```bash
./gradlew checkPortable   # 52 unit tests + every Maven-Central-only target
./gradlew checkAll        # the above plus the desktop targets and the shader test
```

`VisualResponseTest` is the manual procedure above, executed. It drives
`SyntheticAudioSource` — a calibrated, deterministic speech-like generator — through the real
analyzer and the real controller at a simulated frame rate, and asserts on the resulting
parameters.

| Test | Asserts |
|---|---|
| `respondsMonotonicallyToSpeechLevel` | silence < quiet < normal < loud, in `intensity` |
| `silenceIsCalmButNotDead` | intensity < 0.05, but deformation > 0.10 and rotation > 0.01 rad/s, and scale range < 0.05 |
| `loudSpeechDoesNotSimplyInflateTheShape` | scale stays under 1.20, **deformation out-responds scale by more than 5:1**, and both glow and deformation still move frame to frame |
| `nothingJumpsBetweenFrames` | glow and colour steps stay inside their configured slew limits; deformation < 0.10/frame; scale < 0.03/frame |
| `behavesTheSameAtEveryFrameRate` | means at 30 and 120 fps agree within 0.06 |
| `everyParameterStaysInRangeAndFinite` | no NaN/Inf and no range escape at −60, −42, −28, −14 and −3 dBFS |
| `adaptiveNormalizationEqualisesMicrophoneGain` | two sources 18 dB apart land within 0.18 |
| `presetsRemainWithinTheirCharacter` | `Subtle` moves less than `Expressive` |

Supporting suites: `RealFftTest` (bin-for-bin against a naive DFT), `SmoothingTest`
(frame-rate independence, spring stability at 8 fps, dead-band jitter rejection),
`LevelNormalizerTest` (floor tracking, gate hysteresis and hold), `MotionFieldTest`
(seamlessness, continuity, layered time), `GeometryTest` (shape periodicity, outline closure,
idle motion, particle recycling), `PaletteTest` (Oklab round-trip, monotone lightness, no
discontinuities), `ShaderCompilationTest` (the SkSL compiles under real Skia).

### What the numbers look like

Measured by `VisualResponseTest` on the default configuration, deterministic normalization,
averaged over the last 9 seconds of a 12-second run at 60 fps:

| | silence | quiet (−42 dB) | normal (−28 dB) | loud (−14 dB) |
|---|---|---|---|---|
| `intensity` | 0.000 | 0.213 | 0.550 | 0.900 |
| `deformation` | 0.161 | 0.422 | 0.699 | 0.932 |
| `turbulence` | 0.118 | 0.395 | 0.612 | 0.730 |
| `glow` | 0.099 | 0.283 | 0.629 | 0.900 |
| `colorMix` | 0.000 | 0.258 | 0.536 | 0.859 |
| `scale` | 0.999 | 1.030 | 1.051 | 1.073 |
| `rotation` (rad/s) | 0.046 | 0.241 | 0.284 | 0.329 |
| max step/frame, glow | 0.000 | 0.053 | 0.059 | 0.064 |
| max step/frame, colour | 0.000 | 0.000 | 0.000 | 0.002 |

Read it as the design brief in numbers: **scale moves 7% across the whole dynamic range while
deformation moves 480% and glow moves 800%.** Colour never moves more than 0.002 in a frame.
Nothing reaches 1.0 even at −3 dBFS, so there is always headroom left for an emphasis.

## Visual inspection without a device

```bash
./gradlew :preview-tool:renderPreview     # -> preview-tool/build/preview/*.png
./gradlew :preview-tool:updateDocImages   # -> docs/images/*.png (the committed set)
```

`preview-tool` drives the real analyzer, the real controller and the real geometry, then
rasterises the result with Java2D. It depends only on `visualizer-core` and a JDK — no
Compose, no Android SDK, no display — so it runs in CI and its output can be attached to a
pull request.

It renders four contact sheets: free-form outlines across five seeds plus a morphing row,
response across four input levels, named primitives through the identical pipeline, and idle
behaviour across the three palettes. Reviewing tuning changes as
a before/after image pair catches things no assertion will: the un-normalised superformula
drawing at 2.4× the size of every other shape, a `softness` blend that cancelled a star into a
circle, a rim that read as a hard outline, and a specular highlight floating off the body on
concave geometry were all found this way and all fixed.

It is a development tool, not a second renderer. It shares the geometry, motion and colour with
the Compose renderers — which is the part worth looking at — but if the two ever disagree about
*drawing*, the Compose one is correct.

### Adding a regression test for your own tuning

`PipelineRig` drives the whole chain off-thread; `ParamTraces` collects statistics.

```kotlin
@Test
fun myPresetStaysCalmDuringTypingNoise() {
    val traces = ParamTraces(settleSeconds = 3f)
    PipelineRig(analyzerConfig = myConfig, animationConfig = myAnimation)
        .run(seconds = 12f, fill = speechFiller(-52f), observe = traces.observer)
    assertTrue(traces.intensity.mean < 0.1f, traces.report("typing noise"))
}
```

`speechFiller(levelDb)` and `silenceFiller(roomToneDb)` produce calibrated input;
`SyntheticSourceCalibrationTest` pins the generator to within 0.02 dB of its requested level,
so a level in a test means the same thing as a level on a meter.
