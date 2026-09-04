# Audio pipeline

Everything here lives in `visualizer-core` and runs on the audio thread. It has no Compose,
no platform APIs, and no knowledge of what will be drawn.

---

## Signal chain

```
PCM block (any length, any rate)
   │
   ├─ input gain
   ├─ DC blocker            y[n] = x[n] − x[n−1] + 0.995·y[n−1]
   │
   ▼
re-framing                   fixed 1024-sample window, 512-sample hop
   │                         → a stable ~94 Hz analysis rate at 48 kHz
   ▼
┌──────────────────────────────┬───────────────────────────────────────┐
│ level path                   │ spectral path (optional)              │
│                              │                                       │
│ RMS + peak → dBFS            │ Hann window → real FFT (513 bins)      │
│    │                         │    │                                  │
│    ├─ adaptive noise floor   │    ├─ 6 log-spaced bands, each with    │
│    ├─ adaptive loud ref      │    │  its own floor/ref + envelope     │
│    ├─ normalize → 0..1       │    ├─ spectral centroid → brightness   │
│    ├─ noise gate (hysteresis │    └─ spectral flux → onset strength   │
│    │  + hold + envelope)     │                                       │
│    └─ 3 envelope followers   │                                       │
│       fast / slow / sustained│                                       │
└──────────────┬───────────────┴──────────────────┬────────────────────┘
               │                                  │
               └────────► transient detection ◄────┘
                          max(envelope difference, spectral flux)
                          → expander → attack/release envelope
                                        │
                                        ▼
                                  AudioFeatures
```

---

## Why the whole level path works in decibels

Perceived loudness is roughly logarithmic. A linear amplitude mapping spends almost its whole
range on the loudest 10% of speech, so normal conversation sits crushed near zero and only
shouting moves the visual. Working in dBFS spreads speech across the usable range and — more
importantly — makes the adaptive references *mean* something: "the room tone is at −58 dB and
this speaker peaks around −20 dB" is a statement that transfers between microphones. The same
statement in linear amplitude does not.

## DC blocker

```
y[n] = x[n] − x[n−1] + R·y[n−1],   R = 0.995
```

Many phone and laptop microphones present a small DC bias, and handling noise puts a lot of
energy below 20 Hz. Both inflate RMS without being audible, so without this the visualizer
reacts to the user putting the phone down. Cutting them first is what makes every downstream
measurement mean what it claims to mean.

## Re-framing

Device buffers vary from 240 to 4096 samples. The analyzer copies input into its own sliding
window and emits an analysis frame every `hopSize` samples:

| | default | at 48 kHz |
|---|---|---|
| window | 1024 | 21.3 ms |
| hop | 512 | 10.7 ms → 93.75 frames/s |

50% overlap gives ~11 ms of analysis latency, which is comfortably below the ~40 ms at which
audio and visuals start to feel out of sync.

`dt` for every follower is `hopSize / sampleRate`, not wall-clock time. The analyzer is
therefore fully deterministic: the same PCM always produces the same features, which is what
makes `VisualResponseTest` possible.

## Adaptive normalization

Two references, tracked in dB, with deliberately asymmetric dynamics:

```kotlin
// noise floor — falls fast toward any quieter level, rises only slowly
noiseFloorDb = if (db < noiseFloorDb) {
    noiseFloorDb + (db − noiseFloorDb) · α(dt, 0.8 s)
} else {
    min(noiseFloorDb + 0.6 dB/s · dt, db)
}.coerceIn(−75 dB, −28 dB)

// loud reference — instant attack, slow decay
loudReferenceDb = if (db > loudReferenceDb) db else loudReferenceDb − 3 dB/s · dt
loudReferenceDb = loudReferenceDb.coerceIn(noiseFloorDb + 16 dB, noiseFloorDb + 48 dB)

// the mapping
openDb = noiseFloorDb + gateOpenMarginDb
level  = clamp01((db − openDb) / max(loudReferenceDb − openDb, 6 dB))
```

The floor falls quickly and rises slowly so it settles on the room tone rather than on the
gaps between words. The loud reference jumps instantly and decays slowly so it represents
"how loud this speaker has been recently" rather than an all-time maximum.

The clamps matter as much as the tracking: `minimumDynamicRangeDb` stops a monotone whisper
from being expanded until room noise looks like shouting, and `maximumFloorDb` stops a very
noisy room from pushing the floor so high that ordinary speech never opens the gate.

**`NormalizationMode.Fixed(floorDb, ceilingDb)`** turns all of this off in favour of a plain
linear window. Use it for calibrated hardware, for reproducible screenshots, and for A/B
comparing tuning changes — the adaptive mode deliberately makes two different inputs converge,
which is exactly wrong when you are trying to measure a difference.

## Noise gate

Three mechanisms, each fixing a distinct failure:

