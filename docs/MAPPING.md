# Audio-to-animation mapping

`AnimationController.update(features, dt)` is the only place in the system that knows about
both audio and visuals. This document is the complete specification of what it does.

Source: [`AnimationController.kt`](../visualizer-core/src/commonMain/kotlin/com/audioviz/core/anim/AnimationController.kt)
Constants: [`AnimationConfig.kt`](../visualizer-core/src/commonMain/kotlin/com/audioviz/core/anim/AnimationConfig.kt)

---

## The four principles

1. **Different sources.** `intensity`, `pulse`, `energy`, six band energies and spectral
   brightness are separate signals on separate time scales. No visual property is driven by
   just one of them.
2. **Different smoothers.** Springs where mass is wanted, attack/release envelopes where snap
   is wanted, slew limiters where flashing must be impossible, one-poles where nothing should
   ever be noticed. Because the smoothers differ, the parameters *lag each other* — and that
   staggering is what makes a set of numbers read as one living object.
3. **Different response curves.** Gamma, smootherstep and soft-knee, chosen per parameter.
4. **Different strengths.** Scale is deliberately the weakest response in the system.

---

## Notation

`f` is the incoming `AudioFeatures`; `cfg` is the `AnimationConfig`; `dt` is the frame time
clamped to `[1/480, 1/15]` s.

```
γ(x, g)          = clamp01(x)^g                          gamma curve
knee(x, k)       = x                       for x ≤ k
                   k + (1−k)·(1 − e^(−2.2·(x−k)/(1−k)))  for x > k
smootherStep(x)  = x³(x(6x − 15) + 10)                   quintic ease, zero 1st and 2nd derivative at both ends
```

The soft knee is applied to every summed parameter. Contributions are chosen to sum slightly
*above* 1 at full input, and the knee bends that sum asymptotically toward 1 rather than
clipping it. A clipped parameter is a dead parameter: it stops responding exactly when the
user is being most emphatic.

---

## Primary drives

```
intensity  = spring(2.1 Hz, ζ=0.88) ← knee(γ(f.slow, 0.85), 0.72)
pulse      = attackRelease(20 ms, 200 ms) ← γ(f.transient, 0.8)
energy     = onePole(1.6 s) ← f.sustained
```

- `γ = 0.85 < 1` expands quiet speech, so soft talking is visible without raising the gain.
- The knee at 0.72 means shouting adds very little; the visual stays controlled.
- The spring gives intensity **mass**: it does not jump to a new value, it accelerates toward
  one and settles with a slight overshoot.
- `pulse` takes a completely separate, much faster path so consonants read instantly while
  `intensity` is still climbing.

## Idle life

```
activity = clamp01(max(intensity, 0.85·pulse))
idle     = 1 − smootherStep(0, 0.28, activity)

breathPhase₁ += dt·timeScale / 5.3 s        (wrapped at 1)
breathPhase₂ += dt·timeScale / 8.9 s        (wrapped at 1)
breath   = 0.5 + 0.5·(0.62·sin(2π·phase₁) + 0.38·sin(2π·phase₂ + 1.7))
```

Two oscillators at unrelated periods: the sum never visibly repeats, which is the difference
between *breathing* and *blinking on a timer*. Each keeps its own wrapped phase so the hourly
wrap of the animation clock can never introduce a discontinuity.

## The animation clock — the most important line in the file

```
timeScale = 1 + 0.85·intensity + 0.55·pulse
time     += dt · timeScale
field.advance(dt, timeScale)
```

Louder audio does not merely make things bigger; it makes **time move faster** for the whole
visual — the noise field, the pattern drift, the breathing, everything downstream. This does
more for the sense of responsiveness than any amount of extra amplitude, and it is the main
reason the result does not read as "a shape being scaled by a number".

## Scale — deliberately the weakest response

```
scaleTarget = 1 + 0.055·γ(intensity, 1.25)
                + 0.045·pulse
                + 0.012·idle·(2·breath − 1)

pulseRise   = max(0, pulse − pulseₚᵣₑᵥ)
if (pulseRise > 0) scaleSpring.impulse(1.4 · pulseRise)     ← velocity, not target

scale = spring(2.6 Hz, ζ=0.62) ← scaleTarget
```

