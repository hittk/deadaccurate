# Architecture

## 1. Overview

Two layers with a narrow JNI seam between them:

```
┌───────────────────────────────────────────────────────────┐
│  :app  (Kotlin, Jetpack Compose)                          │
│                                                           │
│  Compose UI ── TimegrapherViewModel ── SettingsRepository │
│                      │  StateFlow<UiState>                │
│                      │                                    │
│              AudioEngine (Kotlin facade, JNI boundary)    │
└──────────────────────┼────────────────────────────────────┘
                       │ JNI: start/stop/config + event queue drain
┌──────────────────────┼────────────────────────────────────┐
│  native engine  (C++, NDK)                                │
│                                                           │
│  AAudio input stream (callback, high-priority thread)     │
│        │ lock-free SPSC ring buffer (raw float frames)    │
│  DSP thread: band-pass → envelope → noise gate →          │
│              tick detector → rate estimator               │
│        │ lock-free SPSC event queue                       │
│  events: TickEvent / LevelFrame / EngineStatus            │
└───────────────────────────────────────────────────────────┘
```

Everything real-time-critical lives in C++ where there is no garbage
collector; everything user-facing lives in Kotlin/Compose. The JNI surface is
deliberately tiny: configure, start, stop, and drain a queue of plain-data
events.

## 2. Module layout

Single Gradle project, two modules:

- **`:app`** — Compose UI, ViewModel, DataStore settings, permission flow,
  audio device monitoring (`AudioDeviceCallback`), and the `AudioEngine`
  Kotlin facade that owns the JNI handle.
- **`:engine`** (Android library with NDK/CMake) — all C++ code: AAudio
  stream management, ring buffer, DSP chain, event queue, plus the JNI
  bindings. Ships its DSP core as plain C++ with **no Android dependencies**
  so the identical code compiles on the host for unit tests.

Keeping DSP free of `#include <android/...>` is a hard rule: it is what makes
NFR-5 (host-testable DSP) possible.

## 3. Native engine

### 3.1 AAudio stream

- Built with `AAudioStreamBuilder`: input direction, mono, `FLOAT` format,
  device-native sample rate, `LOW_LATENCY` performance mode,
  `UNPROCESSED` input preset, exclusive-then-shared sharing mode.
- Device selection: Kotlin discovers the wired-headset device id via
  `AudioManager`/`AudioDeviceCallback` and passes it down;
  `AAudioStreamBuilder_setDeviceId` pins the stream to it. Device
  plug/unplug triggers stream disconnect (`AAUDIO_ERROR_DISCONNECTED`), which
  the engine reports as an `EngineStatus` event; Kotlin decides what to do
  (per FR-2: pause and prompt).
- The data callback does exactly one thing: memcpy frames into the SPSC ring
  buffer and return. No locks, no allocation, no JNI, no logging.

### 3.2 DSP thread

A dedicated thread (normal priority is fine; it has ~seconds of buffer slack)
drains the ring buffer in fixed-size hops and runs the chain described in
[03-signal-processing.md](03-signal-processing.md). Outputs are pushed into
the outbound event queue as three event types:

- `TickEvent { frameIndex, phaseDeviationUs, accepted }` — one per detected
  tick; `accepted=false` marks outliers rejected from rate estimation (the UI
  can render them dimmed).
- `LevelFrame { rmsDb, gateThresholdDb, gateOpen }` — ~30 Hz, drives the level
  meter and threshold overlay.
- `EngineStatus { state, detectedBph, confidence, rateSecPerDay, streamInfo }`
  — coalesced snapshot, ~5 Hz, plus on every state change (disconnect,
  unprocessed-preset fallback, etc.).

All timing is expressed in **sample frames**, converted to microseconds using
the stream's sample rate. The audio clock is the sole timebase — wall-clock
time is never mixed into measurements.

### 3.3 JNI surface

```
nativeCreate(config): Long            // returns engine handle
nativeStart(handle, deviceId): Int
nativeStop(handle)
nativeDestroy(handle)
nativeSetGateTrimDb(handle, trimDb)
nativeRecalibrateGate(handle)
nativeSetBphOverride(handle, bphOrZero)
nativeDrainEvents(handle, buffer): Int  // called from Kotlin at UI cadence
```

Kotlin polls `nativeDrainEvents` from a coroutine on a ~16 ms tick while the
engine runs — polling a lock-free queue is simpler and safer than calling up
into the JVM from native threads, and the event rates involved are trivial.

## 4. Kotlin layer

- **`AudioEngine`** — thin lifecycle-safe facade over JNI; exposes
  `Flow<EngineEvent>` and suspend start/stop. Owns permission-state checks
  and translates `AudioDeviceInfo` into engine device ids.
- **`TimegrapherViewModel`** — the only stateholder. Folds engine events into
  a single `UiState` (trace points, rate readout, bph + confidence, level
  meter, gate threshold, input device, error/notice banners). Applies user
  intents (start/stop, trim, override, recalibrate, input select) by calling
  the facade and persisting via the settings repository.
- **`SettingsRepository`** — Jetpack DataStore (proto or preferences): gate
  trim, input preference, remembered bph override policy.
- **Trace buffer** — a fixed-capacity ring of tick dots held in the ViewModel
  (thousands of points max); exposed to Compose as an immutable snapshot per
  frame to keep recomposition cheap.

## 5. UI (Compose, Material 3)

Single activity, single primary screen plus a settings sheet:

- **Beat trace** — `Canvas` drawing the dot tape; vertical axis is phase
  deviation (wrapping), horizontal scrolls with time. Dots are drawn from the
  snapshot buffer with `drawPoints`; no per-dot composables. Target: trace
  rendering under 2 ms/frame.
- **Rate readout** — large `+X.X s/d` figure with a stability indicator;
  greyed while bph confidence is "searching".
- **BPH chip** — shows detected rate; tapping opens the override picker
  (standard rates + "auto").
- **Level meter + gate trim** — vertical meter with threshold line, slider
  for trim, recalibrate button.
- **Input indicator** — which mic is live; tapping opens input selection.
- **Permission / onboarding** — rationale screen for `RECORD_AUDIO`; a short
  "connect your piezo mic" hint with the hardware notes from the
  requirements doc.

## 6. Error and edge-case handling

| Situation | Behavior |
| --- | --- |
| Stream disconnect (unplugged mic) | Engine emits status; UI pauses session, shows "input lost" prompt |
| Unprocessed preset unsupported | Fall back to voice-recognition preset; one-time notice |
| Exclusive mode denied | Silent fallback to shared; recorded in stream info for diagnostics |
| No ticks clearing gate for >5 s | UI hint suggesting trim/recalibrate/reposition |
| bph confidence lost mid-session | Rate readout greys; trace keeps drawing against last locked grid |
| Permission revoked mid-session | Stop engine, return to rationale screen |

## 7. Build & tooling

- Gradle with version catalogs (`libs.versions.toml`), Kotlin 2.x, AGP
  current stable, CMake for `:engine`.
- ABIs: `arm64-v8a` (+ `x86_64` for emulator builds; emulator audio is not
  usable for measurement but must not crash).
- CI (GitHub Actions): assemble both modules, run JVM unit tests, run host
  C++ DSP tests via CMake/ctest. Instrumented tests kept minimal (smoke:
  screen renders, permission flow).
- Static analysis: ktlint + detekt for Kotlin; clang-format/clang-tidy for
  C++.
