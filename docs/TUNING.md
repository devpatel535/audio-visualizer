# Configuration reference

Three configuration objects, all plain data classes with defaults, so a settings screen or a
remote config can drive them directly. All three can be changed at runtime; the analyzer keeps
its adapted noise floor across a config change.

```kotlin
val state = rememberVisualizerState(
    source = createMicrophoneSource(MicrophoneConfig(preferredSampleRate = 48_000)),
    analyzerConfig = AnalyzerConfig(sensitivity = 1.3f, gateOpenMarginDb = 7f),
    animationConfig = AnimationConfig.Expressive,
    palette = VisualPalette.OceanBlue,
)
```

---

## Start here: the five knobs that matter

| Symptom | Knob | Direction |
|---|---|---|
| Doesn't react to normal speech | `AnalyzerConfig.sensitivity` | up (1.0 → 1.5) |
| Reacts to room noise / keyboard | `AnalyzerConfig.gateOpenMarginDb` | up (9 → 14) |
| Reacts but feels sluggish | `AnalyzerConfig.slowReleaseTau` | down (0.6 → 0.4) |
| Too busy / distracting | `AnimationConfig.Subtle` preset | — |
| Not lively enough | `AnimationConfig.Expressive` preset | — |

If nothing moves at all, look at the demo's level meter first: it makes it immediately obvious
whether the problem is a gate that never opens or a normalizer that has not adapted yet.

---

## `MicrophoneConfig`

| Field | Default | Notes |
|---|---|---|
| `preferredSampleRate` | 48000 | Native on essentially all modern hardware. |
| `fallbackSampleRates` | 44100, 32000, 16000 | Tried in order if the preferred rate is refused. |
| `blockSize` | 1024 | Capture block in samples. Smaller = lower latency, more callbacks. |
| `enableVoiceProcessing` | **false** | See below. |

**Leave `enableVoiceProcessing` off** unless the same microphone is also feeding a call.
Automatic gain control is actively hostile to a visualizer: it spends the first second of every
utterance changing the very gain the analyzer is trying to measure, and it *raises* the gain
during silence, so a quiet room slowly turns into a noisy one and the visual starts breathing
on its own. The Android implementation requests the `UNPROCESSED` source and explicitly
releases AGC, noise suppression and echo cancellation where the device exposes them.

---

## `AnalyzerConfig`

### Framing

| Field | Default | Effect |
|---|---|---|
| `fftSize` | 1024 | Analysis window. 21 ms at 48 kHz. Must be a power of two. |
| `hopSize` | 512 | Frames per second = `sampleRate / hopSize` (93.75 at 48 kHz). |
| `window` | `HANN` | `RECTANGULAR` is cheaper and leaks badly; only for level-only work. |
| `enableSpectrum` | true | **false** skips the FFT entirely — bands, brightness and flux-based onsets all go to zero. |
| `bandCount` | 6 | Log-spaced bands between `minBandHz` and `maxBandHz`. |
| `minBandHz` / `maxBandHz` | 55 / 8000 | 55 Hz is below the male fundamental; 8 kHz covers sibilance. |

Larger `fftSize` buys frequency resolution at the cost of time resolution — with 2048 a
plosive smears across 43 ms and transients get mushy. 1024 is the right trade for speech.

### Sensitivity and normalization

| Field | Default | Effect |
|---|---|---|
| `sensitivity` | 1.0 | Multiplies the normalized level **after** the gate, so it makes the visual more reactive without making it react to room tone. 0.6 reserved, 1.8 eager. |
| `inputGain` | 1.0 | Linear gain before everything. Use only to correct a known hardware offset. |
| `normalization` | `Adaptive` | `Fixed(floorDb, ceilingDb)` for reproducibility. |
| `absoluteFloorDb` | −75 | Lowest the adaptive floor may settle. |
| `maximumFloorDb` | −28 | Highest it may drift. Guards very noisy rooms. |
| `minimumDynamicRangeDb` | 16 | Stops a monotone whisper being expanded until room noise looks like shouting. |
| `maximumDynamicRangeDb` | 48 | Caps the expansion the other way. |
| `loudDecayDbPerSecond` | 3 | How fast the loud reference forgets a shout. Higher = recovers faster after one loud word. |

Use `NormalizationMode.Fixed` when you need identical output for identical input —
screenshots, A/B tuning comparisons, calibrated hardware. Adaptive mode deliberately makes
two different inputs converge, which is exactly wrong when you are trying to measure a
difference.

### Noise gate

| Field | Default | Effect |
|---|---|---|
| `gateOpenMarginDb` | 9 | dB above the noise floor at which the gate opens. **The main noise-rejection knob.** |
| `gateCloseMarginDb` | 5 | Must be below `gateOpenMarginDb`; the gap is the hysteresis. |
| `gateHoldSeconds` | 0.35 | Keeps the gate open through the gaps between words. |
| `gateAttackTau` / `gateReleaseTau` | 0.035 / 0.30 | The gate fades rather than switches. |

