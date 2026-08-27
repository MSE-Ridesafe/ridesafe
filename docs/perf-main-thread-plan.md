# Main-thread performance audit — tab switches & ride detail transitions

Date: 2026-08-27 · Branch: `bugfix/performance-on-tab-switch`
Scope: why tab switches (esp. Dashboard) stall before content "snaps in", and why the ride-detail
close animation stalls/skips. Audit covered the recent scoring/eco/adaptive-layout commits
(`61d5f14`..`a9e9199`, `20edc20`) plus everything on the composition path of the affected screens.

## How the symptoms map to causes

| Symptom | Root cause |
|---|---|
| Dashboard blank → snaps in after lag, worse since scoring | F1 (cold flow, heavy transform on main, no caching) |
| Rides/Home tab-switch fade stutters | F1 + F2 (both tabs compose from scratch mid-fade) |
| Ride detail open: occasional severe lag | F3 (GoogleMap + per-event marker bitmaps land mid-animation), worst while F6 (backfill) runs |
| Ride detail close: animation never runs, snaps after stall | F2 (full list recomposition incl. search index on main, mid pane-animation) + map teardown |

## Findings

### F1 — `HomeViewModel.dashboard` is cold, uncached, and computes on the main thread. **[primary]**
[HomeViewModel.kt:33](app/src/main/java/de/uhi/enia/ridesafe/ui/screens/home/HomeViewModel.kt) exposes a raw
`combine(vehicleDao.observeAll(), rideDao.observeAll()) { … }` with **no `flowOn` and no `stateIn`**, collected in
[HomeNavigation.kt:14](app/src/main/java/de/uhi/enia/ridesafe/ui/screens/home/HomeNavigation.kt) via
`collectAsState(initial = HomeDashboardState())`.

Consequences:
- The transform — `logicalRideJourneys`, month totals, activity-by-day, highlights, **3 safety-score windows,
  weekly + monthly score maps, the all-time score history (O(days × rides)), all-time eco profile, and one eco
  profile per vehicle** — executes on the **UI dispatcher** (a `combine` transform runs in the collector's context).
- The flow is cold: **every** visit to the Home tab restarts collection → fresh Room queries + full recompute,
  racing the 250 ms tab-switch fade. First frame shows the empty `initial`, then the whole dashboard
  recomposes at once when the state lands → the "snap".
- Every ride-row write from the analysis pipeline re-emits `observeAll` → recompute per write, on main.

Every other ViewModel already does this right (`RidesViewModel.entries` even documents the pattern:
`.flowOn(Dispatchers.Default)` + `stateIn(viewModelScope, SharingStarted.Eagerly, …)`); Home was the only
one left out, and the scoring/eco commits piled their entire cost into exactly this transform.

**Fix:** `.flowOn(Dispatchers.Default)` + `.stateIn(viewModelScope, SharingStarted.Eagerly, HomeDashboardState())`;
collect without `initial`. Tab switch then reads a warm value on frame one; recomputes happen off-main.

### F2 — Logbook search index + filtering built on the main thread at every list composition. **[primary for close-jank]**
[RidesScreen.kt:97](app/src/main/java/de/uhi/enia/ridesafe/ui/screens/rides/RidesScreen.kt):
`remember(entries, context) { searchIndex(context, entries) }` builds the folded search text for **all** entries —
3 `DateUtils.formatDateTime` calls + `Normalizer.normalize` + regex per entry
([RideFilter.kt:173](app/src/main/java/de/uhi/enia/ridesafe/ui/screens/rides/RideFilter.kt)) — during composition.

Since the adaptive-layout commit (`20edc20`), list↔detail is one `ListDetailSceneStrategy` scene: on a phone the
list pane leaves composition while a detail is open and must **re-compose from scratch on the first frame of the
pop animation**. That frame pays: search index (~75 entries × date formatting) + `applyFilter` + day-grouping +
initial `LazyColumn` layout — while the detail's GoogleMap is still alive and about to be torn down. The pane
animation gets no frames until this completes → "snaps back after a lag".

**Fix (lazy):** the index is only consulted when a search query is non-blank (`applyFilter` short-circuits
otherwise). Build it only when `filter.query` is non-blank — a no-search composition (the normal case, and always
the pop-animation case if no query is active) pays nothing. While a query *is* active, build it off-main
(`produceState`/Default) or accept the one-off cost at the first keystroke, when no animation is running.

### F3 — GoogleMap + marker bitmaps inflate mid-transition on detail open. **[primary for open-jank]**
[RidesNavigation.kt:114](app/src/main/java/de/uhi/enia/ridesafe/ui/screens/rides/RidesNavigation.kt) loads the
route quickly (RDP sidecar, IO) → `RouteMapCard` composes the lite-mode `GoogleMap`
([MapSurface.kt:170](app/src/main/java/de/uhi/enia/ridesafe/ui/components/map/MapSurface.kt)) **while the open
animation is still running**. MapView inflation is a chunky main-thread cost, and each event pin is a
`MarkerComposable` ([MapPin.kt:82](app/src/main/java/de/uhi/enia/ridesafe/ui/components/map/MapPin.kt)) that
renders a sub-composition into a bitmap on main — a ride with many events multiplies this. Explains "smooth,
but sometimes severe lag" (many-event rides; worse during backfill, F6).

**Fix:** gate the map's entry into composition until the pane/nav transition has settled (~300 ms delay before
composing `GoogleMap`; `MapPreview` already shows a spinner cover, so the only visible change is the spinner
living slightly longer). Marker bitmaps then also render after the animation. This does *not* help close —
that's F2 plus unavoidable MapView teardown, which lite mode keeps modest.

