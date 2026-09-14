# WMATA Alstom C v4: loudness consistency brief

In game, the traction sound seems louder or quieter on different stretches of track. The player plays the pack as specified: linear interpolation of CSV pitch and volume, P*(1-SP) and P*SP between sweep and steady layers, no gain matching. So the variation below comes from the pack content and its curves. Please revise the pack to address it.

## How it was measured

Each group was rendered for 3 seconds at 0.5 m/s steps from 0.5 to 40 m/s. The render used the v4 WAV masters at CSV pitch and volume and full mode gain, then measured integrated loudness (ITU-R BS.1770, K-weighted LUFS, pyloudnorm). "Heard mix" means the traction group plus the four continuous mechanical layers plus coast/cruising ducked by 0.85, as the player does under full demand. The attached `wmata-v4-loudness.csv` has every value.

## Findings

1. **Steady load is much quieter than the sweep at the same speed.** On its own, the steady family is 8 to 15 dB below the sweep layers it replaces. In the heard mix, settling into steady load drops the level by 1.6 to 6.3 dB for power and 1.8 to 6.9 dB for brake. Examples:
   - 14 m/s: power -3.1 dB, brake -2.8 dB;
   - 22 m/s: power -6.0 dB;
   - 27 m/s: brake -5.9 dB.

   A held-speed climb or descent therefore sounds quieter than accelerating on the flat, and every entry into or exit from steady load is a 1.5 s level ramp. The v4 README says the whine was cut 12 dB in the steady loops, and that treatment may account for most of this.

2. **Neighbouring sweep loops are not level-matched.** Changes within a single 0.5 m/s step:
   - power: -5.2 dB (7.5 to 8.0), +4.8 dB (7.0 to 7.5), +4.5 dB (8.5 to 9.0), +3.6 dB (13.5 to 14.0);
   - brake: -5.6 dB (8.5 to 9.0), +5.4 dB (7.0 to 7.5), +3.0 dB (27.5 to 28.0).

   18 of 74 power steps and 21 of 74 brake steps change by more than 2 dB.

3. **Steady crossfades dip between anchors.** Power steady averages -31.3 LUFS at its anchor speeds and -34.2 LUFS halfway between anchors. Brake steady averages -31.4 and -33.2. The a/b/c components of neighbouring anchors are uncorrelated, so a linear volume crossfade loses about 3 dB of power at the midpoint. Several power_steady steps change by 2.8 to 3.6 dB (9.0 to 9.5, 10.5 to 11.0, 12.0 to 12.5, 13.5 to 14.0).

## Requested changes

- **Level-match steady to the sweep.** At each speed and mode, the heard mix with steady fraction 1 should be within ±1 dB of the heard mix with steady fraction 0. Keep the v4 spectral balance (the whine sitting lower relative to the rest of the steady content) while restoring the overall level. If a quieter steady load is intended, say by how much, so it can be judged by ear as a choice.
- **Smooth loudness against speed.** Within each group, adjacent 0.5 m/s steps should differ by no more than about ±1 dB, except where a real change is intended (for example the upper sweep), and those should be named. Prefer adjusting CSV volumes over re-rendering WAVs.
- **Use equal-power crossfades in the curves.** The player interpolates linearly between rows, so add intermediate rows with equal-power gains (for example cos/sin at quarter points, or about 0.707 at the midpoint) wherever two uncorrelated layers or anchors overlap. The CSV format needs no change.
- **Keep the constraints:**
  - same CSV columns and folder layout;
  - WAV masters are fine (they are converted to Ogg Vorbis for shipping);
  - steady a/b/c stay simultaneous with their own loop lengths;
  - base loops can stay byte-identical if only CSV volumes change.
- **Keep the player keys in player_settings.json.** A complete pack must include these keys, which v4 left out:
  - `ducked_layers`, `duck_depth`
  - `mode_persistence_seconds`, `mode_blend_seconds`, `mode_blend_curve`
  - `departure_speed_mps`, `slope_power_blend_seconds`
  - `mode_end_persistence_seconds`, `mode_power_persistence_seconds`
  - `steady_exit_hold_seconds` (0.4), `steady_exit_speed_deviation_mps` (0.5)

  Keep their rule strings too. The current mod copy of player_settings.json has the values to use.

## Validation to include

Please include the same loudness table for the revised pack: LUFS at 0.5 m/s steps for each group and for the heard mix at steady fraction 0 and 1, rendered at CSV pitch and volume and full gain. Also report the largest adjacent-step change per group, and the largest steady-vs-sweep difference per mode. Include one preview that holds power at 14 m/s, settles into steady load and leaves it, so the level across the switch can be heard.
