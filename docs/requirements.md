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
| GAR-01 | M | Ridesafe shall provide a Garage page.                                                                        | Draft       | DR-VEH, NAV-01, ONB-01                 |
| GAR-02 | M | The Garage shall let the user create a vehicle.                                                              | Draft       | DR-VEH, ONB-02                         |
| GAR-03 | M | The Garage shall let the user edit an existing vehicle.                                                      | Implemented | DR-VEH                         |
| GAR-04 | M | The Garage shall let the user delete a vehicle.                                                              | Implemented | DR-VEH, UX-01                  |
| GAR-05 | M | The Garage shall display a list of vehicles showing key fields (make, model, license plate, primary marker). | Draft       | DR-VEH                         |
| GAR-06 | M | The Garage shall display a detailed view of a vehicle showing all its fields.                                | Draft       | DR-VEH                         |
| GAR-07 | M | The Garage shall let the user designate exactly one vehicle as the primary vehicle.                          | Draft       | DR-RID, DR-VEH, TRK-02, TRK-08 |
| GAR-08 | M | The Garage shall let the user map one or more paired Bluetooth devices to a vehicle for auto-detection.      | Implemented | DR-VEH, ONB-03, TRK-02, TRK-08         |

### 3.3 Logbook (rides)

| ID     | P | Requirement                                                                                                                                                                                          | Status      | Related                                                                        |
|--------|---|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------|--------------------------------------------------------------------------------|
| LOG-01 | M | Ridesafe shall provide a Logbook page.                                                                                                                                                               | Draft       | DR-RID, NAV-01                                                                 |
| LOG-02 | M | The Logbook shall display a list of rides showing key fields (date, distance, vehicle).                                                                                                              | Draft       | ADR-08, DR-RID, LOG-06, LOG-07, LOG-08, LOG-11, LOG-12, LOG-13, LOG-14, LOG-15 |
| LOG-03 | M | The Logbook shall display a detailed view of a single ride.                                                                                                                                          | Draft       | ADR-09, DR-RID, LOG-10                                                         |
| LOG-04 | M | The Logbook shall let the user edit a ride.                                                                                                                                                          | Draft       | DR-RID                                                                         |
| LOG-05 | M | The Logbook shall let the user delete a ride.                                                                                                                                                        | Draft       | DR-RID, UX-01                                                                  |
| LOG-06 | M | The Logbook shall let the user filter rides by a date range whose start and end are each independently optional.                                                                                     | Implemented | LOG-02, LOG-09, LOG-11                                                         |
| LOG-07 | S | The Logbook shall let the user filter rides by vehicle.                                                                                                                                              | Implemented | DR-VEH, LOG-02, LOG-09                                                         |
| LOG-08 | C | The Logbook shall let the user filter rides by tag.                                                                                                                                                  | Proposed    | DR-RID, LOG-02, LOG-09                                                         |
| LOG-09 | M | The Logbook shall let the user export the currently filtered set of rides.                                                                                                                           | Draft       | EXP-01, LOG-06, LOG-07, LOG-08, LOG-11, LOG-12, LOG-13, LOG-16                 |
| LOG-10 | S | The ride detail view shall visualize the route on a map and show speed information.                                                                                                                  | Draft       | DR-RID, LOG-03, NFR-02, TRK-01                                                 |
| LOG-11 | M | The Logbook shall let the user search rides by free text, matching saved place names, geocoded addresses, vehicle, date, weekday, time of day and duration.                                          | Implemented | ADR-08, DR-RID, DR-VEH, LOG-02, LOG-06, LOG-09                                 |
| LOG-12 | M | The Logbook shall let the user filter rides by start and/or end saved place, each independently optional; for a merged ride the trip's own start and end apply, not those of its intermediate stops. | Implemented | ADR-07, ADR-08, DR-ADR, LOG-02, LOG-09, MRG-05                                 |
| LOG-13 | S | The Logbook shall let the user filter rides by a distance range whose bounds are each independently optional; a ride whose distance is not computed yet is excluded while a bound is set.            | Implemented | ANL-02, DR-RID, LOG-02, LOG-09                                                 |
| LOG-14 | C | The Logbook shall let the user filter the list down to standalone rides or to merged rides.                                                                                                          | Implemented | LOG-02, MRG-01                                                                 |
| LOG-15 | C | The Logbook shall let the user filter the list down to rides with detected driving events.                                                                                                           | Implemented | ANL-01, DR-RID, LOG-02                                                         |
| LOG-16 | M | Ride selection in the Logbook, including "select all", shall act only on the rides left by the current search and filters, while the merge rules keep being evaluated against the whole logbook.     | Implemented | LOG-09, LOG-11, MRG-01, MRG-02                                                 |

