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

| ID     | P | Requirement                                                                                                               | Status | Related                        |
|--------|---|---------------------------------------------------------------------------------------------------------------------------|--------|--------------------------------|
| NAV-01 | M | Ridesafe shall provide a navigation bar to move between the primary pages (Dashboard, Logbook, Garage, Settings).         | Draft  | DSH-01, GAR-01, LOG-01, SET-01 |
| UX-01  | M | Before deleting any user-created item (vehicle, ride, ride group, cost entry), Ridesafe shall show a confirmation prompt. | Draft  | GAR-04, GRP-03, LOG-05         |

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

### 3.3 Logbook (rides)

| ID     | P | Requirement                                                                             | Status   | Related                        |
|--------|---|-----------------------------------------------------------------------------------------|----------|--------------------------------|
| LOG-01 | M | Ridesafe shall provide a Logbook page.                                                  | Draft    | DR-RID, NAV-01                 |
| LOG-02 | M | The Logbook shall display a list of rides showing key fields (date, distance, vehicle). | Draft    | DR-RID, LOG-06, LOG-07, LOG-08 |
| LOG-03 | M | The Logbook shall display a detailed view of a single ride.                             | Draft    | DR-RID, LOG-10                 |
| LOG-04 | M | The Logbook shall let the user edit a ride.                                             | Draft    | DR-RID                         |
| LOG-05 | M | The Logbook shall let the user delete a ride.                                           | Draft    | DR-RID, UX-01                  |
| LOG-06 | M | The Logbook shall let the user filter rides by date.                                    | Draft    | LOG-02, LOG-09                 |
| LOG-07 | S | The Logbook shall let the user filter rides by vehicle.                                 | Proposed | DR-VEH, LOG-02, LOG-09         |
| LOG-08 | C | The Logbook shall let the user filter rides by tag.                                     | Proposed | DR-RID, LOG-02, LOG-09         |
| LOG-09 | M | The Logbook shall let the user export the currently filtered set of rides.              | Draft    | EXP-01, LOG-06, LOG-07, LOG-08 |
| LOG-10 | S | The ride detail view shall visualize the route on a map and show speed information.     | Draft    | DR-RID, LOG-03, NFR-02, TRK-01 |

### 3.4 Tracking & recording

| ID     | P | Requirement                                                                                                                                                                                           | Status   | Related                                                                |
|--------|---|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|------------------------------------------------------------------------|
| TRK-01 | M | Ridesafe shall record rides using GPS location.                                                                                                                                                       | Draft    | ANL-01, DR-RID, LOG-10, NFR-05, NFR-06, NFR-08, TRK-02, TRK-05, TRK-06 |
| TRK-02 | M | Ridesafe shall automatically detect the start and end of a car ride.                                                                                                                                  | Draft    | GAR-07, NFR-05, SET-06, TRK-01, TRK-03, TRK-07, TRK-08                 |
| TRK-03 | M | Ridesafe shall distinguish car driving from other movement (walking, cycling, transit) so only car trips are recorded.                                                                                | Proposed | NFR-05, TRK-02                                                         |
| TRK-04 | M | During recording, Ridesafe shall sample motion sensors (accelerometer/gyroscope) alongside GPS to support safety scoring.                                                                             | Proposed | ANL-01, ANL-03, DR-RID                                                 |
| TRK-05 | S | Ridesafe shall keep recording reliably while the app is in the background or the screen is off.                                                                                                       | Proposed | NFR-05, NFR-06, NFR-08, TRK-01                                         |
| TRK-06 | S | Ridesafe shall tolerate temporary GPS signal loss (e.g. tunnels) without ending a ride prematurely.                                                                                                   | Proposed | TRK-01                                                                 |
| TRK-07 | M | Ridesafe shall provide the ability to start ride recording manually when automatic ride detection is not available, failed, or disabled by the user                                                   | Proposed | SET-06, TRK-02                                                         |
| TRK-08 | S | Ridesafe shall differentiate between the currently sat-in car using bluetooth or other individual indicators of the current car, to avoid falsely recording rides as a passenger in foreign vehicles. | Proposed | DR-VEH, GAR-07, TRK-02                                                 |

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

| ID     | P | Requirement                                                                                                            | Status   | Related                |
|--------|---|------------------------------------------------------------------------------------------------------------------------|----------|------------------------|
| SET-01 | M | Ridesafe shall provide a Settings page.                                                                                | Draft    | NAV-01                 |
| SET-02 | M | Ridesafe shall follow the system theme (light/dark) by default.                                                        | Draft    | NFR-10, NFR-12, SET-03 |
| SET-03 | S | Settings shall let the user override the theme (light / dark / follow system).                                         | Draft    | SET-02                 |
| SET-04 | M | Ridesafe shall follow the system language by default, falling back to English when the system language is unsupported. | Draft    | NFR-11, SET-05         |
| SET-05 | S | Settings shall let the user switch the app language between German and English.                                        | Draft    | NFR-11, SET-04         |
| SET-06 | M | Settings shall let the user enable/disable automatic ride recording.                                                   | Draft    | TRK-02, TRK-07         |
| SET-07 | S | Settings shall let the user choose speed units (mph / km/h).                                                           | Draft    | ANL-02, SET-08         |
| SET-08 | C | Settings shall let the user choose distance and fuel-economy units, applied consistently across the app.               | Proposed | ANL-03, ANL-06, SET-07 |
| SET-09 | C | Settings shall let the user turn grouping reminders on or off.                                                         | Proposed | NOT-01                 |

---

## 4. Data requirements (entities)

### 4.1 Vehicle — `DR-VEH` (M)
*Related: ANL-04, ANL-06, ANL-07, CST-01, CST-02, GAR-01, GAR-02, GAR-03, GAR-04, GAR-05, GAR-06, GAR-07, LOG-07, NFR-03, TRK-08*

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

### 4.2 Ride — `DR-RID` (M)
*Related: ANL-01, ANL-02, ANL-04, DSH-02, EXP-01, GAR-07, GRP-01, LOG-01, LOG-02, LOG-03, LOG-04, LOG-05, LOG-08, LOG-10, NFR-03, TRK-01, TRK-04*

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

## 7. Non-goals (out of scope)

- **Right-to-left (RTL) layouts** — only German/English are targeted; YAGNI.
- **Any backend, cloud sync, or remote processing** — see NFR-01.
- **Multi-user / social / live-group features** — "group rides" means clustering the user's *own* rides (GRP-01..03).
- **Custom branding / bespoke theme** — university project; rely on Material You dynamic color (NFR-12).
