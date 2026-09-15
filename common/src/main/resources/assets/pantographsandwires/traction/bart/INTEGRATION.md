# Integration notes, bart-fotf-0.1.0

## Installation
Copy the whole `bart-fotf-0.1.0/` folder into the player's pack location. `curves.csv` and `player_settings.json` reference every WAV by its relative path without extension. Convert the WAVs to Ogg Vorbis yourself if the shipping player needs it, then recheck loop joins and peak levels after conversion; Vorbis can add pre-echo and a few samples of encoder delay at loop boundaries, which the loop-join measurements in `validation.json` do not cover.

## Layers actually shipped

| Family | Layer | Speed rows | Pitch law | What it is |
|---|---|---|---|---|
| power | `power/fixed` | 0 to 11.5 | 1.0 | fixed 1070 Hz inverter family from the Fruitvale departure |
| power | `power/sync_a` | 10.5 to 24.5 | 90 v Hz (1200 Hz master) | de-glided departure climb, Fruitvale |
| power | `power/sync_c` | 23.5 to 28.5 | 50 v Hz (1350 Hz master) | slowing-glide family 253 to 258 s, borrowed for power |
| power | `power/cruise_b` | 27.5 to 40 | 37.5 v Hz (1500 Hz master) | cruise family at 40 m/s (user anchor) |
| brake | `brake/fixed` | 0 to 11.5 (silent below 1, fading in 1.5 to 3) | 1.0 | fixed family from the Fruitvale stop |
| power, brake | `sb_k{1..3}m{1..3}{u,l}` (14 per family) | same rows as the family's fixed layer | (k x 1070 ± m x 9.5 v) / f_ref, linear in v | PWM sideband lines around the fixed carrier harmonics; their spacing follows motor speed, so as the train slows they slide onto the carrier and beat with it (the helix in the stop spectrograms) |
| brake | `brake/sync_a` | 10.5 to 24.5 | 90 v Hz | de-glided braking descent, Fruitvale stop |
| brake | `brake/sync_c` | 23.5 to 28.5 | 50 v Hz | same source as power/sync_c |
| brake | `brake/cruise_b` | 27.5 to 40 | 37.5 v Hz | de-glided slowing glide 239.6 to 252.6 s |
| power_steady | `a_fixed`, `b_sync_a`, `c_sync_c`, `d_cruise_b` | 4.5 (zero guard) to 40 | as the matching sweep layer | longer loops of the same sources, mirrored volumes |
| brake_steady | same four names | 4.5 to 40 | as the matching sweep layer | longer loops from the stops and the glide |
| coast (ducked) | `coast/cruising_a` | 10.5 to 24.5 | 90 v Hz | faint 1690 Hz cruise whine from 405 to 418 s |
| coast (ducked) | `coast/cruising_c` | 23.5 to 28.5 | 50 v Hz | pattern C content |
| coast (ducked) | `coast/cruising_b` | 27.5 to 40 | 37.5 v Hz | the cruise checkpoint tone |
| coast (mechanical) | `coast/rumble` | 0 to 40 | 0.85 to 1.2 | designed structure-borne rumble, 55 Hz body |
| coast (mechanical) | `coast/roll` | 0 to 40 | 0.8 to 1.3 | designed wheel-rail roll band, 600 Hz, kept out of 150-400 Hz and the whine band |
| coast (mechanical) | `coast/air` | 4 to 40 | 0.9 to 1.05 | designed aerodynamic air band, 1.5 kHz, steep 4 kHz low-pass |

Every layer boundary is an equal-power crossfade written into the CSV at quarter points. The player still interpolates linearly. The pattern changes at 11, 24 and 28 m/s are deliberate audible steps in pitch (the 1070 to 990 Hz dip at 11 m/s is the one the user approved); their level is matched across the crossfade so the loudness step stays small.

## Combination rules the player must implement
Exactly those in `player_settings.json` and the handoff's PLAYER_RULES.md. In short:
- Mechanical layers play in every motion mode at CSV gain, no demand scaling, read heads preserved.
- Cruising layers play whenever moving at CSV volume times (1 - 0.85 max(P, B)), using total family gains before steady partitioning.
- Power sweep layers at P (1 - SP), power_steady at P SP; brake likewise with B and SB. Linear amplitude partition, not equal power.
- Steady a to e components are simultaneous, independently advancing loops. Never restart them together. Advance virtual read heads while inaudible.
- Mode persistence, mode blend, slope swell, mode end and mode power persistence, and the steady entry and exit rules are unchanged from the template; the values are the template's and are not BART measurements.

## Changes from the template that need a conscious decision
- The four template mechanical layers are replaced by three designed layers: `coast/rumble`, `coast/roll`, `coast/air`. No gear or hum assets.
- Three ducked cruising layers instead of one; `ducked_layers` lists all three. No other behaviour change.
- Steady families have four tone components (a to d) plus the 14 sideband lines, not three. Each is byte-identical to its sweep counterpart, so with all loops started together their read heads stay phase-aligned and the steady blend is a pure gain crossfade; if the player ever starts them at different offsets the blend will beat briefly at the blend rate only.
- A global volume correction of 0.712 (-2.95 dB) is baked into `curves.csv` so every volume is at most 1.

## Events
None. No one-shots, horns or announcements.

## Speed model (inferred, not measured)
Only one speed anchor exists: the user's statement that the 228 to 239 s cruise is 40 m/s. Everything else on the speed axis is inference from the tone structure and from BART's published initial acceleration of about 1.3 m/s²:
- 0 to 11 m/s: fixed 1070 Hz family (the departure shows it for the first 8.5 s; 8.5 s at 1.3 m/s² is 11 m/s), with PWM sidebands at k x 1070 ± m x Delta, Delta = 9.5 v Hz (measured 7.7 to 9.7 Hz per m/s on the departure; the braking stop fits the same law). Sideband levels relative to each carrier harmonic were measured on the stop (brake family) and the departure (power family) and are stored per file in masters.json.
- 11 to about 20 m/s: pattern A, 90 Hz per m/s (measured in power up to about 1700 Hz, in braking from 1300 down to 1000 Hz, and the 1312 Hz station-approach tone fits 14.6 m/s).
- 20 to 24 m/s: pattern A extrapolated.
- 24 to 28 m/s: pattern C, 50 Hz per m/s, seen only in the slowing glide from 253 to 258 s.
- 28 to 40 m/s: pattern B, 37.5 Hz per m/s, measured in the slowing glide and at cruise.
The speeds where the drive changes pattern (11, 24, 28) and everything above 20 m/s in power are extrapolations. Sound above 28 m/s under power reuses the cruise family; no powered 40 m/s reference exists.

## Known player requirements
- Cubic or better interpolation for pitched playback; the previews used Catmull-Rom.
- Loops must wrap without fades; tone WAVs are exact integer periods and bed WAVs are periodic by construction.
- The steady detector in the previews is a simulation of the documented rules, not the game's classifier.
