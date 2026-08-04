# Implementierungsplan: Hashtags auf dem Homescreen

Feature-Branch: `feature/homescreen-hashtags` (bereits angelegt, noch leer).
Status: **Plan** — noch kein Code geschrieben.

---

## 1. Idee & bestätigte Entscheidungen

Doubletap auf dem Homescreen wählt den angezeigten **Hashtag**. Apps können
keinem, einem oder mehreren Hashtags angehören. `#favorites` ist Default und
Fallback und wird aus den bestehenden Favoriten abgeleitet.

| Frage | Entscheidung |
|---|---|
| Doubletap-Interaktion | **Picker** (Bottom-Sheet/Dialog), kein Durchschalten |
| Bestehende Clipboard-Doubletap-Aktion | **Umzug auf andere Geste = Option A**: Clipboard wird ein zuweisbarer Swipe-Action-Typ |
| Hashtags im Backup/Restore | **Ja** |
| `#favorites` | Default + Fallback; Mitgliedschaft aus `FavoritesRepository` abgeleitet; **nicht** in der neuen Verwaltungs-UI editierbar (bleibt Settings → „Favoriten auswählen") |
| Hashtag-Identität | **Name** (case-insensitive eindeutig), reserviert: `favorites` |

---

## 2. Kernbefunde aus der Code-Recherche (Ist-Zustand)

**Doubletap-Pfad:**
- `HomeGestureLayout.kt` erkennt Doubletap via `GestureDetector`, Callback
  `onDoubleTap: (() -> Unit)?`.
- `HomeFragment.wireDirectionalGestureCallbacks()` verdrahtet
  `gestures.onDoubleTap = { viewModel.onDoubleTap() }`.
- `LauncherViewModel.onDoubleTap()` → `GestureDelegate.onDoubleTap()`:
  liest `ObserveDoubleTapClipboardSettingUseCase`; wenn an →
  `UiEvent.PerformClipboardAction`, sonst einmaliger Hinweis-Toast.
- Verbraucht in `MainActivity.kt:751` (`UiEvent.PerformClipboardAction`).

**Alle sechs Home-Gesten sind belegt** — es gibt keine freie Geste:
Fling↑ = App-Drawer, Swipe↓ = Recent Apps, Swipe←/→ = zwei zuweisbare
Swipe-Actions (`SwipeSlot`), Long-Press = Customization, Doubletap = Clipboard.
Daraus folgt die Clipboard-Umsiedlung nach Option A (siehe §8).

**Homescreen-Liste:**
- Gespeist von `LauncherViewModel.favoriteAppsState:
  StateFlow<UiState<FavoriteAppsResult>>` (Delegate: `AppManagementDelegate`).
- Quelle: `GetFavoriteAppsUseCase.favoriteApps` — `combine` aus rawApps +
  favoriteComponents + hiddenApps + favoritesOrder; bei leeren Favoriten
  Fallback auf Top-N sichtbare Apps (`isFallback = true`).
- Rendering: `HomeFragment.renderFavorites()` → `HomeFavoritesAdapter`
  (`favoritesRecyclerView`).
- Regel (KDoc `GetFavoriteAppsUseCase`): auf dem Home-Screen bricht nur der
  Favoriten-Status den Hidden-Filter; ein versteckter Favorit bleibt gepinnt.
  Der Hidden-Filter greift nur im Fallback-Pfad.

**Favoriten-Persistenz (Vorlage für Hashtag-Repo):**
- `FavoritesRepository` (Interface, `: Purgeable`) / `FavoritesRepositoryImpl`
  (extends `SharedDataStoreFlowRepository`).
- Store: `kolibri_settings` DataStore (`stringSetPreferencesKey`
  `"favorites_components_set"`, Elemente = componentName `"pkg/class"`).
- Muster: read-modify-write in `dataStore.edit{}`; fail-open Snapshot
  (`getFavoriteComponentsSnapshot`), fail-closed `reconcile...`; `purgeRepository`
  via `dataStore.safePurge`.
- DataStore-Keys sind **pro Repository lokal** definiert, nicht zentral.
- `AppInfo.componentName` (`"packageName/normalizedClassName"`) ist der
  Identifier; `AppInfo` ist pures Kotlin (keine Android-Imports).

**Contract-Test-Triple** (Rule 2), Vorlage Favorites:
- `domain/src/testFixtures/.../data/FavoritesRepositoryContract.kt` (abstract)
- `domain/src/testFixtures/.../fakes/FakeFavoritesRepository.kt`
- `domain/src/test/.../data/FakeFavoritesRepositoryContractTest.kt`
- `data/src/test/.../data/FavoritesRepositoryImplContractTest.kt`
- Impl-only: `FavoritesRepositoryImplTest.kt`, `...ShareInTest.kt`

**Verwaltungs-UI-Vorlage:**
- „Favoriten auswählen" ist `OnboardingActivity` im `LaunchMode.EDIT_FAVORITES`.
- `HiddenAppsActivity` teilt bewusst `ActivityOnboardingBinding` +
  `OnboardingAppListAdapter` (Muster „UI teilen, Logik trennen").
- Chip-Muster: `HorizontalScrollView` + `com.google.android.material.chip.ChipGroup`.
  Filter-Chips mit Single-Selection: `activity_swipe_actions.xml`
  (`Widget.Material3.Chip.Filter`, `checkable`, `singleSelection`,
  `selectionRequired`).
- Item-Layout `item_app_selectable_text.xml` (CheckBox + Label, ganze Zeile klickbar).
- Alle Sekundär-Screens sind eigene Activities (`BaseActivity<E,VM>` /
  `BaseViewModel<E>`), Einstieg über `preferences.xml` + `SettingsFragment`-Listener.

**Swipe-Actions (für Option A):**
- `SwipeSlot { SWIPE_FROM_LEFT_TO_RIGHT, SWIPE_FROM_RIGHT_TO_LEFT, NONE }`.
- `SwipeActionsRepository.getSwipeActionComponent(slot)` speichert **nur einen
  componentName-String** pro Slot.
- `HandleSwipeActionUseCase(slot)` → `Result.LaunchApp | NoAction`.
- Auswahl-UI: `SwipeActionsActivity` (statische Filter-Chips für Slot-Wahl).

**Backup:** `BackupDataAssembler` (JSON Export/Import); dort z. B.
`setDoubleTapClipboard` beim Restore.

**Konstanten:** `AppConstants` — `SETTINGS_DATASTORE_NAME = "kolibri_settings"`,
`FLOW_SHARING_TIMEOUT_MS = 5000L`, `MAX_FAVORITES_ON_HOME = 500`,
`DEFAULT_DOUBLE_TAP_CLIPBOARD = false`, `PrefKeys`.

---

## 3. Datenmodell & Persistenz

Alles im bestehenden `kolibri_settings` DataStore (Rule 5: nur DataStore
Preferences).

- Custom-Hashtags brauchen **stabile Reihenfolge** (Chip-/Picker-Order).
  Preferences hat keine geordnete Liste → Speicherung als **ein JSON-Blob**
  unter `stringPreferencesKey("hashtags_v1")`.
  - Struktur: `{ "order": [name, ...], "members": { name: [componentName, ...] } }`
  - Serialisierung an der bestehenden Backup-JSON-Infra ausrichten
    (kotlinx.serialization prüfen; sonst `org.json`). **Vor Umsetzung
    verifizieren**, welcher Weg im Projekt verfügbar ist.
- **Gewählter Hashtag** separat: `stringPreferencesKey("selected_hashtag")`,
  Default = Sentinel für `#favorites`. Fallback auf `#favorites`, wenn der
  gespeicherte Name nicht mehr existiert.
- Identität = Name (unique, case-insensitive). Rename = Namensänderung +
  ggf. `selected_hashtag`-Pointer nachziehen. Delete = aus `order`/`members`
  entfernen; war er selektiert → Fallback `#favorites`.
  - Tradeoff notiert: Name-als-ID hält es simpel; stabile UUIDs nur, falls
    Rename-Robustheit höher gewichtet wird.

---

## 4. Domain-Layer (`:domain`, pure Kotlin)

**Model** (`domain/model/`):
- `Hashtag(name: String, memberComponentNames: Set<String>)`.
- `HomeAppsResult(apps: List<AppInfo>, activeHashtag: String, isFallback: Boolean)`
  (oder `FavoriteAppsResult` erweitern — neuer Typ ist sauberer).
- Sentinel-Konstante `HASHTAG_FAVORITES` in `AppConstants`.

**Repository-Interface** `HashtagRepository : Purgeable` (`domain/repository/`):
- `val hashtagsFlow: Flow<List<Hashtag>>` (nur Custom)
- `val selectedHashtagFlow: Flow<String>`
- `suspend fun setSelected(name: String)`
- `suspend fun createHashtag(name: String): Boolean`
- `suspend fun renameHashtag(oldName: String, newName: String): Boolean`
- `suspend fun deleteHashtag(name: String)`
- `suspend fun toggleMembership(hashtag: String, componentName: String): Boolean`
- `suspend fun reconcile(installedComponentNames: List<String>,
  isStillPresent: suspend (String) -> Boolean)` (Orphan-Purge, fail-closed —
  analog `reconcileFavoriteComponents`)
- `suspend fun getSnapshot(): ...` (fail-open, für Backup-Export)
- `suspend fun purgeRepository()`

**Use Cases** (feingranular, wie im Projekt üblich):
- `GetHashtagsUseCase` (Flow der Custom-Hashtags — Verwaltung + Picker)
- `ObserveSelectedHashtagUseCase`, `SetSelectedHashtagUseCase`
- `CreateHashtagUseCase` — sealed `Result` (Validierung: nicht leer, unique,
  ≠ `favorites`, Länge/Charset, führendes `#` strippen)
- `RenameHashtagUseCase`, `DeleteHashtagUseCase`, `ToggleHashtagMembershipUseCase`
- **`GetHomeScreenAppsUseCase`** (Kern): `combine(selectedHashtag, …)`.
  - Bei `#favorites` → delegiert an die bestehende
    `GetFavoriteAppsUseCase`-Logik (inkl. Top-N-Fallback bei leeren Favoriten).
  - Bei Custom-Hashtag → Member gegen installierte Apps auflösen, alphabetisch
    sortiert, auf Max gecappt. **Leer oder gelöscht → Fallback `#favorites`.**
  - Liefert `HomeAppsResult`.
  - Hidden-Regel: explizite Mitgliedschaft schlägt Hidden (wie Favoriten;
    Custom-Hashtag zeigt seine Member auch wenn hidden).

`GetFavoriteAppsUseCase` bleibt **unangetastet** — die neue UseCase baut darauf
auf, damit Favoriten-Pfad + Tests stabil bleiben.

---

## 5. Data-Layer (`:data`)

- `HashtagRepositoryImpl` (extends `SharedDataStoreFlowRepository`): JSON-Blob im
  `settingsDataStore`, read-modify-write in `edit{}`, fail-open/fail-closed
  analog `FavoritesRepositoryImpl`. Dual-Konstruktor (`@VisibleForTesting`
  primär + `@Inject` sekundär mit `WhileSubscribed(FLOW_SHARING_TIMEOUT_MS)`).
- DI: `@Binds` in `RepositoryModule`.
- **Reconcile-Hook**: in denselben Load-Path einklinken, der Favoriten/Swipe
  gegen die frische App-Liste abgleicht (`ObserveInstalledAppsUseCase`-Pfad),
  damit deinstallierte Apps aus Hashtags fliegen.

---

## 6. Homescreen-Integration (`:app`)

- `AppManagementDelegate` speist die Home-RecyclerView künftig aus
  `GetHomeScreenAppsUseCase` (State-Typ → `HomeAppsResult`) statt direkt aus
  `GetFavoriteAppsUseCase`. `HomeFavoritesAdapter`/`renderFavorites` bleiben
  weitgehend gleich.
- **Doubletap → Picker**: `GestureDelegate.onDoubleTap()` sendet neues
  `UiEvent.ShowHashtagPicker` (Clipboard-Logik entfällt hier → §8). `MainActivity`
  zeigt Bottom-Sheet/Dialog mit `#favorites` + Custom-Hashtags; Auswahl →
  `SetSelectedHashtagUseCase`. Liste aktualisiert reaktiv.
- **Aktiver Hashtag sichtbar**: kleines Label/Chip über der Liste (vorhandenes
  Chip-Styling nutzen). Minimales UI-Detail.

---

## 7. Verwaltungs-UI (neue Activity `ui/hashtags/`)

Template = `HiddenAppsActivity`/`OnboardingActivity` (Layout/Adapter teilen,
eigene ViewModel/Events).

- Neu: `HashtagsActivity`, `HashtagsViewModel`, `HashtagsUiState`,
  `HashtagsEvent`; Adapter ggf. `OnboardingAppListAdapter` wiederverwenden.
- Layout: `HorizontalScrollView` + `ChipGroup(singleLine, singleSelection)` als
  **Kategorie-Filterchips** (Filter-Chip-Style aus `activity_swipe_actions.xml`)
  + `RecyclerView` (Checkbox-Zeilen) + Suchfeld.
- Chip-Reihe = Custom-Hashtags + **„＋"-Chip** (Anlegen via Namens-Dialog).
  Chip antippen = Hashtag wählen → Checkbox-Liste zeigt dessen Member.
  Checkbox togglen = **sofort persistiert** (`ToggleHashtagMembershipUseCase`;
  kein „Done"-Button — passt zum Drawer/Kontextmenü-Muster).
- **Long-press auf Chip** = Rename/Delete (Kontextmenü).
- `#favorites` erscheint hier **nicht** (bzw. read-only Hinweis „in Settings
  verwalten").

**Settings-Einstieg:** `AppConstants.PrefKeys`-Konstante + `<Preference>` in
`preferences.xml` (App-Kategorie), Listener in `SettingsFragment` → `Intent` auf
`HashtagsActivity`; Activity im `AndroidManifest.xml` registrieren
(`parentActivityName = SettingsActivity`).

---

## 8. Clipboard-Umsiedlung — Option A (bestätigt)

Da keine Home-Geste frei ist, wird die Clipboard-Aktion ein **zuweisbarer
Swipe-Action-Typ**:

- `SwipeSlot`/`SwipeActionsRepository` um einen Nicht-App-Action-Typ erweitern
  (z. B. sealed `SwipeAction { LaunchApp(component) | ClipboardSearch }` oder ein
  reservierter Sentinel-String im gespeicherten Slot-Wert).
- `HandleSwipeActionUseCase` liefert dann auch ein Clipboard-Ergebnis → neues
  UiEvent (bestehende `PerformClipboardAction`-Verarbeitung in `MainActivity`
  wiederverwenden).
- Swipe-Actions-Auswahl-UI (`SwipeActionsActivity`) um den Eintrag
  „Clipboard-Suche" ergänzen.
- Das alte Doubletap-Clipboard-Setting (`DEFAULT_DOUBLE_TAP_CLIPBOARD`,
  `setDoubleTapClipboard`, `ObserveDoubleTapClipboardSettingUseCase`,
  `toast_enable_double_tap_clipboard`) entfällt; Backup-Restore-Pfad
  (`BackupDataAssembler`) entsprechend bereinigen/migrieren.

---

## 9. Backup/Restore

- `BackupDataAssembler` additiv um Hashtag-Definitionen + Memberships
  (+ optional `selected_hashtag`) erweitern; rückwärtskompatibles Feld
  (fehlt in alten Backups → leer).
- Restore stellt Hashtags wieder her; Clipboard-Setting-Feld im Backup-Schema
  gemäß §8 behandeln (ignorieren/migrieren).

---

## 10. Lokalisierung

Neue Strings in **beiden** Dateien (`res/values/strings.xml` +
`res/values-de/strings.xml`): Picker-Titel, Verwaltungs-Titel/Subtitle,
Settings-Eintrag, Anlegen/Rename/Delete-Dialoge, Validierungsfehler,
Limit-Toasts, Swipe-Action-Label „Clipboard-Suche". Reuse `search_hint`, `done`.

---

## 11. Tests (Projekt-Konventionen)

- **Contract-Triple** für `HashtagRepository`: `HashtagRepositoryContract` +
  `FakeHashtagRepository` + `HashtagRepositoryImplContractTest` (Rule 2).
  Impl-only-Tests: JSON-Serialisierung, Limits, Reconcile.
- Unit-Tests: `GetHomeScreenAppsUseCase` (Favorites-Pfad, Custom-Pfad, Fallback
  bei leer/gelöscht), Create/Rename/Delete-Validierung, `HashtagsViewModel`.
- Swipe-Action-Erweiterung: `HandleSwipeActionUseCase`-Tests um Clipboard-Zweig.
- Backup-Roundtrip-Test (Export/Import inkl. Hashtags).
- Durchgehend `MainDispatcherRule` + `runTest(rule.dispatcher)`, MockK, Turbine.

---

## 12. Konstanten/Limits

In `AppConstants`: `MAX_HASHTAGS`, `MAX_APPS_PER_HASHTAG` (oder Display-Cap wie
`MAX_FAVORITES_ON_HOME`), max. Namenslänge, reservierter Name `favorites`,
Sentinel `HASHTAG_FAVORITES`.

---

## 13. Commit-Reihenfolge (Vorschlag)

1. Domain: Model + `HashtagRepository`-Interface + Use Cases
2. Data: `HashtagRepositoryImpl` + DI + Contract-Tests
3. `GetHomeScreenAppsUseCase` + Homescreen-Rendering-Umstellung
4. Doubletap-Picker (UiEvent + MainActivity-Bottom-Sheet)
5. Verwaltungs-Activity + Settings-Einstieg
6. Clipboard-Umsiedlung nach Swipe-Action (Option A)
7. Backup/Restore
8. Strings/Tests-Feinschliff + `./gradlew test checkConventions checkRule13`

---

## 14. Vor der Umsetzung noch zu verifizieren

- Serialisierungsweg für den JSON-Blob (kotlinx.serialization vs. `org.json`) —
  an Backup-Infra ausrichten.
- Genauer Reconcile-Einhängepunkt (`ObserveInstalledAppsUseCase`-Pfad) für die
  Orphan-Bereinigung der Hashtag-Memberships.
- Exakte Form der Swipe-Action-Typ-Erweiterung (sealed type vs. Sentinel-String)
  inkl. Rückwärtskompatibilität bestehender Slot-Werte.