### 3.4 Tracking & recording

| ID     | P | Requirement                                                                                                                                                                                           | Status      | Related                                                                |
|--------|---|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------|------------------------------------------------------------------------|
| TRK-01 | M | Ridesafe shall record rides using GPS location.                                                                                                                                                       | Draft       | ANL-01, DR-RID, LOG-10, NFR-05, NFR-06, NFR-08, TRK-02, TRK-05, TRK-06 |
| TRK-02 | M | Ridesafe shall automatically detect the start and end of a ride from the connection and disconnection of a Bluetooth device mapped to a vehicle.                                                      | Draft       | GAR-07, GAR-08, NFR-05, SET-06, TRK-01, TRK-03, TRK-07, TRK-08, TRK-09 |
| TRK-03 | M | Ridesafe shall record only car trips by gating automatic recording on a vehicle's Bluetooth mapping, so walking, cycling, and other vehicles do not trigger recording.                                | Proposed    | GAR-08, NFR-05, SET-06, TRK-02, TRK-08                                 |
| TRK-04 | M | During recording, Ridesafe shall sample motion sensors (accelerometer/gyroscope) alongside GPS to support safety scoring.                                                                             | Proposed    | ANL-01, ANL-03, DR-RID                                                 |
| TRK-05 | S | Ridesafe shall keep recording reliably while the app is in the background or the screen is off.                                                                                                       | Proposed    | NFR-05, NFR-06, NFR-08, TRK-01                                         |
| TRK-06 | S | Ridesafe shall tolerate temporary GPS signal loss (e.g. tunnels) without ending a ride prematurely.                                                                                                   | Proposed    | TRK-01                                                                 |
| TRK-07 | M | Ridesafe shall provide the ability to start ride recording manually when automatic ride detection is not available, failed, or disabled by the user                                                   | Proposed    | ONB-06, SET-06, TRK-02                                                         |
| TRK-08 | M | Ridesafe shall identify the current vehicle from its mapped Bluetooth device(s) to assign rides correctly and avoid recording rides taken as a passenger in foreign vehicles.                         | Proposed    | DR-VEH, GAR-07, GAR-08, SET-06, TRK-02, TRK-03                         |
| TRK-09 | S | Ridesafe shall continue an ongoing ride when the same vehicle reconnects within a short grace period after its Bluetooth disconnect, rather than ending the ride and starting a new one.              | Implemented | SET-10, TRK-01, TRK-02, TRK-05, TRK-06, TRK-08, TRK-10                 |
| TRK-10 | S | Ridesafe shall discard a recorded ride shorter than a minimum length (30 s by default) instead of logging it, so a Bluetooth blip or a move within the driveway leaves no trace.                      | Implemented | LOG-02, TRK-01, TRK-02, TRK-09, SET-11                                 |

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
- **Reconnect grace (TRK-09):** a car cuts its infotainment (and Bluetooth with it) when the driver
  gets out and brings it back seconds later, until the car is finally locked — which would otherwise
  file every exit as a ride end plus a fresh ride. The trigger still reports the disconnect
  immediately; the **recording layer** absorbs it: the ride keeps recording for a grace period
  (`RideRecordingEngine.reconnectGraceMs`, SET-10: off, 30 s, 1 min (default), 2 min or 5 min)
  into a hold-back buffer. The same vehicle
  reconnecting releases the buffer and the ride carries on uninterrupted; the grace expiring drops
  it, so the ride's end timestamp, end position, top speed and samples are exactly those of the
  moment the car first disconnected. A *different* vehicle connecting closes the held ride at that
  mark and starts a new one. The same buffering covers plain Bluetooth glitches mid-drive.
