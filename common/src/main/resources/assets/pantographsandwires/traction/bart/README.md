# bart-fotf-0.1.0, BART Fleet of the Future (D/E car) traction pack

First full build after the approved departure study (`fable_study_v1`, candidate A) and the user's two anchors: the 228 to 239 s cruise is cruising at 40 m/s, and the 239 to 254 s glide is the train slowing. The user asked for best effort knowing that slower speeds carry less intensity in the recording. This build is numerically validated but has not been listened to, not game-tested, and not converted to Ogg. See `validation.json` for the exact status of every check.

Read `INTEGRATION.md` for what the player must do, `SOURCES.md` for where every sound comes from, `curves.csv` and `player_settings.json` for the contract, and `previews/` for the required player renders.

## What it sounds like, by design
- From rest to about 11 m/s under power: the fixed 1070 Hz inverter whine with its harmonics, the sound the user approved in the study. Silent at 0, in by 1.5 m/s.
- At 11 m/s: the approved dip. The fixed tone hands over to a tone that starts at 990 Hz and rises 90 Hz per m/s.
- 11 to 24 m/s: that rising tone, de-glided from the real climbs so its pitch follows speed while its timbre stays the recording's own.
- 24 to 28 m/s: a short pattern from the slowing glide, then at 28 m/s the cruise family takes over, reaching the user's cruise sound exactly at 40 m/s.
- Braking mirrors it: the descending tone hands over to the fixed family at 11 m/s, which fades out below 3 m/s as the electrical brake lets go. Around the fixed carrier, in both power and brake, 14 sideband lines per family slide toward the carrier as speed falls (spacing 9.5 Hz per m/s, levels measured on the recording), which is the beating helix heard at the end of a stop.
- Under all of it, a designed mechanical background of three layers plays at every speed: a structure-borne rumble, a wheel-rail roll band and, from 5 m/s, an air layer that grows with speed. Coasting is never silent.
- Cruising while unloaded carries a faint pattern-A whine below 24 m/s and the cruise family above 28 m/s, ducked 85 percent under load.

## Files
- `power/`, `brake/`, `coast/`, `power_steady/`, `brake_steady/`: 22 mono 48 kHz 24-bit loops, peaks at -6.0 dBFS, all wrapping sample to sample.
- `curves.csv`: 201 rows, equal-power overlaps written at quarter points, global correction 0.712 applied so every volume is at most 1.
- `player_settings.json`: the template contract with pack identity and the real layer lists.
- `previews/`: the four required scenarios plus an upper-speed sweep, rendered by a simulation of the documented player rules from the shipped masters and curves with no hidden processing.
- `loudness.csv`, `loudness_checks.json`: BS.1770 table at every 0.5 m/s from 0.5 to 40, and the step, endpoint and peak checks.
- `masters.json`, `masters_check.json`, `curves_meta.json`: build metadata and per-file checks.
- `validation.json`, `SHA256SUMS.txt`.

## Previews and their timelines
| File | Timeline |
|---|---|
| preview1 | 0-2 s at rest; 2-16 s accelerate 0 to 14 at 1.0 m/s² under power; 16-46 s hold 14 on a climb (power by slope, steady load enters at about 17.3 s and is fully in by about 18.8 s) |
| preview2 | 0-8 s coast at 14; 8-38 s hold 14 on a descent (brake by slope, brake steady enters after 1.25 s and blends over 1.5 s) |
| preview3 | 0-12 s steady climb at 14; 12-22 s cap lifts, accelerate 14 to 21 at 0.7 m/s² (steady exits when speed has moved 0.5 m/s); 22-28 s hold 21 on a climb |
| preview4 | 0-4 s accelerate 10 to 14 under full power; 4-14 s hold 14 under full power (steady enters); 14-20.7 s accelerate 14 to 16 at 0.3 m/s² (steady exits); 20.7-28.7 s hold 16 |
| preview5 | 0-1 s rest; 1-41 s accelerate 0 to 40 at 1.0 m/s² under power; 41-49 s hold 40 on a climb; 49-57 s coast at 40; 57-97 s brake 40 to 0 at 1.0 m/s²; 97-98 s rest. Above 20 m/s under power and everything from 24 to 28 m/s is extrapolated timbre; 28 to 40 m/s braking is the real slowing glide |

Steady fraction in the previews is driven by the documented detector rules applied to the scenario speed, not by the game's classifier; the exact P, B, SP, SB values every 0.25 s are in `previews/previews_report.json`.

