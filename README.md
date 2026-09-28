# MinimalFlow

A minimal, fast home-screen launcher for Android.

MinimalFlow shows a clock, an optional weather line, a favourites strip and the
apps you have, and gets out of the way. Everything configurable lives behind a
single settings row. It requests no permissions it does not use, sends nothing
anywhere, and contains no ads and no analytics.

## What it does

- **Home screen** — clock, date, greeting, favourites strip, and an A–Z app list
  with section headers.
- **Search** — ranked matching on app name, user-set alias and keyword, with
  optional fuzzy matching for typos. History is off by default.
- **Favourites and hidden apps** — orderable favourites; hidden apps are removed
  from the list and can be restored from settings.
- **Shortcuts** — static and dynamic app shortcuts are discovered and launchable.
- **Widgets** — hosts third-party widgets through `AppWidgetHost`. MinimalFlow
  does not publish widgets of its own.
- **Themes** — six built-in themes plus custom themes, with contrast validation
  that refuses unreadable combinations.
- **Gestures** — swipes from the screen edges and taps, each individually
  assignable.
- **Weather** — optional, off by default, from Open-Meteo. A city can be chosen
  manually, which needs no location permission at all.
- **Backup** — export and import the whole configuration as one JSON file.

## What it deliberately does not do

These are decisions, not omissions:

- **No `QUERY_ALL_PACKAGES`.** Package visibility comes from a narrow `<queries>`
  block that names exactly what the launcher needs: launchable activities, icon
  packs and widget providers.
- **No hidden APIs.** Everything uses the public SDK. That is why widget
  placement goes through `AppWidgetManager.bindAppWidgetIdIfAllowed` and
  `updateAppWidgetOptions` rather than a configuration activity.
- **No accessibility service, no root, no overlay permission.**
- **No storage permission.** Backup uses the Storage Access Framework, so the app
  only ever sees the single file the user picked.
- **No network access except weather**, which is off until you turn it on, and
  which talks to a public endpoint with no account and no key.
- **No usage tracking by default.** Launch counts and timestamps are opt-in, are
  stored in a local database, and are needed only for "recently used" and "most
  used" sorting.

## Requirements

| | |
|---|---|
| Minimum Android | 8.0 (API 26) |
| Compile / target SDK | 36 |
| Language level | JVM 17 |
| Build | Gradle 8.14, AGP 8.13.2, Kotlin 2.2.20 |

## Building

```bash
# Unit tests
./gradlew :app:testDebugUnitTest

# Debug APK
./gradlew :app:assembleDebug

# Lint
./gradlew :app:lintDebug
```

The build expects a JDK 17 on `JAVA_HOME` and an Android SDK with API 36
platforms on `ANDROID_HOME`, or a `local.properties` pointing at one.

## Architecture

```
app/src/main/java/com/minimalflow/launcher/
├── MinimalFlowApplication.kt     Hilt entry point, package-change observation
├── core/
│   ├── apps/       discovery, launch, shortcuts, sorting, icons, repository
│   ├── backup/     archive format, export/import
│   ├── data/       Room entities, DAOs, migrations, DataStore
│   ├── gestures/   pointer handling, slot mapping, dispatch
│   ├── lifecycle/  default-home role requests
│   ├── model/      immutable models and their limits
│   ├── permissions/ AppOps-backed permission checks
│   ├── search/     ranking engine, history
│   ├── themes/     built-in themes, resolution, contrast validation
│   ├── ui/         colour and spacing tokens, shared components
│   ├── weather/    Open-Meteo client, cache, geocoding
│   └── widgetkit/  AppWidgetHost registry, provider, repository
├── di/             Hilt modules
└── ui/
    ├── launcher/   LauncherActivity, home and search surfaces
    └── settings/   MainActivity and its view model
```

The dependency direction is strictly one-way: `ui` knows about `core`, and
`core` never imports anything from `ui`. That is what lets the ranking, sorting,
theming and backup logic be unit tested without Android.

### Data

Two stores, chosen per what the data is:

- **Room** for anything relational or ordered — settings, favourites, hidden
  apps, aliases, usage counters, search history, custom themes, widget
  placements. Ordered lists need real columns and real transactions.
- **DataStore** for the handful of small standalone flags — onboarding state, the
  previous home package, the cached weather reading, the last backup time.

Every write to settings goes through `PreferencesRepository.update`, which reads
the current row, transforms, sanitises and writes back inside a mutex. That makes
two quick toggles safe and guarantees nothing invalid is ever persisted.

## Testing

60 unit tests cover the logic that can be wrong without looking wrong: search
ranking order, locale-aware sorting and A–Z grouping, configuration clamping,
the WMO weather code table, theme contrast, and backup format compatibility.

They are deliberately written against behaviour rather than implementation, so a
refactor that keeps the behaviour keeps the tests passing.

## Licence

MIT. See [LICENSE](LICENSE).

Weather data by [Open-Meteo](https://open-meteo.com/), used under its free
terms for non-commercial use.
