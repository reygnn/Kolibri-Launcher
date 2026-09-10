# PHASE_B_IMPL_NOTE — Enumeration auf `LauncherApps.getActivityList` (noch single-user)

> Umsetzungs-Notiz zu `MULTIUSER_ENUMERATION_SPEC §13 Phase B`. Kolibri-standalone.
> Setzt Phase A voraus (strukturierter `ComponentKey`, `AppInfo.userSerial` mit
> Default 0). Ziel: die Drawer-Enumeration von `PackageManager.queryIntentActivities`
> auf `LauncherApps.getActivityList` umstellen — **noch nur für den Primärnutzer**
> (`Process.myUserHandle()`). Gewinn: Label direkt aus `LauncherActivityInfo`, kein
> separates `loadLabel`-IPC pro App. Verhalten sonst gleich.

---

## 0. Leitidee

Heute (`InstalledAppsRepositoryImpl`): `queryIntentActivities(ACTION_MAIN/CATEGORY_
LAUNCHER)` + in `processResolveInfoList` **ein `loadLabel(packageManager)`-IPC pro
App** — der getracete `drawer_apps_enumerate`-Abschnitt („first drawer open"-Kosten).

Phase B: `launcherApps.getActivityList(null, Process.myUserHandle())` liefert
`List<LauncherActivityInfo>`, und `info.label` **ist** schon das aufgelöste Label →
die per-App-`loadLabel`-Schleife entfällt. `getActivityList` ist außerdem der vom
System für Launcher vorgesehene Pfad und die Basis für Phase D (Profile).

Noch **nicht** in Phase B: `userManager.userProfiles`-Iteration (das ist D),
`PackagePresenceImpl`/`ComponentLabelResolverImpl` (die nutzen weiter
`queryIntentActivities` — Phase C bzw. bleiben).

> **DoD Phase B:** Drawer-Enumeration läuft über `LauncherApps.getActivityList`
> (Primärnutzer), kein `loadLabel`-IPC mehr im Enumerationspfad, App-Set auf Gerät
> identisch zur alten Liste, alle Tests grün, `drawer_apps_enumerate`-Trace messbar
> schneller.

---

## 1. Neuer Hilt-Provider für `LauncherApps` (`app/di/AppModule.kt`)

Es gibt bisher nur `providePackageManager`. Ergänzen:

```kotlin
@Provides
@Singleton
fun provideLauncherApps(@ApplicationContext context: Context): LauncherApps =
    context.getSystemService(LauncherApps::class.java)
        ?: error("LauncherApps service unavailable")
```

(Optionaler Tidy, nicht Teil von B: `AppLauncherImpl` holt `LauncherApps` heute
ad-hoc via `activity.getSystemService` — könnte künftig den injizierten nutzen.)

---

## 2. `InstalledAppsRepositoryImpl` — Quelle tauschen

### 2a. Konstruktor

```kotlin
// VORHER
class InstalledAppsRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val packageManager: PackageManager,
    private val appsUpdateTrigger: MutableSharedFlow<Unit>,
)

// NACHHER
class InstalledAppsRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val launcherApps: LauncherApps,
    private val appsUpdateTrigger: MutableSharedFlow<Unit>,
)
```

`packageManager` fällt aus diesem Konstruktor **nur, wenn** es sonst nirgends in der
Klasse genutzt wird (nach dem Tausch: Enumeration + `loadLabel` sind weg → prüfen mit
`grep packageManager` in der Datei; wenn leer, entfernen, sonst behalten). Der
`intent`-Bau (`ACTION_MAIN`/`CATEGORY_LAUNCHER`) entfällt ebenfalls.

### 2b. Enumerationsstelle (der `Trace`-Block, ~Z. 190)

```kotlin
// VORHER
val freshApps = try {
    Trace.beginSection("drawer_apps_enumerate")
    val resolveInfoList = packageManager.queryIntentActivities(
        intent, PackageManager.ResolveInfoFlags.of(0)
    )
    processResolveInfoList(resolveInfoList)
} finally { Trace.endSection() }

// NACHHER — Trace-Name bewusst gleich (Perfetto-Vergleich alt/neu)
val freshApps = try {
    Trace.beginSection("drawer_apps_enumerate")
    val activities = launcherApps.getActivityList(null, Process.myUserHandle())
    buildAppInfos(activities.map { it.toRawActivity() })
} finally { Trace.endSection() }
```

### 2c. Mapping + reine Bau-Logik (Testbarkeit, Rule 10)

`LauncherActivityInfo` ist ein finaler Android-Typ ohne öffentlichen Konstruktor →
in JVM-Tests nicht baubar. Deshalb die reine „Rohdaten → `List<AppInfo>`"-Logik
(die heute `processResolveInfoList` ist) hinter einen **plain-Kotlin**-Helfer legen
und nur das dünne Android-Mapping in `:data` lassen:

```kotlin
/** Pures Zwischenformat — JVM-testbar, kein Android-Typ. */
data class RawActivity(val packageName: String, val className: String, val label: String)

private fun LauncherActivityInfo.toRawActivity(): RawActivity {
    val cn = componentName                      // stets absolut
    val lbl = try { label?.toString().orEmpty() } catch (e: Throwable) {
        TimberWrapper.silentError(e, "Error reading label for ${cn.packageName}"); ""
    }
    return RawActivity(cn.packageName, cn.className, lbl.ifBlank { cn.packageName })
}

/** War processResolveInfoList — jetzt pur über RawActivity (kein loadLabel-IPC). */
@VisibleForTesting
fun buildAppInfos(entries: List<RawActivity>): List<AppInfo> =
    entries.mapNotNull { e ->
        try {
            AppInfo(
                originalName = e.label,
                displayName = e.label,
                packageName = e.packageName,
                className = e.className,   // AppInfo/ComponentKey normalisiert (Phase A)
                // userSerial default 0 — Multi-User erst Phase D
            )
        } catch (ex: CancellationException) { throw ex }
        catch (ex: Throwable) { TimberWrapper.silentError(ex, "Error building AppInfo"); null }
    }.sortedByDisplayName()
```

- Der `null-activityInfo`-Guard von früher entfällt: `getActivityList` liefert nur
  gültige, startbare Activities (kein `ResolveInfo` mit `null` `activityInfo`).
- Custom-Names werden weiterhin **nicht** hier eingebacken (REACTIVE_APPLIST_SPEC):
  Enumeration emittiert das Originallabel, Display-Sites falten reaktiv.
- `sortedByDisplayName()` bleibt am Ende — Sortierung unverändert.

---

## 3. Was NICHT angefasst wird

- **Noch single-user:** nur `Process.myUserHandle()`. Keine `userManager.userProfiles`-
  Iteration (Phase D). `AppInfo.userSerial` bleibt 0.
- **`PackagePresenceImpl`** (Reconcile-Gate) und **`ComponentLabelResolverImpl`**
  (provisorischer Favoriten-Paint) nutzen weiter `queryIntentActivities` — das ist
  Phase C bzw. bleibt. Nicht in B anfassen.
- **Icon-Pfad** unberührt — Enumeration lädt keine Icons; der Perf-Gewinn ist
  ausschließlich das entfallene `loadLabel`-IPC.
- **`AppLoad.Failed`-Fehlersemantik** (INSTALLED_APPS_LOAD_SPEC Belang A) bleibt: ein
  `getActivityList`-Fehler fliegt in den äußeren Catch → `AppLoad.Failed`, nicht
  `Loaded(emptyList())`.

---

## 4. Verhaltens-Diffs, die man verifizieren muss (der reale Risikopunkt)

`queryIntentActivities(ACTION_MAIN/LAUNCHER, flag 0)` und
`getActivityList(null, primaryUser)` liefern für den Primärnutzer normalerweise
dieselbe Menge, aber **prüfen, nicht annehmen**:

- **App-Set-Diff auf Gerät:** einmal beide Wege parallel laufen lassen und die
  `componentName`-Mengen diffen (Debug-Log oder ein Einmal-Vergleich). Erwartung:
  leere Differenz. Randfälle: synthetische/disabled Einträge, die eine API anders
  behandelt.
- **Label-Quelle:** `LauncherActivityInfo.label` (Activity-Label) vs.
  `ResolveInfo.loadLabel` — praktisch identisch; in seltenen Fällen kann das
  Activity-Label vom App-Label abweichen. Für einen Launcher ist das Activity-Label
  das gewünschte. Kurz querchecken (ein bekanntes Multi-Activity-Paket).
- **Reihenfolge vor `sortedByDisplayName`** ist egal (wir sortieren ohnehin).

---

## 5. Tests

- **Neu/verschoben (JVM):** die bisherigen `processResolveInfoList`-Tests wandern auf
  `buildAppInfos(List<RawActivity>)` — jetzt **ohne** Android-Mock, reiner Input→Output
  (leeres Label → Package-Fallback, Sortierung, `componentName`-Bildung via Phase-A-Key).
- **Neu (Robolectric/`androidTest`):** `toRawActivity()`-Mapping + die
  `getActivityList`-Anbindung gegen echten `LauncherApps` (Set nicht leer, Labels
  gesetzt). Das ist die einzige neue gerätegebundene Stelle.
- **Bestehend:** Fehlerpfad-Tests (`AppLoad.Failed` bei Enumerationsfehler) bleiben,
  Ziel-Aufruf jetzt `getActivityList` statt `queryIntentActivities` (Mock/Fake
  anpassen).
- **Dispatcher:** unverändert; ein Dispatcher via `MainDispatcherRule`, kein separater
  `TestScope`.

---

## 6. Verifikation (Definition of Done)

```
grep -rn "queryIntentActivities" data/src/main/.../InstalledAppsRepositoryImpl.kt  # → leer
grep -rn "loadLabel"             data/src/main/.../InstalledAppsRepositoryImpl.kt  # → leer
grep -rn "getActivityList"       data/src/main                                     # → Enumerationsstelle
./gradlew :domain:test :data:test :app:test   # → grün
./tools/check-conventions.sh                    # → grün
```
Plus auf Gerät: Drawer öffnen → gleiche App-Liste; `drawer_apps_enumerate`-Trace in
Perfetto gegen den alten Wert halten (loadLabel-IPCs sind weg → messbar kürzer).

---

## 7. Risiken

- **App-Set-Diff** (der einzige echte): erst mit dem Parallel-Vergleich aus §4
  absichern, bevor `queryIntentActivities` entfernt wird.
- **`LauncherApps`-Service null** auf exotischen Builds → Provider `error(...)` macht
  es laut statt still falsch; alternativ nullable + `AppLoad.Failed`.
- **Min-SDK:** `getActivityList`/`LauncherApps` sind ab API 21 verfügbar,
  `getSystemService(Class)` ab 23 — Kolibris min-SDK ist darüber; kein Guard nötig
  (verifizieren).
- **Test-Bruch durch Signaturwechsel** `processResolveInfoList → buildAppInfos`: die
  reine Logik bleibt gleich, nur der Input-Typ wird pur — die Tests werden dadurch
  *einfacher*, nicht schwächer.
