# Movement catalogue

Real recordings are the ground truth this project tunes against. Each
catalogued watch gets a 60 s recording made with the app's **Record 60 s
for tuning** button, is analyzed offline with `engine/host/analyze_wav`
(lock, rate, beat error, and the per-band fold-score signature), and is
then committed to `engine/host/fixtures/` with a line in `manifest.txt` —
after which the build fails if any DSP change ever breaks that watch.

## Nominal rates (for verification against recordings)

| Movement | Nominal bph | Notes |
| --- | --- | --- |
| Seiko 6R64 | 28,800 | GMT — the first catalogued fixture |
| Seiko NH35 | 21,600 | |
| Seiko NH34 | 21,600 | GMT — recorded in the four-watch batch (the collection list originally said NH35) |
| Seiko 4R36 | 21,600 | |
| ETA 2824-2 | 28,800 | |
| Seagull ST3621 | 21,600 | ST36/6497 family; some variants run 18,000 — the recording will settle it |
| Seagull ST2533 | 21,600 | |
| Longines L844.4 | 25,200 | silicon hairspring |
| Omega co-axial (Planet Ocean, 8500/8900 family) | 25,200 | co-axial escapement: different impulse structure per beat — folding cares only about beat periodicity so lock should be unaffected, but the beat-error estimate deserves scrutiny on this one |

## Recorded fixtures

| Fixture | Reported watch | Measured | Status |
| --- | --- | --- | --- |
| `seiko_6r64_28800.wav` | Seiko 6R64 | locks 28,800 in ~4 s (band 2, 8–16 kHz), rate ≈ −0.4 s/d, beat error ≈ 0.07 ms (reference app: 0.3 ms) | ✅ confirmed — initially reported as a 6R54 (21,600); the acoustic evidence said 28,800 and the owner confirmed the watch is the 6R64. A nice validation that the fold's rate identification can be trusted over a label. |
| `eta2824_28800.wav` | ETA 2824-2 | locks 28,800 in ~4 s, rate ≈ +3 s/d, beat error ≈ 0.4–0.6 ms | ✅ confirmed. Tick energy 8–16 kHz; signal fades in and out over the minute (wrist-shadowing during recording?), which the sticky-peak tracking now rides through. |
| `longines_l844_25200.wav` | Longines L844.4 | locks 25,200 in ~10 s, rate ≈ −0.8 s/d, beat error ≈ 0.01 ms | ✅ confirmed — the first 25,200 fixture. Weakest signal of the batch; the placement knock at t≈1.8 s used to delay lock to ~36 s until the overload-floor fix. |
| `seiko_nh34_21600.wav` | Seiko NH34 GMT | locks 21,600 in ~17 s, rate ≈ −1.4 s/d (edge mode agrees: −3.3), beat error ≈ 0.1 ms | ✅ confirmed. Also locks in edge mode — the loudest, cleanest recording of the batch. Two placement knocks (t≈3.7 s, 300× ambient at t≈7.3 s) each re-floor the usable history, hence the later lock. |
| `seagull_st2533_21600.wav` | Seagull ST2533 | locks 21,600 in ~4 s (band 3, 16–21.5 kHz — nearly ultrasonic tick energy), rate ≈ **+200 s/d**, beat error noisy ≈ 0.7–2 ms | ✅ rate identified, ⚠️ the watch itself is running ~200 s/day fast — coherent across bands and confirmed by offline analysis, not a measurement artifact. Typical of a magnetized hairspring or a movement overdue for service. This watch motivated both the 4th analysis band and the rate-offset-tolerant scoring. |

## Piezo session findings (first contact-mic field test)

- NH34 and NH35 movements measure well in edge mode on the piezo.
- The ST2533 does **not** produce edge-detectable ticks even on contact
  (fixture `seagull_st2533_piezo_21600.wav`): its faint, high-frequency
  ticks reach only ~48 gate crossings a minute. Correlation mode locks
  the same recording in ~4 s at +200.8 s/d — matching the phone-mic
  measurement, so the mode switch (which the app now suggests
  automatically) is the answer for this calibre, not more coupling.
- The edge band-pass top moved 12 kHz → 21.5 kHz: the ST2533's energy
  sits at 16-22 kHz, and the wider band also raised the NH34's edge
  tick yield by ~50% with an unchanged rate reading.

## In-app watch log and movement recognition

Since 0.3.6 the app closes this loop itself: when a measurement settles
(16 consecutive valid readings spanning ≤ 0.8 s/day), a popup shows the
rate and beat error and offers to save them against a named watch. Each
save stores the acoustic signature (per-band fold scores) alongside the
numbers; labeling the watch's movement teaches the recognizer, and later
measurements of a same-rate watch are matched by cosine similarity of
their signatures ("Sounds like a NH35"). The log lives on-device in
`watch_log.json` and tracks each watch's numbers over time.

## Intake procedure

1. Record 60 s in-app (watch caseback on the mic, quiet room, phone on a
   table), share the WAV, note which watch it is.
2. Offline: `analyze_wav <file>` → verify lock matches the nominal rate,
   record rate/beat error and the band signature here.
3. Commit as `fixtures/<movement>_<bph>.wav` + manifest line.
