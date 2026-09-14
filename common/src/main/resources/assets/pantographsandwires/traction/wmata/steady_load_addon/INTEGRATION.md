# WMATA Alstom steady-load addon

This adds dry, exterior **power_steady** and **brake_steady** layers for sustained load at 5–40 m/s. It is intended for the accepted `wmata-alstom-upper-v3` pack plus `wmata-cruising-addon`. Existing files, curves, mode gains and mechanical sounds are unchanged.

## Install

Copy `power_steady/` and `brake_steady/` into the existing pack. Append the rows from `steady_curves.csv` to the existing `curves.csv`, keeping only one header. Merge the new `steady_*` keys from this addon's `player_settings.json` into the player's settings; **do not replace the existing settings file** with this fragment.

The CSV schema remains `layer,mode,speed_mps,pitch,volume`. Its new modes are `power_steady` and `brake_steady`. Paths omit `.wav`. Interpolate linearly, and silence a layer outside its first and last row. All curves use pitch 1.0; changes between reference speeds use overlapping layer volumes. Every nonzero layer plays simultaneously. The `a`, `b` and `c` files are components of one sound, not random alternatives.

There are 19 reference speeds per family, including exact 14 and 21 m/s anchors. In total the addon has 114 mono, 48 kHz, 24-bit PCM WAV loops. A component lasts approximately 8.173, 9.947 or 11.729 seconds. At an exact reference speed three components play; between reference speeds up to six play per steady family. The CSV includes a silent 0 m/s point on the lowest-speed layers, and the upper layers include the 40 m/s endpoint. Rows below 5 m/s are an exit fade guard; steady eligibility begins at 5 m/s.

## Detect steady load

Keep the existing grade-aware power/brake/coast classifier. A hill can require substantial power or braking with almost no measured acceleration, so **do not derive load gain from acceleration alone**. The steady detector only chooses the sound inside the already selected load family.

Maintain a separate qualifying timer and steady fraction for power and brake:

1. The existing classifier must continuously select that family, its existing availability gate must permit it, and speed magnitude must be between 5 and 40 m/s inclusive.
2. Actual `abs(delta_speed / delta_time)` must remain at or below **0.08 m/s²**. Use actual speed change here, not the gravity-compensated effort used by the load classifier. Also require a speed span of at most **0.18 m/s** over the previous **1.25 seconds**.
3. After **1.25 seconds** of qualifying load, target that family's steady fraction to 1. Start its **1.5-second** blend from the fraction's current value. Coasting at a fixed speed does not prequalify a later power/brake mode: start that family's timer when its load classification begins.
4. Once steady is active, actual acceleration magnitude of at least **0.15 m/s²** for **0.15 seconds** targets the fraction back to 0. Between the entry and exit acceleration thresholds, retain the current target. Loss of family classification, availability or speed eligibility targets 0 immediately. Existing power/contact muting still takes precedence; do not keep an unavailable motor audible while waiting out the steady blend.

At 20 ticks per second these durations are about 25 qualifying ticks, 3 exit ticks and 30 blend ticks; calculate from elapsed time if tick spacing varies. Thresholds are initial player-tuning values, not physical WMATA measurements.

## Mix the layers

Let `P` and `B` be the existing smoothed power and brake family gains, including their demand, mode envelopes and availability gating. Let `SP` and `SB` be their independently maintained steady fractions, all clamped to 0–1.

| Layer family | Gain before its CSV volume |
|---|---|
| Existing power | `P * (1 - SP)` |
| New power_steady | `P * SP` |
| Existing brake | `B * (1 - SB)` |
| New brake_steady | `B * SB` |
| Cruising tone | `1 - 0.85 * max(P, B)` |
| Existing rolling and gear | Unchanged, continuous and independent of demand |

Use a linear **gain partition** between sweep and steady layers. Shape the 1.5-second change of the steady fraction with smoothstep: `u = clamp(elapsed / 1.5, 0, 1)` and `S = start + (target - start) * (3*u*u - 2*u*u*u)`. Do not use an equal-power crossfade, which can raise the level of related tonal material. If the target changes mid-blend, begin a fresh envelope at the current fraction.

The steady layers **duck cruising exactly like power and brake do**. Duck using total family gains `P` and `B` before partitioning, not just `P*(1-SP)` or `B*(1-SB)`. Thus settling into steady load neither unducks cruising nor ducks it twice.

Keep the existing 0.25-second mode blending and 1-second slope-load ramp. Steady blending is a separate change inside the load family. Do not apply it to the continuous mechanical bed or reset any of those read heads. Keep steady read heads running, or advance virtual read heads while their gains are zero. Re-entering steady load must not restart the same pattern. Preserve the cruising read head as well.

No new driver-direction requirement is added: use the existing classifier for hand driving or automated movement. Power contact gating is unchanged. Brake steady follows the existing electrical-braking availability rule rather than assuming a new regeneration or resistor capability.

## Sound construction and repetition

Each reference speed is derived from the **actual combination of existing power or brake layers and their CSV gains at that speed**, not just the dominant file in a server log. The original spectral balance supplies the tonal identity. Closely spaced versions of the same tone, introduced by the frozen sweep snapshots, are merged within 2.5 Hz to avoid a regular beat on long holds. Their combined spectral energy is preserved. Small, smooth irregular variations in partial level and phase provide texture without a repeating up/down sweep or LFO. No station, crowd, voice, reverb or field-recording waveform is added.

Each WAV is necessarily periodic; three different lengths make the **combined** pattern non-repeating over the requested hold duration. No individual file is claimed to be mathematically non-periodic. Always play the complete a/b/c set to obtain this effect. The loops have periodic joins without fade-ins or fade-outs. Their relative levels are set in the CSV using one common file gain for the whole bank.

These are synthesized steady-load approximations, not new recordings of a loaded train on a grade. Intermediate speeds interpolate between anchors, and the original pack's absolute-speed and 28–40 m/s modeling assumptions still apply. Technical checks and long auditions support review; final audible realism and in-game switching require the user's ears.

## Previews

| File | Scenario |
|---|---|
| `previews/climb_14.wav` | 0–14 s: accelerate to 14 m/s. 14–44 s: full-load climb at 14 m/s for 30 s. Then a one-second release tail. |
| `previews/descent_14.wav` | 0–5 s: neutral/cruising at 14 m/s. 5–35 s: full-load descent at 14 m/s for 30 s, with a one-second slope-brake onset ramp. Then a one-second release tail. |
| `previews/cap_lifts.wav` | 0–20 s: load held at 14 m/s, settling into steady. At 20 s the cap lifts; accelerate at 1 m/s² to 21 m/s at 27 s, followed by two seconds at 21 m/s. |

All three include the accepted continuous rolling/gear bed and cruising addon with the ducking formula above. They use the new hold detection and steady blending. The descent's one-second brake ramp is a preview assumption matching the slope-power ramp; the real player should retain its existing slope-brake onset policy if different. The corresponding timeline CSVs document each preview at the game tick rate.

Additional 45-second **traction-only** auditions are supplied for power and brake at both 14 and 21 m/s, so the mechanical/cruising bed cannot hide repetition. Preview files alone have short outer cut fades; loop files do not. No preview normalization, compressor or limiter is used.

`validation.json` contains format, seam, pitch, level, spectral, repetition and preview checks. `SHA256SUMS.txt` covers delivered files. No game code is included or changed by this addon.
