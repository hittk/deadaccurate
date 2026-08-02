# DeadAccurate

A native Android timegrapher: point a piezo contact microphone at a mechanical
watch and see, in real time, how accurately its escapement is beating.

Built with Kotlin and Jetpack Compose on top of a low-latency AAudio capture
engine, with a software noise gate to isolate the escapement's acoustic
signature from background noise.

## Status

**M4 — v1 code-complete.** All planned v1 functionality is implemented
and verified in software: the full measurement chain (band-pass →
envelope → auto-calibrating noise gate → tick detection → beat-rate
auto-lock → robust s/day regression), the live tape and rate readout,
DataStore-persisted settings (gate trim, rate override, input preference,
onboarding), the input selector with wired/built-in switching, the
no-ticks hint, permission-revocation handling, first-run hardware
guidance with the honest audio-clock accuracy disclosure, and dynamic
Material You theming. Debug and minified release builds, 17 JVM tests,
45 host C++ golden tests, and detekt are all green.

**M5 progress:** beat error (ms) and the clock calibration factor are
implemented, plus **WAV replay** — "Analyze a recording (WAV)" runs any
recorded file through the exact same DSP chain as live capture, so the
full pipeline (gate, lock, s/day, beat error, trace) can be exercised
with real watch recordings before a piezo rig exists.

What separates code-complete from **v1 done** (docs/04-milestones.md) is
hardware verification: live level from the piezo-TRRS rig with the
unprocessed preset confirmed, a real movement drawing a clean trace, and
a rate cross-check against a commercial timegrapher (which is also how
the clock calibration value gets measured). Remaining stretch work (M5):
amplitude, session export.

### Building

```sh
./gradlew assembleDebug          # Android app (needs SDK + NDK)
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew detekt                 # Kotlin static analysis

# Host C++ core tests (no Android SDK needed):
cmake -S engine/host -B engine/host/build
cmake --build engine/host/build
ctest --test-dir engine/host/build --output-on-failure
```

## Documents

| Document | Contents |
| --- | --- |
| [docs/01-requirements.md](docs/01-requirements.md) | Product scope, functional and non-functional requirements, explicit non-goals for v1 |
| [docs/02-architecture.md](docs/02-architecture.md) | Module layout, audio engine design (AAudio/NDK), threading model, UI architecture |
| [docs/03-signal-processing.md](docs/03-signal-processing.md) | The DSP chain: filtering, noise gate, tick detection, beat-rate detection, rate estimation |
| [docs/04-milestones.md](docs/04-milestones.md) | Delivery milestones, testing strategy, risks and mitigations |

## Decisions already made

These were settled during planning and are treated as fixed unless revisited
deliberately:

- **v1 metrics:** real-time beat trace and rate deviation (s/day). Beat error
  and amplitude are stretch goals, but the pipeline is designed so they slot in.
- **Beat rate:** auto-detected from the signal against the standard set
  (18000–36000 bph), with a manual override in the UI.
- **Audio input:** external piezo contact mic on the 3.5mm TRRS headset path
  (directly or via USB-C dongle) is the primary input; the built-in microphone
  is a supported fallback.
- **Noise gate:** auto-calibrates its threshold from ambient noise at session
  start; the user can trim sensitivity with a slider.
- **Platform floor:** minSdk 31 (Android 12), AAudio via the NDK with the
  UNPROCESSED input preset, Kotlin + Jetpack Compose, single-activity app.
