# Ride-event detection rework: misclassification analysis and plan

2026-08-30. Prompted by two reference rides recorded today whose events came out wrong
(ANL-01): harsh acceleration stored as braking, straight-line emergency braking spawning a
simultaneous cornering event, a slalom spawning braking *and* acceleration, and genuinely harsh
steering going unrecorded.

Both rides were replayed offline from their raw NDJSON samples (extracted from
`ridesafe_30_08_26.backup`), through a faithful Python replica of the production detector. The
replica reproduces all 8 stored events of the two rides within a few ms and a few hundredths of a
g, so the findings below describe the shipped algorithm, not an approximation of it.

## Reference rides

| Ride | DB id | Stored events | Driver's account |
|---|---|---|---|
| → Adlumer Str. 8, 17:15 | 127 | ACCEL 200.3s, ACCEL 208.7s, **BRAKE 240.1s** | 240s was harsh *acceleration*; a harsh corner near the end is missing |
| → Home, 17:25 | 129 | **ACCEL 107.0s, CORNER 107.0s, BRAKE 107.4s**, BRAKE 269.4s, **CORNER 269.5s** | 107s was a slalom (cornering only); 269s was straight-line emergency braking (no cornering) |

## Root causes, in evidence order

### 1. The magnetometer corrupts the world-frame projection

The recorder samples `TYPE_ROTATION_VECTOR`, whose yaw is fused from the magnetometer. Inside a
car the magnetometer is unreliable — vehicle iron, electronics — and its error *varies with
heading and time*. Measured against GPS course on ride 127: the rotation-vector heading wanders
4–47° from the true course, including a 14° step snap at 200.6s that itself fired the stored
ACCEL event at 200.3s.

The detector rotates acceleration into the world frame (`R·a`) and projects it onto a heading.
The calibrated-axis path cancels a *constant* yaw error exactly, but not a varying one; the
GPS-heading fallback path cancels nothing. Every degree of yaw error mixes lateral force into
longitudinal and vice versa by `sin δ`.

Ride 129 is the worst case: its axis calibration was *rejected* (coherence 0.914 < 0.95 —
scattered by exactly this yaw wobble), so the whole ride ran on the GPS-heading fallback.
During the slalom the interpolated GPS course froze at 275° while the car's real heading swung —
the swinging lateral force (±0.45 g true, confirmed by gyro `v·ω`) projected onto the frozen
axis as alternating ±0.29 g of fake braking/acceleration *plus* cornering: all three stored
events. During the emergency stop, the projection sent 0.56 g of the braking vector into
"lateral": the fake cornering event.

### 2. The phone was not rigidly mounted

Both rides show the phone upright (in hand) for the first seconds, then lying flat for the whole
drive. A flat, loose phone slides and rocks a few degrees during hard maneuvers. Ride 127 at
240.1s: the raw accelerometer shows the *phone* lurching backwards at 0.3 g for ~250 ms while
GPS Doppler shows the *car* gaining speed — the driver had just floored it, and the phone
slid. That lurch is the stored "harsh braking". No frame math can fix it; the accelerometer
honestly measured the phone. Worse, the sustained real acceleration that followed (Doppler slope
up to +0.34 g for 6 s) registered only ~0.15 g on the accelerometer (sliding phone plus the
rotation vector's gravity estimate tilting under sustained force), so the harsh acceleration the
driver expected never crossed a threshold. The same mechanism hid a genuinely hard brake at
201.6s (114→60 km/h, −0.5 g peak) almost completely.

### 3. Cornering had no second witness

Lateral acceleration is the only signal cornering detection reads, yet the gyro's yaw rate gives
an independent measurement of the same physics: in any turn, lateral = `v·ω`. Every false
cornering event in the logbook (rides 22, 74, 129, and the simultaneous brake+corner /
accel+corner pairs on rides 40 and 46) has `v·ω` of 0.01–0.16 g under a claimed 0.34–0.57 g of
"lateral" — braking/acceleration bleed, not turning. Every genuine corner has `v·ω` within ~20%
of the accelerometer's lateral.

### 4. The missed corner was a threshold razor edge

Ride 127's expected corner near the end exists in the data (298.7s, 0.35 g lateral, 0.43 g
`v·ω`) and measured 0.92 g/s jerk against the 0.9 g/s gate in the replica — production's
marginally different Kalman bearings landed it just under. A real, harsh evasive steer sat on
the wrong side of a knife edge.

## The rework

The redesign moves the decomposition out of the world frame entirely and gives every direction a
second, independent witness. Six coordinated changes:

