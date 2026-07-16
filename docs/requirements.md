# Ridesafe — Requirements Specification

> Single source of truth for Ridesafe's requirements and their tracking.

## 1. Product overview

Ridesafe is a **native Android app built with Jetpack Compose** for drivers who
want to record their car trips, get an insurer-style **driver safety rating**,
group their own rides, and analyse usage across multiple vehicles.

Defining constraint: **everything runs on-device. There is no backend.** Public
map APIs (Google Maps / OpenStreetMap / Mapbox / …) may be used to render maps,
but user data (trips, locations, vehicles, costs) is never stored or processed
externally — all analytics and safety scoring happen locally.

## 2. Conventions

- **Priority (MoSCoW)** — `M` Must-have, `S` Should-have, `C` Could-have.
- **Wording** — every requirement is a single "Ridesafe shall …" / "The X shall …"
  statement. (The original `must`/`shall`/`can` split was a priority scheme,
  not formal language — "shall" formally equals "must", so it can't also be a
  middle tier. Priority now lives in the `P` column.)
- **ID** — `<AREA>-XX`, stable across priority changes (priority is a column, not
  baked into the ID).
- **Status** — `Draft` = baseline requirement. `Proposed` = added during review,
  pending confirmation. Later lifecycle values: `Approved` → `Implemented` → `Verified`.
- **Related** — IDs of other requirements (and data entities `DR-…`) this one
  interoperates with or depends on. Links are bidirectional.

---

## 3. Functional requirements

### 3.1 App shell & general UX

| ID     | P | Requirement                                                                                                               | Status | Related                                |
|--------|---|---------------------------------------------------------------------------------------------------------------------------|--------|----------------------------------------|
| NAV-01 | M | Ridesafe shall provide a navigation bar to move between the primary pages (Dashboard, Logbook, Garage, Settings).         | Draft  | DSH-01, GAR-01, LOG-01, SET-01         |
| UX-01  | M | Before deleting any user-created item (vehicle, ride, ride group, cost entry), Ridesafe shall show a confirmation prompt. | Draft  | ADR-03, GAR-04, GRP-03, LOG-05, MRG-03 |

### 3.2 Garage (vehicles)

| ID     | P | Requirement                                                                                                  | Status      | Related                        |
|--------|---|--------------------------------------------------------------------------------------------------------------|-------------|--------------------------------|
| GAR-01 | M | Ridesafe shall provide a Garage page.                                                                        | Draft       | DR-VEH, NAV-01                 |
| GAR-02 | M | The Garage shall let the user create a vehicle.                                                              | Draft       | DR-VEH                         |
| GAR-03 | M | The Garage shall let the user edit an existing vehicle.                                                      | Implemented | DR-VEH                         |
| GAR-04 | M | The Garage shall let the user delete a vehicle.                                                              | Implemented | DR-VEH, UX-01                  |
| GAR-05 | M | The Garage shall display a list of vehicles showing key fields (make, model, license plate, primary marker). | Draft       | DR-VEH                         |
| GAR-06 | M | The Garage shall display a detailed view of a vehicle showing all its fields.                                | Draft       | DR-VEH                         |
| GAR-07 | M | The Garage shall let the user designate exactly one vehicle as the primary vehicle.                          | Draft       | DR-RID, DR-VEH, TRK-02, TRK-08 |
| GAR-08 | M | The Garage shall let the user map one or more paired Bluetooth devices to a vehicle for auto-detection.      | Implemented | DR-VEH, TRK-02, TRK-08         |

### 3.3 Logbook (rides)

