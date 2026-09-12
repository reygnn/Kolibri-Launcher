# APP_USAGE_SPEC — App-Nutzungslogik (Tracking, Score, Store, Export) als geteilter Kern

> **Erzeugt** gegen `main` @ `<HEAD-Hash einsetzen>`, als Detail-Spec für das
> Usage-Subsystem, ergänzt zur Wunschliste in `MONOREPO_MERGE_SPEC §3.5`. Konsumiert
> die Port-Doktrin (MRG-INV-8) und reitet für den Usage-Export die Schienen aus
> `BACKUP_SCHEMA_PORT_SPEC`.
>
> **Fokus:** die selbst-getrackte App-Nutzungslogik teilen — das **Scoring**
> (exponentieller Zeit-Decay), den **Timestamp-Store** (per Package-Name gekeyt),
> die neutralen Use-Cases (Record/Reset/Recent/Check/Toggle), die **Ranking-Funktion**
> für die Sortierung und den **Usage-Export/Import**. Zieht die dünne Produktgrenze
> an den App-Modell-Rändern (Record nimmt `AppInfo`, Drawer gibt `AppInfo` zurück).
>
> **Nicht im Fokus:** die *Drawer-UI* selbst und Kolibris flache Favoriten-Sortier-/
> Split-Semantik (`APPLIST_SORT_SPLIT_SPEC`, Klasse B); die reaktive Drawer-Pipeline
> im Detail (`REACTIVE_APPLIST_SPEC`, hier nur referenziert); Androids
> `UsageStatsManager` (wird **nicht** verwendet, s. u.).
>
> **Status:** ENTWURF v1.2. Die Signaturen in §3–§4 sind Vorschläge auf Basis des
> realen Kolibri-Codes; Review-Runde 1 ausstehend.
>
> **Verhältnis zum großen Kolibri:** Kolibri trackt Launches **selbst** (kein
> `UsageStatsManager`, keine `PACKAGE_USAGE_STATS`-Permission) und bewertet sie mit
> `timeWeightedUsageScore` (λ-Decay). Store und Scoring sind bereits produktneutral;
> `UsageScore` wurde ausdrücklich aus `AppUsageRepositoryImpl` in die reine
> `:domain`-Schicht gezogen, „JVM-testbar + JMH-benchmarkbar ohne Android". Dieser
> Spec entkoppelt nur die eine produktgetippte Methode und hebt den Rest hoch.

---

## §0 Bestandsaufnahme (was real existiert)

| Rolle | Datei | Android? | Kopplung |
|---|---|---|---|
| Scoring | `core/UsageScore.kt` (`timeWeightedUsageScore`), `core/UsageTimestamp.kt` (`isValidUsageTimestamp`) | **nein** | produktneutral |
| Store-Port | `domain/repository/AppUsageRepository.kt` (`: Purgeable`) | nein | **fast** neutral (1 Methode getippt) |
| Store-Impl | `data/AppUsageRepositoryImpl.kt` (DataStore, Key `KEY_USAGE_PREFIX + packageName`) | ja | per Package-Name gekeyt → neutral |
| DataStore | `di/UsageDataStore.kt` | ja | neutral |
| Neutrale Use-Cases | `CheckAppUsageUseCase`, `ResetAppUsageUseCase`, `ToggleSortOrderUseCase` | nein | packageName/enum |
| App-Modell-Use-Cases | `RecordAppLaunchUseCase(AppInfo)`, `GetRecentAppsUseCase→List<AppInfo>`, `GetDrawerAppsUseCase→Flow<List<AppInfo>>` | nein | **an `AppInfo` getippt** |
| Sort-Modelle | `domain/model/SortOrder.kt` (`ALPHABETICAL`/`TIME_WEIGHTED_USAGE`), `AppInfoSort.kt` | nein | Enum neutral / Sort getippt |
| Export | `domain/repository/UsageExportRepository.kt`, `Export/ImportUsageFromFileUseCase`, `data/UsageExportRepositoryImpl.kt`, `domain/model/UsageImportResult.kt`, `ui/usageexport/*` | ja (Datei-IO) | **neutrales Payload** |

Beobachtete Fakten, die den Schnitt bestimmen:

- **Selbst-getrackt, nicht System-API.** Kein `UsageStatsManager`, kein
  `queryUsageStats`, keine `PACKAGE_USAGE_STATS`-Permission irgendwo im Baum. Der
  Store ist die einzige Wahrheitsquelle.
