# Source map (study v1)

Reference: bartchives - ATP Transit, "BART Fleet of the Future - Full Sound (Oakland Wye to Coliseum)", https://www.youtube.com/watch?v=gQcK3jDiiMw, 618 s. Onboard recording with the car end door locked open. Not a calibrated exterior measurement. No speed telemetry exists; every mode below is a candidate inferred from chapter labels and the audio, not a measured state.

Audio used: `reference/gQcK3jDiiMw.wav`, 48 kHz, mono, PCM_16, 618.432 s (SHA256 verified against SHA256SUMS.txt). The compressed original `gQcK3jDiiMw.webm` is stereo Opus at 48 kHz; the WAV is its mono downmix. All times in this folder are seconds into that WAV. The 22 s study excerpt is 330.000 to 352.000 s, so reconstruction second n = source second 330 + n.

How the audio was inspected: numerically only. Spectrograms (8192-point Hann, 10 ms hop), a Viterbi ridge tracker with a harmonic-sum score, per-band level envelopes and BS.1770 loudness. Nothing in this folder has been listened to by me; "confident" and "uncertain" below are numeric prominence classes (dB of the ridge above the local frequency-median floor), not perceptual judgements.

## Chapter map

| Start-end (s) | Chapter | Candidate mode | Contamination seen or suspected | Used here | Confidence |
|---|---|---|---|---|---|
| 0-112 | Screeching in the Oakland Wye | low-speed curves, mixed | heavy wheel squeal per title | no | n/a |
| 112-167 | Departing Lake Merritt | power from rest, then approach to cruise | rolling noise dominates after ~126 s; bumps | corroboration of the motor tone | see below |
| 167-204 | A15 Interlocking | unknown (interlocking does not imply coast) | switch/crossing bumps likely | no | n/a |
| 204-292 | Fruitvale Aerial | unknown, likely cruise or coast | "howling" per description | no | n/a |
| 292-328 | Stopping at Fruitvale | braking | brake squeal per description | no (spectrogram rendered only) | n/a |
| 328-429 | Departing Fruitvale | power from rest (328-~348), then unknown | HF bumps at 340.5-340.8 and 342.0-342.3 s; no squeal identified in 330-352 | study excerpt 330-352 | see below |
| 429-472 | A25 Interlocking | unknown | switch bumps likely | no | n/a |
| 472-512 | Stopping at Coliseum | braking | brake squeal likely | no | n/a |
| 512-618 | Departing Coliseum | power from rest | rolling noise dominates after ~526 s | corroboration of the motor tone | see below |

## Motor tone evidence (the "whine")

The same pitched structure appears in all three departures, which is why it is treated as motor/inverter rather than track:

| Feature | Fruitvale | Lake Merritt | Coliseum |
|---|---|---|---|
| Fixed tone ~1070 Hz with harmonics at 2x, 3x, 4x, 5x while the train pulls away | 330.3-338.8 s, ridge 13-34 dB above floor | 112.3-122.3 s, 11-36 dB | 512.0-521.7 s, 11-32 dB |
| Sudden dip of the tone to ~990 Hz | 338.8-339.3 s (to 990 Hz) | 122.3-122.7 s (to 994 Hz) | 521.7-522.0 s (to ~1010 Hz) |
| Climb after the dip | 339.3-344.5 s, about 100 Hz/s, reaching ~1450-1500 Hz, 11-18 dB | 122.7-127.5 s, about 95 Hz/s, reaching ~1450 Hz, 10-15 dB | 522.0-526.0 s, about 90 Hz/s, reaching ~1380 Hz, 7-14 dB (weaker; a second steeper line 1900-2800 Hz coexists at 522-524 s) |
| Plateau / slowing climb | 344.5-348.3 s at 1500-1700 Hz, only 5-9 dB (uncertain) | 131.5-137.5 s at 1500-1760 Hz, 7-16 dB (confident in places) | not resolved |
| Afterwards | unsupported; tracker falls to a flat ~960 Hz stripe | same fall to ~930 Hz at 138 s | same fall to ~910 Hz at 527 s |

The flat 900-1000 Hz stripe that all three tracks fall onto after the plateau coincides with the 6th harmonic of a fixed ~160 Hz line that is present continuously (including around the stop) and reads as an auxiliary hum, with visible harmonics near 480, 640, 800 and 960 Hz once the rolling noise rises. It is therefore treated as background, not motor, and the whine mask is gated off after 348.3 s. This is an inference from spacing, not a confirmed source.

