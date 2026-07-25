# CLAUDE.md

Orientierungshilfe für zukünftige Sessions, die sich mit den Unit-Tests
dieses Projekts beschäftigen. Nicht als technisches Handbuch gedacht —
dafür gibt es `java/com/github/reygnn/kolibri_launcher/TESTING_CONVENTIONS.kt`,
und dort steht alles Konkrete. Diese Datei beantwortet die _Meta_-Fragen:
Was existiert? Warum? Was ist bewusst weggelassen? Wo fange ich an?

---

## Stack im Kurzformat

- Kotlin Android Launcher, Hilt-basierte DI
- Unit-Tests: JUnit4, MockK, kotlinx-coroutines-test, Turbine
- Einige Tests brauchen Robolectric (alles was `android.net.Uri` berührt)
- Test-Konventionen zementiert in
  `java/com/github/reygnn/kolibri_launcher/TESTING_CONVENTIONS.kt`
- Rules für Tests: `MainDispatcherRule`, `TimberRule` (beide in `rule/`)

Das Projekt hat eine bewusste Trennung zwischen:

- **`data/`** — Produktions-Implementierungen (z.B. `FavoritesRepositoryImpl`)
- **`domain/repository/`** — Interfaces (z.B. `FavoritesRepository`)
- **`fakes/`** (im Test-Sourceset) — Test-Doubles

Fast jedes Interface hat sowohl eine Produktionsklasse (`XyzRepositoryImpl`)
als auch einen Fake (`FakeXyzRepository`). Das ist der Angriffspunkt für
Contract-Tests.

> **Namens-Historie:** Vor der Hilt-Migration hießen die Produktions-
> Implementierungen `XyzManager`. Nach dem Rename auf `XyzRepositoryImpl`
> kann ältere Doku oder ältere Commits noch die `Manager`-Schreibweise
> verwenden — gemeint ist immer dasselbe: die konkrete Klasse, die das
> jeweilige `XyzRepository`-Interface implementiert. Ein Rest trägt den
> alten Namen weiterhin bewusst: `WallpaperFileManager` (kein Repo, reines
> File-Helper-Konstrukt). Ein zweiter Rest, `DataMigrationManager`, ist
> mittlerweile gelöscht — siehe Migration-history-Note in CLAUDE.md §5.

---

## Was bereits existiert — Contract-Tests

Das Projekt hat eine vollständige Contract-Test-Suite, die Fake-vs-Impl-Drift
für alle Repository-Interfaces abfängt. Namens-Schema:

- `XyzRepositoryContract.kt` — abstract class mit @Test-Methoden
- `FakeXyzRepositoryContractTest.kt` — läuft die Tests gegen den Fake
- `XyzRepositoryImplContractTest.kt` — läuft dieselben Tests gegen die Impl

### Status je Repository

Alle 16 Repository-Interfaces sind behandelt — 12 mit Contract-Pair,
4 mit ADR-only-Begründung:

| Interface              | Contract | Impl-CT | Kommentar |
|------------------------|:--------:|:-------:|-----------|
| FavoritesRepository    |    ✓     |    ✓    | + ShareIn-Infrastruktur-Test |
| SettingsRepository     |    ✓     |    ✓    | — |
| SwipeActionsRepository |    ✓     |    ✓    | — |
| FavoritesOrderRepository |  ✓     |    ✓    | — |
| HiddenAppsRepository   |    ✓     |    ✓    | — |
| InstalledAppsRepository|    ✓     |    —    | Zwei Fake-Subklassen, keine Impl (PackageManager) |
| AppUsageRepository     |    ✓     |    ✓    | Sort-Algorithmus NICHT im Vertrag (Clock-Problem) |
| WallpaperRepository    |    ✓     |    ✓    | Robolectric (Uri) |
| CustomNamesRepository  |    ✓     |    ✓    | — |
| InstalledAppsStateRepository |  ✓ |    ✓    | — |
| FabPositionRepository  |    ✓     |    ✓    | — |
| BackupRepository       |    ✓     |    —    | Fake-only (Uri + ContentResolver) |
| TimeBasedEventsRepository | ADR only |  —   | System-API (AlarmManager + Calendar); ImplTest deckt Logik via MockK ab |
| ShortcutRepository     | ADR only |    —    | System-API (LauncherApps); ImplTest deckt Logik via MockK ab |
| ResetRepository        | ADR only |    —    | Komposition (orchestriert 12 Purgeable-Repos); ImplTest deckt Koordination ab |
| UsageExportRepository  | ADR only |    —    | Mixed: pure JSON + Uri-File-I/O; 5 Spec-Files decken Format / Time / I/O / Adversarial ab |