- **Store per Package-Name (`String`).** `usageSnapshotFlow: Flow<Map<String, List<Long>>>`,
  `recordPackageLaunch(packageName: String?)`, `removeUsageDataForPackage`,
  `hasUsageDataForPackage`, `getRecentlyLaunchedPackages(limit): List<String>` — alles
  produktneutral. Package-granular (nicht volle `ComponentKey` mit Activity).
- **Scoring rein & wurffest.** `timeWeightedUsageScore(timestamps, currentTime)`:
  dedupe, Clock-Skew-Guard (Zukunfts-Timestamps → 0), `exp`-Overflow-Guards,
  `coerceInSafe` — kann nicht werfen, braucht kein try/catch.
- **Eine produktgetippte Methode.** `AppUsageRepository.sortAppsByTimeWeightedUsage(...)`
  gibt sortierte `AppInfo` zurück — die einzige `AppInfo`-Berührung im sonst neutralen
  Store-Port.
- **Usage-Export ist end-to-end neutral.** Payload = `Map<String, List<Long>>`
  (Package→Timestamps), JSON via `buildExportJson` + `validateJsonStructure`
  (org.json), `UsageImportResult` sealed (Success/InvalidFormat/UnsupportedVersion/
  Error), `mergeWithExisting`-Flag. **Kein** Produktmodell im Payload.

---

## §1 Zielbild

- Scoring, Store, neutrale Use-Cases, Ranking und der **komplette** Usage-Export
  liegen geteilt; beide Launcher benutzen dieselbe Nutzungslogik.
- Die eine produktgetippte Store-Methode wird zur reinen Ranking-Funktion
  entkoppelt; die App wendet sie auf ihr eigenes App-Modell an.
- Nyx bekommt Usage-Tracking, -Scoring, -Sortierung und -Export, indem es nur die
  drei dünnen App-Modell-Adapter schreibt (`RecordAppLaunch`, `GetRecentApps`,
  `GetDrawerApps` über `LauncherApp` statt `AppInfo`) — **kein** duplizierter
  Score-, Store- oder Export-Code.

---

## §2 Klassifizierung

> **USG-INV-1 — Nutzung ist package-gekeyt und produktfrei.** Scoring, Store,
> Snapshot-Flow, Record/Reset/Recent/Check und der Export hängen nur an
> `String`-Package-Namen, `Long`-Timestamps und dem `SortOrder`-Enum — kein
> `AppInfo`/`LauncherApp`/`HomeLayout`/Favoriten. Die einzige App-Modell-Berührung
> lebt in den drei Adapter-Use-Cases (§4).

| Teil | Neutral (geteilt) | Dünner Adapter pro App |
|---|---|---|
| `timeWeightedUsageScore`, `isValidUsageTimestamp` | ✅ `:core` | |
| `AppUsageRepository` (neutralisiert) + Impl + `UsageDataStore` | ✅ | |
| `recordPackageLaunch`/`remove`/`has`/`recentPackages`/`snapshotFlow` | ✅ | |
| Ranking-Funktion (`rankByUsage`) | ✅ `:core` | |
| `SortOrder` (Enum), `CheckAppUsageUseCase`, `ToggleSortOrderUseCase`, `ResetAppUsageUseCase` | ✅ | |
| Usage-Export/Import (Payload `Map<String,List<Long>>`, `UsageImportResult`) | ✅ **end-to-end** | |
| `RecordAppLaunchUseCase(app)`, `GetRecentAppsUseCase→App`, `GetDrawerAppsUseCase→App` | | ✅ (wenige Zeilen) |
| `AppUsageRepository.sortAppsByTimeWeightedUsage(AppInfo)` | wird zu `rankByUsage` (neutral) | Anwendung pro App |

---

## §3 Der geteilte Kern

### §3.1 Scoring (`:core`, rein)

`timeWeightedUsageScore(timestamps: List<Long>, currentTime: Long): Double` und
`isValidUsageTimestamp(timestamp, currentTime): Boolean` wandern unverändert (nur
Namespace) nach `:core`. `AppConstants.USAGE_DECAY_LAMBDA` bleibt daneben.

> **USG-INV-2 — Scoring bleibt total & wurffest.** Dedupe, Clock-Skew-Guard
> (`Δt < 0 ⇒ 0`), `exp`-Overflow-Guards (`exponent < -100 ⇒ 0`, `> 100 ⇒ 1`) und
> `coerceInSafe(0..1)` pro Launch bleiben erhalten. Die Funktion wirft nicht; der
> einzige Fehlermodus ist der umgebende DataStore-Read (Store-Schicht).

