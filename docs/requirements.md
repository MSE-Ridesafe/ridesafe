# Ridesafe — Requirements Specification

> Consolidated, normalized version of the three source CSVs
> (`ridesafe_must_requirements.csv`, `ridesafe_shall_requirements.csv`,
> `ridesafe_can_requirements.csv`). This file is the single source of truth for
> requirement tracking going forward.

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
  statement. (The source files' `must`/`shall`/`can` split was a priority scheme,
  not formal language — "shall" formally equals "must", so it can't also be a
  middle tier. Priority now lives in the `P` column.)
- **ID** — `<AREA>-XX`, stable across priority changes (priority is a column, not
  baked into the ID).
- **Status** — `Draft` = carried over from your CSVs (cleaned up). `Proposed` =
  newly suggested by this review (also tagged **NEW** in the trace column). Later
  lifecycle values: `Approved` → `Implemented` → `Verified`.
- **Trace** — origin requirement ID(s) from the CSVs, and dependencies on other
  requirements.

---

## 3. Functional requirements

### 3.1 App shell & general UX

| ID     | P | Requirement                                                                                                               | Status | Trace  |
|--------|---|---------------------------------------------------------------------------------------------------------------------------|--------|--------|
| NAV-01 | M | Ridesafe shall provide a navigation bar to move between the primary pages (Dashboard, Logbook, Garage, Settings).         | Draft  | MR01   |
| UX-01  | M | Before deleting any user-created item (vehicle, ride, ride group, cost entry), Ridesafe shall show a confirmation prompt. | Draft  | MR05.1 |

### 3.2 Garage (vehicles)

| ID     | P | Requirement                                                                                                  | Status | Trace       |
|--------|---|--------------------------------------------------------------------------------------------------------------|--------|-------------|
| GAR-01 | M | Ridesafe shall provide a Garage page.                                                                        | Draft  | MR02        |
| GAR-02 | M | The Garage shall let the user create a vehicle.                                                              | Draft  | MR04        |
| GAR-03 | M | The Garage shall let the user edit an existing vehicle.                                                      | Draft  | MR06        |
| GAR-04 | M | The Garage shall let the user delete a vehicle.                                                              | Draft  | MR05; UX-01 |
| GAR-05 | M | The Garage shall display a list of vehicles showing key fields (make, model, license plate, primary marker). | Draft  | MR07        |
| GAR-06 | M | The Garage shall display a detailed view of a vehicle showing all its fields.                                | Draft  | MR08        |
| GAR-07 | M | The Garage shall let the user designate exactly one vehicle as the primary vehicle.                          | Draft  | MR23        |

### 3.3 Logbook (rides)

| ID     | P | Requirement                                                                             | Status   | Trace                                     |
|--------|---|-----------------------------------------------------------------------------------------|----------|-------------------------------------------|
| LOG-01 | M | Ridesafe shall provide a Logbook page.                                                  | Draft    | MR09                                      |
| LOG-02 | M | The Logbook shall display a list of rides showing key fields (date, distance, vehicle). | Draft    | MR11                                      |
| LOG-03 | M | The Logbook shall display a detailed view of a single ride.                             | Draft    | MR12                                      |
| LOG-04 | M | The Logbook shall let the user edit a ride.                                             | Draft    | MR16                                      |
| LOG-05 | M | The Logbook shall let the user delete a ride.                                           | Draft    | MR14; UX-01                               |
| LOG-06 | M | The Logbook shall let the user filter rides by date.                                    | Draft    | MR13                                      |
| LOG-07 | S | The Logbook shall let the user filter rides by vehicle.                                 | Proposed | **NEW** — multi-vehicle is a core purpose |
| LOG-08 | C | The Logbook shall let the user filter rides by tag.                                     | Proposed | **NEW**                                   |
| LOG-09 | M | The Logbook shall let the user export the currently filtered set of rides.              | Draft    | MR17; EXP-01                              |
| LOG-10 | S | The ride detail view shall visualize the route on a map and show speed information.     | Draft    | SR14                                      |

### 3.4 Tracking & recording

