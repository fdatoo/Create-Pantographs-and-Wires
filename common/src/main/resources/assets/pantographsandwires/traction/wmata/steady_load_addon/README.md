# WMATA Alstom steady-load addon

Additional power and brake sounds for long, loaded holds at 5–40 m/s. The existing Alstom upper-v3 pack and cruising addon remain unchanged.

## Start here

1. Audition `previews/climb_14.wav`, `previews/descent_14.wav`, and `previews/cap_lifts.wav`.
2. Copy `power_steady/` and `brake_steady/` into the existing pack.
3. Append `steady_curves.csv` rows to the existing curve table, excluding its extra header.
4. Implement the new settings in `player_settings.json` according to `INTEGRATION.md`; merge these addon keys instead of replacing the existing settings.

The package contains **114 seamless WAV loops**: 19 speed anchors for each of two modes, with three simultaneous components per anchor. Each WAV is mono 48 kHz, 24-bit PCM and 8–12 seconds long. Unequal loop lengths and irregular partial variation keep the combined sound developing during long holds without an imposed up/down LFO. The sound remains tonal; these layers are not an acceleration sweep.

Default steady entry: **1.25 seconds of stable loaded speed**, then a **1.5-second crossfade**. Returning to changing speed crossfades back to the original family. Original power/brake and their steady equivalents share one family gain; mechanical sound continues, and cruising ducking remains based on total family demand.

Four additional `*_steady_*mps_45s.wav` files audition traction alone at 14 and 21 m/s. The original mechanical or cruising beds therefore cannot conceal repetition during review.

`validation.json` contains technical checks and measurements. The loop seam, long-hold, loudness and preview checks pass, but audible realism and in-game behaviour still await review. `SHA256SUMS.txt` covers the delivered files except itself. This addon contains no modifications to game code or prior pack files.
