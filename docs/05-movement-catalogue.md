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
| Seiko 4R36 | 21,600 | |
| ETA 2824-2 | 28,800 | |
| Seagull ST3621 | 21,600 | ST36/6497 family; some variants run 18,000 — the recording will settle it |
| Seagull ST2533 | 21,600 | |
| Longines L844.4 | 25,200 | silicon hairspring |
| Omega co-axial (Planet Ocean, 8500/8900 family) | 25,200 | co-axial escapement: different impulse structure per beat — folding cares only about beat periodicity so lock should be unaffected, but the beat-error estimate deserves scrutiny on this one |

## Recorded fixtures

| Fixture | Reported watch | Measured | Status |
| --- | --- | --- | --- |
| `seiko_6r64_28800.wav` | Seiko 6R64 | locks 28,800 in ~11 s (band 2, 8–16 kHz), beat error ≈ 0.07 ms (reference app: 0.3 ms) | ✅ confirmed — initially reported as a 6R54 (21,600); the acoustic evidence said 28,800 and the owner confirmed the watch is the 6R64. A nice validation that the fold's rate identification can be trusted over a label. |

## Intake procedure

1. Record 60 s in-app (watch caseback on the mic, quiet room, phone on a
   table), share the WAV, note which watch it is.
2. Offline: `analyze_wav <file>` → verify lock matches the nominal rate,
   record rate/beat error and the band signature here.
3. Commit as `fixtures/<movement>_<bph>.wav` + manifest line.
