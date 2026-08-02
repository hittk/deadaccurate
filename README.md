# DeadAccurate

A native Android timegrapher: point a piezo contact microphone at a mechanical
watch and see, in real time, how accurately its escapement is beating.

Built with Kotlin and Jetpack Compose on top of a low-latency AAudio capture
engine, with a software noise gate to isolate the escapement's acoustic
signature from background noise.

## Status

**M1 — capture pipeline (code-complete).** The AAudio capture path is
implemented end to end: unprocessed low-latency input (exclusive→shared
fallback), wired-headset/piezo routing with disconnect handling, the
lock-free ring buffer → DSP thread → event queue pipeline, the RECORD_AUDIO
permission flow, and a live RMS/peak level meter with stream diagnostics in
the UI. Builds, JVM tests, detekt, and host C++ tests are all green.

Still pending for M1 sign-off (needs hardware, per docs/04-milestones.md):
verifying the piezo-TRRS rig shows live level with the unprocessed preset
confirmed, and that unplugging pauses with a prompt.

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
