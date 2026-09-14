# WMATA Alstom C v5 — loudness revision of v4

Complete replacement pack. Use these WAVs, curves.csv and player_settings.json together; do not append to v4. Original v4 remains untouched. The sound identity and C spectral cuts remain, with speed-dependent level correction and improved speed-anchor overlaps. Revised audio awaits user listening approval.

## Measured results

3-second mono renders at every 0.5 m/s from 0.5 to 40 m/s, at CSV pitch/volume and full demand, measured by pyloudnorm BS.1770 integrated loudness. Steady comparisons apply to its eligible 5–40 m/s range. Cruising is silent at 0.5 m/s; its adjacent finite-value comparison starts at 1 m/s. No exceptions were needed among the finite adjacent pairs in the requested survey, including the existing 28–30 m/s upper-power timbre change. The separate offset-grid check exposes the intended cruising onset from silence above 0.5 m/s: 0.75–1.25 m/s rises by 6.45 dB. This departure fade is the named exception; the finite cruising survey begins at 1 m/s.

| Group | Largest adjacent 0.5 m/s change | Location |
|---|---:|---|
| power | 0.860 dB | 3–3.5 m/s |
| power_steady | 0.858 dB | 5–5.5 m/s |
| brake | 0.850 dB | 5–5.5 m/s |
| brake_steady | 0.850 dB | 5–5.5 m/s |
| mechanical | 0.850 dB | 2–2.5 m/s |
| cruising | 0.852 dB | 3–3.5 m/s |

Largest steady/sweep heard-mix difference: power 0.114 dB, brake 0.070 dB. At 14 m/s, power sweep/steady are -22.6225 / -22.5905 LUFS; brake sweep/steady are -23.0889 / -23.0871 LUFS. No intentional quieter-steady offset remains.

See wmata-v5-loudness.csv for all requested columns, validation.json for additional read-head ages and offset-grid checks, and provenance/wmata-v4-loudness.csv for the supplied before measurements. Reproduction of the original survey at selected speeds differed by at most 0.005 dB.

## Changes

The target loudness follows a smoothed version of the supplied sweep survey, with adjacent target steps constrained to 0.85 dB. Steady uses the same family loudness target. Mechanical and cruising levels are also smoothed. Levels have therefore changed on both sides of the comparison: this is not simply a blanket steady boost or peak normalization.

Neighboring tone and steady-anchor windows now use sine/cosine equal-power overlap shapes, with quarter points and a fine speed grid. The upper-power handover gets the same treatment. A common measured group correction then compensates for the actual summed spectrum and interference at each speed. The delivered CSV already contains the final gains; it is still interpolated linearly by the player. No CSV format change.

152 of 269 WAVs are byte-identical to v4. The other 117 (114 steady and 3 sweep masters) receive only constant gain to keep CSV volumes within 0–1. Each steady anchor's a/b/c masters share one gain, preserving their internal balance and loop lengths; no new EQ, compression, denoising, or synthesis. All master peaks remain at or below -6 dBFS. Rendered mix peaks can exceed -6 dBFS, but checked mixes do not clip. Detailed master gains are in validation.json.

## Listen

previews/v5_power_14_steady_switch.wav: constant 14 m/s and full power, sweep for 0–5 s; blend to steady 5–6.5 s; steady until 17 s; blend back 17–18.5 s; sweep until 24 s. This explicitly automates the steady fraction to isolate the switch, rather than simulating the speed detector. The v4 companion uses identical controls and playback level. Neither is normalized for audition; only 30 ms outer fades are used.

The player's existing linear temporal family partition remains. Equal-power SPEED overlaps in the CSV do not change that temporal blend. Even with matched endpoints, uncorrelated sweep/steady content can dip during the transition. The isolated 14 m/s fixed-phase test measures a maximum 0.30 dB dip. Removing this entirely would require a separately reviewed player blending change; a speed-only CSV cannot depend on steady fraction. This is distinct from the corrected persistent steady/sweep level difference.

Current mod operational settings and rule strings are preserved, including its tested steady exit revision. See INTEGRATION.md. Validate the shipped Ogg conversions separately; these measurements are of PCM masters. Measurements support level consistency, not an assertion of realism or listening approval.