Maximum growth is about 11%, and measured range during loud speech is ~1%. The impulse is the
point: a rising transient is a **push**, not a new destination, so the shape recoils and
settles like something with mass. `VisualResponseTest` asserts that deformation out-responds
scale by more than 5:1.

## Deformation — the primary response

```
formTarget  = 0.16                                  ← present in silence
            + 0.46·smootherStep(0, 1, intensity)
            + 0.14·f.lowEnergy
form        = spring(1.55 Hz, ζ=0.72) ← formTarget

deformation = deadband(knee(form + 0.30·γ(pulse, 0.8), 0.75))
```

**Note where the pulse term enters.** It is added *after* the spring, not passed through it.
A 1.55 Hz spring attenuates a 4 Hz syllable rate almost completely; running the transient
through it made the shape stop looking synchronised with the voice. The spring carries the
slow *form* — how deformed the object is while someone is speaking at all — and the transient
rides on top. The same split is used for glow and turbulence.

## Turbulence, detail, wave, emission

```
turbulenceBase = attackRelease(70 ms, 300 ms) ← 0.12 + 0.40·f.highEnergy
                                                + 0.20·f.brightness·intensity
turbulence     = deadband(knee(turbulenceBase + 0.34·pulse, 0.75))

detail         = onePole(300 ms) ← 0.20 + 0.45·f.midEnergy + 0.30·intensity
waveAmplitude  = onePole(90 ms)  ← 0.10 + 0.55·f.level + 0.35·pulse
emission       = attackRelease(30 ms, 350 ms) ← 0.06 + 0.45·intensity + 0.70·pulse
```

Turbulence is driven by the *high* bands and brightness, so sibilance produces surface
shimmer; detail is driven by the *mid* bands, so vowels produce interior structure. Different
parts of the spectrum drive different parts of the visual.

## Rotation — velocity, never angle

```
rotationImpulse ·= e^(−dt / 0.55 s)
if (pulseRise > 0) rotationImpulse += 0.5 · pulseRise

ω = 0.055·(1 + 1.4·energy + 0.9·intensity)      base + energy scaling
  + 0.06·field.scalar(ROTATION)                  slow noise wobble
  + rotationImpulse                              decaying transient kick

rotation = wrap(rotation + ω·dt, 2π)
```

Integrating velocity rather than setting an angle means the object keeps its momentum through
a pause and never snaps back to a reference orientation. The noise wobble stops the rotation
from looking like a constant-rate motor.

## Position drift

```
amount = 0.012 + 0.022·intensity
driftX = onePole(450 ms) ← field.scalar(DRIFT_X) · amount
driftY = onePole(450 ms) ← field.scalar(DRIFT_Y) · amount
```

Expressed as a fraction of the reference radius, so the drift is the same *visually* at any
screen size.

## Glow — character, then a hard safety bound

```
glowBase   = attackRelease(45 ms, 240 ms) ← 0.10 + 0.50·smootherStep(0,1,intensity)
                                             + 0.18·f.highEnergy
glowTarget = knee(glowBase + 0.40·γ(pulse, 1.6), 0.75)
glow       = slewLimit(+4.5/s, −1.8/s) ← glowTarget
```

`γ(pulse, 1.6) > 1` compresses small transients and expands large ones, so glow flares on
emphasis rather than on every syllable. The slew limiter is the safety net: whatever the
inputs do, a full 0→1 swing takes at least 13 frames at 60 fps. Flashing is not expressible.

## Colour — four contributors, four time scales, then rate-limited

```
colorTarget = knee(0.26·energy            ← seconds
                 + 0.46·intensity         ← ~0.5 s
                 + 0.24·pulse             ← ~0.2 s
                 + 0.16·f.brightness·intensity, 0.75)

colorMix    = slewLimit(+1.1/s, −0.55/s) ← spring(1.1 Hz, ζ=1.0) ← colorTarget
brightness  = onePole(180 ms) ← 0.12 + 0.55·intensity + 0.30·pulse
```