Rules of thumb: quiet room / headset 6 dB · normal room 9 dB · café or open office 14–18 dB.
Raising the open margin without raising the close margin widens the hysteresis, which is
usually what you want in a noisy room.

### Envelopes and transients

| Field | Default | Effect |
|---|---|---|
| `fastAttackTau` / `fastReleaseTau` | 0.008 / 0.120 | Syllables and consonants. |
| `slowAttackTau` / `slowReleaseTau` | 0.120 / 0.600 | Sustained energy → `intensity`. **Lower the release to feel snappier.** |
| `sustainedAttackTau` / `sustainedReleaseTau` | 1.2 / 3.0 | Session mood → colour drift. |
| `transientAttackTau` / `transientReleaseTau` | 0.015 / 0.130 | Shape of a pulse. |
| `transientDifferenceGain` | 1.4 | Weight of the `fast − slow` path. |
| `transientThreshold` | 0.20 | **Expander floor.** Raise it if the visual throbs continuously during speech; lower it if emphasis is not registering. |
| `onsetSensitivity` | 1.3 | Spectral-flux threshold multiplier. Higher = fewer, stronger onsets. |

### Presets

| Preset | For |
|---|---|
| `AnalyzerConfig()` | Close-range speech on a phone or laptop. |
| `.LevelOnly` | No FFT, 512-sample window. Low-end devices, or renderers that ignore bands. |
| `.NoisyRoom` | Higher gate, lower sensitivity, calmer transients. |
| `.CloseMic` | Headset or studio mic; reacts to whispers. |
| `.Deterministic` | Fixed normalization. Tests and screenshots. |

---

## `AnimationConfig`

Full formulas in [MAPPING.md](MAPPING.md). The groups worth knowing:

### Presets

| Preset | Character |
|---|---|
| `.Default` | Calm, premium. Suitable for a voice assistant. |
| `.Expressive` | Larger, faster, more theatrical. Music, or a full-screen listening state. |
| `.Subtle` | Minimal movement. A small always-on indicator that must not distract. |

### Response and feel

| Field | Default | Effect |
|---|---|---|
| `intensityGamma` | 0.85 | `< 1` expands quiet speech. Drop to 0.7 to make soft talking more visible. |
| `intensityKnee` | 0.72 | Where shouting stops adding much. |
| `intensitySpringHz` / `Damping` | 2.1 / 0.88 | Higher Hz = snappier. Damping below ~0.7 starts to visibly bounce. |
| `summationKnee` | 0.75 | Applied to every summed parameter. Lower = more headroom, less contrast. |
| `timeScaleFromIntensity` | 0.85 | **The single most effective "make it feel alive" knob.** How much louder audio speeds up the whole animation clock. |
| `timeScaleFromPulse` | 0.55 | Extra clock speed-up on a transient. |

### Amplitudes

| Group | Fields | Default sum |
|---|---|---|
| Scale | `scaleFromIntensity` 0.055, `scaleFromPulse` 0.045, `scaleFromIdleBreath` 0.012 | ~11% max growth |
| Deformation | `idleDeformation` 0.16, `…FromIntensity` 0.46, `…FromPulse` 0.30, `…FromLowBand` 0.14 | 1.06 → knee |
| Turbulence | `idleTurbulence` 0.12, `…FromHighBand` 0.40, `…FromPulse` 0.34, `…FromBrightness` 0.20 | 1.06 → knee |
| Glow | `idleGlow` 0.10, `…FromIntensity` 0.50, `…FromPulse` 0.40, `…FromHighBand` 0.18 | 1.18 → knee |
| Colour | `colorFromEnergy` 0.26, `…FromIntensity` 0.46, `…FromPulse` 0.24, `…FromBrightness` 0.16 | 1.12 → knee |

**Keep the scale contributions small.** They are the one place where raising a number makes
the whole thing look cheap.

### Safety limits

| Field | Default | Guarantees |
|---|---|---|
| `glowRisePerSecond` / `glowFallPerSecond` | 4.5 / 1.8 | Glow cannot traverse 0→1 in under 13 frames at 60 fps. |
| `colorRisePerSecond` / `colorFallPerSecond` | 1.1 / 0.55 | A full palette traverse takes ≥ 0.9 s. |
| `jitterDeadband` | 0.0015 | Sub-perceptual movement is discarded outright. |
| `maxDeltaSeconds` | 1/15 | A stall can never make the visual jump. |
| `minDeltaSeconds` | 1/480 | Guards against zero and denormals. |

These are the parameters that make undesirable behaviour *inexpressible* rather than merely
unlikely. `VisualResponseTest` asserts the glow and colour bounds directly against the
configured values.

### Idle life

| Field | Default | Effect |
|---|---|---|
| `idleDeformation` / `idleTurbulence` / `idleGlow` | 0.16 / 0.12 / 0.10 | The visual has form and light in silence. Set all to 0 for a truly flat resting state. |
| `breathPeriodPrimary` / `Secondary` | 5.3 s / 8.9 s | Keep them non-harmonic or the breathing becomes a visible loop. |
| `baseRotationSpeed` | 0.055 rad/s | One turn every ~2 minutes at rest. |
| `idleFadeEnd` | 0.28 | Activity level at which `idle` reaches 0. |