| ID     | P | Requirement                                                                                                                                                                                           | Status   | Trace                                          |
|--------|---|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|------------------------------------------------|
| TRK-01 | M | Ridesafe shall record rides using GPS location.                                                                                                                                                       | Draft    | MR21                                           |
| TRK-02 | M | Ridesafe shall automatically detect the start and end of a car ride.                                                                                                                                  | Draft    | MR20; SET-06                                   |
| TRK-03 | M | Ridesafe shall distinguish car driving from other movement (walking, cycling, transit) so only car trips are recorded.                                                                                | Proposed | **NEW** — required for TRK-02 to be meaningful |
| TRK-04 | M | During recording, Ridesafe shall sample motion sensors (accelerometer/gyroscope) alongside GPS to support safety scoring.                                                                             | Proposed | **NEW** — safety = GPS + motion sensors        |
| TRK-05 | S | Ridesafe shall keep recording reliably while the app is in the background or the screen is off.                                                                                                       | Proposed | **NEW**; NFR-05                                |
| TRK-06 | S | Ridesafe shall tolerate temporary GPS signal loss (e.g. tunnels) without ending a ride prematurely.                                                                                                   | Proposed | **NEW**                                        |
| TRK-07 | M | Ridesafe shall provide the ability to start ride recording manually when automatic ride detection is not available, failed, or disabled by the user                                                   | Proposed | TRK-02                                         |
| TRK-08 | S | Ridesafe shall differentiate between the currently sat-in car using bluetooth or other individual indicators of the current car, to avoid falsely recording rides as a passenger in foreign vehicles. | Proposed | TRK-02                                         |

### 3.5 Dashboard

| ID     | P | Requirement                                                                     | Status | Trace                     |
|--------|---|---------------------------------------------------------------------------------|--------|---------------------------|
| DSH-01 | M | Ridesafe shall provide a Dashboard page.                                        | Draft  | MR18                      |
| DSH-02 | M | The Dashboard shall show mileage statistics, ride statistics, and recent rides. | Draft  | MR19                      |
| DSH-03 | S | The Dashboard shall show mileage trend visualizations.                          | Draft  | SR12                      |
| DSH-04 | S | The Dashboard shall show driving-behavior / safety visualizations.              | Draft  | SR13; ANL-01              |
| DSH-05 | C | The Dashboard shall show monthly mileage summaries.                             | Draft  | CR02 (overlaps DSH-02/03) |
| DSH-06 | C | The Dashboard shall show the current driver safety score.                       | Draft  | CR03; ANL-01              |

### 3.6 Analytics & safety

| ID     | P | Requirement                                                                                                                                                                                      | Status | Trace                                                      |
|--------|---|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|------------------------------------------------------------|
| ANL-01 | M | Ridesafe shall compute a driver safety score from GPS and motion-sensor data (harsh braking, hard acceleration, cornering, speeding).                                                            | Draft  | SR09 (safety); promoted to Must — headline feature; TRK-04 |
| ANL-02 | M | Ridesafe shall compute mileage statistics.                                                                                                                                                       | Draft  | SR09 (mileage)                                             |
| ANL-03 | S | Ridesafe shall provide fuel-efficiency insights from GPS and motion-sensor data (favouring steady moderate speed over stop-and-go, gentle acceleration, and gentle braking / gliding to a stop). | Draft  | SR10; TRK-04                                               |
| ANL-04 | S | Ridesafe shall provide vehicle-usage statistics across the user's vehicles.                                                                                                                      | Draft  | SR11                                                       |
| ANL-05 | C | Ridesafe shall provide a per-ride safety ranking/comparison.                                                                                                                                     | Draft  | CR06                                                       |
| ANL-06 | C | Ridesafe shall estimate fuel cost per ride/period.                                                                                                                                               | Draft  | CR04; CST                                                  |
| ANL-07 | C | Ridesafe shall estimate CO₂ emissions.                                                                                                                                                           | Draft  | CR05                                                       |

### 3.7 Group rides (cluster your own rides)

| ID     | P | Requirement                                                                                                                          | Status   | Trace                                          |
|--------|---|--------------------------------------------------------------------------------------------------------------------------------------|----------|------------------------------------------------|
| GRP-01 | S | Ridesafe shall let the user group several of their own rides into a named ride group (e.g. a recurring commute or a multi-leg trip). | Proposed | **NEW** — core purpose, previously unspecified |
| GRP-02 | S | Ridesafe shall display aggregated statistics (total distance, average safety score, …) for a ride group.                             | Proposed | **NEW**                                        |
| GRP-03 | C | Ridesafe shall let the user add/remove rides from a group and delete a group.                                                        | Proposed | **NEW**; UX-01                                 |

