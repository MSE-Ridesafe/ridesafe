# UI & Package Restructure Plan

Tracking document for the structural refactor of 2026-08. Check items off as they land;
reference the item ID in the commit message (one commit per item, same as the cleanup pass).
Items within a phase are independent unless a dependency is noted. Nothing here changes
behavior except where explicitly marked **[behavioral]**.

**Convention ruling** (the question that started this): one file per *screen* or per
*component family* — public main composable plus the private helpers only it uses — is
standard Jetpack Compose practice (Now in Android, Google's Compose samples). One file per
trivial composable is over-fragmentation; we do not do that. Package-by-feature stays.

**Current state**: 13 UI files hold 7,970 lines and ~80 composables. Two of the three
largest files in `ui/screens/rides/` contain zero Compose imports (backup + export
subsystems, ~1,600 lines). One string helper in a screen package is imported by 9 files
including two background services. Target: no file over ~400 lines, no non-UI subsystem
under `ui/`, one shared implementation per repeated UI pattern.

**Suggested order**: Phase 1 (pure moves) → Phase 2 (shared components) → Phases 3+4
together, one feature area at a time (rides → settings → onboarding → garage → home) →
Phase 5. Phase 2 first shrinks the big files so the Phase 3 splits get smaller.

---

## Phase 1 — Package & layering moves (no content changes)

### Quick wins

- [x] **P1.1** Move `Vehicle.displayTitle()` from `ui/screens/garage/VehicleUi.kt:26` to
  `data/Vehicle.kt`. Kills the repo's only hard layering inversion: 9 importers, including
  `rides/recording/RideEventNotification.kt`, `car/RidesafeCarAppService.kt`,
  `car/CarScreens.kt` (non-UI code importing a screen package) and
  `ui/components/RecordingStatusBar.kt` (shared component depending on a feature).
  `FuelType.labelRes()` and `previewVehicles` stay in `VehicleUi.kt`.
- [x] **P1.2** Split `TimelineEntry` + `buildTimeline` + selection helpers
  (`RidesViewModel.kt:607-690`, plus `LogbookEntry` at `:78-108`) into
  `ui/screens/rides/TimelineEntry.kt`. Zero imports touched (same package);
  `TimelineEntryTest.kt` already exists and finally gets a matching source file.
- [x] **P1.3** Split `effectiveVehicleSelection` / `ridesForVehicle` / `refuelsForVehicle`
  / `addRefuelCosts` (`HomeViewModel.kt:116-150`) into `ui/screens/home/HomeVehicleFilter.kt`.
  Same rationale: `HomeVehicleFilterTest.kt` exists, source file doesn't.
- [x] **P1.4** Split the 22 `Migration` objects out of `data/RidesafeDatabase.kt` (514
  lines) into `data/Migrations.kt`. Zero imports touched.
- [x] **P1.5** Split `consolidateSavedAddressDuplicates` + `rematchRides` (top-level
  repository functions) out of `data/SavedAddressDao.kt` into
  `data/SavedAddressMaintenance.kt`. Zero imports touched.
- [x] **P1.6** Split `Long.toLocalDate()` out of `util/TimeFormatter.kt:11` into
  `util/TimeConversions.kt`, so `domain/` stops transitively pointing at a file full of
  Android formatting. Zero imports touched.

### New `backup/` package (from `ui/screens/rides` + `ui/screens/settings`)

Both source files have zero Compose imports. Everything is `internal`, so no visibility
breaks. Do before P1.13 (export depends on backup, not vice versa).

- [x] **P1.7** `backup/BackupManifest.kt` ← the 14 `@Serializable` models, format
  constants, `backupJson`, codec functions from `RideZipBackup.kt`.
- [x] **P1.8** `backup/BackupArchiveWriter.kt` ← `RideZipBackup` class,
  `writeRideBackupZip`, snapshot/manifest builders, archive-path helpers, the six
  `toBackup()` entity mappers.
- [x] **P1.9** `backup/BackupArchiveValidator.kt` ← `RideBackupArchiveValidator`,
  `RideBackupValidationException`, the `validate*` helpers, `unique`, `requireRef`.
- [x] **P1.10** `backup/BackupIntegrity.kt` ← `FileIntegrity`, `fileIntegrity`,
  `streamIntegrity`, the file-to-file `copyCancellable` wrapper.
- [x] **P1.11** `backup/RideBackupImporter.kt` ← all of
  `ui/screens/settings/RideBackupImport.kt` (importer, DTOs, match/conflict rules,
  `publishImportFile`). Deletes the settings→rides feature dependency outright.
- [x] **P1.12** Move `RideZipBackupTest.kt` to `app/src/test/.../backup/` (package line
  only). `RideBackupImportScreen.kt` (state + VM + composable) stays in settings.

### New `export/` package (from `ui/screens/rides/RidePdfExport.kt`)

The filename is a lie — it holds CSV, ZIP dispatch, MediaStore, and notifications.

- [x] **P1.13** `export/RideExportModels.kt` ← `RideExportRequest/Item/Format/Journey`,
  `SavedRideExport`, `CompletedRideExport` (pure DTOs).
- [x] **P1.14** `export/RideExportJourneys.kt` ← `buildExportJourneys` (NOT `exportRequests` — it maps LogbookEntry, a UI model, so it stayed in ui/screens/rides/RideExportController.kt),
  `Ride.toExportItem`, `actualExportAddress` (pure mapping, already unit-tested).
- [x] **P1.15** `export/RideExporter.kt` ← `RideExporter`, `saveToDownloads`, filename
  helpers, `cleanupStaleTemps`, `notifyExportComplete`, notification/intent helpers.
- [x] **P1.16** `export/RideCsvWriter.kt` ← CSV building + `RideExportValueFormatter` +
  `AndroidRideExportValueFormatter`.
- [x] **P1.17** `export/RidePdfReport.kt` ← `RidePdfReport` + `PageWriter`.
- [x] **P1.18** `RideExportState` + `RideExportController` stay in `ui/screens/rides/`
  (screen state machine). Move `RidePdfExportTest.kt` + `RideCsvExportTest.kt` to
  `app/src/test/.../export/`.

---

## Phase 2 — Shared component library (`ui/components/`)

Each item: create the component, convert the listed call sites, delete the duplicates.
Similarity was verified per site — "identical" below means byte-identical modulo strings.

- [x] **P2.1** `FormScaffold` — top bar + save button + embedded/chromeless mode + IME
  handling. Sites: `VehicleFormScreen.kt:124-180`, `SavedAddressFormScreen.kt:351-401`
  (these two share a near-verbatim 3-line comment — copy-paste smoking gun),
  `RefuelFormScreen.kt:246-275` (gains embedded support for free). Net ~-58.
- [ ] **P2.2** `DetailScaffold` — transparent top bar + back arrow + 16.dp scrolling card
  column. Sites: `RideDetailScreen.kt:88-126`, `MergedRideDetailScreen.kt:117-156` (+ its
  degenerate loading copy at `:93-114`), `VehicleDetailScreen.kt:88-133`. Encodes the
  transparent-vs-opaque top bar rule (detail = transparent, form = opaque) so it stops
  being re-decided per screen. Net ~-66.
- [ ] **P2.3** `EmptyState(symbolName, title, message, action?)` — sites: `EmptyGarage`,
  `EmptyRides`, `NoMatchingRides`, `EmptySavedAddresses` (4 byte-identical),
  `EmptyQueue` (same shape; its 48.dp icon + missing spacer are drift — keep only the
  deliberate `primary` tint via param). Net ~-71.
- [ ] **P2.4** `ConfirmDestructiveDialog` — sites: `VehicleFormScreen.kt:358-385`,
  `SavedAddressFormScreen.kt:759-779`, `RidesScreen.kt:546-575` (identical). Surfaces a
  strings cleanup: rides uses bespoke `ride_delete_confirm/cancel` where the others use
  `action_delete/action_cancel`. Net ~-36.
- [ ] **P2.5** `DestructiveOutlinedButton` — `VehicleFormScreen.kt:332-344`,
  `VehicleDetailScreen.kt:207-217`, `SavedAddressFormScreen.kt:642-651` (identical). ~-16.
- [ ] **P2.6** `SectionTitle(text, modifier)` — the `titleSmall` + `primary` header token
  written longhand at 11 sites; padding stays at call sites. Do NOT fold in
  `RideFilterUi.SectionLabel` (different tokens, deliberate). Net ~-36.
- [ ] **P2.7** `SectionCard(title, headerAction?, content)` — the card + header +
  divider-between-children shell shared by `DetailCard`, `TrackingCard`, the
  attached-refuels card, and `MergedJourneyCard`. `DetailCard` becomes a thin wrapper.
  Include `CardDivider()` (the `surfaceContainerHighest` divider written at 6 sites). ~-28.
- [ ] **P2.8** `BackNavIcon(showBack, symbolName = "arrow_back", onBack)` — mop-up for
  the ~4 sites P2.1/P2.2 don't absorb (8 identical sites today). ~-54 before absorption.
- [ ] **P2.9** `NumberField` — promote `RefuelFormScreen.DecimalField:513-533` to
  `ui/components/`; converts `DistanceField` (`RideFilterUi.kt:674-689`) and the four
  numeric fields in `VehicleFormScreen`. ~9 call sites.
- [ ] **P2.10** `TimelineListRow` — merge the render (not the derivation) of `LogbookRow`
  and `RefuelTimelineRow` (`RidesScreen.kt:870-1034`). `RefuelTimelineRow` is already
  cross-screen (`RideDetailScreen.kt:212`, `MergedRideDetailScreen.kt:292`). ~-30.
- [ ] **P2.11** `LabelValueRow(label, value, symbolName?, valueStyle)` — `DetailRow` +
  `HighlightRow`. Do NOT fold in `InfoChip` (vertical chip) or `StatLine` (a11y-only
  label is its point). ~-20.
- [ ] **P2.12** `EnumDropdown` — shared `ExposedDropdownMenuBox` shell for
  `FuelTypeDropdown` (`VehicleFormScreen.kt:387-422`) and `VehicleDropdown`
  (`RefuelFormScreen.kt:402-463`) only. See R6 for the two dropdowns that must NOT join.
- [ ] **P2.13** `MapLoadingCover` next to `MapLoadingIndicator` — 3 identical sites
  (`SavedAddressFormScreen.kt:588,704`, `map/MapPreview.kt:108-117`). ~-4.
- [ ] **P2.14** Token drift fixes: replace `RoundedCornerShape(28.dp)` with
  `MaterialTheme.shapes.extraLarge` at 4 home-card sites (`VehicleOverviewCard.kt:39,106`,
  `SummaryCards.kt:176`, `HighlightsCard.kt:35` — same value, hardcoded); consider
  `RidesafeCard(contentPadding, spacing)` for the 18-site card shell if P2.7 leaves
  enough repetition. Fix the one positional `MaterialSymbol` call
  (`RideBackupImportScreen.kt:131`); `checkIfSelected` leading-icon helper (3 chip sites).
- [ ] **P2.15** Onboarding reuses `EcoSection`: `OnboardingFlow.kt:720-733` hand-rolls it
  next to a line that already correctly reuses `SafetyScoreCard`. 14 lines → 1.
- [ ] **P2.16** Promote already-shared-in-spirit components trapped in wrong files:
  `BluetoothPickerDialog` + `TrackingCard` (`VehicleDetailScreen.kt:334-451`, imported by
  onboarding) → own files (garage or `ui/components`); `VehicleImage` (imported by home's
  `VehicleOverviewCard`) → `ui/components/VehicleImage.kt`; `SelectionCircle`,
  `AppSnackbarHost` (`RidesScreen.kt:713-728,1036-1045`); `ProgressRing` + `Modifier.animatePlacement()`
  (`RideFilterUi.kt:301-326`, generic layout util) → `ui/components/`.

---

## Phase 3 — File splits (one main composable per file + its private helpers)

Line numbers are pre-Phase-2 and will shift; the declaration names are the anchor.

### rides

- [ ] **P3.1** `RidesScreen.kt` (1123) → keep root + `DayHeader` (~370 after P2); extract
  `LogbookRow.kt`, `RidesSelectionTopBar.kt` (+ its four enum→resource mappers, see P4.1),
  `RideExportFormatSheet.kt` (sheet + `ExportFormatOption`), empty states → P2.3,
  selection-state block (`:136-179`) → `rememberLogbookSelection(...)` in
  `LogbookSelectionState.kt`.
- [ ] **P3.2** `AnalysisQueue.kt` (470) — three unrelated surfaces → `AnalysisStatusBar.kt`,
  `AnalysisQueueScreen.kt` (+ `QueueCard`, `EmptyQueue`), `AnalysisQueueRows.kt`
  (`rememberQueueRows` state holder), `AnalysisNoticeCard.kt` (sole consumer is
  RideDetailScreen), `ProgressRing` → P2.16.
- [ ] **P3.3** `RideFilterUi.kt` (741) → `RideSearchBar.kt`, `ActiveFilterChips.kt`
  (+ chips + `dateChipLabel`), `RideFilterSheet.kt` (+ `SectionLabel`, `DistanceField`
  until P2.9), `RideFilterControls.kt` (`FilterDropdown`, `DateBoundButton`).
- [ ] **P3.4** `RideDetailScreen.kt` (430) → `JourneyTimeline.kt` (constants +
  `JourneyStop` + `JourneyCard` + `JourneyTimeline` + `JourneyStopRow` + `Connector`,
  ~200 lines — a coherent public component family), `AssociatedRefuelsCard.kt`
  (named, replacing the inline card); root keeps ~140.
- [ ] **P3.5** `MergedRideDetailScreen.kt` (395) → `MergedJourneyCard.kt` (+ `StopRow`,
  owns manage-mode state); root keeps ~125. See P5.1 before polishing this.
- [ ] **P3.6** `RefuelFormScreen.kt` (533) → `RefuelStateScreens.kt` (loading +
  unavailable + shared frame — separate nav destinations), `RefuelFormPickers.kt`
  (date/time picker dialogs), `RefuelFormFields.kt` (`VehicleDropdown`, `DateTimeFields`);
  root keeps ~210.
- [ ] **P3.7** `RideRouteMap.kt` (287, optional — already defensible) →
  `RideEventMapModel.kt` (`pinColors`, `label`, `rideMapPins`), `RideFullScreenMap.kt`
  (overlay + event sheet + row); `RouteMapCard` + `NoGps` stay.

### settings

- [ ] **P3.8** `SettingsScreen.kt` (760) — eight screens in one file →
  `SettingsSelectionScreen.kt` (shared frame + `SelectableSettingRow`),
  `LanguageSettingsScreen.kt` (the only sub-screen with real logic),
  `PreferenceSettingsScreens.kt` (Theme/Unit/Currency), `RecordingSettingsScreens.kt`
  (AutoTrack/ReconnectGrace/MinRideLength), `SettingsLabels.kt` (@Composable label
  mappers); menu keeps `SettingsListItem` + `SettingsCategoryHeader` + `SettingsIcon`.
- [ ] **P3.9** `SavedAddressFormScreen.kt` (780; a 584-line composable, worst
  per-function offender) → `SavedAddressFormState.kt`
  (`rememberSavedAddressFormState`: state + effects + `locate()`/`save()`),
  `AddressSearchSection.kt` (field + results surface), `PlaceMapPickerDialog.kt`
  (~100-line self-contained dialog), `PlaceMapPreviewCard.kt`, `PlaceIconPicker.kt`
  (+ `CURATED_PLACE_ICONS`); root keeps ~180.

### onboarding

- [ ] **P3.10** `OnboardingFlow.kt` (796) → `OnboardingSteps.kt` (enum + pure sequencing
  — zero Compose imports, unit-testable), `OnboardingGate.kt` (router, pairs with
  `firstRunDecision`), `OnboardingPageKit.kt` (`StepPage`, `StepFormHeader`, `StepIntro`,
  `FeatureRow`), `OnboardingSetupPages.kt` (Car/Bluetooth/AutoTrack/Place),
  `OnboardingExplainerPages.kt` (Welcome/Recording/Scores + `SampleScore`); flow +
  `WizardHeader` keep ~190.

### garage

- [ ] **P3.11** `VehicleDetailScreen.kt` (452) → extract `TrackingCard` +
  `BluetoothPickerDialog` (P2.16); root + `VehicleHeader` keep ~270.
- [ ] **P3.12** `VehicleFormScreen.kt` (424) → `DeleteVehicleDialog` → P2.4;
  `VehicleFormFields.kt` (`FuelTypeDropdown` + extended-info block as
  `VehicleExtendedFields`); root keeps ~215.

### home

- [ ] **P3.13** `HomeScreen.kt` (316) → `VehicleSelectorTitle.kt` (+ `VehicleMenuItem`),
  `RecordRideFab.kt` (own feature: permission launcher + service start + picker dialog);
  root keeps ~100 and matches its neighbours (home is already the best-structured package).

---

## Phase 4 — Non-UI logic out of composables

- [ ] **P4.1** Enum→resource label mappers move beside their enums (matching the
  `FuelType.labelRes()` precedent): the four mappers in `RidesScreen.kt:825-858` (enums in
  `RefuelAssociation.kt`/`data/`); `minRideLengthLabelRes` + `reconnectGraceLabelRes`
  (`SettingsScreen.kt:734-750`, pure `Int` mappers) → `rides/recording/RecordingPrefs.kt`;
  `TripType.icon()/labelRes()` (`RideFilterUi.kt:691-702`) → beside `TripType` in
  `RideFilter.kt`.
- [ ] **P4.2** Date/time math → `util/`: `rideTimeRange` (`RidesScreen.kt:1047`),
  `formatFilterDate` + `startOfDayMs`/`utcMillis`/`toUtcDay` (`RideFilterUi.kt:704-732`),
  `DateTimeFields`' inline `DateFormat` conversions (`RefuelFormScreen.kt:473-484`).
- [ ] **P4.3** Unit conversions → `util/UnitFormatter.kt`: `METERS_PER_KM/MILE` +
  `toFieldText`/`toMeters` (`RideFilterUi.kt:93-94,734-741`), `KM_PER_MILE` odometer
  round-trip (`VehicleFormScreen.kt:53,76-77,104`) as `odometerToDisplay`/`displayToKm`.
  Same constant currently lives in two files.
- [ ] **P4.4** Recent-address-search history (`SavedAddressFormScreen.kt:110-144`,
  SharedPreferences + JSONArray IO in a UI file) → `util/AddressSearchHistory.kt`.
- [ ] **P4.5** `findExistingSavedPlace` (`SavedAddressFormScreen.kt:146-164`, pure ADR-09
  matching via `normalizeForMatching` + `haversineMeters`) → `data/SavedAddress.kt`
  beside `matchAddress`; add a unit test (currently untestable).
- [ ] **P4.6** **[behavioral]** Onboarding Room writes (`OnboardingFlow.kt:258-273`:
  `vehicleDao.addVehicle/updateVehicle` from `rememberCoroutineScope` + double-insert
  latch) → small onboarding VM or reuse `GarageViewModel`.
- [ ] **P4.7** **[behavioral]** `SavedAddressFormScreen` platform calls out of
  composition: `locate()`/`requestLocate()` (FusedLocationProvider callbacks), geocode
  debounce + distance sort → the P3.9 state holder; sort key is domain logic.
- [ ] **P4.8** **[behavioral]** `RecordRideFab` calls `RideRecordingService.start` +
  raises a `Toast` directly (`HomeScreen.kt:247-251`) — route through a callback/VM.
- [ ] **P4.9** `stopFor` (`RideDetailScreen.kt:142-157`, haversine + address comparison →
  `JourneyStop`) → non-composable `journeyStopFor(...)`; distance/avg-speed fallback
  derivation (`:111-116`) belongs beside the pipeline.
- [ ] **P4.10** `rideEndpointLabel` + `String.lineOne()` (`AnalysisQueue.kt:292-303`)
  collapse onto `rides/processing/Geocoding.kt`'s existing `addressLines()`/`shortAddress()`.
- [ ] **P4.11** `RefuelFormScreen.save()` arithmetic (`:202-244`) → outbound
  `refuelFromForm(...)` in `RefuelNumbers.kt` (which already owns the inbound half);
  `VehicleFormScreen.save()` (`:103-122`, 11 trim/parse rules) → a builder beside
  `Vehicle`.
- [ ] **P4.12** Duplicate permission/adapter reads: delete `hasBluetoothConnect`
  (`VehicleDetailScreen.kt:248-250`; `AppPermission.BLUETOOTH.isGranted` exists and
  onboarding already uses it); `BluetoothDevices.bonded(context)` read in two composables
  (`OnboardingFlow.kt:558`, `VehicleDetailScreen.kt:238`) → hoist into the P2.16 dialog.
- [ ] **P4.13** `currentLanguageLabel` (`SettingsScreen.kt:691-701`) — non-composable
  `currentAppLanguageTag(context)` in `util/AppLocale.kt`; only the `stringResource`
  switch stays in UI.
- [ ] **P4.14** Export-open intent resolution (`RidesScreen.kt:198-229`, resolves against
  `packageManager` + `startActivity` inside an effect) → `openExportedFile(context, saved)`
  in `export/RideExporter.kt`.

---

## Phase 5 — Deliberate consolidations (each is its own design decision)

- [ ] **P5.1** **[behavioral]** Reconcile the two journey renderers: `JourneyTimeline`
  (`RideDetailScreen`, public, KDoc says "extracted so a merged ride can embed it") vs
  `MergedJourneyCard`/`StopRow` (`MergedRideDetailScreen`, built separately anyway). The
  real delta is manage-mode checkboxes. Single biggest duplication in the rides package.
- [ ] **P5.2** **[behavioral]** Make `map/MapPreview` accept an external
  `CameraPositionState` so `SavedAddressFormScreen`'s hand-rolled map card can reuse it
  (today only the loading cover is shareable).
- [ ] **P5.3** **[behavioral]** `RidesViewModel` repository work → `rides/RideFileStore.kt`:
  file deletion with canonical-path traversal guards (`safeRideFile:457`,
  `safePrivateFile:466`), raw-track reading (`route:582`), geocode backfill
  (`fillAddresses:592`). Security-relevant path checks — refactor with care, own commit,
  never bundled with a move.
- [ ] **P5.4** `PickerDialog` shell for `BluetoothPickerDialog` + `RecordRideFab`'s
  vehicle picker (only the AlertDialog shell merges; rows differ). Fix their
  confirm/dismiss slot disagreement regardless.

---

## Rejected — checked and deliberately NOT doing (with reasons)

- **R1** `FilterDropdown` / `VehicleSelectorTitle` must NOT merge onto
  `ExposedDropdownMenuBox`: both deliberately avoid EDMB for a documented re-measure loop
  inside bottom sheets (`RideFilterUi.kt:561-564` comment; confirmed bug from earlier work
  on this repo). Merging would reintroduce it.
- **R2** `data/MergeMath.kt` stays in `data/`: moving to `domain/` costs 16 import lines
  across 10 files for zero testability gain, and `MergedSummary` carries a persistence
  column type.
- **R3** `data/` stays flat: alphabetical ordering already pairs entity/DAO files; an
  `entities/`+`daos/` split breaks that adjacency and touches every `data.*` import.
- **R4** `util/Geo.kt`, `util/UnitFormatter.kt`, `AppLocale.kt`, `HomeFormatters.kt`,
  `ActivityDateHelpers.kt`, `RideFilter.kt`, `RefuelNumbers.kt`, `RefuelAssociation.kt`
  stay put — checked, each is either correctly placed or too Context-bound to demote
  cleanly.
- **R5** `AnalysisQueue.kt` is UI, not logic — misleading name only (P3.2 renames by
  splitting).
- **R6** Do NOT merge: `NoGps` into `EmptyState` (map-card placeholder, different scale);
  `DetailPlaceholder` into `EmptyState` (text-only by design); `InfoChip`/`StatLine` into
  `LabelValueRow`; `SectionLabel` into `SectionTitle` (different tokens);
  `SettingsListItem` into the ListItem-row family (hand-rolled 72.dp row with icon chip —
  different control); `RideBackupImportScreen`'s informational dialogs into
  `ConfirmDestructiveDialog` (destructive vs informational semantics); the saved-address
  map picker into `FullScreenMap` (free camera + confirm are genuinely different).
- **R7** `SettingsFade` stays in settings (used by MainActivity; cosmetic move, skip).
- **R8** `car/` is `androidx.car.app` template UI, not Compose — out of scope.
- **R9** Top-bar color split (transparent vs `surfaceContainer`) is an intentional
  detail-vs-form rule, not drift — encode in P2.1/P2.2, don't "fix".

---

## Estimated impact

- The 13 oversized files (7,970 lines) become ~45 files with none over ~400 lines.
- Component consolidation: roughly −500 lines of duplicated UI.
- ~450 lines of formatting/unit-math/prefs-IO/domain logic move out of `ui/` into
  `util/`, `domain/`, `data/`, `rides/`, `backup/`, `export/`.
- Two new non-UI packages (`backup/`, `export/`) remove ~1,600 lines of subsystem code
  from screen packages; the settings→rides and non-UI→garage dependencies disappear.

## Progress log

| Date | Items landed | Notes |
|---|---|---|
| 2026-08-30 | — | Plan created. |
