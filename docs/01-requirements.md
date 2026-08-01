# Requirements

## 1. Product summary

DeadAccurate turns an Android phone into a timegrapher for mechanical watches.
The user connects a piezo contact microphone (or uses the built-in mic), rests
the watch on it, and the app shows a live beat trace and the movement's rate
deviation in seconds per day.

The audience is watch enthusiasts and hobbyist watchmakers regulating their own
movements. The bar for v1 is: *good enough to regulate a healthy movement* —
stable rate readout within a few seconds of measurement on a clean signal.

## 2. Functional requirements

### FR-1 Audio capture
- Capture mono audio through **AAudio** with:
  - `AAUDIO_PERFORMANCE_MODE_LOW_LATENCY`
  - `AAUDIO_INPUT_PRESET_UNPROCESSED` (so device AGC, noise suppression, and
    echo cancellation do not mangle the tick transients)
  - Exclusive sharing mode requested first, transparent fallback to shared.
- Use the device's native sample rate (typically 48 kHz); never resample on the
  capture path.
- If the device reports no support for the unprocessed source
  (`AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` is false), fall
  back to `VOICE_RECOGNITION`, which disables most input processing on the
  majority of devices, and surface a one-time notice in the UI.

### FR-2 Input device routing
- **Primary path:** external piezo contact mic on the wired headset input —
  `AudioDeviceInfo.TYPE_WIRED_HEADSET` — whether connected to a physical 3.5mm
  jack or a USB-C analog dongle.
- When a wired headset input appears, switch to it automatically and show the
  active input in the UI. When it disappears mid-session, pause measurement
  and prompt rather than silently degrading to the built-in mic.
- **Fallback path:** built-in microphone, explicitly selectable.
- USB audio interfaces (`TYPE_USB_DEVICE`/`TYPE_USB_HEADSET`) are not a v1
  target but must not be blocked: if the platform routes one in as the default
  input, capture should still work.

### FR-3 Noise gate
- A software noise gate sits between capture and tick detection.
- **Auto-calibration:** on session start (and on demand via a "recalibrate"
  action), estimate the ambient noise floor over a short window (~1 s) and set
  the gate threshold a configurable margin above it.
- **Manual trim:** a slider lets the user move the effective threshold up or
  down around the calibrated value, for faint movements or noisy rooms.
- The gate must use hysteresis (separate open/close thresholds) and a hold
  time so a single tick's multi-pulse structure is not chopped into fragments.
- UI shows a live signal level meter with the current threshold overlaid, so
  the user can *see* whether ticks clear the gate.

### FR-4 Beat detection and beat-rate identification
- Detect tick onsets from the gated signal with a refractory period derived
  from the candidate beat rate.
- Auto-detect the beat rate by matching observed inter-tick intervals against
  the standard set: **18000, 19800, 21600, 25200, 28800, 36000 bph**.
- The user can override the detected rate at any time by picking from the same
  set; the override pins the rate until cleared.
- Show detection confidence (locked / searching) so users know when readings
  are trustworthy.

### FR-5 Rate deviation (s/day)
- Compute rate deviation by regressing detected tick timestamps against the
  ideal tick grid for the active beat rate, over a rolling window.
- Display: a large signed readout in s/day (e.g. `+4.2 s/d`), updating
  continuously, with an indication of measurement stability (e.g. readout
  greys out or shows a spinner until enough clean ticks have accumulated).
- Outlier ticks (missed beats, double-triggers, bumps) must be rejected from
  the regression, not averaged in.

### FR-6 Real-time beat trace (primary UI)
- The classic timegrapher "paper tape" plot: each detected tick is a dot;
  x advances with tick index/time, y is the tick's phase deviation from the
  ideal grid, wrapping vertically. A healthy watch draws straight line(s);
  slope = rate error.
- Renders in real time as ticks arrive, scrolling horizontally; smooth at the
  display refresh rate with no jank from the audio path.
- Trace history covers at least the rolling measurement window; clearing the
  session clears the trace.

### FR-7 Session controls
- Start / stop measurement. Stopping releases the audio stream.
- Recalibrate noise gate on demand.
- Select input (auto / wired headset / built-in mic) and override beat rate.
- Settings persist across launches (last beat-rate override behavior, gate
  trim, chosen input preference) via DataStore.

## 3. Non-functional requirements

- **NFR-1 Latency & stability:** the audio callback never blocks, allocates,
  or takes locks; zero `AAUDIO_CALLBACK_RESULT` underruns attributable to the
  app under normal operation. UI updates are decoupled from the audio thread.
- **NFR-2 Accuracy:** on a clean signal, the s/day readout stabilizes within
  ~10 seconds and is limited by the device audio clock, not by the algorithm.
  (See risk R-4: the audio crystal's ppm error bounds absolute accuracy; a
  calibration factor is a stretch goal.)
- **NFR-3 Platform:** minSdk 31 (Android 12), target latest stable SDK.
  Kotlin, Jetpack Compose (Material 3), single-activity. Native capture/DSP
  layer in C++ via the NDK (AAudio is a C API).
- **NFR-4 Permissions & privacy:** `RECORD_AUDIO` runtime permission with a
  clear rationale screen. Audio is processed in memory only — nothing is
  recorded to disk or leaves the device in v1.
- **NFR-5 Testability:** the DSP chain is deterministic and runs off-device on
  recorded/synthetic PCM in unit tests; tick detection and rate estimation
  have golden-file tests. No part of the DSP requires an Android device to
  test.
- **NFR-6 Battery/thermal:** measurement runs only while the app is
  foregrounded in v1; the stream is closed when the UI is stopped.

## 4. Non-goals for v1

Explicitly out of scope (several are designed-for but not built):

- Beat error (ms) and amplitude (°) readouts — stretch milestone M5.
- Session recording, export, or history.
- Screen-off / background measurement (foreground service).
- USB audio interface as a first-class tuned path.
- Cloud anything, accounts, analytics.
- Tablet/foldable-optimized layouts (must not crash, need not be beautiful).

## 5. Hardware notes (piezo on TRRS)

The TRRS mic input provides ~2 V bias through ~2 kΩ intended for electret
capsules. A raw piezo disc is high-impedance and will work when wired across
mic and ground, but with a heavily loaded low end and modest level — which is
acceptable here because tick energy is concentrated in the kHz range and the
gate/filters remove the low end anyway. Documentation for users should
recommend a piezo disc wired to a TRRS (CTIA) plug, and note that some
USB-C-to-3.5mm dongles omit mic support entirely (dongles with DAC+ADC are
required). This is a docs/support concern, not an app-code concern, but test
hardware must include: a known-good piezo+TRRS rig, a USB-C dongle, and at
least one Pixel-class reference device.
