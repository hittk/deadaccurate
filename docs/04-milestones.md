# Milestones, testing, and risks

## Milestones

Each milestone ends in a demoable, mergeable state.

### M0 — Project scaffold
Gradle project with `:app` and `:engine` modules, version catalog, Compose +
Material 3 wired, CMake/NDK building an empty engine with a JNI smoke call,
CI running assemble + unit tests + ktlint/detekt/clang-format, README updated.
**Done when:** a checkout builds on CI and a blank screen runs on a device.

### M1 — Capture pipeline and level meter
AAudio stream with the FR-1 configuration, device routing for wired headset
vs built-in mic (FR-2), permission flow, ring buffer, DSP thread skeleton,
`LevelFrame` events, live level meter in Compose.
**Done when:** plugging the piezo rig in shows its live level with the
unprocessed preset confirmed in stream info; unplugging pauses with a prompt.

### M2 — Noise gate and beat trace
Band-pass, envelope, auto-calibrating gate with trim slider and recalibrate,
tick onset detection, `TickEvent` stream, scrolling dot trace rendering.
No bph logic yet — trace plots against a manually chosen rate.
**Done when:** a real watch on the piezo draws a recognizable line trace and
the golden tests for gate + onset detection pass on synthetic fixtures.

### M3 — Beat-rate detection and rate readout
Interval tracker with lock/unlock hysteresis, manual override UI, unwrapped
phase tracking, robust regression, s/day readout with stability indicator.
**Done when:** synthetic fixtures at all six standard rates auto-lock and read
within ±0.3 s/day of ground truth (excluding clock error), and a real
movement's reading is stable within ~10 s and cross-checks against a
commercial timegrapher to within the clock-error budget.

### M4 — Polish and hardening
Settings persistence (DataStore), edge-case handling table from architecture
§6, onboarding/hardware-hint screens, empty/error states, dark theme,
performance pass (trace render budget, zero underruns), accuracy copy per
signal-processing §7.
**Done when:** the v1 requirement list is fully green and a cold user can get
a reading without instructions beyond the onboarding hints.

### M5 — Stretch (post-v1, in priority order)
1. **Beat error (ms)** — split tick/tock series, difference of intercepts.
2. **Clock calibration factor** — settings flow to correct the crystal ppm
   against a reference; unlocks true absolute accuracy.
3. **Amplitude (°)** — sub-pulse detection on the raw band-passed signal +
   lift-angle input. Hardest; needs very clean signals.
4. Session export/history.

## Testing strategy

| Layer | How |
| --- | --- |
| DSP (C++) | Host-built via CMake/ctest on synthetic + recorded WAV fixtures; golden assertions on bph lock, s/day, outlier counts (signal-processing §8) |
| Kotlin logic | JVM unit tests: ViewModel state folding, settings repository, event decoding across the JNI seam (fake engine) |
| UI | Compose UI tests for the main screen states (searching, locked, input lost, permission); screenshot tests optional |
| Device | Manual matrix per milestone: Pixel-class reference device + one non-Pixel; piezo-TRRS rig, USB-C dongle, built-in mic; quiet room + noisy room |
| CI | GitHub Actions: assemble, JVM tests, host DSP tests, lint/format — every PR |

Emulators are for UI smoke only; audio measurements are meaningless there.

## Required before M1 (hardware checklist)

- [ ] Piezo disc wired to TRRS (CTIA) plug — the reference input rig
- [ ] USB-C→3.5mm dongle **with ADC + mic support** (verified, not assumed)
- [ ] Reference Android device, minSdk-compatible (Android 12+)
- [ ] At least two mechanical watches (one strong signal, one faint)
- [ ] Access to a commercial timegrapher reading for cross-checking (or a
      known-regulated movement)

## Risks

| # | Risk | Impact | Mitigation |
| --- | --- | --- | --- |
| R-1 | Device applies DSP despite UNPROCESSED preset (OEM variance) | Mangled transients, poor detection | Detect via property check + fallback preset (FR-1); keep detection robust to softened transients; test on non-Pixel hardware early (M1) |
| R-2 | Piezo/TRRS electrical mismatch yields too little signal on some phones | Primary input unusable | Level meter makes this visible immediately; gate trim extends reach; document known-good rig wiring; built-in mic fallback always available |
| R-3 | Faint movements never clear a gate set above room noise | App useless for small/dressy watches | Manual trim range is generous (±15 dB); "no ticks" UI hint; M2 fixture set includes a faint-signal case so the floor of usability is measured, not guessed |
| R-4 | Audio crystal ppm error (±1–3 s/day) read as app inaccuracy | Trust damage vs commercial timegraphers | Honest accuracy copy in-app (§7); calibration factor is the top M5 item after beat error |
| R-5 | USB-C dongles without ADC silently provide no mic path | Support confusion | Onboarding hint lists the requirement; input indicator shows what's actually routed |
| R-6 | AAudio exclusive mode unavailable / underruns on some devices | Latency jitter (harmless to accuracy — timestamps come from sample position, not delivery time) | Shared-mode fallback is automatic; document that measurement quality is unaffected |

Note on R-6: because all measurement timing derives from **sample frame
position** within the stream rather than callback arrival wall-time, buffer
size and scheduling jitter do not affect accuracy — only UI responsiveness.
This is a deliberate architectural property, worth preserving through every
refactor.

## Definition of v1 done

All of FR-1…FR-7 and NFR-1…NFR-6 satisfied; M0–M4 complete; cross-check
against a commercial timegrapher documented in the repo; no known crash or
ANR on the reference devices.