Note: "Impl-CT" = Impl-Contract-Test, NOT generic ImplTest. Reguläre
ImplTests existieren für alle ADR-only-Einträge — sie liegen ausserhalb
der Contract-Suite und prüfen die System-API-getriebene Logik direkt.

"ADR only" = der jeweilige `XyzRepositoryContract.kt` enthält ausschließlich
KDoc, der begründet warum es keinen ausführbaren Contract-Test gibt. Kein
ungeschriebener Test, sondern eine dokumentierte Entscheidung. Vier
Begründungen:

- **System-API**: Der Manager ist nicht ehrlich JVM-instanziierbar
  (TimeBasedEvents: AlarmManager+Calendar; Shortcut: LauncherApps).
  Selbe Begründung wie bei BackupRepository, dort aber mit Fake-only-
  Contract als Drift-Schutz für eventuelle zweite Fakes.
- **Komposition**: Surface zu dünn für Contract (Reset: Boolean-Returns
  über Orchestrierung), Logik liegt in der Koordination — ImplTest
  ist die richtige Test-Form.
- **Mixed**: Teils testbar (JSON), teils system-API (Uri/ContentResolver);
  Format-Logik wäre in einem Fake nur Selbst-Re-Implementierung
  (UsageExport).

### Bugs, die die Contracts aufgedeckt haben

Drei echte Divergenzen zwischen Fake und Impl wurden über den Prozess
gefunden und gefixt. Jeweils Fake an die Impl angeglichen (die Impl ist
Produktions-Wahrheit):

1. **`FakeFavoritesRepository.saveFavoriteComponents`** filterte keine
   Blanks, die Impl tut es. Fix: `componentNames.filter { it.isNotBlank() }.toSet()`.
2. **`FakeFavoritesOrderRepository.sortFavoriteComponents`** produzierte bei
   duplikaten Einträgen in `order` Apps mehrfach im Output. Fix:
   `order.distinct().mapNotNull { appMap[it] }`.
3. **`FakeCustomNamesRepository.removeCustomNameForPackage`** war nicht
   idempotent (false für nicht-existente Keys), die Impl ist es (true,
   immer). Fix: Fake entsprechend angleichen.

Alle drei Fixes stehen in den jeweiligen Fake-Dateien mit Kommentar, der
auf die Parallele zur Impl verweist. Wenn jemand das beim Refactoring
wieder rückgängig macht, schlägt der Contract erneut an.

### Ein Impl-Fix, ebenfalls durch Contract aufgedeckt

`FavoritesRepositoryImpl.saveFavoriteComponents` bekam denselben Blank-
Filter — nicht weil die Impl kaputt war, sondern um die Asymmetrie zum
Fake zu schließen und die Semantik projektweit konsistent zu machen.

---

## Was in den Contracts **nicht** geprüft wird — und warum

Bewusst weggelassene Verhaltensweisen sind in den jeweiligen KDocs der
Contracts unter "NICHT IM CONTRACT — bewusste Drifts" dokumentiert. Ein
paar prominente Beispiele:

- **`InstalledAppsStateRepository`**: `purgeRepository` ist bei der Impl
  explizit No-Op (System-Daten), beim Fake ein echter Reset
  (Test-Isolation).
- **`AppUsageRepository`**: Konkrete Sortierreihenfolge von
  `sortAppsByTimeWeightedUsage` — die Impl benutzt
  `System.currentTimeMillis()` direkt ohne Clock-Abstraktion. Contract
  prüft nur Multiset-Properties (gleiche Apps rein wie raus).
- **`WallpaperRepository`**: drei Drifts bei exotischen States
  (scale ohne imageUri, non-file URIs, fehlende Dateien) — alle im
  KDoc dokumentiert, keine Tests dafür weil die Fakes "kaputt" zu
  deklarieren übertrieben wäre für Pfade die in der Praxis keiner geht.

Wenn eine dieser Drifts doch mal relevant wird (weil neuer Produktions-
Code einen der exotischen Pfade betritt), gehört der entsprechende Test
in den Contract. Fake anpassen, dann rot→grün.

---

## Wenn du als Claude-Session in dieses Projekt kommst

### Reihenfolge beim Einlesen

1. **Zuerst** `java/com/github/reygnn/kolibri_launcher/TESTING_CONVENTIONS.kt`.
   Das ist die technische Referenz. Coroutine-Konventionen, MockK-/MockK-
   Naming, Contract-Test-Muster, MutableSharedFlow-Traps, plus seit
   2026-05-01 die Robolectric+Hilt-Activity/Fragment-Test-Sektion. Ist
   verpflichtend bevor du irgendeinen Test anfasst.
