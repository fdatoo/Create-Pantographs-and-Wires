# WMATA Alstom — platform listening candidate v0

**A first synthesis candidate for listening feedback, not a finished or approved reproduction.**

Target: the refurbished 2000/3000 and 6000 Alstom traction family, heard outside about 1–2 metres from a bogey. This initial bank uses the 3000-series induction reference and the approved platform recordings. It does not yet establish audible differences between the three car series.

## Listen first

- `preview_14mps.wav`: 0–14 seconds accelerates from 0 to 14 m/s; 14–19 seconds holds 14 m/s in coast mode; 19–33 seconds brakes to rest.
- `preview_40mps.wav`: 0–32 seconds accelerates from 0 to 40 m/s; 32–37 seconds coasts at 40 m/s; 37–69 seconds brakes to rest.

These are rendered from the supplied WAV loops and CSV using linear interpolation and playback-rate pitch. The previews apply a short, 60 ms mode-edge envelope to avoid clicks at abrupt demonstration switches. That envelope is not present in the loop files. No reverb, Doppler, spatial attenuation, compression or mastering is applied to the previews.

The first feedback priority is the acceleration tone: too thin/bright, too smooth, too buzzy, missing a particular pitch movement, or recognizably correct. Then evaluate the braking and the rolling/gear balance in coast. The 40 m/s preview is chiefly an extension check.

## Files and player contract

- `curves.csv` uses exactly `layer,mode,speed_mps,pitch,volume`; layer names omit `.wav`.
- `power/`, `brake/`, and **required** `coast/` contain mono, 48 kHz, 24-bit PCM WAVs.
- All loops are two seconds long and periodic, with no end fades. Pitch multipliers remain between 0.5 and 2.0.
- Relative gains live in the CSV. Tonal banks use a common gain within each mode rather than individual peak normalization. Individual quieter components intentionally peak below −6 dBFS.
- Interpolate linearly, and silence a layer outside its first and last speeds. The domain includes 40 m/s.
- No one-shots are included in this candidate.
- `validation.json` records format checks, levels, and loop-join sample steps. `provenance.json` links every reconstructed tonal layer to its reference time.

## What is measured and what is provisional

The tonal loops are additive spectral reconstructions of local peaks in the induction audio. A broad spectral weighting, estimated from the departure microphone recording, gives a first exterior tonal balance. This weighting is **not** a calibrated acoustic transfer function. The microphone recording includes station acoustics, source movement and other sound, and cannot establish an exact 1–2 metre sound-pressure level.

The rolling bed is synthetic periodic noise shaped by a smoothed microphone spectrum; recorded speech, announcements and other event waveforms are not copied into it. The gear hum is an explicitly provisional synthesized component. It needs the user's ears, particularly for the balance at low speed.

Absolute speed is not supplied by these references. For this first audition only, acceleration reference time 6–37 s maps to 0–28 m/s, and braking time 110.7–74.5 s maps to 0–28 m/s. These are different, provisional mappings, not measured vehicle performance. The source timestamps themselves are preserved in provenance so the mappings can be revised. Above 28 m/s the last traction spectrum is held, while the mechanical bed changes; **28–40 m/s is an artistic extension, not validated WMATA behaviour.**

The densely spaced tonal snapshots preserve changes of spectral shape at fixed carrier pitch. They are a first approximation: crossfading snapshots can soften transitions and produce interference between nearby partials. If heard as graininess, stepping or an artificial chorus, the next iteration should replace affected regions with tracked partial layers. The bank is intentionally not yet optimized for minimum file count.

The reference distinguishes powered **Cruising** from unpowered **Coasting**. Default coast layers contain rolling and gear components, with no energized inverter tone. A player that infers demand solely from speed changes cannot reliably distinguish the two states; constant-speed powered cruising remains an integration limitation. Also, duplicating the mechanical bed in all three modes preserves it at full demand, but demand scaling can still reduce it under weak acceleration/braking. An eventual always-on mechanical layer would resolve that if the player contract is extended.

## Approved reference sources

1. Ebara Express, [WMATA 2000/3000/6000 induction recording](https://www.youtube.com/watch?v=aQlV_W8bvaY). Actual recorded car family: 3000 series. Used for tonal structure and explicit motion labels.
2. Ben Schumin, [2000/3000 departure](https://www.youtube.com/watch?v=jSfBas5a2BQ). Used for exterior spectral balance and rolling-texture guidance.
3. Ben Schumin, [2000/3000 arrival](https://www.youtube.com/watch?v=p0xQk-Nl0Us). Used as a separate braking comparison; its spectrum has been inspected, but the final audible match is pending user feedback.

No source media files are included in the distributed candidate.

## Label observations

The following are visual observations sampled at approximately one-second intervals, not frame-accurate transition boundaries:

| Reference position | Visible label / carrier |
|---|---|
| 0:06–0:07 | Acceleration, 1235 Hz |
| 0:08–0:10 | Acceleration, 1190 Hz |
| 0:11 | Acceleration, 1210 Hz |
| 0:12–0:13 | Acceleration, 1235 Hz |
| 0:14 | Acceleration, 1460 Hz |
| 0:15–0:20 | Acceleration, 1230 Hz at sampled frames; short intermediate stages require finer checking |
| 0:21–0:37 | Acceleration, 1190 Hz |
| 0:38 | Coasting, PWM panel blank |
| 0:39–0:40 | Cruising, 1190 Hz |
| 0:41–0:44 | Coasting, PWM panel blank |
| 0:45 | Cruising, 1190 Hz |
| 0:46–0:47 | Coasting, PWM panel blank |
| 0:48–0:59 | Cruising, 1190 Hz |
| 1:00–1:01 | Coasting, PWM panel blank |
| 1:02–1:05 | Cruising, 1190 Hz |
| 1:06 | Coasting, PWM panel blank |
| 1:07–1:12 | Cruising, 1190 Hz |
| 1:13 | Coasting, PWM panel blank |
| 1:14–1:29 | Braking, 1190 Hz |
| 1:30–1:37 | Braking, 1230 Hz |
| 1:38–1:39 | Braking, 1210 Hz |
| 1:40 | Braking, 1460 Hz |
| 1:41–1:43 | Braking, 1235 Hz |
| 1:44–1:45 | Braking, 1210 Hz |
| 1:46–1:50 | Braking, 1190 Hz |
| 1:51–1:53 | Braking label remains, PWM panel blank |

The video's carrier-frequency list is not a speed table. Motion labels, spectral content, and speed assumptions must remain separate during refinement.
