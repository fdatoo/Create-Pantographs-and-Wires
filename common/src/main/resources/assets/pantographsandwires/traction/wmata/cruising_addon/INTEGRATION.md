# Cruising layer for the WMATA Alstom pack

Add `coast/cruising.wav` to the accepted pack and append the rows in `cruising_curve.csv` to its speed table. The WAV is a seamless two-second, mono 48 kHz, 24-bit loop, peaking around −6.4 dBFS. Use the table for its quieter playback level. Pitch stays at 1.0 to retain the carrier tone; the speed-volume curve is provisional.

The layer is reconstructed from [Ebara's recording at 0:52](https://www.youtube.com/watch?v=aQlV_W8bvaY&t=52s), explicitly labeled **Cruising**, using the same exterior spectral weighting as the accepted first-pass tone. It represents powered cruising, intentionally used here as a neutral-motion compromise. It is not a true unpowered-coast recording.

## Player blending

- Keep the existing rolling and gear layers continuous and independent of demand.
- Keep this cruising loop's read head running throughout motion. At neutral demand, use its CSV volume. As power or brake tones strengthen, duck it to avoid double-counting the whine: `cruise_gain = CSV_volume × (1 − 0.85 × max(power_gain, brake_gain))`, with the two demand/envelope gains clamped to 0–1. Thus a faint 15% remains under full traction or braking.
- To prevent short squeaks, trial a **200 ms persistence requirement** before changing power/brake mode while moving, then blend from current gains over about **250 ms**. Use the same smoothed gains to duck cruising; don't switch it off on every sign change. These are initial tuning values, not measured train timings. Retain the accepted startup behaviour when departing from rest.

`mode=coast` preserves the requested CSV schema, but the continuous/ducked behaviour above is a player override. A speed-only table cannot encode persistence or time-based blending. No game code has been changed.

`preview_cruising_14mps.wav` auditions eight seconds at 14 m/s with the accepted rolling/gear bed and cruising at CSV volume 0.18. Its outer 100 ms fades are for audition only; the loop itself has no fades. Rapid-switch behaviour still needs player testing.