| ID     | P | Requirement                                                                                                                                                                                                                                                                                                                                                                      | Status   | Related                                        |
|--------|---|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|------------------------------------------------|
| LOG-01 | M | Ridesafe shall provide a Logbook page.                                                                                                                                                                                                                                                                                                                                           | Draft    | DR-RID, NAV-01                                 |
| LOG-02 | M | The Logbook shall display a list of rides showing key fields (date, distance, vehicle).                                                                                                                                                                                                                                                                                          | Draft    | ADR-08, DR-RID, LOG-06, LOG-07, LOG-08, LOG-11 |
| LOG-03 | M | The Logbook shall display a detailed view of a single ride.                                                                                                                                                                                                                                                                                                                      | Draft    | ADR-09, DR-RID, LOG-10, MRG-01, MRG-04         |
| LOG-04 | M | The Logbook shall let the user edit a ride.                                                                                                                                                                                                                                                                                                                                      | Draft    | DR-RID, MRG-01, MRG-03                         |
| LOG-05 | M | The Logbook shall let the user delete a ride.                                                                                                                                                                                                                                                                                                                                    | Draft    | DR-RID, UX-01                                  |
| LOG-06 | M | The Logbook shall let the user filter rides by date.                                                                                                                                                                                                                                                                                                                             | Draft    | LOG-02, LOG-09                                 |
| LOG-07 | S | The Logbook shall let the user filter rides by vehicle.                                                                                                                                                                                                                                                                                                                          | Proposed | DR-VEH, LOG-02, LOG-09                         |
| LOG-08 | C | The Logbook shall let the user filter rides by tag.                                                                                                                                                                                                                                                                                                                              | Proposed | DR-RID, LOG-02, LOG-09                         |
| LOG-09 | M | The Logbook shall let the user export the currently filtered set of rides.                                                                                                                                                                                                                                                                                                       | Draft    | EXP-01, LOG-06, LOG-07, LOG-08                 |
| LOG-10 | S | The ride detail view shall visualize the route on a map and show speed information.                                                                                                                                                                                                                                                                                              | Draft    | DR-RID, LOG-03, MRG-07, NFR-02, TRK-01         |
| LOG-11 | M | The Logbook shall enter a multi-select mode on long-press of a ride, presenting a contextual top app bar (Material 3) showing the selection count, a close/exit affordance, select-all / deselect-all utilities, and an overflow (⋮) menu of actions on the selection; selected rides shall be marked with a filled circular checkmark per native Android selection conventions. | Draft    | LOG-01, LOG-02, MRG-08, NFR-10                 |

### 3.4 Tracking & recording

| ID     | P | Requirement                                                                                                                                                                                                                                                                   | Status   | Related                                                                        |
|--------|---|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|--------------------------------------------------------------------------------|
| TRK-01 | M | Ridesafe shall record rides using GPS location.                                                                                                                                                                                                                               | Draft    | ANL-01, DR-RID, LOG-10, NFR-05, NFR-06, NFR-08, TRK-02, TRK-05, TRK-06         |
| TRK-02 | M | Ridesafe shall automatically detect the start and end of a ride from the connection and disconnection of a Bluetooth device mapped to a vehicle.                                                                                                                              | Draft    | GAR-07, GAR-08, NFR-05, SET-06, TRK-01, TRK-03, TRK-07, TRK-08, TRK-09, TRK-10 |
| TRK-03 | M | Ridesafe shall record only car trips by gating automatic recording on a vehicle's Bluetooth mapping, so walking, cycling, and other vehicles do not trigger recording.                                                                                                        | Proposed | GAR-08, NFR-05, SET-06, TRK-02, TRK-08, TRK-10                                 |
| TRK-04 | M | During recording, Ridesafe shall sample motion sensors (accelerometer/gyroscope) alongside GPS to support safety scoring.                                                                                                                                                     | Proposed | ANL-01, ANL-03, DR-RID                                                         |
| TRK-05 | S | Ridesafe shall keep recording reliably while the app is in the background or the screen is off.                                                                                                                                                                               | Proposed | NFR-05, NFR-06, NFR-08, TRK-01                                                 |
| TRK-06 | S | Ridesafe shall tolerate temporary GPS signal loss (e.g. tunnels) without ending a ride prematurely.                                                                                                                                                                           | Proposed | TRK-01                                                                         |
| TRK-07 | M | Ridesafe shall provide the ability to start ride recording manually when automatic ride detection is not available, failed, or disabled by the user                                                                                                                           | Proposed | SET-06, TRK-02                                                                 |
| TRK-08 | M | Ridesafe shall identify the current vehicle from its mapped Bluetooth device(s) to assign rides correctly and avoid recording rides taken as a passenger in foreign vehicles.                                                                                                 | Proposed | DR-VEH, GAR-07, GAR-08, SET-06, TRK-02, TRK-03                                 |
| TRK-09 | M | Ridesafe shall not end an in-progress ride recording when the Bluetooth connection to the mapped vehicle is briefly interrupted; recording shall continue and end only when the disconnection is sustained beyond a configurable timeout, or when the user stops it manually. | Proposed | NFR-06, NFR-08, SET-06, TRK-01, TRK-02, TRK-06, TRK-07                         |
| TRK-10 | M | After a ride recording ends, Ridesafe shall suppress automatic-start triggers for a cooldown period, preventing a brief reconnect, a nearby walk, or a cycling leg immediately after a car trip from starting a new recording.                                                | Proposed | SET-06, TRK-02, TRK-03, TRK-08                                                 |