### 3.8 Costbook

| ID     | P | Requirement                                                   | Status | Trace |
|--------|---|---------------------------------------------------------------|--------|-------|
| CST-01 | C | Ridesafe shall provide a Costbook for tracking vehicle costs. | Draft  | CR01  |
| CST-02 | C | The Costbook shall calculate operating costs per vehicle.     | Draft  | CR07  |
| CST-03 | C | The Costbook shall provide expense summaries.                 | Draft  | CR08  |

### 3.9 Export & reporting

| ID     | P | Requirement                                                                                       | Status   | Trace                                   |
|--------|---|---------------------------------------------------------------------------------------------------|----------|-----------------------------------------|
| EXP-01 | M | Ridesafe shall export the selected/filtered set of rides.                                         | Draft    | MR17                                    |
| EXP-02 | S | Ridesafe shall generate a PDF report of rides.                                                    | Draft    | SR15                                    |
| EXP-03 | S | Ridesafe shall export rides as CSV (spreadsheet / tax-logbook use).                               | Proposed | **NEW** (default — confirm formats, §6) |
| EXP-04 | C | Ridesafe shall let the user back up and restore the full local dataset to/from an on-device file. | Proposed | **NEW** — no cloud = device loss risk   |

### 3.10 Notifications

| ID     | P | Requirement                                                               | Status | Trace                                            |
|--------|---|---------------------------------------------------------------------------|--------|--------------------------------------------------|
| NOT-01 | C | Ridesafe shall remind the user to assign ungrouped rides to a ride group. | Draft  | CR09 (reinterpreted as grouping); GRP-01; SET-09 |
| NOT-02 | C | Ridesafe shall notify the user when an export completes.                  | Draft  | CR10                                             |

### 3.11 Settings

| ID     | P | Requirement                                                                                                            | Status   | Trace                               |
|--------|---|------------------------------------------------------------------------------------------------------------------------|----------|-------------------------------------|
| SET-01 | M | Ridesafe shall provide a Settings page.                                                                                | Draft    | SR03 (was mislabeled in shall file) |
| SET-02 | M | Ridesafe shall follow the system theme (light/dark) by default.                                                        | Draft    | SR01, SR02                          |
| SET-03 | S | Settings shall let the user override the theme (light / dark / follow system).                                         | Draft    | SR04 ("design system" → theme)      |
| SET-04 | M | Ridesafe shall follow the system language by default, falling back to English when the system language is unsupported. | Draft    | SR05                                |
| SET-05 | S | Settings shall let the user switch the app language between German and English.                                        | Draft    | SR06 ("englisch" typo)              |
| SET-06 | M | Settings shall let the user enable/disable automatic ride recording.                                                   | Draft    | SR07; TRK-02                        |
| SET-07 | S | Settings shall let the user choose speed units (mph / km/h).                                                           | Draft    | SR08                                |
| SET-08 | C | Settings shall let the user choose distance and fuel-economy units, applied consistently across the app.               | Proposed | **NEW** — broadens SR08             |
| SET-09 | C | Settings shall let the user turn grouping reminders on or off.                                                         | Proposed | **NEW**; NOT-01                     |

---

## 4. Data requirements (entities)

### 4.1 Vehicle — `DR-VEH` (M, trace MR03)

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
| fuel economy, tank size | optional — needed only for ANL-06 fuel cost / ANL-07 CO₂ |

### 4.2 Ride — `DR-RID` (M, trace MR10)

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

### 4.3 Ride group — `DR-GRP` (S, **NEW**)

| Field                  | Notes            |
|------------------------|------------------|
| identifier             |                  |
| name                   |                  |
| member ride references |                  |
| aggregate stats        | derived (GRP-02) |

### 4.4 Cost entry — `DR-COST` (C, **NEW** — implied by Costbook, never defined)

| Field      | Notes                              |
|------------|------------------------------------|
| identifier |                                    |
| vehicle    |                                    |
| date       |                                    |
| category   | fuel / maintenance / insurance / … |
| amount     |                                    |
| notes      |                                    |

---

## 5. Non-functional requirements

