# Integration — complete C pack

## Installation and C mix
Use this folder as a complete replacement for upper-v3, cruising-addon and steady-load-addon. curves.csv already includes all modes and cruising. Replace prior tables; do not append or load duplicate banks. Merge no older addon settings over this file. Preserve unrelated game settings.

Gear CSV gains already include -9 dB; rolling gains include +2 dB. Steady WAVs already include the accepted spectral adjustments. Apply no additional C EQ or mix offsets. Original power/brake sweeps are unchanged. Play the four coast mechanical layers continuously once at CSV gains; coast/cruising uses the duck rule below. These coast layers are not restricted to neutral motion.

Cruising gain = its CSV volume * (1 - 0.85 * max(P,B)), using total smoothed power/brake family demand before steady splitting. Mechanical audio is not ducked or demand-scaled. Use P*(1-SP), P*SP for power and power_steady; B*(1-SB), B*SB for brake and brake_steady. All a/b/c components are simultaneous. Keep every loop's actual or virtual read head continuous while inaudible.

Keep existing game mode/contact gates and timing. This update changes sound balance only; it does not implement the previously discussed experimental jitter-tolerant steady detector. Existing entry 1.25 seconds and blend 1.5 seconds remain in player_settings.json. If the player already has an independently tested detector fix, retain that code; this package adds no new detector behaviour.

## Steady entry and exit
Use actual speed magnitude change, not gravity-compensated effort, for the steady detector. The existing grade-aware classifier decides power versus brake independently. Steady eligibility includes 5 through 40 m/s.

Entry requires the same available load family, absolute acceleration at most 0.08 m/s² and a speed span at most 0.18 m/s over the preceding 1.25 seconds. Qualify continuously for 1.25 seconds. Coasting does not prequalify a subsequent load. Reset qualification on failure or family change.

Exit targets zero steady fraction after absolute acceleration is at least 0.15 m/s² for 0.15 seconds. Intermediate values retain the current target. Family change, unavailable electrical family or speed outside range targets zero immediately, with existing contact/mode gates taking precedence. Do not invent a new regeneration availability rule.

Maintain separate SP and SB. Crossfade each fraction to its target using smoothstep over 1.5 seconds in either direction, starting at the current fraction if interrupted. Use linear amplitude family partition, not equal-power crossfading. Existing mode blend is 0.25 seconds and slope onset ramp is 1 second; keep departure power onset 0.06 seconds, power release 0.18 seconds and brake onset behaviour from the player's existing implementation. The steady blend does not replace these gates.

## Curves and loops
CSV columns are layer,mode,speed_mps,pitch,volume. Interpolate pitch and volume linearly. Silence a layer outside its first and last speed rows; include endpoints. Every power row belongs to power, including upper_ layers. Speed domain is 0–40 m/s; steady eligibility is 5–40 m/s. Sub-5 steady rows are fade guards, not an extension of eligibility.

Play a/b/c steady components together; each has its own 8–12 second loop length. Do not random-select components, restart them at mode entry, synchronize their boundaries or reset their phases while silent. This independent continuous playback preserves the intended long composite variation. Original mechanical loops keep their original lengths. No fade is built into loop boundaries; preview edge fades are not loop behaviour.

## Installation checks
The combined CSV references 269 WAV layers: 154 original base layers, one cruising layer and 114 revised steady layers. validation.json verifies formats, seam-step checks, curve ranges, sample agreement with the accepted acceleration demo, full-demand level checks and source-file preservation. SHA256SUMS.txt covers every delivered file except itself. The provenance directory contains historical source information only; it does not override this integration document.