## What to listen for
1. Preview 1 first: is the departure still the approved sound, and does the hold at 14 keep the whine alive without an audible cycle over 30 s?
2. Preview 5 from 41 s: is the 40 m/s hold the cruise you identified?
3. Preview 2: does the braking descent and the fixed brake whine read as the stops in the recording?

## Revision after the third listening note (regenerative braking helix)
The stop spectrograms show slanted sideband lines converging on the fixed carrier harmonics as the train slows; two lines closing on each other beat at their difference, which is the waver. Sideband spacing scales with motor speed, not with carrier pitch, so each line is its own single-tone layer with a pitch law that slides it: `sb_k{k}m{m}{u|l}` at k x 1070 ± m x 9.5 v Hz. Levels are the measured sideband-minus-carrier medians (brake: Fruitvale and Coliseum stops averaged; power: Fruitvale departure). The departure now carries its measured sidebands too; if that changes the approved departure character, the power sideband levels are a per-file offset in `masters.json` and can be pulled back.

## Revision after the sixth listening note (background "muddy and hissy", rebuilt from the ground up)
Every earlier background was adapted from the recording's residual, and each carried part of its problems. Measured on the recording's own background at about 8, 19 and 40 m/s: below 150 Hz holds 54 to 68 percent of the energy, the 150 to 400 Hz mud region 4 to 11 percent, and above 4 kHz under 0.9 percent. The previous tilt-fit bed had smeared that into 31 to 37 percent mud and roughly twice the recording's share above 4 kHz.

The background is now three designed layers, each a periodic random-phase noise loop with an explicit response and a slow synthetic movement (definitions in `fable_study_v1/scripts/beds_ground_up.py`):
- `coast/rumble`: 55 Hz body with a 110 Hz shoulder, 28 Hz high-pass, 300 Hz low-pass, slow 0.15 to 1.2 Hz movement.
- `coast/roll`: 600 Hz band, 260 Hz 3rd-order high-pass to stay out of the mud region, 1.8 kHz 6th-order low-pass to stay out of the whine band.
- `coast/air`: 1.5 kHz band, 600 Hz high-pass, 4 kHz 8th-order low-pass so there is no hiss shelf, slow 0.08 to 0.7 Hz gusts; silent below 4 m/s.

Nothing is copied from the recording. Levels by speed were calibrated against the approved whine rather than by loudness alone, because a first loudness-matched attempt put 5 to 7 dB more energy into the whine band and would have buried it. `calibrate_bed.py` holds two targets taken from the previous build: whine-to-background ratio in 900 to 3500 Hz (roll and air levels) and background-to-whine loudness (rumble level, with a mud guard). Results, in `background_metrics.json` and `whine_band_masking.json`:

| Speed | Mud 150-400 Hz, previous / now / recording | Above 4 kHz, previous / now / recording | Whine-band ratio change |
|---|---|---|---|
| 8 m/s | 31 / 9 / 11 % | 0.56 / 0.00 / 0.23 % | 0.0 dB |
| 19 m/s | 37 / 12 / 4 % | 0.74 / 0.02 / 0.34 % | 0.0 dB |
| 39 m/s | 34 / 13 / 9 % | 1.25 / 0.14 / 0.87 % | 0.0 dB |

Known shortfalls: the mud guard stopped the rumble short at high speed, so the background sits 1.4 dB quieter relative to the whine at 27 m/s and 3.2 dB quieter at 39 m/s than in the previous build. The top end is now darker than the recording; the air share in `make_curves.py` is the lever if it sounds dull. Loudness of the background varies up to 1.1 dB with read-head position.

`previews/background_only/speed_ladder_5_14_21_30_40_BACKGROUND_ONLY.wav` holds the background alone at 5, 14, 21, 30 and 40 m/s, 6 s each.

## Revision after the fifth listening note (tunnel echoes in the background)
A spectrum copied from the recording carries the room: station canopy and tunnel reflections colour the residual with resonances and comb ripple, and envelopes measured in those windows carry any flutter of the reflections. Both were being reproduced. The beds are now fully synthetic: each keeps only the broad spectral tilt of its window (a 1/3-octave median to remove lines, then a degree-4 polynomial fit in log-frequency, which cannot hold anything narrower than about an octave), and the per-band movement is generated, not copied: periodic random envelopes with 1/f-weighted energy between 0.15 and 3 Hz at the measured standard deviations (1.2, 0.8, 0.7, 0.6 dB for the four bands). The rms deviation of each window's smoothed spectrum from its fit, the amount of room structure discarded, is stored per bed in `masters.json`; `fable_study_v1/analysis/bed_tilt_fits.png` shows raw against fit. Levels and crossover speeds are unchanged. Nothing of the recording remains in the beds except four tilt curves and four RMS levels.