#### Auto-tracking trigger — implementation notes

The auto-tracking **trigger** (detection + vehicle mapping + the SET-06 mode) is implemented
and unit-tested. **Ride recording is not built yet**: the trigger fires start/end events
through a `RideRecorder` seam (`tracking/RideRecorder.kt`) that currently only logs; the
recording layer plugs in there. TRK-02/03/08 are therefore *trigger-complete* but stay open
end-to-end until recording (TRK-01, DR-RID) exists.

- **Mapping (GAR-08):** a vehicle is linked to one or more **paired** Bluetooth devices, chosen
  from the phone's bonded-device list — no need to be in the car. Stored as MAC addresses in
  `DR-VEH.bluetoothAddresses`; requires the `BLUETOOTH_CONNECT` permission.
- **Detection per mode (SET-06):**
  - *Paired vehicles only* (default) — a Bluetooth ACL connect/disconnect of a mapped device
    (manifest receiver) starts/ends the trip and assigns that vehicle.
  - *Any vehicle* — Activity Recognition `IN_VEHICLE` (Google Play Services, `ACTIVITY_RECOGNITION`)
    starts/ends the trip and assigns a connected mapped vehicle if any, else leaves it unassigned.
  - *Off* — no detection.
- **Reboot:** a `RECEIVE_BOOT_COMPLETED` receiver re-arms tracking after a restart.
- **Why ACL, not CompanionDeviceManager:** CDM can only associate a device through a
  present-device scan dialog, so it can't map a saved car with the engine off; ACL broadcasts
  were chosen instead. Background delivery is reliable on most devices and will be hardened via
  the recording foreground service (TRK-05) once recording exists.
- **On-device (NFR-01):** activity recognition runs on-device; no trip or location data leaves the phone.
- **BT drop tolerance (TRK-09):** a brief disconnection mid-ride (phone call, interference, momentary range loss) must not end the recording. The implementation should hold a "disconnection pending" timer — configurable, default TBD — and only finalize the ride if the device has been gone for the full duration. A reconnect within the window resets the timer and the ride continues.
- **Post-ride cooldown (TRK-10):** once a ride ends, the trigger must ignore BT connect events and activity signals for a cooldown window (configurable, default TBD) before it will start a new recording. This prevents the common false-positive of the phone reconnecting briefly while the user walks away from their parked car, or a subsequent cycling leg being recorded as a car trip.

### 3.5 Dashboard

| ID     | P | Requirement                                                                     | Status | Related                |
|--------|---|---------------------------------------------------------------------------------|--------|------------------------|
| DSH-01 | M | Ridesafe shall provide a Dashboard page.                                        | Draft  | NAV-01                 |
| DSH-02 | M | The Dashboard shall show mileage statistics, ride statistics, and recent rides. | Draft  | ANL-02, DR-RID, DSH-05 |
| DSH-03 | S | The Dashboard shall show mileage trend visualizations.                          | Draft  | ANL-02, DSH-05         |
| DSH-04 | S | The Dashboard shall show driving-behavior / safety visualizations.              | Draft  | ANL-01, DSH-06         |
| DSH-05 | C | The Dashboard shall show monthly mileage summaries.                             | Draft  | ANL-02, DSH-02, DSH-03 |
| DSH-06 | C | The Dashboard shall show the current driver safety score.                       | Draft  | ANL-01, DSH-04         |

### 3.6 Analytics & safety

