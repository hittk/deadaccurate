# Signal processing

The chain, in order, all operating on mono float PCM at the device sample rate
(assume 48 kHz below; nothing depends on the exact value):

```
raw frames → band-pass → envelope → noise gate → tick onset detector
           → interval tracker (bph detect) → rate estimator (s/day)
```

Every stage is plain C++, deterministic, and host-testable on WAV fixtures.

## 1. Band-pass filter

Watch tick transients carry most of their energy in the low-to-mid kHz range,
while room noise (voices, HVAC, handling rumble) dominates below ~1 kHz and
piezo resonance can spike high. A 4th-order Butterworth band-pass (biquad
cascade) at roughly **2–12 kHz** strips both ends. Corner frequencies are
compile-time constants for v1 but kept in one place — the piezo rig's real
response may justify tuning after hardware testing.

This also solves the TRRS piezo's loaded low-end response for free: we discard
that band anyway.

## 2. Envelope follower

Full-wave rectify, then a fast-attack / slow-release one-pole smoother
(attack ≈ 0.5 ms, release ≈ 5 ms). The envelope is what both the gate and the
onset detector consume; the raw band-passed signal is not used past this
point in v1. (Amplitude measurement in M5 will need the raw signal's
sub-pulse timing, which is why the stage boundary sits here.)

## 3. Noise gate

Purpose: hard-isolate tick bursts so the onset detector never sees room noise.

- **Floor estimation:** on session start and on recalibrate, track the
  envelope for ~1 s and take a high percentile (e.g. 95th) of it as the noise
  floor `N`. Percentile-over-window is robust against a stray tick landing in
  the calibration window.
- **Thresholds:** open at `N + margin + trim`, close at ~6 dB below the open
  threshold (hysteresis). `margin` defaults to ~12 dB; `trim` is the user
  slider, roughly ±15 dB.
- **Hold:** once open, the gate stays open a minimum of ~15 ms so the
  multi-pulse structure of a single tick (unlocking, impulse, drop) passes as
  one burst instead of being chopped into fragments.
- **Slow adaptation:** between ticks (gate closed), the floor estimate leaks
  slowly toward the current envelope so gradual ambient changes don't require
  manual recalibration. Adaptation freezes while the gate is open.

Gate state and threshold are reported in `LevelFrame` events so the UI meter
can show exactly what the gate is doing.

## 4. Tick onset detection

- An onset is the first envelope sample crossing the open threshold while the
  gate was closed. The timestamp is refined by looking back a few samples for
  the burst's leading edge (first crossing of a fraction of its local peak) —
  the raw threshold crossing time varies with tick loudness; the leading-edge
  refinement doesn't.
- **Refractory period:** after an onset, ignore further onsets for 60% of the
  current candidate beat period (falls back to 60% of the fastest standard
  period — 36000 bph ≈ 16.7 ms... i.e. refractory ≈ 10 ms — until a candidate
  exists). This suppresses double-triggers from a tick's internal pulses that
  outlast the gate hold.
- Output: onset frame indices, converted downstream to microseconds on the
  audio clock.

## 5. Beat-rate identification (auto-detect with override)

- Maintain a rolling window of the last ~8 s of inter-onset intervals.
- For each standard rate (18000, 19800, 21600, 25200, 28800, 36000 bph),
  score how well observed intervals fit integer multiples of that rate's beat
  period (missed ticks make intervals 2× or 3× the period — integer-multiple
  matching keeps them usable). Score = fraction of intervals within a
  tolerance (±2%) of an integer multiple, weighted toward multiple = 1.
- **Lock** when one rate scores above 0.7 and beats the runner-up by a clear
  margin for ~3 consecutive evaluations; **unlock** (back to "searching")
  when its score decays below 0.4. Hysteresis prevents flapping between
  neighboring rates on noisy signals.
- A user override (FR-4) bypasses scoring entirely and pins the rate; the
  detector keeps scoring in the background so the UI can show whether the
  override disagrees with the signal.

## 6. Rate estimation (s/day)

With a locked (or overridden) beat rate, the ideal beat period `T` is known
(e.g. 28800 bph → 250 ms... 3600·24/28800 = 3.0 s of daily drift per 104 µs
of period error — hence the precision obsession below).

- For each accepted onset `t_i`, assign the nearest ideal grid index
  `k_i = round((t_i − t_0) / T)` and record phase deviation
  `d_i = t_i − (t_0 + k_i·T)`.
- **Unwrapping:** track deviation continuously so a steadily drifting watch
  doesn't alias when `d_i` exceeds ±T/2 — carry the accumulated wrap count
  forward (the same unwrapped series drives the trace's vertical position).
- **Regression:** over a rolling window (default 30 s, min 10 s before the
  readout is shown as stable), least-squares fit `d` against `k`. The slope
  `s` (µs of drift per beat) converts as:
  `rate_s_per_day = −s · 86400 / T` (sign such that a fast watch reads `+`).
- **Outlier rejection:** robust fit — one pass, then discard points with
  residuals beyond 3× MAD and refit. Rejected onsets are still emitted as
  `TickEvent(accepted=false)` for the dimmed trace dots.
- Update the readout ~2×/s; smooth the displayed number with a short EMA so
  the last digit doesn't churn.

### Tick/tock alternation

Mechanical escapements alternate two mechanically distinct beats, so
deviations naturally form **two** parallel traces separated by the beat error.
The v1 rate regression treats all accepted onsets as one series — beat error
shifts alternate points by a constant and does not bias the slope. The trace
will visibly show two lines when beat error is present, which is correct and
is exactly the M5 entry point for the beat-error metric (difference of the
two series' intercepts).

## 7. Accuracy budget

| Error source | Magnitude | Handling |
| --- | --- | --- |
| Onset timing jitter | ~±0.2 ms/tick raw | Averaged out by regression over hundreds of ticks; sub-µs slope precision is achievable in seconds of data |
| Audio clock crystal error | ±10–30 ppm typical (≈ ±0.9–2.6 s/day!) | **Dominant absolute error.** Irreducible in software; documented honestly in-app. M5 stretch: one-time calibration factor measured against a reference (GPS-disciplined tick source or a reference watch), stored in settings |
| Thermal/clock drift during session | small vs. crystal offset | Ignored in v1 |
| Missed/spurious ticks | signal-dependent | Integer-multiple interval matching + robust regression |

The practical consequence: DeadAccurate is excellent for *relative* work
(regulating a movement toward its own zero, comparing positions) out of the
box, and its *absolute* accuracy matches the phone's audio crystal until the
M5 calibration lands. The UI copy must not overclaim.

## 8. Test fixtures

- **Synthetic WAVs:** generated tick trains at each standard bph with
  configurable rate offset, beat error, noise floor, missed ticks, and
  impulse shape. Golden tests assert detected bph, s/day within tolerance,
  and outlier counts.
- **Real recordings:** short unprocessed captures from the piezo rig of at
  least two real movements (one clean, one faint/noisy), checked into
  `engine/src/test/fixtures/` with known-good reference readings from a
  commercial timegrapher for cross-checking.
- Host-built DSP (see architecture §2) runs these in ctest; no device needed.