## Revision after the fourth listening note (background, superseded by the note above)
The beds were stationary shaped noise and read as flat. The real background breathes: measured in four bands (20 to 300, 300 to 1000, 1000 to 3000, 3000 to 12000 Hz) on the stable windows, movement faster than about 2 s has a standard deviation of 0.6 to 1.6 dB per band, most in the lowest band. Each bed is now the same notched shaped noise with that window's own measured per-band movement imposed: the fast envelope is detrended, clipped to ±3 dB so no bump survives, stitched from random 1 s chunks with 200 ms crossfades to the loop length and wrap-crossfaded, then applied band by band to the periodic noise (filtered in its periodic steady state so the loop still wraps exactly). A fourth bed, `coast/rolling_crawl`, comes from the first four seconds of the departure, which are only 3 dB quieter overall but 8 to 10 dB duller above 1 kHz; it covers 0 to 7 m/s and hands over to rolling_low between 4 and 7. Level anchors (source-scale RMS) are the measured window levels kept monotone: -22.1 at 2.5 m/s, -18.9 at 8, -16.8 at 19, -15.0 at 40. The 19 m/s at-grade window is actually louder than the 40 m/s aerial one; that was smoothed away, so level versus speed above 19 m/s is a choice, not a measurement, and is a one-line change if the rise should be steeper.

`previews/background_only/` holds the same three scenarios with only the beds playing, at their exact mix gains.

## Speed model
Inferred, not measured, with one user anchor. Summarised in `INTEGRATION.md`; the full evidence trail is in `SOURCES.md`.

## Known limitations
- No measured power sound above about 20 m/s; 20 to 40 m/s power reuses the slowing and cruise material.
- Pattern changes at 11, 24 and 28 m/s are placed by inference; only the 11 m/s dip has a directly matching recording.
- Rolling beds come from four different track sections; their relative levels were smoothed into a monotone contour and are not a measurement of level versus speed.
- The station-approach howl and the friction-brake warble are not represented.
- Vorbis conversion, in-game behaviour and listening approval are all outstanding.

## Revisions after listening notes
First note (departure "junk noise", "singing"): I first attacked the tone stems with a narrower cleaning mask, which was wrong and made it worse ("swarm of bees around the whine"). The real cause was the beds: they were granular loops of the residual, and the residual still carried the skirts of the whine after masking, so random grain starts produced a phase-scrambled buzz at the whine's own frequencies. Changes now in force, recorded per file in `masters.json`:
- All rolling beds are stationary shaped noise: a periodic random-phase spectrum shaped by the residual's 1/6-octave-smoothed magnitude, with the tone family that lived in each window notched out ±150 Hz and bridged from the flanks, so neither a tonal line nor its skirt survives into a bed. The two load-texture layers were dropped for the same reason. This is the bed construction of the approved study candidate A.
- The extracted 160 Hz hum layer is dropped; the beds carry that region as noise.
- Tone stems are extracted as in the approved study (source STFT, ±2.2-bin bands, source phase) and gliding ones are de-glided against a smooth quadratic fit; those stems are then used only as measurements.

Second note (whine stems "waver and falter, squiggly"): the source tone moves about ±3 dB and several hertz from moment to moment, and in a 3.6 s loop that movement repeats every cycle (amplitude-modulation autocorrelation 0.66 at the loop length, against 0.20 in the non-looping study stem). Every tone loop is therefore now a periodic resynthesis: the median magnitude of each harmonic and the phases at the strongest frame are measured on the source stem, and a sum of cosines is written at exactly an integer number of periods, so the loop has no join and no waver. The harmonic tables are in `masters.json`. This is synthesised audio whose timbre numbers come from the recording; the recording's own micro-movement is not in the loops, and all movement comes from the speed law. Steady tone loops are identical in content and length to the sweep loops so that both read heads stay phase-aligned through steady blends; with periodic tones there is no natural variation to preserve, which departs from the 8 to 12 s guidance on purpose.