| Mechanism | Default | Fixes |
|---|---|---|
| Hysteresis | open at floor+9 dB, close at floor+5 dB | chattering when the level hovers at the threshold |
| Hold | 0.35 s | dropping out during the gaps between words |
| Attack/release envelope | 35 ms / 300 ms | the gate *switching* instead of fading |

The output is a continuous `0..1` multiplier, not a boolean. That is the difference between a
visual that settles and one that blinks.

## Envelope followers

Three followers on the same gated level, at three time scales:

| Follower | Attack | Release | Expresses |
|---|---|---|---|
| `fast` | 8 ms | 120 ms | syllables, consonants, transients |
| `slow` | 120 ms | 600 ms | sustained speech energy |
| `sustained` | 1.2 s | 3.0 s | session-scale "mood" |

Having all three is what allows the mapping layer to drive different visual properties from
different time scales, which is most of why the result reads as one organism rather than one
oscillator.

## Transient detection

Two independent views of "something just happened", combined by `max`:

**Envelope difference** — `clamp01((fast − slow) · 1.4)`. Catches loudness jumps.

**Spectral flux** — the sum of *positive* frame-to-frame changes in the magnitude spectrum:
energy appearing where there was none. Exactly what a plosive or a consonant looks like, and
unlike an amplitude derivative it fires on timbral change at constant loudness.

```
flux     = Σ max(0, m[i] − m_prev[i])
relative = flux / Σ m[i]                     ← level-independent
onset    = clamp01((relative − mean − 1.3·dev) / (mean + 2·dev))
```

Dividing by the frame's total magnitude is what stops a shouted vowel held steady from
producing a continuous onset; the detector responds to *change*, not to volume.

**The expander is what makes it a transient at all.** Continuous speech produces a steady
trickle of small onsets. Without a floor, the signal never returns to zero between syllables
and `pulse` sits permanently half-open — which looks like constant throbbing and leaves no
headroom for a real emphasis:

```
expanded = clamp01((combined − 0.20) / (1 − 0.20))
```

This one line took the measured mean `pulse` during steady speech from ~0.6 (saturated,
expressionless) to ~0.45 with a 0.35–0.60 working range. See
[TESTING.md](TESTING.md#what-the-numbers-look-like).

## Spectral analysis

**Real-input FFT.** A real signal of length N is transformed with an N/2-point complex FFT
(the standard real-input packing), halving both the butterfly count and the memory traffic
against zero-padding the imaginary part. Twiddle factors, the bit-reversal permutation and
the window are precomputed in the constructor, so `magnitudes()` performs no allocation and
no trigonometry. `RealFftTest` validates it bin-for-bin against a naive DFT.

**Log-spaced bands with per-band normalization.** In speech the 4–8 kHz band sits 25–30 dB
below the 100–300 Hz band. A shared normalization would leave the high bands permanently near
zero and the visual would never show sibilance, so each band gets its own floor/reference
tracker and its own envelope. Every band is then expressive over its own natural range.

**Spectral centroid → brightness.** `Σ(f·m) / Σm`, mapped logarithmically to `0..1`. A cheap,
robust proxy for perceived brightness that the mapping layer uses to shift colour and add
surface turbulence during sibilants.

---

## Smoothing primitives

Every follower is parameterised by a **time constant in seconds**, never by a per-frame
coefficient:

```kotlin
alpha = 1 − exp(−dt / tau)
```

A 150 ms release then behaves identically at 30 fps, 60 fps, 120 fps, and at the irregular
block rate of an audio callback. The common `value += (target − value) * 0.1f` is the single
most frequent cause of visualizers that feel different on different devices.

| Primitive | Behaviour | Used for |
|---|---|---|
| `OnePole(tau)` | symmetric first-order low-pass | energy, detail, drift, opacity |
| `AttackRelease(atk, rel)` | asymmetric: rises with one constant, falls with another | every audio envelope, pulse, turbulence, glow base |
| `SlewLimiter(rise/s, fall/s)` | hard cap on rate of change | glow and colour — makes flashing *inexpressible* |
| `Deadband(threshold)` | ignores movement inside a band | the last of the sub-perceptual frame jitter |
| `Spring(freqHz, damping)` | damped harmonic oscillator, sub-stepped | anything that should have mass: intensity, scale, deformation, colour |

**The spring is sub-stepped at a fixed 1/240 s** and its input `dt` is clamped to 100 ms. A
single-step Euler spring diverges when a frame is dropped; this one does not. `SmoothingTest`
runs it at 8 fps and asserts it still converges.

**`Spring.impulse(v)` adds velocity rather than moving the value.** That distinction is what
makes an emphasised syllable read as a physical recoil instead of a step change in size.

**Layering a slew limiter after an envelope** gives a parameter both a character and a hard
safety bound. Glow uses a 45 ms/240 ms envelope for feel and a 4.5/s rise limit for safety: a
full 0→1 swing still takes 13 frames at 60 fps, so an emphasised word flares convincingly
while a strobe is not expressible whatever the audio does. `VisualResponseTest` asserts this
bound directly against the configured value rather than against an empirical threshold.