Interpretation offered, not proven: a fixed-carrier inverter phase at low speed, a switch to a speed-locked (synchronous) phase at the dip, after which the audible switching harmonic rises with motor speed. Consistent across three departures; the physics is not needed for the study and is not relied on.

Not tracked and left in the residual: a weak fixed line near 430 Hz (330-335 s) and the ~160 Hz aux line and its harmonics.

## Whine extraction actually used (Fruitvale 330-352)

Source STFT (8192 Hann, 1024 hop) multiplied by a mask of Gaussian bands (sigma 2.2 bins, widened during the glide) centred on k x f0(t) for k = 1..5. f0(t) is the tracked ridge (median 90 ms, then 60 ms mean; the dip survives). Each harmonic band is gated by its own smoothed prominence (smoothstep from 4 dB to 8 dB) and harmonics are also gated by the fundamental's support. Phase is the source's own, so no oscillators and no chirps are introduced. The stem is source-derived tonal audio, including whatever noise lay inside the narrow bands.

Support classes along the track (see `analysis/source_with_track.png`):
- confident (ridge >= 8 dB): 330.3-344.5 s
- uncertain (5-8 dB): 344.5-348.3 s, plateau 1500-1700 Hz, kept in the stem at natural level because Lake Merritt shows the same plateau more clearly
- gated off: after 348.3 s (0.5 s fade), and before 330.3 s where the tone has not yet started

## Background bed

Stable window chosen by scanning 2 s windows of the residual (source minus whine) for the smallest 100 ms level range and HF range: 344.75-346.75 s (broadband range 3.8-4.5 dB, HF range 5.8-7.6 dB, no bump bursts). This coincides with the window tried in the earlier work, chosen here independently. Bed = white noise (seed 20260914) through a linear-phase FIR whose magnitude is the residual's mean magnitude spectrum in that window (mask notches refilled from a 60 Hz median), scaled to the window's RMS. Fully synthetic noise with a source-derived spectrum; contains no bumps, dips or squeal by construction. It is stationary in shape.

Bed level over the excerpt: a monotone smooth envelope (1 s 20th-percentile of the residual level, isotonic non-decreasing fit, 1.5 s smoothing), 0 dB at the window: -7.2 dB at 330 s, -4.4 dB at 335 s, -1.6 dB at 340 s, 0 dB from 344.75 s, +0.4 dB at 352 s. Chosen so the bed swells with the departure as the source does but cannot dip. A constant-level bed is a one-line change if preferred.

## Cruising and approach evidence (added after the first ear judgement)

| Source s | Chapter | Candidate mode | Pitched content | Broadband | Confidence |
|---|---|---|---|---|---|
| 403-420 | Departing Fruitvale (late) | cruise | thin steady line ~1690 Hz, 10-16 dB above floor; bends down from 421 s | -17 dB, stable | moderate |
| 365-380, 398-402 | same | cruise or light power | intermittent ~1620 Hz line, 7-12 dB | -17 dB | low |
| 229-239 | Fruitvale Aerial | cruise | 1500/2250/3000 Hz family, 11-13 dB | -17 dB | moderate |
| 239-254 | Fruitvale Aerial | slowing (coast or brake) | those lines glide down to ~1300 Hz | -18 to -20 dB | moderate that it slows; mode unknown |
| 254-292 and 444-472 | Aerial approach; A25 | slowing to a stop | fixed 440/850/1312 Hz howl, up to 24 dB above floor | falls to -25 dB | high that it recurs; source of the howl unknown (structure, wheel or inverter) |
| 150-166, 167-195, 380-400 | Lake Merritt late, A15, Fruitvale late | cruise or coast | none found; 160 Hz hum harmonics as stripes | -16 to -18 dB, bumps at switches | moderate |

Bed source window for v1b: 343.0-347.0 s of the residual (broadband range 3.7-5.7 dB, HF range 3.7-7.6 dB, no bump bursts).

## Cruise and slowing evidence, user-anchored (2026-09-14)

User states: 228-239 s is cruising at max speed; 239-254 s is the train slowing.

