# Kolibri + Nyx — launcher monorepo

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![API](https://img.shields.io/badge/API-36-brightgreen.svg?style=flat-square)](https://source.android.com/docs/setup/about/build-numbers)

A single Gradle build housing two minimalist Android launchers — **Kolibri**
and **Nyx** — plus the shared, product-neutral infrastructure they both build
on. Guiding rule: *inherit the architecture, not the product philosophy.* Only
launcher-neutral infra is shared; each launcher keeps its own home model.

> Formerly two standalone repos. The pre-merge Kolibri history is preserved at
> [`reygnn/Kolibri-Launcher-legacy`](https://github.com/reygnn/Kolibri-Launcher-legacy)
> (and in this repo's import commit); the old standalone Nyx repo was folded in
> here too.

## The two launchers

### Kolibri — [`kolibri/`](kolibri/)
A flat favourites launcher: time, date, battery, and a hand-picked list of
favourite apps, with a swipe-up app drawer and instant search. No widgets, no
icon grid. **Releases:** this repo's [Releases](../../releases) page.

### Nyx — [`nyx/`](nyx/)
A grid/icon launcher: a clean home layout with folders and a dock, plus a
swipe-up drawer. Early days. **Releases:**
[`reygnn/Nyx-Launcher`](https://github.com/reygnn/Nyx-Launcher) (release-only
repo; its source is here).

## Layout

```
:core                     product-neutral Kotlin/JVM foundation — constants,
                          logging, dispatchers, ComponentKey, wallpaper + time
                          models (no Android SDK)
:common-ui                shared UI infra — gesture stack, flow-collection,
                          error-event bus, wallpaper render views, clock
:common-data              shared data infra — DataStore helpers, wallpaper
                          repository, time-based events
:feature-crashreporting   ACRA wiring as a self-contained feature module
:kolibri:{app,domain,data}  Kolibri, a thin app over the shared modules
:nyx:{app,domain,data}      Nyx, likewise
```

Both launchers keep the Clean-Architecture three-module split (`:app` /
`:domain` / `:data`), Hilt for DI, Coroutines/Flow for async, and a JVM-first
test suite. `:domain` is pure Kotlin, no Android dependencies.

## Build

```sh
./gradlew :kolibri:app:assembleDebug     # Kolibri debug APK
./gradlew :nyx:app:assembleDebug         # Nyx debug APK
./gradlew :kolibri:app:bundleRelease     # unsigned release AAB (the deliverable)
./gradlew :kolibri:app:test :kolibri:domain:test   # unit tests
```

Requires JDK 21 and the Android SDK. `local.properties` / `secrets.properties`
are intentionally not committed.

## Why a monorepo

Both apps are by the same author and started from the same three-module Kolibri
scaffold, so the expensive part is not the features — it is the shared
foundation. Extracting it into `:core` / `:common-*` / `:feature-*` lets
Kolibri's battle-tested infra back Nyx without copy-paste. The full module cut
and migration path is in
[`docs/specs/MONOREPO_MERGE_SPEC.md`](docs/specs/MONOREPO_MERGE_SPEC.md).

## License

GPL-3.0-or-later.