### F4 — `MaterialSymbol` allocates a fresh `FontFamily` per call. **[minor, broad]**
[MaterialSymbol.kt:59](app/src/main/java/de/uhi/enia/ridesafe/ui/components/MaterialSymbol.kt) builds
`FontFamily(Font(resId, variationSettings))` on every composition of every icon (lists, timelines, chips, gauges).
Typeface resolution is cached by equality, so this is allocation/GC churn rather than re-instantiation — but it's
churn on every icon of every frame of every animation. **Fix:** cache `FontFamily` instances in a small map keyed
by (fill, weight, grade, opsz).

### F5 — Dashboard recompute storm during analysis backfill. **[accepted after F1]**
Each pipeline stage stamp/metric write re-emits `rideDao.observeAll()` → full dashboard recompute per write.
After F1 this is Default-dispatcher churn with conflation (`stateIn` drops intermediate values); at 75-ride scale,
not worth a debounce. Re-visit only if profiling still shows it.

### F6 — Context: backfill competes for cores. **[no change]**
`RideAnalysisPipeline` is correctly bounded (3 rides in flight, `Dispatchers.Default`,
[RideAnalysisPipeline.kt:265](app/src/main/java/de/uhi/enia/ridesafe/rides/processing/RideAnalysisPipeline.kt)).
While a backfill runs, some UI competition is expected; F1–F3 remove the main-thread part of it.

## Checked and cleared (non-issues)
- Room JSON `TypeConverter`s (score/dynamics/eco) run on Room's query executor, not main.
- Score/eco domain windows ([SafetyScoreWindows.kt](app/src/main/java/de/uhi/enia/ridesafe/domain/SafetyScoreWindows.kt),
  [EcoWindows.kt](app/src/main/java/de/uhi/enia/ridesafe/domain/EcoWindows.kt)) are linear scans — cheap once off-main.
  `allTimeSafetyScoreHistory` is quadratic-ish but trivial at personal-logbook scale.
- Charts/gauges (`SafetyScoreCharts`, `ScoreGauge`, `EcoCard`) draw cheap Canvas primitives; animations are bounded.
- `RidesViewModel.entries` already hot + off-main; detail-row flows (`ride(id)` etc.) are single-row queries.
- No `runBlocking`, no main-thread DB access anywhere.
- `AutoTrackPrefs`/`UnitPrefs` are snapshot-cached; no per-frame prefs reads.

## Implementation order

1. **F1** — dashboard `flowOn(Default)` + `stateIn(Eagerly)`. Expected: tab-switch snap-lag gone. No UX change.
2. **F2** — lazy search index. Expected: detail-close animation runs again. No UX change.
3. **F3** — defer map composition until transition settles. Expected: detail-open consistently smooth. Visible change: map spinner lasts ~300 ms longer.
4. **F4** — cache `MaterialSymbol` font families. Expected: less allocation churn everywhere. No UX change.

Each step is independent and separately revertable, so cause-attribution stays possible.
