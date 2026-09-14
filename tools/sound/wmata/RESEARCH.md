# WMATA 6000-series traction sound: research notes

The WMATA profile is synthesised live from a model of the drive (`onix_model.py`, mirrored by
`OnixTraction.java` and `OnixTractionSynth.java`). It uses no recorded audio, so no station
ambience, speech or announcements can reach it. These notes record what the model rests on.

## The drive

- Per the description of a pickup-coil recording of the same equipment (YouTube `aQlV_W8bvaY`),
  the 6000s were built with, and the 2000s and 3000s rebuilt with, Alstom "ONIX" 2-level IGBT
  inverters feeding asynchronous traction motors. The motors keep asynchronous PWM throughout.
- One short video title (`An9C56J5vbk`, 2022) claims some 6000s later received MITRAC propulsion.
  That is unverified here. The reference platform recording (`3hwLMeuslhY`) matches the ONIX
  schedule below, so the model follows ONIX.

## Sources used

| ID | What | Used for |
|---|---|---|
| `aQlV_W8bvaY` | Pickup-coil ("inductor") recording, 3000 series, same ONIX drive | Carrier schedules, modulation index, line structure. No acoustic background at all. |
| `3hwLMeuslhY` | Platform departure (the original reference) | Checking the schedule and the airborne balance between line groups |
| `tgDArXHHlEI`, `KKof3n1r6ZM` | Onboard audio | The rotor-locked line family and its harmonic ratios |
| `AssjserR_2I`, `sv6SsTZIo0o`, `vqyjSAADgHQ` | Platform departures | Cross-checks (Doppler limits their use for levels) |

## Measurements

All from `aQlV_W8bvaY` unless noted. f1 is the motor supply frequency, fc the carrier.

- **Carrier schedule, powering** (fc against f1): 1235 below about 6 Hz (the recording's first
  stage), 1186 to 29.3, 1207 to 35.2, 1237 to 46.9, 1455 to 52.7, 1207 to 55.7, 1230 to 85, then
  1186.
- **Carrier schedule, braking**: 1186 above 82, 1230 down to 49.8, 1207 to 46.9, 1455 to 41.0,
  1237 to 32.2, 1207 to 23.4, then 1186. The switch points sit 3 to 6 Hz lower than when
  powering, consistent with about 3 Hz of motor slip either way.
- **Modulation index** (from the 2fc±5f1 to 2fc±f1 ratio against Bessel functions): 0.57 at
  10 Hz rising to about 1.08 by 90 Hz, then flat (field weakening).
- **Line structure**: the strongest electrical groups are fc±2f1, 2fc±f1 and 3fc±2f1, as expected
  for the line-to-line voltage of a naturally sampled 2-level inverter.
- **f1 against speed**: about 117 Hz at the recording's cruise, taken as 4.5 Hz per m/s.
- **Platform recording**: its upper band follows 2fc of this schedule through every step (the
  2.38 kHz plateau, the steps up, the burst near 2.9 kHz when fc is 1455). The motor frequency
  there rises at about 6.5 Hz/s. Upper sidebands of 2fc dominate.
- **Rotor-locked family**: on the platform recording a line rises at 23.4 x rotor frequency.
  Onboard, lines stack at median ratios 1.92, 2.85 and 4.12 to the lowest, which is a harmonic series,
  modelled as harmonics of 11.7 x rotor frequency with the second strongest.

## Method notes

- Airborne levels were measured only at frequencies the model predicts (n x fc + m x f1, relative
  to the local spectral floor), so ambience cannot count as signal. Phone-quality recordings still
  gave noisy per-line levels, so levels come from PWM theory, weighted by current (1/f) and one
  smooth acoustic response. That response is a lift around 2.45 kHz, chosen so the second carrier
  group dominates as it does on the platform recording.
- Coasting gates the inverter off, so its lines vanish. A small share of the rotor family stays.
- Braking uses the braking schedule, with f1 below rotor frequency.

## Not modelled, or assumed

- Rolling and wheel noise: Create plays its own.
- The acoustic response and the rotor family's levels are fitted by eye and ear to the recordings,
  not measured precisely.
- The f1-per-speed mapping assumes the recorded cruise was about 26 m/s.