- **Minimum length (TRK-10):** a ride shorter than `RideRecordingEngine.minRideMs` (SET-11: off,
  15 s, 30 s (default), 1 min or 2 min) is deleted outright — row and sample file — rather than
  finalized, so a Bluetooth blip, a move within the driveway or a passenger's phone catching a
  connect never reaches the logbook. Length is measured to the TRK-09 mark, so the grace period
  never pads a short ride over the threshold. Rides left dangling by a kill (NFR-06) are held to
  the same rule when recovery finalizes them.
- **On-device (NFR-01):** activity recognition runs on-device; no trip or location data leaves the phone.

### 3.5 Dashboard

| ID     | P | Requirement                                                                     | Status | Related                |
|--------|---|---------------------------------------------------------------------------------|--------|------------------------|
| DSH-01 | M | Ridesafe shall provide a Dashboard page.                                        | Draft  | NAV-01                 |
| DSH-02 | M | The Dashboard shall show mileage statistics, ride statistics, and recent rides. | Draft  | ANL-02, DR-RID, DSH-05 |
| DSH-03 | S | The Dashboard shall show mileage trend visualizations.                          | Draft  | ANL-02, DSH-05         |
| DSH-04 | S | The Dashboard shall show driving-behavior / safety visualizations.              | Draft  | ANL-01, DSH-06         |
| DSH-05 | C | The Dashboard shall show monthly mileage summaries.                             | Draft  | ANL-02, DSH-02, DSH-03 |
| DSH-06 | C | The Dashboard shall show the current driver safety score.                       | Draft  | ANL-01, DSH-04, ONB-06         |

### 3.6 Analytics & safety

*ANL-01 originally included speeding. It was dropped rather than deferred: judging speeding needs
posted speed limits, and every source of those is a paid commercial API, which an on-device app
with no backend (NFR-03) cannot use. The three remaining dimensions are all derived from sensors
the phone already has.*

| ID     | P | Requirement                                                                                                                                                                                      | Status      | Related                                                |
|--------|---|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------|--------------------------------------------------------|
| ANL-01 | M | Ridesafe shall compute a driver safety score from GPS and motion-sensor data (harsh braking, hard acceleration, cornering).                                                                      | Implemented | ANL-05, DR-RID, DSH-04, DSH-06, GRP-02, LOG-15, ONB-06, TRK-01, TRK-04 |
| ANL-02 | M | Ridesafe shall compute mileage statistics.                                                                                                                                                       | Draft       | DR-RID, DSH-02, DSH-03, DSH-05, GRP-02, LOG-13, SET-07 |
| ANL-03 | S | Ridesafe shall provide fuel-efficiency insights from GPS and motion-sensor data (favouring steady moderate speed over stop-and-go, gentle acceleration, and gentle braking / gliding to a stop). | Draft       | ANL-06, ONB-06, SET-08, TRK-04                                 |
| ANL-04 | S | Ridesafe shall provide vehicle-usage statistics across the user's vehicles.                                                                                                                      | Draft       | DR-RID, DR-VEH                                         |
| ANL-05 | C | Ridesafe shall provide a per-ride safety ranking/comparison.                                                                                                                                     | Draft       | ANL-01                                                 |
| ANL-06 | C | Ridesafe shall estimate fuel cost per ride/period.                                                                                                                                               | Draft       | ANL-03, ANL-07, CST-02, DR-VEH, SET-08                 |
| ANL-07 | C | Ridesafe shall estimate CO₂ emissions.                                                                                                                                                           | Draft       | ANL-06, DR-VEH                                         |