2. **Dann** eine existierende Contract-Pair-Gruppe als Beispiel —
   `FavoritesRepositoryContract.kt` + `FakeFavoritesRepositoryContractTest.kt`
   + `FavoritesRepositoryImplContractTest.kt`. Das zeigt das Muster in seiner
   vollen Form, mit ausführlichem KDoc.
3. **Bei Bedarf** `FavoritesRepositoryImplShareInTest.kt` für die `shareIn`-
   Infrastruktur-Test-Mechanik.
4. **Bei Bedarf** `OnboardingActivityRobolectricTest.kt` als Vorlage für
   Activity/Fragment-Tests via Robolectric + Hilt — eingeführt 2026-05-01
   als Pilot, Pattern dokumentiert in `TESTING_CONVENTIONS.kt` →
   „ROBOLECTRIC + HILT — ACTIVITY / FRAGMENT TESTS". Sparsam einsetzen
   (~10× langsamer als JVM-Unit-Tests wegen Robolectric-Boot), aber
   wertvoll als Smoke-Test-Backstop für UI-Refactorings (z. B. den
   try/catch-Sweep aus dem Roadmap-Punkt §2).

### Häufige Aufgaben und wo sie reingehören

**Neues Interface + neue Impl + neuer Fake:**
Baue Contract-Pair. Reihenfolge: abstract Class zuerst, dann die zwei
Subklassen. Siehe TESTING_CONVENTIONS.kt → "CONTRACT TESTS" für das Muster.
Wenn die Impl `shareIn` benutzt, `externalScope = null` im Test.

**Neue Methode in existierendem Interface:**
Methode auch in beide Implementierungen einfügen, dann `@Test`-Methode(n)
zum bestehenden `XyzRepositoryContract` hinzufügen. Beide Subklassen laufen
automatisch mit — keine Änderung nötig.

**Rot gewordener Contract-Test nach einem Refactor:**
Die Standard-Antwort ist Option B: Fake an die Impl angleichen, nicht
umgekehrt. Impl-Verhalten ist Produktions-Wahrheit. Details in
TESTING_CONVENTIONS.kt → "DRIFT-FIX POLICY".

**Test hängt im CI:**
Wenn das Repository `MutableSharedFlow` benutzt, wahrscheinlich Buffer-
Overflow-Trap. Siehe TESTING_CONVENTIONS.kt → "MUTABLESHAREDFLOW IN
CONSTRUCTOR" für die zwei Fixes (DROP_OLDEST oder Turbine).

**Test soll `advanceUntilIdle()` benutzen aber es passiert nichts / zu viel:**
Siehe TESTING_CONVENTIONS.kt, erster großer Block. Die Dispatcher-Regeln
sind streng und die Fehler sind _stumm_ (Test scheitert ohne verwertbare
Fehlermeldung). `testScheduler.runCurrent()` als Alternative wenn
`advanceUntilIdle` zu weit läuft (passiert bei `WhileSubscribed`-Tests).

**Impl braucht Android-System-APIs:**
Dann in den meisten Fällen KEIN Impl-Contract-Test — nur Fake-Subklasse,
plus ehrliches KDoc das erklärt warum. Beispiele im Projekt:
`BackupRepositoryContract`, `InstalledAppsRepositoryContract`,
`TimeBasedEventsRepositoryContract` (letzterer rein ADR-Doku ohne Tests).

### Anti-Patterns, die ich in diesem Projekt schon mal gemacht habe

(Damit du sie nicht wiederholst.)

1. **`advanceUntilIdle()` in einem `WhileSubscribed`-Test.** Läuft durch
   den Timeout-Delay und droppt den Upstream, bevor der Test ihn prüft.
   Fix: `testScheduler.runCurrent()`.

2. **`MutableSharedFlow()` in einem Test-Setup ohne Subscriber.**
   Erste Emission geht ins default-0-Buffer und suspendiert. Fix:
   `BufferOverflow.DROP_OLDEST` oder Turbine.

3. **Existenz-Checks auf `find` statt tatsächliches Ausführen eines
   `grep`/`ls`.** Ich habe einmal gesagt "`ReactiveFakeInstalledAppsRepository`
   ist toter Code" ohne vorher ein zweites Suchwerkzeug zu benutzen.
   War falsch — der Test, der ihn benutzt, war nicht im Zip das ich
   hatte. Lerning: bei "existiert X?"-Fragen immer **explizit nachschauen**
   und bei "ich finde X nicht" den Unsicherheits-Marker setzen, nicht
   direkt zu "es existiert nicht" springen.

4. **Contract-Test mit echten `content://`-URIs schreiben**, ohne zu
   merken dass die Impl non-file-URIs als `WallpaperState.NONE`
   interpretiert. Immer die Read-Pfade in der Impl lesen, bevor man
   Test-Daten konstruiert.