### §3.2 Store (`:domain` Port, `:common-data` Impl) — neutralisiert

```kotlin
// :domain — nur noch String-gekeyte, produktneutrale Methoden
interface AppUsageRepository : Purgeable {
    val usageSnapshotFlow: Flow<Map<String, List<Long>>>   // package → launch-millis
    suspend fun recordPackageLaunch(packageName: String?)
    suspend fun removeUsageDataForPackage(packageName: String?)
    suspend fun hasUsageDataForPackage(packageName: String?): Boolean
    suspend fun getRecentlyLaunchedPackages(limit: Int): List<String>
    // ENTFERNT: sortAppsByTimeWeightedUsage(apps: List<AppInfo>, …)  → §3.3
}
```

> **USG-INV-3 — Der Store kennt kein App-Modell.** `sortAppsByTimeWeightedUsage`
> (die einzige `AppInfo`-Methode) verlässt den Store-Port. Was bleibt, ist reine
> Timestamp-Verwaltung, per Package-Name gekeyt (`KEY_USAGE_PREFIX + packageName`,
> `stringSetPreferencesKey`).

### §3.3 Ranking-Funktion (`:core`, rein) — Ersatz für die getippte Sort-Methode

```kotlin
// :core — ordnet BELIEBIGE Package-Keys nach Score; Anwendung aufs App-Modell in §4
fun rankByUsage(
    packageNames: List<String>,
    snapshot: Map<String, List<Long>>,
    currentTime: Long,
): List<String> =                       // absteigend nach timeWeightedUsageScore,
    packageNames.sortedByDescending {    // stabiler Tie-Break überlässt der Aufrufer
        timeWeightedUsageScore(snapshot[it].orEmpty(), currentTime)
    }
```

> **USG-INV-4 — Sortierung ist Schlüssel-Ranking, keine Modell-Sortierung.** Der
> geteilte Kern ordnet `String`-Keys; die App wendet die Reihenfolge auf ihre
> `AppInfo`/`LauncherApp`-Liste an (§4). Der alphabetische Tie-Break
> (`sortedByDisplayName`, `AppInfoSort`) ist App-Sache (er kennt das Anzeige-Modell).

### §3.4 Usage-Export/Import (voll geteilt)

Weil das Payload neutral ist (`Map<String, List<Long>>`), wandert der **gesamte**
Export/Import geteilt — als zweiter Klient der Backup-Engine
(`BACKUP_SCHEMA_PORT_SPEC`) mit einem `BackupSchema<UsageData>`, oder als
eigenständiger schlanker JSON-Writer.

```kotlin
// :domain — UsageImportResult bleibt sealed (Erfolg/Format/Version/Fehler)
sealed class UsageImportResult {
    data class Success(val packagesImported: Int, val timestampsImported: Int, val packagesSkipped: Int = 0)
    data object InvalidFormat; data class UnsupportedVersion(val version: String); data class Error(val message: String)
}
interface UsageExportRepository {                    // 4 Methoden, alle neutral
    suspend fun exportToJson(): String
    suspend fun importFromJson(json: String, mergeWithExisting: Boolean = true): UsageImportResult
    suspend fun saveToFile(uriString: String): Boolean
    suspend fun loadFromFile(uriString: String, mergeWithExisting: Boolean = true): UsageImportResult
}
```

> **USG-INV-5 — Usage-Export ist der neutralste Datei-Pfad.** Anders als das
> Haupt-Backup (produktspezifisches `LauncherSettings`, `BACKUP_SCHEMA_PORT_SPEC`)
> hat der Usage-Export **kein** Produktmodell im Payload und ist end-to-end teilbar:
> Serialisierung, Validierung (`validateJsonStructure`, `MAX_TIMESTAMPS_PER_APP`),
> `mergeWithExisting`-Semantik und `UsageImportResult` gelten unverändert für beide
> Apps.

---

## §4 Die dünne App-Adapter-Naht

Nur drei Use-Cases berühren das App-Modell; jeder ist wenige Zeilen und wird pro App
geschrieben (Kolibri über `AppInfo`, Nyx über `LauncherApp`):

```kotlin
// Beispiel Nyx — RecordAppLaunch: extrahiert den Package-Key, ruft den neutralen Store
class RecordAppLaunchUseCase @Inject constructor(private val usage: AppUsageRepository) {
    suspend operator fun invoke(app: LauncherApp) = usage.recordPackageLaunch(app.packageName)
}

// GetDrawerApps (Nyx): kombiniert installierte Apps + versteckte + Usage-Snapshot,
// ordnet im TIME_WEIGHTED_USAGE-Modus per rankByUsage, sonst sortedByDisplayName.
```

