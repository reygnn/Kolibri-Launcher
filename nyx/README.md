# Nyx Launcher

[![Android CI](https://github.com/reygnn/Nyx-Launcher/actions/workflows/android.yml/badge.svg)](https://github.com/reygnn/Nyx-Launcher/actions/workflows/android.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![API](https://img.shields.io/badge/API-36-brightgreen.svg?style=flat-square)](https://source.android.com/docs/setup/about/build-numbers)

A minimalist Android home-screen launcher. Early days — `0.1.0-dev`.

## What it is

Nyx is a stripped-down home screen: a clean home layout with folders and a
dock, plus a swipe-up app drawer with instant search. No widgets, no
"discover" tabs, no clutter.

It shares its architecture and conventions with its sister project
[Kolibri Launcher](https://github.com/reygnn/Kolibri-Launcher): a
Clean-Architecture module split, Hilt for DI, Coroutines/Flow for async,
and a JVM-first test suite. The launcher-specific behaviour (home-layout
edits, folder membership, reconciliation against installed apps) lives in
`:domain` as pure use cases, specified under [`docs/specs/`](docs/specs/).

## Modules

- **`:app`** — Android UI (Compose / Material 3), activities, adapters,
  icon loading.
- **`:domain`** — pure-Kotlin use cases and home-layout model. No Android
  dependencies; JVM-tested.
- **`:data`** — persistence (DataStore) and repository implementations.

## Building

```sh
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest :domain:test   # unit tests
./gradlew bundleRelease        # unsigned release AAB (the deliverable)
```

Requires JDK 21 and the Android SDK (`compileSdk = targetSdk = minSdk = 36`).
Point `local.properties` at your SDK (`sdk.dir=…`); it is intentionally not
committed.

## Status

Pre-release and evolving. Expect breaking changes.

## License

GPL-3.0-or-later — see [LICENSE](LICENSE).
