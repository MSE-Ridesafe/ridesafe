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

### 1. Magnetometer yaw wobble pushes rides into the GPS-heading fallback, and the fallback misfiles

The recorder samples `TYPE_ROTATION_VECTOR`, whose yaw is fused from the magnetometer. Inside a
car the magnetometer is unreliable — vehicle iron, electronics — and its error *varies with
heading and time*. Measured against GPS course on ride 127: the rotation-vector heading wanders
4–47° from the true course, including a 14° step snap at 200.6s that itself fired the stored
ACCEL event at 200.3s.

The calibrated-axis path is algebraically immune to this — `(R·a)·normalize(R·f)` cancels any
common rotation about the vertical — but the *coherence check* is not: yaw wander scatters the
calibration samples, and the 0.95 bar read that scatter as "no usable axis". The ride then fell
back to projecting world-rotated acceleration onto the interpolated GPS course, which both
carries the full yaw error and lags the car's real heading through every maneuver.

Ride 129 is the worst case: its axis calibration was *rejected* (coherence 0.914 < 0.95, pure
yaw scatter — the mean axis itself was accurate), so the whole ride ran on the fallback. During
the slalom the interpolated course froze at 275° while the car's real heading swung — the
swinging lateral force (±0.45 g true, confirmed by gyro `v·ω`) projected onto the frozen course
as alternating ±0.29 g of fake braking/acceleration *plus* cornering: all three stored events.
During the emergency stop, the projection sent 0.56 g of the braking vector into "lateral": the
fake cornering event. Replaying the same ride with its (rejected) axis and the calibrated-path
math produces exactly the driver's account — one cornering event, one braking event.

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
   the calibrated forward axis directly in device coordinates. Algebraically this is what the
   world-frame projection onto `R·forward` already computed — the rewrite states it in the frame
   where the yaw-immunity is explicit, and drops the one implementation wart of the old path
   (heading was computed from whatever rotation was latest at drain time, not the sample's own).

2. **Axis acceptance bar 0.95 → 0.80.** The old bar treated calibration scatter as "no usable
   axis", but the scatter is mostly magnetometer yaw wobble around an accurate mean, and the
   calibrated split depends only on the mean (standard error ~1° over hundreds of samples). The
   bar's remaining job is catching a re-mounted phone. Ride 129 (0.914) now calibrates instead
   of falling back.

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

All 96 rides in today's backup were replayed through the finished Kotlin implementation
(`RealRideReplayTest`, pointed at the backup's `f/rides/`):

- **Ride 127** comes out as: ACCEL 200.2s, ACCEL 245.2s *(the maneuver the fake brake had
  eaten)*, CORNER 298.7s *(the missing corner)*; the fake brake at 240.1s and the phantom accel
  at 208.7s are vetoed by Doppler. Matches the driver's account.
- **Ride 129** comes out as: ACCEL 48.5s (the launch), CORNER 106.6s (the slalom, alone), BRAKE
  269.4s at a true 1.11 g (the stop, alone), ACCEL 278.5s (pulling away hard after the stop).
  Matches the driver's account; the slalom's three-way misfire and the stop's fake corner are
  gone.
- **Logbook-wide**: 29 stored events → 21, across 9 rides. Every removal was audited against
  the raw data and is a confirmed artifact — corners the gyro never witnessed (rides 22, 74,
  46's brake-corner pair), longitudinals the Doppler contradicts (ride 33's "0.71 g brake"
  during which speed drifted −3 km/h over six seconds; ride 127's mount lurch). The few
  additions (rides 93, 115, 129's launch) are Doppler-corroborated sustained maneuvers. No
  ride's count exploded; short/parked rides still yield nothing.

## Follow-up (same day): scoring coupling and re-seated phones

Driver review of the reworked build surfaced two downstream gaps.

**The score ignored what detection had learned.** The Home ride's acceleration score sat at 93
despite two confirmed hard launches, one full-throttle: both entered through the Doppler-armed
path, so the accelerometer-side jerk histograms — 70% of the penalty weight — never saw them.
Fix: detected events now add their own penalty (`ScoreWeights.eventWeight`), priced by the same
density curve from each event's measured average/peak g and duration. Whatever detection learns
to see, the score charges for, with no separate tuning pass. Near-floor events cost fractions of
a second, so clean rides are untouched (ride 127: 93 → 92) while the Home ride's acceleration
lands at 61 and its emergency stop pulls braking to 5. ScoreStage v3.

**A re-seated phone voided whole rides.** The 16:42 Bördestraße ride (5.8 km, 11 min) reported
"no sufficient data": the driver re-seated the phone ~3.5 minutes in, the axis contributions
split into two clusters ~130° apart, and the whole-ride average failed coherence — correctly, but
at the cost of the entire ride. Calibration is now segmented per mounting epoch: five consecutive
contributions disagreeing with the running mean by >45° close an epoch and seed the next, each
epoch is accepted on its own count and coherence, and detection re-seeds its projection filters at
the boundary while the unproven gap between epochs stays unmeasurable. The ride now scores (327 s
qualified, 47% coverage) with no invented events. AXIS_VERSION 3, EVENTS_VERSION 12.

## Known limits (deliberate)

- A loosely lying phone underreports per-axis force; maneuvers it absorbs are recovered by the
  Δv path only when their hard phase outlasts roughly the 3 s window — the arm inherently lags
  by the window's length, so a 2 s hidden brake stays missed (observed once on ride 127, at
  202s). A rigid mount remains the honest fix.
- Events opened by the Δv path report the Doppler-measured peak and near-zero jerk — accurate,
  but timed to fix granularity (±1 s).
- The Δv arm thresholds (+24/−40 km/h per 3 s) are calibrated on this logbook; they are config
  knobs like every other threshold and re-tunable through the version-bump loop, using
  `RealRideReplayTest` against extracted ride files.
