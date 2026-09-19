# Phase 1 — Integration

Kompilierbarer erster Wurf der TAPL-lite-Fassade: das geteilte
`:common-testing-android`-Modul, die Kolibri-Page-Objekte (`Home`, `AppDrawer`,
`Search`) und ein portierter Test. Entscheidungen aus §11 umgesetzt: **eigenes
Modul** (nicht `testFixtures`), **sekundäre Activities erst bei Bedarf**,
**gemischter Assertions-Stil** (fluent-Navigation + `assertOnPage` in `init`).

## Dateien → Zielpfade

Das Zip spiegelt die Repo-Struktur; einfach über die Wurzel entpacken. Manuell:

| Datei im Zip | Ziel im Repo |
|---|---|
| `common-testing-android/build.gradle.kts` | `common-testing-android/build.gradle.kts` (neues Modul) |
| `common-testing-android/src/main/java/com/github/reygnn/launcher/testing/Await.kt` | gleicher Pfad |
| `.../testing/Instrumentation.kt` | gleicher Pfad |
| `.../testing/BasePage.kt` | gleicher Pfad |
| `kolibri/app/.../tapl/Launcher.kt` | `kolibri/app/src/androidTest/java/com/github/reygnn/kolibri_launcher/tapl/Launcher.kt` |
| `.../tapl/Home.kt` · `AppDrawer.kt` · `Search.kt` | gleicher `tapl/`-Ordner |
| `.../ui/appdrawer/AppDrawerSwipeDismissTaplTest.kt` | gleicher Pfad |
| `.../support/AwaitUntil.kt` | **überschreibt** die bestehende Datei (Forwarder) |

## Zwei Gradle-Edits (nicht im Zip — bewusst manuell)

**1. `settings.gradle.kts`** — Modul registrieren, bei den anderen `include`s:

```kotlin
include(":common-testing-android")
```

**2. `kolibri/app/build.gradle.kts`** — im `dependencies { }`-Block:

```kotlin
androidTestImplementation(project(":common-testing-android"))
```

Kein neuer Catalog-Eintrag nötig: das Modul nutzt nur schon vorhandene
`libs.*`-Aliase (`android.library`, `androidx.test.espresso.core/contrib`,
`androidx.test.runner/rules`, `androidx.test.core.ktx`,
`kotlinx.coroutines.android`).

## Warum `src/main` + `androidTestImplementation`

Der Support-Code liegt im `main`-SourceSet des Library-Moduls und wird von den
Apps über **`androidTestImplementation`** gezogen. Dadurch landet die
Espresso-/androidx.test-Oberfläche ausschließlich im androidTest-Klassenpfad der
Konsumenten, nie in einer Produktions-`implementation`. → Das Modul niemals als
normale `implementation` einbinden.

## awaitUntil-Migration

`awaitUntil` ist nach `com.github.reygnn.launcher.testing` gewandert. Die
ersetzte `support/AwaitUntil.kt` ist jetzt ein `@Deprecated`-Forwarder, damit die
~12 bestehenden Aufrufer weiter kompilieren. Neue Page-Objekte nutzen bereits die
geteilte Fassung. Imports der Altfälle bei Gelegenheit umziehen, dann den
Forwarder löschen.

## Konventionen (unverändert übernommen)

- **Kein `MainDispatcherRule`, kein `runTest`** hier (INSTRUMENTED_TESTING_NOTES §1/§2).
- Hilt-Regel bleibt beim Test: `@get:Rule(order = 0)`.
- Seeding (`setOnboardingCompleted`, `ConsentBootstrap.seedDecision`) im `@Before`
  **vor** `Launcher.start()`; App-Daten-Cleanup weiter über den Orchestrator.
- Animationen deaktiviert der `HiltTestRunner` global — die Fassade verlässt sich
  darauf.
- `androidx.test:core`-Force im App-Modul greift auch für die androidTest-Deps des
  neuen Moduls (gleicher Klassenpfad) — nichts zusätzlich zu pinnen.

## Ausführen

```sh
./gradlew :kolibri:app:connectedDebugAndroidTest \
    --tests "*AppDrawerSwipeDismissTaplTest"
```

Grün = die Sync-Kapselung (BasePage/awaitUntil) trägt durch den Produktions-
Öffnungspfad und die DragToDismiss-Geste. Danach die alte
`AppDrawerSwipeDismissTest` löschen.

## Bewusst noch offen (Phase 2)

- `Home.favoritesCount/longPressFavorite`, `Search.resultCount/launchFirst` sind
  `TODO()` — kompiliert, aber ungenutzt. RecyclerView-`itemCount` braucht eine
  kleine custom `ViewAction`; `launchFirst()` verlässt die Fassade und gehört in
  einen `Intents`-Test.
- `AppContextMenu` ist ein leerer Platzhalter für den Long-Press-Rückgabetyp.
- Nyx-Seite: erst wenn Nyx-Flows anstehen — dann `Home`/`AppDrawer` als zweite
  Fassaden-Instanz gegen dieselbe `BasePage`.