| ID     | P | Requirement                                                                                                                                                                                      | Status | Related                                                |
|--------|---|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|--------------------------------------------------------|
| ANL-01 | M | Ridesafe shall compute a driver safety score from GPS and motion-sensor data (harsh braking, hard acceleration, cornering, speeding).                                                            | Draft  | ANL-05, DR-RID, DSH-04, DSH-06, GRP-02, TRK-01, TRK-04 |
| ANL-02 | M | Ridesafe shall compute mileage statistics.                                                                                                                                                       | Draft  | DR-RID, DSH-02, DSH-03, DSH-05, GRP-02, SET-07         |
| ANL-03 | S | Ridesafe shall provide fuel-efficiency insights from GPS and motion-sensor data (favouring steady moderate speed over stop-and-go, gentle acceleration, and gentle braking / gliding to a stop). | Draft  | ANL-06, SET-08, TRK-04                                 |
| ANL-04 | S | Ridesafe shall provide vehicle-usage statistics across the user's vehicles.                                                                                                                      | Draft  | DR-RID, DR-VEH                                         |
| ANL-05 | C | Ridesafe shall provide a per-ride safety ranking/comparison.                                                                                                                                     | Draft  | ANL-01                                                 |
| ANL-06 | C | Ridesafe shall estimate fuel cost per ride/period.                                                                                                                                               | Draft  | ANL-03, ANL-07, CST-02, DR-VEH, SET-08                 |
| ANL-07 | C | Ridesafe shall estimate CO₂ emissions.                                                                                                                                                           | Draft  | ANL-06, DR-VEH                                         |

### 3.7 Group rides (cluster your own rides)

| ID     | P | Requirement                                                                                                                          | Status   | Related                                |
|--------|---|--------------------------------------------------------------------------------------------------------------------------------------|----------|----------------------------------------|
| GRP-01 | S | Ridesafe shall let the user group several of their own rides into a named ride group (e.g. a recurring commute or a multi-leg trip). | Proposed | DR-GRP, DR-RID, GRP-02, GRP-03, NOT-01 |
| GRP-02 | S | Ridesafe shall display aggregated statistics (total distance, average safety score, …) for a ride group.                             | Proposed | ANL-01, ANL-02, DR-GRP, GRP-01         |
| GRP-03 | C | Ridesafe shall let the user add/remove rides from a group and delete a group.                                                        | Proposed | DR-GRP, GRP-01, UX-01                  |

### 3.8 Ride merging (merge subsequent rides into one trip)

Distinct from ride *groups* (§3.7): a group is a named, unordered label over otherwise-independent
rides for shared stats. A merge instead collapses a contiguous run of **one vehicle's** subsequent
rides into a single merged ride whose constituent rides become its "stops", replacing them with one
entry in the Logbook until un-merged. Merges are physically honest: each stop keeps its own recorded
route, and the parked gap between two stops implies no travel — so no aggregated metric spans a gap
(MRG-05, MRG-07). Merging is initiated from the Logbook's multi-select mode (LOG-11); un-merging from
the merged ride's detail view (MRG-11).

*(Note: MRG-07 was reworked — the map now keeps each stop's own start and end and draws them
disconnected, replacing the earlier "collapse to N+1 points / use the subsequent ride's start"
approach, because fabricating a shared boundary point implies GPS/speed/time that never happened.)*

