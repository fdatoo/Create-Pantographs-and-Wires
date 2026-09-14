# WMATA Alstom C — complete pack v4

This replaces upper-v3 plus the cruising and steady-load addons with one combined pack. C balance was selected by the user in 14 m/s listening demos.

Install this folder as the active sound-pack root. Replace the old curves and settings with these combined files; do not append this CSV to previous tables or load old addons alongside it. No additional runtime EQ or C gain changes are required.

Changes: synthetic gearbox hum -9 dB and rolling sound +2 dB in CSV throughout motion. Steady motor whine peaks retain the -12 dB demo reduction; remaining steady electrical partials are reduced 4 dB, with protected whine neighbourhoods. The original acceleration and braking sweep WAVs, cruising WAV and mechanical WAVs remain byte-identical. Only steady WAVs and mechanical CSV volumes change.

The exact 14 m/s treatment is retained. At other steady anchors, the two strongest separated spectral peaks above 300 Hz identify provisional whine centres for the same treatment. This is an extension of the accepted balance, not an ear-verified result at every speed. See mix_revision.json. Speeds inferred from references and 28–40 m/s behaviour remain provisional.

All loops: mono 48 kHz 24-bit PCM, seamless periodic audio, peaks below -6 dBFS. Tonal/mechanical original loops retain their lengths; steady loops retain three independent 8–12 second components. Existing source packs remain unchanged on disk. Game code has not been modified.

Previews include acceleration to 14 followed by a 30-second climb, coast to a 30-second descent at 14, and a cap lift from 14 to 21. The 26-second accepted acceleration demo is also supplied for direct comparison. Validation and SHA256SUMS cover this delivery.
