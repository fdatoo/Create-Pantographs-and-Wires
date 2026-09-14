# Integration — complete loudness revision

Replace the previous sound bank with this complete pack. Load its CSV once and use its accompanying WAV masters. Do not combine the revised curves with v4 masters: 117 master files receive constant gain compensated by their CSV values. No additional normalization, equal-power curve transformation, gear boost/cut or whine EQ is needed. Spectral C treatment is already in the audio.

CSV columns and folder layout remain unchanged. Interpolate volume and playback-rate pitch linearly between rows, inclusive at endpoints; outside a layer's curve it is silent. Domain 0–40 m/s, steady eligibility 5–40 m/s. More rows encode the desired speed crossfades and loudness compensation. At overlapping anchors play all nonzero a/b/c components simultaneously with independent continuously advancing read heads. Preserve phase while inaudible. Never restart or synchronize them on mode entry.

The supplied current mod player_settings.json is the authority for all detection, gates, timing and rule strings. Operational fields were preserved exactly. It includes ducked_layers, duck_depth, mode_persistence_seconds, mode_blend_seconds, mode_blend_curve, departure_speed_mps, slope_power_blend_seconds, mode_end_persistence_seconds, mode_power_persistence_seconds, steady_exit_hold_seconds=0.4 and steady_exit_speed_deviation_mps=0.5.

The steady exit uses the supplied tested rule: departure of at least 0.5 m/s from held speed plus net acceleration of at least 0.15 m/s² over 0.4 seconds; slow drift moves the held speed. Use the exact accompanying rule for qualifications and immediate family/availability exits. Do not restore the original v4 0.15-second exit detector. Existing mode hold, slope, contact and driver logic is unchanged.

Continuous mechanical layers are coast/rolling_low, coast/rolling_high, coast/gear_low and coast/gear_high. Play once at revised CSV gains in every mode, without demand scaling or ducking. coast/cruising also plays continuously while moving with volume = CSV volume * (1 - 0.85 * max(P,B)). P and B are total smoothed family gains before steady partitioning.

Retain power = P*(1-SP), power_steady = P*SP; brake = B*(1-SB), brake_steady = B*SB. Separate steady fractions, 1.5-second smoothstep target transitions, restarting from the current fraction if interrupted. These are LINEAR AMPLITUDE temporal partitions. The sine/cosine equal-power speed-anchor overlaps are already baked into CSV volumes and must not be applied to SP/SB by this update. Endpoint loudness matching does not mathematically eliminate all mid-transition dips; the README quantifies the controlled preview test.

Steady a/b/c loop lengths and all pitches follow v4. Master changes are scalar gains only, shared within an anchor, so the relative whine suppression is preserved. WAVs are mono 48 kHz 24-bit PCM. Convert to shipping Ogg using the existing pipeline and rerun checks if encoding materially changes levels or loop boundaries.