| ID     | P | Requirement                                                                                                                                                                                                                         | Status   | Trace                                                             |
|--------|---|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|-------------------------------------------------------------------|
| NFR-01 | M | All user data (rides, locations, vehicles, costs) shall be stored and processed exclusively on-device; Ridesafe shall not transmit user data to any external server.                                                                | Proposed | **NEW** — the defining constraint; MR22 covered only ride storage |
| NFR-02 | M | Ridesafe may fetch map tiles / geocoding from public map APIs for display only, and shall not upload stored trip/location data to them beyond the minimum needed to render the requested view.                                      | Proposed | **NEW** (see tension note, §6)                                    |
| NFR-03 | M | All entities (vehicles, rides, groups, costs, settings) shall be persisted in a local on-device database.                                                                                                                           | Draft    | MR22 (generalized)                                                |
| NFR-04 | M | Ridesafe shall be a native Android app implemented in Jetpack Compose, compiling against and targeting Android SDK 37 with a minimum supported SDK of 34.                                                                           | Proposed | **NEW** — target SDK 37 / min SDK 34                              |
| NFR-05 | M | Ridesafe shall request and gracefully handle location (foreground + background), activity-recognition, and notification permissions, including rationale screens.                                                                   | Proposed | **NEW** — required by TRK-02..05                                  |
| NFR-06 | M | An in-progress ride recording shall survive app restarts/crashes without losing already-recorded data.                                                                                                                              | Proposed | **NEW**                                                           |
| NFR-07 | M | All core features except map-tile display shall work without an internet connection.                                                                                                                                                | Proposed | **NEW** — follows from on-device design                           |
| NFR-08 | S | Continuous tracking shall minimize battery drain (adaptive sampling, pause when stationary).                                                                                                                                        | Proposed | **NEW**                                                           |
| NFR-09 | S | Ridesafe shall meet basic accessibility guidelines (content descriptions, contrast, dynamic font scaling).                                                                                                                          | Proposed | **NEW**                                                           |
| NFR-10 | M | Ridesafe shall use Material Design 3 (including Material 3 Expressive where appropriate), Material Icons, and native Android UI components wherever appropriate, staying as close to the native platform look and feel as possible. | Proposed | **NEW**; SET-02/03                                                |
| NFR-11 | M | Ridesafe shall be fully internationalized, with all user-facing strings externalized and localized for German and English.                                                                                                          | Proposed | **NEW**; SET-04/05                                                |
| NFR-12 | M | Ridesafe shall use Material You dynamic color (themed from the system wallpaper) instead of a fixed brand palette.                                                                                                                  | Proposed | **NEW**; min SDK 34 guarantees availability                       |
| NFR-13 | M | Ridesafe shall render edge-to-edge and support predictive-back gestures while respecting Android system-bar and gesture insets.                                                                                                     | Proposed | **NEW**                                                           |
| NFR-14 | M | Ridesafe shall build responsive, tablet-capable layouts using the Compose adaptive layout APIs (window size classes), not Fragments.                                                                                                | Proposed | **NEW** — hard requirement                                        |

---

## 6. Open questions / to be defined

1. **Map provider & the NFR-02 tension** — rendering a route inherently sends viewport coordinates to whichever tile/geocoding provider you pick. "No external processing of user data" and "use a public map API" are in mild tension. Decide: which provider, and is sending view coordinates acceptable (vs. bundling offline tiles)?
2. **Device sensors** — SDK decided (target 37 / min 34, NFR-04). Remaining: degrade gracefully on devices lacking a gyroscope/accelerometer.
3. **Export formats** — confirmed PDF (EXP-02) + CSV (EXP-03). Also GPX for routes? Anything else?
4. **Group-ride priority** — I set GRP-01/02 to Should; confirm that's right for a "core purpose."
5. **Safety algorithm** — thresholds/weighting for ANL-01 are design-level, not captured here; flag if you want them as requirements.
6. **Optional tiers** — Costbook, CO₂, and notifications are all Could; confirm none should be promoted.

## 7. Non-goals (out of scope)

- **Right-to-left (RTL) layouts** — only German/English are targeted; YAGNI.
- **Any backend, cloud sync, or remote processing** — see NFR-01.
- **Multi-user / social / live-group features** — "group rides" means clustering the user's *own* rides (GRP-01..03).
- **Custom branding / bespoke theme** — university project; rely on Material You dynamic color (NFR-12).