### Was die Contracts NICHT sind

- Sie sind keine Impl-Logik-Tests. Die `BackupRepositoryImpl*Test`-Suite
  (~200KB) existiert separat und prüft die Business-Logik der Impl.
  Contracts prüfen nur Interface-Garantien.
- Sie sind keine Integration-Tests. Sie laufen gegen `FakeDataStore`,
  nicht gegen echtes DataStore-auf-Disk.
- Sie sind keine UI-Tests. Die liegen in `androidTest/`.
- Sie sind kein Ersatz für Impl-spezifische Tests bei nicht-trivialer
  Logik (z.B. das Sort-Verhalten in `AppUsageRepositoryImpl`). Der Contract
  dokumentiert _warum_ er solche Logik nicht prüft, und zeigt so, wo
  zusätzliche Tests nötig sind.

---

## Verhaltens-Disziplin für die Test-Arbeit

Dinge, die in dieser Codebase mehrfach nachweislich funktionieren und
deshalb Standard sein sollten:

1. **Contract-Tests sind das Default-Werkzeug für Drift-Schutz.** Wenn
   ein Interface zwei Implementierungen hat, gehört ein Contract dazu.
   Ausnahmen sind begründet in TESTING_CONVENTIONS.kt.

2. **Bewusste Drifts werden dokumentiert, nicht versteckt.** Wenn Fake
   und Impl absichtlich unterschiedlich sind, gehört das in den
   Klassen-KDoc des Contracts, in den Abschnitt "NICHT IM CONTRACT —
   bewusste Drifts".

3. **Bei Contract-Rot gewinnt die Impl.** Fake angleichen, nicht
   Impl lockern. (Ausnahme: bewusste Drift, dann in KDoc dokumentieren
   und Test _nicht_ schreiben.)

4. **Vor dem Testschreiben: beide Implementierungen lesen.** Am
   liebsten nebeneinander. Subtile Asymmetrien (blank==remove im
   Single-Pfad aber blank==ignore im Batch-Pfad bei CustomNames, z.B.)
   sind sonst nicht sichtbar und führen zu falschen Tests.

5. **Ehrliches KDoc > zu viele Tests.** Wenn ein Test nicht ehrlich
   möglich ist (Mock-Theater, System-APIs), gehört das begründet
   dokumentiert statt mit pseudo-Tests erschlagen. `TimeBasedEvents`
   ist das Extrem davon: ein ganzer File nur für ein ADR, kein einziger
   Test. Das ist legitim.

---

## Historische Notiz

Die Test-Infrastruktur dieses Projekts wurde in zwei großen,
aufeinanderfolgenden Aufräum-Runden auf den heutigen Stand gebracht.
Beide wurden schrittweise durchgeführt, nicht in einem Rutsch, und
beide hatten denselben Rhythmus: **eine Einheit + Verifikation ("alle
Tests grün") + nächste Einheit**. Die Verifikation zwischendrin hat
in beiden Runden nicht-triviale Probleme aufgedeckt, die ohne sie erst
viel später sichtbar geworden wären.

### MockK-Migration

Die Unit-Test-Suite wurde in einer separaten Session vollständig von
Mockito auf MockK migriert (72 Testdateien, ~1134 Tests). Die dabei
gesammelten Fallstricke stehen in `TESTING_CONVENTIONS.kt` →
"MOCKK CONVENTIONS". Darunter: `relaxUnitFun` vs. `relaxed` für suspend-
Funktionen, `emit(Unit)` statt `emit(any())` für `MutableSharedFlow<Unit>`,
`coEvery + answers` statt des nicht-existenten `coAnswers`, und die
`DataStore`-Extension-Function-Falle mit `FakeDataStore` als Lösung.
**Neue Tests schreiben ausschließlich MockK, nie Mockito.**

### Contract-Test-Initiative

Die Contract-Test-Suite (12 echte Contracts + 1 ADR-Doku) wurde in
sieben aufeinanderfolgenden Runden aufgebaut. Das Muster pro Repository
war: **ein Contract + zwei Subklassen + Verify + falls rot: Fake fixen
+ Verify**. Nicht versuchen, alles auf einmal zu machen — jede Runde
hat mindestens eine neue Subtilität offenbart (shareIn-Write-then-Read,
WhileSubscribed-Timeout in Infrastruktur-Tests, SharedFlow-Buffer-
Overflow-Trap, Wallpaper-URI-Validierung, idempotenter Remove).

Wenn neue Repositories dazukommen, ist diese Reihenfolge weiterhin
empfohlen, statt Shortcut zu suchen.