### 3.7 Group rides (cluster your own rides)

| ID     | P | Requirement                                                                                                                          | Status   | Related                                |
|--------|---|--------------------------------------------------------------------------------------------------------------------------------------|----------|----------------------------------------|
| GRP-01 | S | Ridesafe shall let the user group several of their own rides into a named ride group (e.g. a recurring commute or a multi-leg trip). | Proposed | DR-GRP, DR-RID, GRP-02, GRP-03, NOT-01 |
| GRP-02 | S | Ridesafe shall display aggregated statistics (total distance, average safety score, …) for a ride group.                             | Proposed | ANL-01, ANL-02, DR-GRP, GRP-01         |
| GRP-03 | C | Ridesafe shall let the user add/remove rides from a group and delete a group.                                                        | Proposed | DR-GRP, GRP-01, UX-01                  |

### 3.8 Costbook

| ID     | P | Requirement                                                   | Status | Related                 |
|--------|---|---------------------------------------------------------------|--------|-------------------------|
| CST-01 | C | Ridesafe shall provide a Costbook for tracking vehicle costs. | Draft  | CST-03, DR-COST, DR-VEH |
| CST-02 | C | The Costbook shall calculate operating costs per vehicle.     | Draft  | ANL-06, DR-COST, DR-VEH |
| CST-03 | C | The Costbook shall provide expense summaries.                 | Draft  | CST-01, DR-COST         |

### 3.9 Export & reporting

| ID     | P | Requirement                                                                                       | Status   | Related                                |
|--------|---|---------------------------------------------------------------------------------------------------|----------|----------------------------------------|
| EXP-01 | M | Ridesafe shall export the selected/filtered set of rides.                                         | Draft    | DR-RID, EXP-02, EXP-03, LOG-09, NOT-02 |
| EXP-02 | S | Ridesafe shall generate a PDF report of rides.                                                    | Draft    | EXP-01, NOT-02                         |
| EXP-03 | S | Ridesafe shall export rides as CSV (spreadsheet / tax-logbook use).                               | Proposed | EXP-01, NOT-02                         |
| EXP-04 | C | Ridesafe shall let the user back up and restore the full local dataset to/from an on-device file. | Proposed | NFR-01, NFR-03                         |

### 3.10 Notifications

| ID     | P | Requirement                                                               | Status | Related                        |
|--------|---|---------------------------------------------------------------------------|--------|--------------------------------|
| NOT-01 | C | Ridesafe shall remind the user to assign ungrouped rides to a ride group. | Draft  | GRP-01, SET-09                 |
| NOT-02 | C | Ridesafe shall notify the user when an export completes.                  | Draft  | EXP-01, EXP-02, EXP-03, NFR-05 |

### 3.11 Settings

| ID     | P | Requirement                                                                                                            | Status      | Related                |
|--------|---|------------------------------------------------------------------------------------------------------------------------|-------------|------------------------|
| SET-01 | M | Ridesafe shall provide a Settings page.                                                                                | Draft       | ADR-01, ADR-03, NAV-01, ONB-07 |
| SET-02 | M | Ridesafe shall follow the system theme (light/dark) by default.                                                        | Draft       | NFR-10, NFR-12, SET-03 |
| SET-03 | S | Settings shall let the user override the theme (light / dark / follow system).                                         | Draft       | SET-02                 |
| SET-04 | M | Ridesafe shall follow the system language by default, falling back to English when the system language is unsupported. | Draft       | NFR-11, SET-05         |
| SET-05 | S | Settings shall let the user switch the app language between German and English.                                        | Draft       | NFR-11, SET-04         |
| SET-06 | M | Settings shall let the user set automatic recording: off (default), paired vehicles only, or any vehicle (unassigned). | Implemented | NFR-05, ONB-04, TRK-02, TRK-07, TRK-08 |
| SET-07 | S | Settings shall let the user choose speed units (mph / km/h).                                                           | Draft       | ANL-02, SET-08         |
| SET-08 | C | Settings shall let the user choose distance and fuel-economy units, applied consistently across the app.               | Proposed    | ANL-03, ANL-06, SET-07 |
| SET-09 | C | Settings shall let the user turn grouping reminders on or off.                                                         | Proposed    | NOT-01                 |
| SET-10 | S | Settings shall let the user set how long a ride keeps recording after the vehicle disconnects.                         | Implemented | SET-01, TRK-09         |
| SET-11 | S | Settings shall let the user set the minimum length a recording must reach to be kept as a ride.                        | Implemented | SET-01, TRK-10         |

