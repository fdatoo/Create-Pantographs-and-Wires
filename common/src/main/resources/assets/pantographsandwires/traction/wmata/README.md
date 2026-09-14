# WMATA Alstom — upper acceleration revision v3

Original tone and accepted mode transitions are retained. This revision addresses the static acceleration buzz above 28 m/s. It awaits listening feedback; it is not measured real-world operation at 40 m/s.

## Listen

- `previews/upper_acceleration_AB.wav`: 9.6 seconds of the previous 28–40 m/s acceleration, one second of silence, then 9.6 seconds of the revision. Original relative gain is retained. Only outer clip edges have 20 ms audition fades.
- `previews/preview_40mps.wav`: revised 69-second journey. Acceleration ends at 32 seconds; braking starts at 37 seconds. The new upper acceleration starts at 22.4 seconds (28 m/s).
- `previews/preview_14mps.wav`: accepted lower-speed journey, unchanged to PCM quantization precision.

## Revision

The final acceleration spectrum is split into fixed carrier neighbourhoods, low/mid motor components, and higher sideband components. The old complete spectrum fades out between 28 and 30 m/s as these new layers enter.

`power/upper_carrier.wav` stays at pitch 1.0. `power/upper_rotor.wav` rises from pitch 1.0 at 28 m/s to 1.428571 at 40 m/s. Higher sideband layers move their frequency offsets around approximate fixed carrier multiples. The original spectral amplitudes and phases supply the starting material.

The higher partials' carrier assignments are modeling assumptions. The approximate carrier neighbourhood is 1186.5 Hz, close to the video's 1190 Hz label. Absolute speed and high-speed trajectories remain provisional. Braking is unchanged. No brighter-v1 processing or new generic buzz has been added.

## Integration

Use this folder as a complete replacement candidate. Do not append its table to the old CSV. It contains 154 mono 48 kHz, 24-bit PCM, two-second periodic loops. The new trajectories use ordinary pitch/volume rows in the existing five-column CSV; the game does not need frequency-shifting processing. Pitch multipliers remain within 0.5–2.0.

The accepted mechanical routing and envelopes still need explicit player support, described in `player_settings.json`:

- Play the four `coast/` mechanical loops in every mode, without demand scaling. Preserve their read heads across mode changes.
- Apply demand and envelopes to all power/brake tonal layers. Power onset: 60 ms; power release: 180 ms smoothstep; brake onset: 350 ms smoothstep. Begin interrupted envelopes from their current gain.
- Interpolate CSV rows linearly and silence layers outside the first/last speeds; include the 40 m/s endpoint. Keep playback continuous through layer crossfades rather than repeatedly restarting loops as gains change.
- Treat every CSV row with mode `power` as power, including the new `upper_` filenames. Do not select only filenames beginning `tone_`.

No game code was modified. Direct power/brake reversals, brake release, rapid toggles and performance remain to be tested. This bank has more layers to permit independent pitch movement; voice-count optimization should follow sound approval. Powered cruising still cannot be reliably distinguished from true coasting using speed change alone.

## Verification and references

`validation.json` records format, loop, level and pitch checks. The accepted 14 m/s preview is preserved to PCM quantization precision, acceleration through 28 m/s is unchanged, and the full 40 m/s preview does not clip. `upper_revision.json` documents the new trajectories; `provenance.json` maps the assets; `SHA256SUMS.txt` verifies files.

`REFERENCE_NOTES.md` preserves the initial study, including its source limitations. Its old pack layout, frozen high-speed behaviour, and original preview descriptions are superseded here. References remain Ebara Express's induction recording and Ben Schumin's platform departure/arrival recordings; no source media is distributed.