1. **Device-frame decomposition.** Gravity is removed with the rotation matrix's vertical row
   (`up = Rᵀ·ẑ`), which yaw error cannot touch, and the horizontal remainder is projected onto
   the calibrated forward axis directly in device coordinates. No world frame, no heading, so
   rotation-vector yaw error — constant or wandering — cancels out of detection completely.
   Slope-proofing and gravity-exactness are unchanged (both live in the vertical row).

2. **Axis acceptance bar 0.95 → 0.80.** The old bar rejected calibrations scattered by yaw
   wobble because the world-frame method was per-sample sensitive to it. The device-frame method
   only needs the *mean* axis (standard error ~1° over hundreds of samples); the bar's remaining
   job is catching a re-mounted phone. Ride 129 (0.914) now calibrates instead of falling back.

3. **The GPS-heading per-sample fallback is removed.** It manufactured three of ride 129's five
   events. A ride whose axis cannot be calibrated gets accelerometer-based detection switched
   off rather than guessed — the codebase's own "no events beats invented events" rule — while
   the Doppler path (5) still catches sustained harshness there.

4. **Cornering = min(|lateral|, |v·ω|), enter jerk 0.9 → 0.8.** The gyro corroborates every
   cornering sample; longitudinal bleed and mount artifacts die because the car demonstrably
   wasn't yawing. With the false-positive path closed, the entry gate can afford the sensitivity
   that catches the real corner at 298.7s. (Verified: all stored genuine corners on rides 37,
   40, 46, 59, 47 survive; all bleed corners fall.)

5. **A Doppler Δv path for sustained harshness.** GPS Doppler speed measures the *car*
   regardless of how the phone is mounted. A trailing 3 s speed-change window arms a direction —
   +24 km/h within 3 s for acceleration, −40 km/h for braking — and while armed, the per-fix
   Doppler slope may open and sustain an event at the normal force floor. Jerk gates stay
   accelerometer-only (Doppler slopes are 1 Hz steps; differentiating them is meaningless).
   This recovers ride 127's expected harsh acceleration (+27 km/h in 3 s) and its hidden hard
   brake (−42 km/h in 3 s), and ride 129's full-throttle launch at 47s (+42 km/h in 3 s), while
   ordinary standing-start blips (+11 km/h in one second, then done — rides 23, 61) stay silent.

6. **Doppler corroboration veto on braking/acceleration events.** At close, an event's
   fix-bracketed speed change must agree with its claimed direction and at least 30% of its
   claimed magnitude (floor 0.8 m/s); otherwise it is discarded. This is the only defense
   against mount-lurch fakes: ride 127's fake brake (claimed −0.26 g avg, car actually +6 km/h)
   and its fake 208.7s acceleration (claimed +0.29 g avg, car actually −22 km/h) both die. A
   ride with no usable fixes around the event keeps it — no evidence is not counter-evidence.

Versioning: `AXIS_VERSION` 1→2, `EVENTS_VERSION` 10→11; the pipeline re-derives axis → events →
score for every ride on next launch, which is the entire migration.

## Validation against the logbook

All 96 rides in today's backup were replayed through the final design:

- **Ride 127** comes out as: ACCEL 200.3s, ACCEL ~245s *(recovered)*, CORNER 298.7s
  *(recovered)*, hard brake ~205s *(recovered via Δv)*; the fake brake at 240.1s and fake accel
  at 208.7s are vetoed. Matches the driver's account.
- **Ride 129** comes out as: ACCEL ~48s (the launch), CORNER 106.6s (the slalom, alone), BRAKE
  269.4s (the stop, alone), ACCEL ~278s (pulling away hard after the stop). Matches the
  driver's account; the slalom's three-way misfire and the stop's fake corner are gone.
- **Logbook-wide**: 29 stored events → 22 proposed; every removal is a verified artifact
  (gyro-uncorroborated corners, Doppler-contradicted longitudinals), every addition a verified
  real maneuver. No ride's count exploded; 34 short/parked rides still yield nothing.

## Known limits (deliberate)

- A loosely lying phone underreports per-axis force; sub-second stabs it absorbs are gone for
  good. The Δv path recovers only maneuvers long enough for GPS to see (≥ ~2 s). A rigid mount
  remains the honest fix.
- Events opened by the Δv path report the Doppler-measured peak and near-zero jerk — accurate,
  but timed to fix granularity (±1 s).
- The Δv arm thresholds (+24/−40 km/h per 3 s) are calibrated on this logbook; they are config
  knobs like every other threshold and re-tunable through the version-bump loop.