| Source s | Tone family (Hz) | Prominence | Broadband | Used for |
|---|---|---|---|---|
| 228-232 | 1435-1475 and partners, less clean | 5-10 dB | -17 dB | start of the cruise excerpt only |
| 233-238 | 750 / 1500 / 2250 / 3000, 1500 strongest | 11-17 dB | -17 dB, stable | cruise whine stem, cruise loop 234-238, bed window 234.0-237.5 |
| 239-253 | same family gliding down in proportion; 1500 reaches ~1050 by 253 s | 6-11 dB | -18 to -20 dB | slowing sweep shape (pitch proportional to speed), not yet built |
| 253-258 | jump up to ~1400 then ~1250-1350 | 13-20 dB | falling | switching-pattern step while slowing; then the 440/850/1312 approach howl takes over from ~256 s |

Speed in m/s is unknown for every row.

## Pack masters (bart-fotf-0.1.0): exact source spans

All masters are cut from `reference/gQcK3jDiiMw.wav` (mono downmix, 48 kHz). Tone stems are the source STFT multiplied by narrow bands on the tracked fundamental and its harmonics, phase kept; gliding tones are then resampled against a smooth fit so the fundamental becomes constant. The shipped tone loops are periodic resyntheses of those stems: median harmonic magnitudes and phases measured on the stem, written as a sum of cosines at an integer number of periods (see masters.json for the per-harmonic dB tables). The source-phase stems themselves live only in the study folder. The mechanical background is designed, not derived: three periodic noise layers with explicit responses (see README and beds_ground_up.py). Only its level versus speed relates to the recording, calibrated against the whine so whine-band ratio and background-to-whine loudness match the previous build. Loops are joined with a 50 ms equal-power crossfade placed inside the file, so the file end and start are adjacent source samples. `masters.json` lists every span, method, natural level, master gain and K-weighted loudness.

| Master | Source span (s) | Chapter and mode evidence | Coverage |
|---|---|---|---|
| power/fixed, power_steady/a_fixed | 332.0-335.6; 331.5-338.3 + 514.5-520.9 | Fruitvale and Coliseum departures, fixed 1070 Hz family under power | measured |
| power/sync_a, power_steady/b_sync_a | 339.4-344.3; + 122.6-127.4 | Fruitvale and Lake Merritt climbs, 990 to 1450 Hz, de-glided to 1200 Hz | measured 11 to 20 m/s (inferred axis), extrapolated to 24 |
| power/sync_c, power_steady/c_sync_c, brake/sync_c, brake_steady/c_sync_c, coast/cruising_c | 253.4-258.0 | Fruitvale Aerial, slowing (user), 1400 to 1280 Hz family de-glided to 1350 Hz | borrowed for power; only 4.2 s exists |
| power/cruise_b, power_steady/d_cruise_b, coast/cruising_b | 234.0-237.95; 232.7-238.6 | cruise at max speed (user: 40 m/s), 750/1500/2250/3000/3750 Hz family | measured at 40; used 28 to 40 |
| brake/fixed, brake_steady/a_fixed | 300.0-303.6; 297.6-307.2 + 477.6-487.2 | both stops, fixed family under braking | measured |
| brake/sync_a, brake_steady/b_sync_a | 292.8-296.7 + 472.0-476.7 | both stops, descent 1300 to 1000 Hz de-glided to 1200 Hz | measured (about 14.5 to 11 m/s inferred) |
| brake/cruise_b, brake_steady/d_cruise_b | 239.6-252.6 | the slowing glide (user), 1500 to 1080 Hz de-glided to 1500 Hz | measured as slowing; braking demand not proven |
| coast/cruising_a | 405.0-418.5 | late Fruitvale departure, faint 1690 Hz whine at cruise, de-glided to 1200 Hz | measured (about 19 m/s inferred) |
| coast/aux_hum | 328.4-330.15 | standing at Fruitvale, 160 Hz family | measured |
| power_steady/e_load_texture, brake_steady/e_load_texture | 339.7-344.0 and 297.7-307.2 residual, 1.5 to 6 kHz | high band of the rolling noise under load | design choice, low level |

| coast/rumble, coast/roll, coast/air | none | designed components | level versus speed calibrated against the whine; spectra designed |

Excluded on purpose: the brake squeal at 325-327 s and 507-509 s, the low warbling friction-brake tones at 313-324 s and 491-505 s, the Oakland Wye screeching, the 440/850/1312 Hz approach howl (mode unresolved), and all rail bumps at switches.