A critically damped spring (ζ = 1.0) so colour never overshoots — an overshooting hue reads
as a glitch — followed by a rate limit of 1.1 per second, meaning a full traverse of the
palette takes just under a second at minimum. Colour can only ever **drift**.

`colorMix` then indexes a gradient interpolated in **Oklab** and baked to a LUT. Interpolating
in sRGB drags a deep navy through a desaturated grey on its way to light blue; Oklab keeps it
saturated and makes the *rate* of apparent change constant, so there is no dead zone in the
middle and no rush at the ends.

The bundled `OceanBlue` treatment runs `#0B1B3A → #14449E → #2E86FF → #9FE0FF`: the hue
barely moves while lightness and chroma do the work, so the transition reads as the object
being lit from within rather than as a colour change. `PaletteTest` asserts that lightness
increases monotonically and that no step between adjacent samples exceeds 0.03.

---

## The shared motion field

Every spatial variation in the system — outline deformation, interior contours, particle
drift, position wander, glow wobble, the light's position — samples the *same*
`MotionField`. That is the single biggest contributor to the result reading as one object
rather than several animations that happen to be in the same place.

```kotlin
field.loop(theta, layer, octaves)   // seamless around a closed contour
field.line(u, layer, octaves)       // along an open parameter
field.plane(x, y, layer, octaves)   // over a plane, for particles
field.scalar(channel, octaves)      // one slowly wandering value
```

Two properties earn their keep:

**Seamless closed shapes.** `loop` samples the field along a circle *in noise space*, so
`theta` and `theta + 2π` land on the same point by construction. A closed outline deformed by
it can never show a seam, whatever the parameters do. `MotionFieldTest` asserts this.

**Layered time.** Each octave carries its own phase advancing at its own rate
(`rate_i = timeRate · timeLacunarity^i`, default 0.22 and 1.85). Fine detail therefore
shimmers roughly twice as fast as the large-scale shape drifts — the signature of organic
motion, and something a single global time value cannot produce. `MotionFieldTest` asserts
that the 4-octave sample moves faster than the 1-octave one.

Each phase wraps at 256, Perlin's lattice period, so the wrap is invisible and float precision
never degrades however long the session runs.

---

## Summary table

| Parameter | Sources | Smoother | Curve | Idle value |
|---|---|---|---|---|
| `intensity` | slow envelope | spring 2.1 Hz ζ0.88 | γ0.85 + knee | 0 |
| `pulse` | transient / onset | attack 20 / rel 200 ms | γ0.8 | 0 |
| `energy` | sustained envelope | one-pole 1.6 s | linear | 0 |
| `scale` | intensity, pulse, breath | spring 2.6 Hz ζ0.62 + impulse | γ1.25 | 1.0 ±0.012 |
| `deformation` | intensity, low band, **pulse** | spring 1.55 Hz + post-add | smootherstep, knee | 0.16 |
| `turbulence` | high band, brightness, **pulse** | attack 70 / rel 300 + post-add | knee | 0.12 |
| `detail` | mid band, intensity | one-pole 300 ms | linear | 0.20 |
| `waveAmplitude` | level, pulse | one-pole 90 ms | linear | 0.10 |
| `emission` | intensity, pulse | attack 30 / rel 350 ms | linear | 0.06 |
| `rotation` | energy, intensity, noise, impulses | velocity integration | linear | 0.055 rad/s |
| `driftX/Y` | noise field, intensity | one-pole 450 ms | linear | ±0.012 |
| `glow` | intensity, high band, **pulse** | attack 45 / rel 240 + slew | smootherstep, γ1.6, knee | 0.10 |
| `opacity` | intensity | one-pole 350 ms | linear | 0.86 |
| `blur` | idle, pulse | one-pole 250 ms | linear | 0.11 |
| `colorMix` | energy, intensity, pulse, brightness | spring 1.1 Hz ζ1.0 + slew | knee | 0 |
| `brightness` | intensity, pulse | one-pole 180 ms | linear | 0.12 |
