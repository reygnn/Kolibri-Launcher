package com.github.reygnn.kolibri_launcher.data

/**
 * ============================================================================
 * TIME BASED EVENTS REPOSITORY — KEIN CONTRACT TEST
 * ============================================================================
 *
 * Diese Datei enthält absichtlich KEINEN Code. Sie dokumentiert eine bewusste
 * Lücke in der Contract-Test-Suite.
 *
 * STATUS:
 *   12 von 14 Repository-Fakes haben einen Contract-Test (siehe `*Contract.kt`
 *   in diesem Package). [com.github.reygnn.kolibri_launcher.domain.repository.TimeBasedEventsRepository]
 *   gehört NICHT dazu — und das ist eine bewusste Entscheidung, kein Versehen.
 *
 * DAS INTERFACE:
 *   ```
 *   interface TimeBasedEventsRepository : Purgeable {
 *       suspend fun getUpcomingTimeBasedEvents(maxCount: Int = 5): List<TimeBasedEvent>
 *   }
 *   ```
 *   Eine einzige (suspend-)Methode. Klein.
 *
 * WARUM TROTZDEM KEIN CONTRACT?
 *
 * 1. **Kein ehrlicher Manager-Test möglich.**
 *    `TimeBasedEventsRepositoryImpl` braucht im Konstruktor:
 *      - `Context`
 *      - `SettingsRepository`
 *      - `AlarmManager` (System-Service)
 *      - `ContentResolver` (für Calendar-Provider-Queries)
 *    Plus interne Calls auf `PackageManager.checkPermission()`,
 *    Calendar-URI-Queries, Alarm-Snapshots. Im Unit-Test-Setup ist das
 *    nicht ehrlich instanziierbar — nur als komplettes Mock-Theater. Wir
 *    würden dann Vertragstreue zwischen "Fake" und "Mock-Konstrukt" prüfen,
 *    nicht zwischen "Fake" und echtem Manager. Den selben Punkt haben wir
 *    bei `BackupRepositoryContract` und `InstalledAppsRepositoryContract`
 *    bereits ausführlich diskutiert.
 *
 * 2. **Fake-only-Contract wäre trivial bis tautologisch.**
 *    Der Fake `FakeTimeBasedEventsRepository` macht im Wesentlichen
 *    `events.sortedBy { it.triggerTimeMillis }.take(maxCount)`. Ein Contract,
 *    der das gegen sich selbst prüft, beweist nichts — er prüft die
 *    Implementierung gegen ihre eigene Implementierung. Das ist anders bei
 *    `BackupRepositoryContract`: dort schützt der dünne Fake-only-Contract
 *    immerhin vor Drift wenn jemand später einen ZWEITEN Fake schreibt.
 *    Hier gibt es keine zweite Implementierung in Sicht.
 *
 * 3. **Die echte Logik liegt im Manager — und die ist abgedeckt.**
 *    `TimeBasedEventsRepositoryImpl` macht die nicht-trivialen Sachen: Calendar-
 *    Permission-Check, Alarm-Snapshot vs. Calendar-Merge, chronologisches
 *    Sortieren, Cap auf maxCount. Diese Logik wird im
 *    `TimeBasedEventsRepositoryImplTest` gegen Mocks getestet — das ist die
 *    angemessene Test-Form für system-API-getriebenen Code.
 *
 * 4. **Der Fake hat keinen Konsumenten, der vom Vertrag profitieren würde.**
 *    `FakeTimeBasedEventsRepository` wird benutzt von ViewModel-Tests, die
 *    einfach eine Liste TimeBasedEvents zurückgegeben bekommen wollen. Sie
 *    rufen `setEvents(...)` und prüfen das Resultat in der UI. Wenn der
 *    Fake je vom Manager driftete, würden diese Tests trotzdem das tun
 *    wofür sie da sind: das ViewModel-Verhalten gegen eine bekannte
 *    Eingabe prüfen. Es gibt keinen UI-Pfad der davon abhängt, dass Fake
 *    und Manager byte-genau dieselbe Sortier-Strategie nutzen.
 *
 * WANN SOLLTE DIESE ENTSCHEIDUNG REVIDIERT WERDEN?
 *   - Wenn ein zweiter Fake für `TimeBasedEventsRepository` dazu kommt
 *     (z.B. ein `TestTimeBasedEventsRepository` für Instrumented Tests).
 *     Dann wird der Fake-only-Contract sinnvoll, um beide Fakes
 *     austauschbar zu halten — Pattern wie bei
 *     `InstalledAppsRepositoryContract` (`Fake*` vs `ReactiveFake*`).
 *   - Wenn das Interface erweitert wird (mehr Methoden, mehr Properties),
 *     besonders um State der von mehreren Konsumenten beobachtet wird.
 *
 * SIEHE AUCH:
 *   - `BackupRepositoryContract` für ein vergleichbares Beispiel (dünner
 *     Fake-only-Contract, kein Manager-Test).
 *   - `InstalledAppsRepositoryContract` für das Doppel-Fake-Muster.
 *   - `TimeBasedEventsRepositoryImplTest` für die echten Logik-Tests via MockK.
 * ============================================================================
 */
@Suppress("unused")
private val ARCHITECTURAL_DECISION_RECORD_ONLY = Unit