> **USG-INV-6 — Der Adapter mappt nur, er rechnet nicht.** Die App-Use-Cases
> übersetzen App-Modell ↔ Package-Key und rufen `rankByUsage`/`recordPackageLaunch`;
> keine Score-Mathe, keine Store-Details, kein Export-Code wird pro App dupliziert.

> **Nachtrag (Single-User-/AppInfo-Entscheidung, `MONOREPO_MERGE_SPEC` MRG-INV-9):**
> Da Nyx das geteilte `AppInfo` übernimmt (statt eigenem `LauncherApp`), schrumpfen
> diese drei Adapter auf nahezu null — `RecordAppLaunch`/`GetRecentApps`/`GetDrawerApps`
> sind dann bereits `AppInfo`-typisiert und größtenteils geteilt. Was pro App bleibt,
> ist nur die Drawer-Pipeline-Verdrahtung (§5), nicht das App-Modell-Mapping.

---

## §5 Reaktive Naht (referenziert `REACTIVE_APPLIST_SPEC`)

`usageSnapshotFlow` ist produktneutral und treibt Kolibris Drawer reaktiv: ein
Launch-Tick re-emittiert. Kolibris `GetDrawerAppsUseCase` gated per `flatMapLatest`,
sodass die Usage **nur** im `TIME_WEIGHTED_USAGE`-Modus Eingang ist (im
`ALPHABETICAL`-Modus löst ein Launch-Tick kein `applyCustomNames`-Re-Run aus).

> **USG-INV-7 — Der Snapshot-Flow ist geteilt, das Gating ist App-Sache.**
> `usageSnapshotFlow` (neutral) wandert mit; die reaktive Drawer-Pipeline inkl.
> Modus-Gating bleibt pro App (sie kennt Custom-Names/Hidden/Anzeige-Modell). Nyx
> darf ein einfacheres Gating wählen.

---

## §6 Modul-Zuordnung

| Einheit | Zielmodul |
|---|---|
| `timeWeightedUsageScore`, `isValidUsageTimestamp`, `rankByUsage`, `SortOrder`, `USAGE_DECAY_LAMBDA` | `:core` |
| `AppUsageRepository` (Port), `UsageExportRepository` (Port), `UsageImportResult`, neutrale Use-Cases (`Check`/`Reset`/`Toggle`, `getRecentlyLaunchedPackages`) | `:domain` |
| `AppUsageRepositoryImpl`, `UsageExportRepositoryImpl`, `UsageDataStore` | `:common-data` |
| `RecordAppLaunchUseCase`, `GetRecentAppsUseCase`, `GetDrawerAppsUseCase`, `AppInfoSort`/`sortedByDisplayName`, Drawer-UI, `ui/usageexport` UI | `:app-nyx` / `:app-kolibri` |

Der `ui/usageexport`-Bildschirm ist app-lokal (eigene Strings/Dialoge); die Logik
darunter (`UsageExportRepository`) ist geteilt. Bei Bedarf zu `:feature-usage`
bündeln (`MONOREPO_MERGE_SPEC §4.2`); Start in `:core`/`:common-data`.

---

## §7 Testplan (Rule 10 + eure Testkonvention)

- **JVM (schnell, kein Gerät):** `timeWeightedUsageScore` als Wahrheitstabelle
  (leer ⇒ 0, Clock-Skew ⇒ 0, sehr alt ⇒ 0, dedupe, Monotonie recent>old);
  `rankByUsage` (Ordnung, leerer Snapshot, Tie-Break-Delegation); Export-Roundtrip
  (`buildExportJson` ↔ Parse), Import-`mergeWithExisting` (Merge vs. Replace,
  `MAX_TIMESTAMPS_PER_APP`-Cap, `UnsupportedVersion`/`InvalidFormat`). Kolibris
  JMH-Benchmark für `timeWeightedUsageScore` wandert mit.
- **Robolectric/`androidTest` (nur Gerätewahrheit):** `AppUsageRepositoryImpl`
  gegen echten DataStore (`recordPackageLaunch`, `usageSnapshotFlow`-Emission,
  Purge), Datei-IO des Exports.
- **Dispatcher:** ein Dispatcher via `MainDispatcherRule`, **kein** separater
  `TestScope`/`StandardTestDispatcher`; Repos als MockK-Fakes;
  `TESTING_CONVENTIONS.kt`.