| ID     | P | Requirement                                                                                                                                                                                                                                                                                                                                                                                                                                                               | Status   | Related                                                        |
|--------|---|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|----------------------------------------------------------------|
| MRG-01 | M | Ridesafe shall let the user merge two or more subsequent rides of the same vehicle, with no upper limit on how many, into a single merged ride, retaining each original ride as a "stop" within it.                                                                                                                                                                                                                                                                       | Draft    | DR-RID, LOG-03, LOG-04, MRG-02, MRG-03, MRG-05, MRG-07, MRG-09 |
| MRG-02 | M | Ridesafe shall only allow merging a contiguous run of the selected vehicle's rides: the user shall not skip one of that vehicle's rides lying chronologically between two selected ones. Rides of other vehicles that fall within that span do not break the run and are not part of the merge (MRG-09).                                                                                                                                                                  | Draft    | MRG-01, MRG-04, MRG-08, MRG-09                                 |
| MRG-03 | M | Ridesafe shall let the user un-merge a merged ride, restoring its stops to independent rides.                                                                                                                                                                                                                                                                                                                                                                             | Draft    | MRG-01, MRG-04, MRG-11, UX-01                                  |
| MRG-04 | M | The detail view of a merged ride shall list its stops individually and indicate which ones can be un-merged on their own — only a stop at either end of the sequence, since removing an interior one would break the contiguity rule (MRG-02).                                                                                                                                                                                                                            | Draft    | LOG-03, MRG-01, MRG-02, MRG-03, MRG-11                         |
| MRG-05 | M | When rides are merged or un-merged, Ridesafe shall recompute the merged ride's distance and duration as the sum of its stops' (excluding the parked gaps between stops), its average speed as total distance over total moving duration (not an average of per-stop speeds), and its top speed as the maximum among its stops. No metric shall span the gap between two stops (MRG-07).                                                                                   | Draft    | ANL-02, DR-RID, MRG-01, MRG-07                                 |
| MRG-06 | S | Ridesafe shall recompute any other derived per-ride metric (e.g. safety score) for a merged ride from its stops using the same mathematically-valid aggregation as MRG-05, rather than a naive average.                                                                                                                                                                                                                                                                   | Proposed | ANL-01, MRG-05                                                 |
| MRG-07 | M | On the map, Ridesafe shall render each stop's route as its own polyline with its own start and end marker, with no line connecting one stop's end to the next stop's start; the boundary between consecutive stops shall be treated as unrelated (the car was parked) so no distance, speed, or elapsed time is implied across it.                                                                                                                                        | Draft    | LOG-10, MRG-01, MRG-05                                         |
| MRG-08 | M | In the Logbook's multi-select mode (LOG-11), Ridesafe shall offer "Merge rides" in the selection's overflow (⋮) menu, enabled only when the selection is a valid merge set (two or more subsequent same-vehicle rides per MRG-02/MRG-09); otherwise it shall appear disabled with a short message explaining why (e.g. rides are not subsequent, or belong to different vehicles).                                                                                        | Draft    | LOG-11, MRG-01, MRG-02, MRG-09                                 |
| MRG-09 | M | Ridesafe shall permit merging only rides that share the same assigned vehicle; rides of different vehicles shall not be mergeable together, and rides with no assigned vehicle shall not be mergeable at all. When another vehicle's rides fall chronologically between two rides of the selected vehicle, those same-vehicle rides still count as subsequent to each other, so the merge stays possible without including the other vehicle's rides.                     | Draft    | DR-VEH, MRG-01, MRG-02, MRG-08                                 |
| MRG-10 | S | Ridesafe shall let the user select an existing merged ride together with one or more further same-vehicle rides (or another merged ride) and merge them, inserting each added ride at its correct chronological position within the merged ride rather than nesting merges.                                                                                                                                                                                               | Draft    | MRG-01, MRG-02, MRG-09                                         |
| MRG-11 | M | In the merged ride's detail view (LOG-03), Ridesafe shall let the user un-merge stops only from the start or end of the sequence, and un-merge several at once as long as they form a contiguous run inward from the start and/or the end. An "Unmerge All" action shall always be available. When a merged ride has only two stops, Ridesafe shall offer only "Unmerge All" (no individual-stop selection), since either single un-merge would dissolve the pair anyway. | Draft    | LOG-03, MRG-03, MRG-04, UX-01                                  |

### 3.9 Costbook

| ID     | P | Requirement                                                   | Status | Related                 |
|--------|---|---------------------------------------------------------------|--------|-------------------------|
| CST-01 | C | Ridesafe shall provide a Costbook for tracking vehicle costs. | Draft  | CST-03, DR-COST, DR-VEH |
| CST-02 | C | The Costbook shall calculate operating costs per vehicle.     | Draft  | ANL-06, DR-COST, DR-VEH |
| CST-03 | C | The Costbook shall provide expense summaries.                 | Draft  | CST-01, DR-COST         |

### 3.10 Export & reporting