### The motion field

| Field | Default | Effect |
|---|---|---|
| `fieldOctaves` | 3 | More = more detail, linear cost in the profile loop. |
| `fieldBaseFrequency` | 0.55 | Lobes around a closed outline ≈ `2π × frequency`. 0.55 → ~3.5 lobes. |
| `fieldLacunarity` | 2.05 | Spatial frequency ratio between octaves. |
| `fieldGain` | 0.5 | Amplitude ratio. Higher = rougher. |
| `fieldTimeRate` | 0.22 | How fast the base shape drifts. |
| `fieldTimeLacunarity` | 1.85 | **How much faster fine detail moves than the base shape.** 1.0 makes everything move at one speed and immediately looks mechanical. |

---

## `PolarProfileConfig` (radial renderers)

| Field | Default | Effect |
|---|---|---|
| `sampleCount` | 128 | Outline resolution. 64 is fine on low-end hardware. |
| `deformationStrength` | 0.22 | Peak large-scale displacement, as a fraction of radius. **Lower this for shapes with flat sides** — a hexagon shows deformation far more than a circle. |
| `turbulenceStrength` | 0.075 | Peak fine displacement. |
| `bandStrength` | 0.085 | Peak band-harmonic displacement. |
| `firstHarmonic` | 2 | Band `b` drives angular harmonic `firstHarmonic + b`. |
| `deformationOctaves` / `turbulenceOctaves` | 2 / 4 | Field octaves per term. |
| `minRadiusFactor` / `maxRadiusFactor` | 0.45 / 1.85 | Hard bounds; the outline can never invert or explode. |
| `patternDriftPerSecond` | 0.035 | Stops the lobes looking pinned to fixed screen angles. |

---

## Shape

`BlobRendererConfig.shape` defaults to `RadialShapes.Organic`, a free-form abstract outline.
It is not a circle, and nothing in the system assumes it is one.

| Want | Use |
|---|---|
| A different abstract form | `RadialShapes.organic(seed = ...)` |
| Rounder / lumpier | `organic(irregularity = 0.2f)` / `organic(irregularity = 0.6f)` |
| Busier outline | `organic(harmonics = 8, lowestHarmonic = 3)` |
| An outline that never settles | `RadialShapes.driftingOrganic(count, secondsPerForm)` |
| A neutral reference | `RadialShapes.Circle` |
| A named primitive | `polygon`, `star`, `superellipse`, `superformula` |
| A designer's outline | `RadialShapes.sampled(radii)` |

Shapes with flat sides show deformation far more than round ones, so lower
`PolarProfileConfig.deformationStrength` when you switch to a polygon or a star — the demo
uses 0.16 and 0.15 against the default 0.22.

## Renderer configs

`BlobRendererConfig` — `shape`, `sampleCount`, `glowLayers` (3), `innerContours` (2),
`drawRim`, `drawSpecular`, light direction. Presets: `.Economy` (64 samples, 1 glow layer, no
contours, no specular) and `.Rich` (160 samples, 4 glow layers, 3 contours).

`WaveRibbonConfig` — `ribbons` (4), `sampleCount` (96), `amplitudeFraction`, `fillBody`,
`featherEnds`.

`ParticleAuraConfig` — wraps `ParticleFieldConfig` (`capacity` 160, `emissionRate` 110/s,
lifetimes, speeds, `drag`, `turbulenceAcceleration`). Presets `.Economy` (48) and `.Rich` (320).

`ShaderGlowConfig` — `spread` (2.2), `strength`, `fallbackLayers` (3).

---

## Colour

```kotlin
VisualPalette(
    gradient = Palette(listOf(
        ColorStop(0.00f, 0xFF0B1B3A.toInt()),   // calm
        ColorStop(0.35f, 0xFF14449E.toInt()),
        ColorStop(0.70f, 0xFF2E86FF.toInt()),
        ColorStop(1.00f, 0xFF9FE0FF.toInt()),   // peak
    )),
    glow = Palette(listOf(/* separate ramp, alpha carries the bloom */)),
    backgroundArgb = 0xFF04070F.toInt(),
)
```

Bundled: `OceanBlue` (the dark-blue-to-light-blue treatment), `Ember`, `Graphite`.

Stops are interpolated in **Oklab** and baked into a 128-entry LUT at construction, so
sampling per particle or per outline segment is an index and one lerp. Position 0 is silence,
1 is peak energy; `colorMix` indexes it and is rate-limited upstream, so a palette can be as
high-contrast as you like without any risk of flashing.

Design advice: move **lightness and chroma** far more than hue. The `OceanBlue` ramp barely
changes hue, so the transition reads as the object being lit from within rather than as a
colour change. That is most of the difference between a premium treatment and a disco light.
