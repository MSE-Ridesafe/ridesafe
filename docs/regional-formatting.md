# Regional formatting — analysis and plan

**Date:** 2026-08-28 · **Branch:** `bugfix/regional-settings`

## Symptom

With an in-app language selected (SET-05), units and formatting follow that language instead
of the device's regional settings. A device set to *language English, region Germany* (with
Android's regional-preference overrides for measurement system etc.) shows miles, 12-hour
times, and US number/date styles as soon as the app language is "English" — even though the
system itself formats everything German-style.

## Root cause

`UnitFormatter.getFormattingLocale()` already tries to do the right thing: for AUTOMATIC it
reads the *system* locale from `Resources.getSystem().configuration.locales`. That API is the
trap. On Android 13+ the per-app language override (`LocaleManager.applicationLocales`, which
our language picker sets) is applied by the platform to the **process-wide** configuration —
including the one backing `Resources.getSystem()`. So the "system" read returns the in-app
language: a bare `en` or `de` with no region and no `-u-` extensions.

From a bare `en`, ICU's likely-subtags resolve the region to US → `LocaleData.getMeasurementSystem`
says imperial. The device's real region (`DE`) and its regional-preference extensions
(`-u-ms-metric`, `-u-mu-celsius`, …) never reach the app.

The correct API is `LocaleManager.getSystemLocales()` (API 33+, we're minSdk 34), documented as
"returns the current system locales, **ignoring app-specific overrides**" — it exists precisely
because `Resources.getSystem()` doesn't ignore them. (AndroidX `LocaleManagerCompat` only falls
back to `Resources.getSystem()` below API 33, where platform per-app locales don't exist.)

Beyond units, several formatting paths never consulted the system at all — they format with the
app-language locale (activity context / `Locale.getDefault()` / `LocalLocale`), so date, time,
and number styles also follow the language:

| Where | What follows the language today |
|---|---|
| `TimeFormatter` (`DateUtils` with activity context) | date order, 12/24-h fallback |
| `UnitFormatter` numbers (`oneDecimal`, METRIC/IMPERIAL path) | decimal separator |
| `HomeFormatters` (fuel economy, ride count) | decimal/grouping separators |
| `ActivityDateHelpers` `"dd.MM."`, `SafetyScoreCharts` `"d.M."` | hardcoded German-style order, ignores both language *and* region |

## The rule

One formatting locale, defined in one place (`AppLocale.kt`):

> **Words from the in-app language; region and `-u-` extensions from the device.**

- App locale bare (our picker sets `en`/`de`) → merge: language/script from the app locale,
  region + variant + unicode extensions from `LocaleManager.getSystemLocales()[0]`.
  English UI on a Germany-region device formats as `en-DE`: metric, 24-h, `26.08.2026`, `1,5 km`.
- App locale already carries a region (no in-app override → it *is* the system locale; or the
  user picked a full locale like "English (US)" in Android's own per-app picker) → use it as-is.
  That is a deliberate full-locale choice, and it's how the OS itself treats the app.
- The km/mi decision reads the merged locale: explicit `-u-ms` override first (already
  implemented), else `LocaleData.getMeasurementSystem` on the region. The in-app METRIC/IMPERIAL
  setting (SET-07/08) still forces units; it no longer changes the *number* locale.

Translated words (weekday/month names via `LocalLocale`, `getString`) are untouched — the merged
locale has the same language anyway.

## Fixes (one commit each)

1. **Core:** `AppLocale.kt` gets `formattingLocale()` (merge above; system locales via
   `LocaleManager.getSystemLocales`, app context held by `RidesafeApplication`).
   `UnitFormatter` uses it for AUTOMATIC *and* forced settings. Fixes km/mi, m/ft, km/h/mph
   and their number styles. Pure merge function + JVM test.
2. **Dates/times:** `TimeFormatter` formats through a context configured with the merged locale
   (single-entry cache; `Today`/`Yesterday` strings stay on the caller's context).
3. **Numbers:** `HomeFormatters` (fuel economy, ride count) use the merged locale.
4. **Hardcoded date patterns:** `ActivityDateHelpers` and `SafetyScoreCharts` axis labels use
   `DateFormat.getBestDateTimePattern` skeletons (`MMdd`, `Md`, `yyMd`) with the merged locale —
   byte-identical for a German region, correctly ordered elsewhere.

## Visible changes (intended — this is the requested behavior)

- In-app language + differing system region now formats region-style: English UI, German region
  → `1,5 km`, 24-h times, `dd.MM.` dates. Matches how the OS renders an `en-DE` system.
- Forced METRIC/IMPERIAL: unit unchanged, but decimal/grouping separators now regional too.

## Out of scope

- **First day of week / week numbering:** calendars and score weeks are deliberately ISO/Monday
  (`WeekFields.ISO`, score buckets keyed by Monday) — regionalizing them would change score
  grouping, not just presentation. Not part of this bug.
- **Temperature:** app displays none; Android's `-u-mu` override has nothing to apply to.
- **Live re-format on system-locale change while the app is foreground:** a system locale/region
  change recreates activities when no in-app override is set; with an override the merged locale
  self-refreshes on the next composition/process start. No extra receiver.