| ID     | P | Requirement                                                                                       | Status   | Related                                |
|--------|---|---------------------------------------------------------------------------------------------------|----------|----------------------------------------|
| EXP-01 | M | Ridesafe shall export the selected/filtered set of rides.                                         | Draft    | DR-RID, EXP-02, EXP-03, LOG-09, NOT-02 |
| EXP-02 | S | Ridesafe shall generate a PDF report of rides.                                                    | Draft    | EXP-01, NOT-02                         |
| EXP-03 | S | Ridesafe shall export rides as CSV (spreadsheet / tax-logbook use).                               | Proposed | EXP-01, NOT-02                         |
| EXP-04 | C | Ridesafe shall let the user back up and restore the full local dataset to/from an on-device file. | Proposed | NFR-01, NFR-03                         |

### 3.11 Notifications

| ID     | P | Requirement                                                               | Status | Related                        |
|--------|---|---------------------------------------------------------------------------|--------|--------------------------------|
| NOT-01 | C | Ridesafe shall remind the user to assign ungrouped rides to a ride group. | Draft  | GRP-01, SET-09                 |
| NOT-02 | C | Ridesafe shall notify the user when an export completes.                  | Draft  | EXP-01, EXP-02, EXP-03, NFR-05 |

### 3.12 Settings

| ID     | P | Requirement                                                                                                            | Status      | Related                |
|--------|---|------------------------------------------------------------------------------------------------------------------------|-------------|------------------------|
| SET-01 | M | Ridesafe shall provide a Settings page.                                                                                | Draft       | NAV-01                 |
| SET-02 | M | Ridesafe shall follow the system theme (light/dark) by default.                                                        | Draft       | NFR-10, NFR-12, SET-03 |
| SET-03 | S | Settings shall let the user override the theme (light / dark / follow system).                                         | Draft       | SET-02                 |
| SET-04 | M | Ridesafe shall follow the system language by default, falling back to English when the system language is unsupported. | Draft       | NFR-11, SET-05         |
| SET-05 | S | Settings shall let the user switch the app language between German and English.                                        | Draft       | NFR-11, SET-04         |
| SET-06 | M | Settings shall let the user set automatic recording: off, paired vehicles only (default), or any vehicle (unassigned). | Implemented | TRK-02, TRK-07, TRK-08 |
| SET-07 | S | Settings shall let the user choose speed units (mph / km/h).                                                           | Draft       | ANL-02, SET-08         |
| SET-08 | C | Settings shall let the user choose distance and fuel-economy units, applied consistently across the app.               | Proposed    | ANL-03, ANL-06, SET-07 |
| SET-09 | C | Settings shall let the user turn grouping reminders on or off.                                                         | Proposed    | NOT-01                 |

---

## 4. Data requirements (entities)

### 4.1 Vehicle — `DR-VEH` (M)
*Related: ANL-04, ANL-06, ANL-07, CST-01, CST-02, GAR-01, GAR-02, GAR-03, GAR-04, GAR-05, GAR-06, GAR-07, GAR-08, LOG-07, NFR-03, TRK-08*

| Field                   | Notes                                                    |
|-------------------------|----------------------------------------------------------|
| id                      | **NEW** — entity had no identifier                       |
| name / nickname         | **NEW** — disambiguate multiple vehicles in lists        |
| make                    |                                                          |
| model                   |                                                          |
| year                    |                                                          |
| license plate           |                                                          |
| fuel type               |                                                          |
| mileage / odometer      |                                                          |
| isPrimary               | from GAR-07                                              |
| bluetooth mapping       | **NEW** — paired device MAC(s) for auto-detection        |
| fuel economy, tank size | optional — needed only for ANL-06 fuel cost / ANL-07 CO₂ |

### 4.2 Ride — `DR-RID` (M)
*Related: ADR-07, ADR-09, ANL-01, ANL-02, ANL-04, DSH-02, EXP-01, GAR-07, GRP-01, LOG-01, LOG-02, LOG-03, LOG-04, LOG-05, LOG-08, LOG-10, MRG-01, MRG-02, MRG-04, MRG-05, MRG-06, MRG-07, NFR-03, TRK-01, TRK-04*