### 3.12 Saved addresses

| ID     | P | Requirement                                                                                                                                                                                                                                                                                           | Status   | Related                                |
|--------|---|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|----------------------------------------|
| ADR-01 | M | Ridesafe shall let the user save named addresses ("places"), each anchored to an exact GPS point.                                                                                                                                                                                                     | Proposed | ADR-03, DR-ADR, ONB-05, SET-01                 |
| ADR-02 | M | Each saved address shall have a recognition radius around its GPS point (default 100 m, maximum 500 m) defining its parking/arrival area.                                                                                                                                                             | Proposed | ADR-07, DR-ADR                         |
| ADR-03 | M | Ridesafe shall provide a screen to add, edit, and delete saved addresses.                                                                                                                                                                                                                             | Proposed | DR-ADR, SET-01, UX-01                  |
| ADR-04 | M | The address editor shall let the user set the GPS point (drag a map pin, use current location, or take a ride endpoint) and adjust the radius.                                                                                                                                                        | Proposed | ADR-02, DR-ADR, LOG-10, NFR-02         |
| ADR-05 | M | Ridesafe shall provide shortcuts to create a Home, Work, and School address with fixed icons, each of which may exist at most once.                                                                                                                                                                   | Proposed | ADR-01, DR-ADR, ONB-05                         |
| ADR-06 | S | For custom addresses, Ridesafe shall let the user assign an icon from a curated set of Material Symbols.                                                                                                                                                                                              | Proposed | ADR-01, NFR-10                         |
| ADR-07 | M | When a ride's start or end point lies within a saved address's radius, Ridesafe shall recognize that endpoint as that address; on overlap, the nearest center wins.                                                                                                                                   | Proposed | ADR-02, DR-ADR, DR-RID, LOG-02, LOG-03 |
| ADR-08 | M | In the Logbook list, Ridesafe shall show the matched address's label in place of the raw address for a recognized ride endpoint.                                                                                                                                                                      | Proposed | ADR-07, LOG-02                         |
| ADR-09 | M | In the ride detail view, Ridesafe shall show a recognized endpoint as `<address>, <distance> from "<label>"` (user's distance units); when the endpoint's reverse-geocoded address exactly matches the saved address's stored address, the distance suffix shall be omitted and only the label shown. | Proposed | ADR-07, DR-RID, LOG-03, SET-08         |

#### Saved addresses — UX / entry points

- **Primary entry:** Settings → **"Saved addresses"** opens the management screen (ADR-03). No new
  nav destination — the bar already carries four, and this is low-frequency configuration.
- **Management screen:** Home / Work / School shortcut cards at the top (tap an empty one to create
  it pre-filled, ADR-05); a list of custom addresses below; a "+" FAB to add a custom one. Delete
  from the row or the editor, behind the UX-01 confirmation.
- **Editor:** label (locked for the three shortcuts), a map with a draggable pin + radius circle, a
  radius slider (…–500 m), a "use current location" button, and — for custom addresses — the icon
  picker (ADR-06).
- **Secondary entry:** ride detail → a **"Save as place"** action on the start/end endpoint opens
  the editor pre-filled with that GPS point (ADR-04).
- **Matching is computed live** at display time (recommended): editing or deleting a place instantly
  re-labels past rides with no stored back-reference to clean up — see open question 7.

### 3.13 Onboarding

| ID     | P | Requirement                                                                                                                                                                                     | Status      | Related                                        |
|--------|---|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------|------------------------------------------------|
| ONB-01 | S | On first launch with an empty garage, Ridesafe shall guide the user through initial setup in a full-screen step wizard shown before the main app; an installation that already has vehicles shall skip it silently. | Implemented | GAR-01, ONB-02, ONB-04, ONB-05, ONB-06, ONB-07 |
| ONB-02 | S | The onboarding shall let the user create their first vehicle, using the Garage's add form.                                                                                                       | Implemented | GAR-02, ONB-01, ONB-03                         |
| ONB-03 | S | The onboarding shall let the user map paired Bluetooth devices to the vehicle created in it.                                                                                                    | Implemented | GAR-08, ONB-02                                 |
| ONB-04 | S | The onboarding shall offer enabling automatic recording (paired vehicles only) and shall request the permissions it needs only after the user opts in.                                          | Implemented | NFR-05, ONB-01, SET-06                         |
| ONB-05 | S | The onboarding shall let the user create a first saved place, preset to the Home shortcut.                                                                                                      | Implemented | ADR-01, ADR-05, ONB-01                         |
| ONB-06 | S | The onboarding shall explain manual ride recording and the safety and eco scores, using the app's real score visuals with clearly labelled sample data.                                         | Implemented | ANL-01, ANL-03, DSH-06, ONB-01, TRK-07         |
| ONB-07 | S | Every onboarding step shall be individually skippable, the whole flow shall be leavable at any point, and Settings shall let the user replay it.                                                | Implemented | ONB-01, SET-01                                 |

#### Onboarding — UX / entry points

- **Wizard, not coach marks:** a full-screen step flow shown *instead of* the app (hosted above the
  navigation shell in MainActivity), embedding the real forms — vehicle add (GAR-02), the address
  editor (ADR-03/04) — so onboarding creates exactly what those screens would. Steps that act on the
  created car (ONB-03/04) drop out when the car step is skipped.
- **One bar, one back:** the embedded forms render chromeless inside the wizard — no own app bar,
  their save pinned full-width at the bottom like every step's primary action — so the wizard header
  is the only bar and back exists exactly once (its arrow, mirrored by the system back gesture);
  skipping forward lives only in the header. Returning to the car step re-opens the created car for
  editing (GAR-03) rather than offering a second blank form.
- **Permissions stay opt-in:** nothing is requested up front (the NFR-05/SET-06 rule); flipping the
  auto-record switch applies the paired-only mode and surfaces the same permission card Settings
  uses, including the background-location settings deep-link.
- **Replay:** Settings → Help → "Show the introduction again" re-enters the same wizard; the
  completed flag is not cleared, so abandoning a replay leaves nothing behind.

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
*Related: ADR-07, ADR-09, ANL-01, ANL-02, ANL-04, DSH-02, EXP-01, GAR-07, GRP-01, LOG-01, LOG-02, LOG-03, LOG-04, LOG-05, LOG-08, LOG-10, NFR-03, TRK-01, TRK-04*

| Field                               | Notes                                                                    |
|-------------------------------------|--------------------------------------------------------------------------|
| identifier                          |                                                                          |
| start timestamp                     |                                                                          |
| end timestamp                       |                                                                          |
| distance                            |                                                                          |
| route (track points)                |                                                                          |
| assigned vehicle                    |                                                                          |
| purpose description                 |                                                                          |
| notes                               |                                                                          |
| tags                                |                                                                          |
| safety score + safety-event summary | **NEW** — required by ANL-01; was missing despite being the core feature |
| average & max speed                 | **NEW** — supports analytics                                             |

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
| NFR-05 | M | Ridesafe shall request and gracefully handle location (foreground + background), activity-recognition, and notification permissions, including rationale screens, and shall surface any still-missing permission at the top of Settings and as a badge on the Settings tab. | Implemented | NAV-01, NOT-02, ONB-04, SET-06, TRK-01, TRK-02, TRK-03, TRK-05 |
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