> **USG-INV-8 — Verschoben, nicht verwässert.** Kolibris bestehende Usage- und
> Export-Tests wandern mit und bleiben grün; die Neutralisierung fügt nur den
> `rankByUsage`-Test hinzu (vorher als `sortAppsByTimeWeightedUsage`-Test getarnt).

---

## §8 Migration in grün-bleibenden Phasen

Voraussetzung: `:core`, `:common-data` existieren.

- **Phase A — Scoring + Store hochziehen.** `UsageScore`/`UsageTimestamp` → `:core`;
  `AppUsageRepository` (Port) → `:domain`; Impl + `UsageDataStore` → `:common-data`,
  neutraler Namespace. Verhalten unverändert.
- **Phase B — Store neutralisieren.** `sortAppsByTimeWeightedUsage(AppInfo)` aus dem
  Port entfernen; `rankByUsage` in `:core` einführen; Kolibris Drawer-Use-Case ruft
  `rankByUsage` + wendet es auf `AppInfo` an. Tests grün.
- **Phase C — Usage-Export teilen.** `UsageExportRepository` + Impl +
  `UsageImportResult` → geteilt (als `BackupSchema<UsageData>` auf der Backup-Engine
  oder eigenständig). Kolibri konsumiert die geteilte Fassung.
- **Phase D — Nyx.** Drei Adapter-Use-Cases (`RecordAppLaunch`, `GetRecentApps`,
  `GetDrawerApps`) über `LauncherApp` + der `ui/usageexport`-Screen. Nyx hat
  Tracking/Scoring/Sortierung/Export ohne duplizierte Logik.

---

## §9 Querschnitt-Konventionen (geerbt)

- **Policy/IO-Split** — Score + Ranking + Export-Serialisierung rein; DataStore +
  Datei-IO in der Android-Schicht.
- **`:core`/`:domain` bleiben Android-frei** — alles per `String`/`Long`/Enum.
- **Sealed Ergebnis-Identifier** — `UsageImportResult`; `:app` mappt zu UI-Feedback.
- **„Nur bei echter Änderung schreiben"** — leerer/nicht-mergender Import ⇒ kein
  Store-Write.
- **Referenz per nacktem Namen** — `APP_USAGE_SPEC §3.3`, nie ein Pfad.

---

## §10 Offene Punkte (für Review-Runde 1)

**Entschieden:**
- **Usage-Export = `BackupSchema<UsageData>` auf der geteilten Backup-Engine** (nicht
  eigener Writer). Payload `Map<String,List<Long>>`, keine Blobs, triviale Preview.
  „Geteilte Engine" heißt *nicht* „dieselbe Datei": `BackupSchema<T>` ist pro
  Payload-Typ, Haupt-Backup und Usage-Export bleiben getrennte Artefakte. Die Engine
  schreibt bei einem **blob-losen** Schema eine schlichte `.json` statt `.zip`
  (menschenlesbar), s. `BACKUP_SCHEMA_PORT_SPEC §4`.
- **Granularität = package-granular** (per Store-Keying-Regel `MONOREPO_MERGE_SPEC §7`,
  MRG-INV-10): Usage keyt `key.packageName`. Kein Wechsel auf volle `ComponentKey`.
- **`rankByUsage` bekommt einen injizierbaren Tie-Break-Comparator**, damit der
  alphabetische Sekundär-Sort (Anzeige-Modell) nicht ins `:core` sickert: `:core`
  ordnet nur nach Score, der Tie-Break kommt vom Aufrufer.
- **Settings-Keys (`sort_order`, `USAGE_DECAY_LAMBDA`) gehören ins Settings-/Haupt-
  Backup, nicht in den Usage-Export.** Der Usage-Export trägt ausschließlich die
  Nutzungs-Timestamps; keine Doppelsicherung.

---

## Review-Log

| Runde | Datum | Reviewer | Ergebnis |
|---|---|---|---|
| v1.2 | `<offen>` | `<offen>` | Usage-Export entschieden: `BackupSchema<UsageData>` auf geteilter Engine, blob-los ⇒ `.json`. Keine offenen Punkte mehr |
| v1.1 | `<offen>` | `<offen>` | Empfehlungen als Entscheide: package-granular (per MRG-INV-10), `rankByUsage` mit injizierbarem Tie-Break, Settings-Keys nur im Settings-Backup. Offen: Usage-Export-Form |
| 1 | `<offen>` | `<offen>` | ausstehend |