| Field                               | Notes                                                                                                                                                                                                                                                                      |
|-------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| identifier                          |                                                                                                                                                                                                                                                                            |
| start timestamp                     |                                                                                                                                                                                                                                                                            |
| end timestamp                       |                                                                                                                                                                                                                                                                            |
| distance                            |                                                                                                                                                                                                                                                                            |
| route (track points)                |                                                                                                                                                                                                                                                                            |
| assigned vehicle                    |                                                                                                                                                                                                                                                                            |
| purpose description                 |                                                                                                                                                                                                                                                                            |
| notes                               |                                                                                                                                                                                                                                                                            |
| tags                                |                                                                                                                                                                                                                                                                            |
| safety score + safety-event summary | **NEW** — required by ANL-01; was missing despite being the core feature                                                                                                                                                                                                   |
| average & max speed                 | **NEW** — supports analytics                                                                                                                                                                                                                                               |
| merge group id                      | **NEW** — non-null tags this ride as a stop in a merged ride; all stops of one merged ride share it, null = standalone (MRG-01). No separate merged-ride row and no stored stop-order: stops order by start timestamp, metrics/addresses derived on read (MRG-05, MRG-07). |

### 4.3 Ride group — `DR-GRP` (S)
*Related: GRP-01, GRP-02, GRP-03, NFR-03*

| Field                  | Notes            |
|------------------------|------------------|
| identifier             |                  |
| name                   |                  |
| member ride references |                  |
| aggregate stats        | derived (GRP-02) |

### 4.4 Cost entry — `DR-COST` (C)
*Related: CST-01, CST-02, CST-03, NFR-03*

| Field      | Notes                              |
|------------|------------------------------------|
| identifier |                                    |
| vehicle    |                                    |
| date       |                                    |
| category   | fuel / maintenance / insurance / … |
| amount     |                                    |
| notes      |                                    |

### 4.5 Saved address — `DR-ADR` (M)
*Related: ADR-01, ADR-02, ADR-03, ADR-04, ADR-05, ADR-07, ADR-09, DR-RID, NFR-03*

| Field      | Notes                                                                                                      |
|------------|------------------------------------------------------------------------------------------------------------|
| id         |                                                                                                            |
| label      | "Home", "Work", "School", or a custom name                                                                 |
| kind       | home / work / school / custom — enforces the three singleton shortcuts and fixed icons                     |
| latitude   |                                                                                                            |
| longitude  |                                                                                                            |
| address    | reverse-geocoded (or entered) address at the point; compared for the ADR-09 exact-match suffix suppression |
| radius (m) | ≤ 500; default 100                                                                                         |
| icon       | Material Symbols name; fixed for home/work/school, user-chosen for custom (ADR-06)                         |

---

## 5. Non-functional requirements

| ID     | P | Requirement                                                                                                                                                                                                                         | Status   | Related                                         |
|--------|---|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|-------------------------------------------------|
| NFR-01 | M | All user data (rides, locations, vehicles, costs) shall be stored and processed exclusively on-device; Ridesafe shall not transmit user data to any external server.                                                                | Proposed | EXP-04, NFR-02, NFR-03, NFR-07                  |
| NFR-02 | M | Ridesafe may fetch map tiles / geocoding from public map APIs for display only, and shall not upload stored trip/location data to them beyond the minimum needed to render the requested view.                                      | Proposed | LOG-10, NFR-01, NFR-07                          |
| NFR-03 | M | All entities (vehicles, rides, groups, costs, settings) shall be persisted in a local on-device database.                                                                                                                           | Draft    | DR-COST, DR-GRP, DR-RID, DR-VEH, EXP-04, NFR-01 |
| NFR-04 | M | Ridesafe shall be a native Android app implemented in Jetpack Compose, compiling against and targeting Android SDK 37 with a minimum supported SDK of 34.                                                                           | Proposed | NFR-10, NFR-12, NFR-13, NFR-14                  |
| NFR-05 | M | Ridesafe shall request and gracefully handle location (foreground + background), activity-recognition, and notification permissions, including rationale screens.                                                                   | Proposed | NOT-02, TRK-01, TRK-02, TRK-03, TRK-05          |
| NFR-06 | M | An in-progress ride recording shall survive app restarts/crashes without losing already-recorded data.                                                                                                                              | Proposed | TRK-01, TRK-05                                  |
| NFR-07 | M | All core features except map-tile display shall work without an internet connection.                                                                                                                                                | Proposed | NFR-01, NFR-02                                  |
| NFR-08 | S | Continuous tracking shall minimize battery drain (adaptive sampling, pause when stationary).                                                                                                                                        | Proposed | TRK-01, TRK-05                                  |
| NFR-09 | S | Ridesafe shall meet basic accessibility guidelines (content descriptions, contrast, dynamic font scaling).                                                                                                                          | Proposed | NFR-10                                          |
| NFR-10 | M | Ridesafe shall use Material Design 3 (including Material 3 Expressive where appropriate), Material Icons, and native Android UI components wherever appropriate, staying as close to the native platform look and feel as possible. | Proposed | NFR-04, NFR-09, NFR-12, SET-02                  |
| NFR-11 | M | Ridesafe shall be fully internationalized, with all user-facing strings externalized and localized for German and English.                                                                                                          | Proposed | SET-04, SET-05                                  |
| NFR-12 | M | Ridesafe shall use Material You dynamic color (themed from the system wallpaper) instead of a fixed brand palette.                                                                                                                  | Proposed | NFR-04, NFR-10, SET-02                          |
| NFR-13 | M | Ridesafe shall render edge-to-edge and support predictive-back gestures while respecting Android system-bar and gesture insets.                                                                                                     | Proposed | NFR-04                                          |
| NFR-14 | M | Ridesafe shall build responsive, tablet-capable layouts using the Compose adaptive layout APIs (window size classes), not Fragments.                                                                                                | Proposed | NFR-04                                          |

---

## 6. Open questions / to be defined

1. **Map provider & the NFR-02 tension** — rendering a route inherently sends viewport coordinates to whichever tile/geocoding provider you pick. "No external processing of user data" and "use a public map API" are in mild tension. Decide: which provider, and is sending view coordinates acceptable (vs. bundling offline tiles)?
2. **Device sensors** — SDK decided (target 37 / min 34, NFR-04). Remaining: degrade gracefully on devices lacking a gyroscope/accelerometer.
3. **Export formats** — confirmed PDF (EXP-02) + CSV (EXP-03). Also GPX for routes? Anything else?
4. **Group-ride priority** — I set GRP-01/02 to Should; confirm that's right for a "core purpose."
5. **Safety algorithm** — thresholds/weighting for ANL-01 are design-level, not captured here; flag if you want them as requirements.
6. **Optional tiers** — Costbook, CO₂, and notifications are all Could; confirm none should be promoted.
7. **Saved-address matching — live vs. stored** — recommend computing the match at display time (a place edit instantly re-labels past rides; deleting a place needs no orphan cleanup) rather than denormalizing a matched-address id onto `DR-RID`. Confirm.
8. **Radius default & floor** — proposed default 100 m, adjustable 25–500 m. Confirm the floor (too small never matches given GPS noise) and the default.
9. **Setting the point** — pin drag + "use current location" + "save from a ride endpoint". Also allow typing an address to forward-geocode (native Geocoder, already an accepted exception)?
10. **Icon set** — which curated subset of Material Symbols is offered for custom places? The full font is thousands of glyphs; pick a short list.
11. **Home / Work / School** — confirm each is a singleton with a fixed, non-editable icon (kind drives the icon).
12. **Export wording** — should exported / PDF / CSV rides use the raw geocoded address (tax-accurate) or the friendly label? Recommend the raw address.
13. **Exact-match suppression (ADR-09)** — this compares two reverse-geocoded strings, and the Geocoder can return slightly different strings for the same point across runs, so strict equality may rarely fire. Confirm string equality is intended, or suppress the suffix when the endpoint is at the saved point (distance ≈ 0 / below a small threshold) instead.

## 7. Non-goals (out of scope)

- **Right-to-left (RTL) layouts** — only German/English are targeted; YAGNI.
- **Any backend, cloud sync, or remote processing** — see NFR-01.
- **Multi-user / social / live-group features** — "group rides" means clustering the user's *own* rides (GRP-01..03).
- **Custom branding / bespoke theme** — university project; rely on Material You dynamic color (NFR-12).
