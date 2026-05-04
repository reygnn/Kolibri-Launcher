# androidTests — Bring-up-Erkenntnisse

Stand: 2026-05-04 (Branch `fix/instrumented-test-design-issues`, NICHT
gemergt). Das Dokument ist als **Lessons-Learned-Anker** gedacht: was
hat funktioniert, was nicht, und vor allem: **was haben die Tests am
Production-Code aufgedeckt, das wir vorher nicht wussten**. Wer immer
diese Suite weiterführt, sollte hier zuerst lesen — die naheliegenden
Annahmen sind mehrfach kollidiert mit der Realität.

---

## 1. Aktueller Test-Status

Aus dem letzten Lauf gegen `Virtual_Pixel_9a` (Android 16, Stock-AOSP-
Image, Pixel-Skin):

| Test | Pass / Fail | Details |
|------|---|---|
| `BackupRoundTripSafTest.loadFromFile_oversizedFile_returnsErrorWithoutOOM` | ✅ Pass | Ground-truth-Test für die `MAX_BACKUP_SIZE_BYTES`-Guard via echtem `openFileDescriptor.statSize`. |
| `BackupRoundTripSafTest.saveAndLoad_throughRealContentResolver_…` | ⚠ Pending | Mit Warmup gefixt (siehe §3.A), aber nicht erneut verifiziert nach Stop-Anweisung. |
| `PackageUpdateReceiverGoAsyncTest.realBroadcast_resolvesHiltEntryPointAndEmitsSignal_…` | ❌ Fail | `am broadcast`-Pfad triggert den Receiver nicht — siehe §3.B. |
| `ShortcutRepositoryRoleGatedTest.getShortcuts_whenWeAreDefaultLauncher_returnsRealShortcuts` | ✅ Pass | LauncherApps-Permission-Gate funktioniert via `cmd role`. |
| `ShortcutRepositoryRoleGatedTest.getShortcuts_whenWeAreNOTDefaultLauncher_returnsEmptyList` | ✅ Pass | SecurityException-catch-Branch (Line 77) wird tatsächlich getroffen. |
| `ShortcutRepositoryRoleGatedTest.getShortcuts_blankPackage_shortcircuitsBeforeRoleCheck` | ✅ Pass | Blank-String-Guard funktioniert. |
| `DefaultLauncherRoleConsistencyTest.afterSettingRole_bothPathsConvergeOnDefault` | ✅ Pass | Set-Direction konvergiert quasi instant. |
| `DefaultLauncherRoleConsistencyTest.afterClearingRole_bothPathsConvergeOnNotDefault` | ❌ Fail | Strukturelles Problem, kein Cache-Lag — siehe §3.C. |
| `OnboardingToHomeSmokeTest.firstRun_selectsFavorites_firesIntentToMainActivity` | ❌ Fail | RecyclerView leer beim Klick — siehe §3.D. |

**Brutto: 6 ✅, 2 ❌, 1 ⚠ unverifiziert.**

Strukturell: nicht aus Infrastrukturproblemen, sondern aus **drei
echten Production-Codeverhalten** (§3.A, §3.C) und einem Test-Design-
Issue (§3.D). Das ist genau die Sorte „etwas, was JVM/Robolectric
strukturell nicht sehen kann", die diese Suite überhaupt rechtfertigt.

---

## 2. Kontext: Drei Branches in dieser Session

Chronologie wichtig fürs Verständnis:

1. **`test/instrumented-androidtest-bring-up`** (gemerged) — die
   ursprünglichen 5 Test-Files aus `newAndroidTests/` ins richtige
   Source-Set gelegt, API-Drift gegenüber den realen Repos repariert
   (`addFavoriteComponent` statt `addFavorite`, `purgeRepository()`
   statt `clearAllFavorites()`, etc.). Resultat: Tests kompilieren,
   crashen aber alle.

2. **`fix/instrumented-tests-pm-clear-kills-self`** (gemerged) — die
   `ClearAppDataRule.starting()` rief `pm clear <ourPkg>` von INNERHALB
   des Test-Prozesses auf. Da AndroidJUnitRunner im Target-App-Prozess
   läuft, war das ein SIGKILL gegen sich selbst. Symptom: 10/10 Tests
   „Test instrumentation process crashed", leerer Stack. Behebung:
   `clearPackageData=true` als orchestrator-Argument (orchestrator
   räumt zwischen Tests auf, wenn keine Instrumentation läuft) — Rule
   und alle Verwender entfernt.

3. **`fix/instrumented-test-design-issues`** (offen, dieses Dokument
   beschreibt den Stand) — Reparatur der echten Test-Design-Issues
   nach dem orchestrator-Fix. Vier Reparaturen geplant, drei davon
   beim ersten Anlauf nicht ganz fertig.

---

## 3. Detaillierte Befunde pro Test

### A. `BackupRoundTripSafTest.saveAndLoad_…`

**Symptom (Iteration 1):** Round-Trip rot mit `expected: [com.example.alpha,
com.example.beta] but was: []`.

**Diagnose:** Der ursprüngliche Test seedete synthetische ComponentName-
Strings wie `"com.example.alpha"` (Plain-Package, ohne `/Activity`-
Suffix). Das geht durch `FavoritesRepository.addFavoriteComponent` ohne
Beschwerde, wird auch ins Backup geschrieben, aber beim Restore filtert
`BackupDataAssembler.kt:189` jeden Component-String gegen die
`installedComponentsSet`-Menge — und synthetische Strings sind nicht
installiert.

**Erste Reparatur:** Echte Launcher-Components zur Laufzeit aus
`packageManager.queryIntentActivities(LAUNCHER)` ziehen, im Format
`pkg/Activity`. Damit sollten sie die Filter passieren.

**Symptom (Iteration 2):** Trotzdem rot — diesmal mit `missing (2):
com.android.camera2/com.android.camera.CameraLauncher,
com.android.chrome/…`. Die Components SIND installiert, der Test sieht
sie selbst, aber der Backup-Restore wirft sie weg.

**Diagnose 2:** `BackupDataAssembler.performImport` (Line 175):
```kotlin
val installedApps = installedAppsRepository.getInstalledApps().first()
```
`getInstalledApps()` returnt einen `StateFlow` mit
`SharingStarted.WhileSubscribed(5000)` und `initialValue = emptyList()`.
**Ohne primed Subscriber sieht `.first()` sofort den initialValue (leer)
und unsubscribt wieder, bevor die Source-Funktion überhaupt anlaufen
kann.** Production sieht das nicht, weil HomeViewModel & Co. den
StateFlow permanent subscribed halten. Im Test ist das Backup
strukturell der erste Konsument — und sieht dadurch eine leere Liste,
filtert restlos alles weg.

**Reparatur 2 (im Test):** Im `@Before` einen Warmup einbauen, der
`installedApps.getInstalledApps().filter { it.isNotEmpty() }.first()`
mit Timeout aufruft. Damit ist beim Start des eigentlichen Tests
sichergestellt, dass der StateFlow einen echten Wert hat.

**Production-Implikation (TODO.md §15, ggf. §18):** Das ist ein echtes
Robustness-Issue. Wenn ein User direkt nach App-Install einen Restore
versucht, BEVOR HomeViewModel & Co. den StateFlow primen — kann die
gleiche Race auftreten. Wahrscheinlich theoretisch, aber kostenlos zu
fixen via `.first { it.isNotEmpty() }` im Backup-Pfad. Sollte in §15
unter „WhileSubscribed-unprimed-restore" subsumiert oder als §18
ausgegliedert werden.

**Status:** Reparatur committed im Working Tree, nach Stop-Anweisung
nicht erneut gegen die Maschine verifiziert.

---

### B. `PackageUpdateReceiverGoAsyncTest`

**Initial-Annahme** (von mir, falsch): `setPackage(ourPkg)` umgeht den
AMS-Schutz für Protected Broadcasts wie `PACKAGE_ADDED`.

**Realität:** AMS prüft beim `broadcastIntent()` die SENDER-Permission
unabhängig davon, ob `setPackage()` gesetzt ist. `setPackage` schränkt
nur die EMPFÄNGER ein. Konsequenz: jeder Broadcast aus unserem
Test-Prozess (UID 10232) für eine Protected Action → SecurityException,
unabhängig von Receiver-Targeting.

**Zweite Annahme** (User): `am broadcast` via `UiAutomation`-Shell hat
die Permission, weil Shell privilegiert ist. Mit `-p ${context.packageName}`
sollte der Broadcast scoped an unsere registrierte Receiver-Instanz
gehen.

**Realität (Iteration mit Shell-Broadcast):** Test failt mit
`TurbineAssertionError: No value produced in 5s`. D.h. der Shell-
Broadcast ging vermutlich durch (kein SecurityException), aber unser
Receiver hat das Signal nicht bekommen — oder der Receiver wurde nie
getroffen, oder die Hilt-EntryPoint-Resolution failed silent, oder
der Coroutine-Path failed silent.

**Was wir bisher noch NICHT diagnostiziert haben:**
- Hat `am broadcast -p` den Receiver tatsächlich erreicht?
  (Logcat-Inspektion erforderlich — vermutlich ein „Sending broadcast"-
  Eintrag aus AMS, aber kein „Receiver triggered"-Eintrag aus
  PackageUpdateReceiver.)
- Falls erreicht: failt `EntryPointAccessors.fromApplication(...,
  InstalledAppsRepositoryEntryPoint::class.java)` unter HiltTestApplication?
  Per Theorie sollte es nicht — HiltTestApplication ist `@HiltAndroidApp`
  und der EntryPoint ist `@InstallIn(SingletonComponent::class)`. Aber
  wir haben es nicht verifiziert.
- Falls EntryPoint resolvable: führt `appUpdateSignal.sendUpdateSignal()`
  zur Emission auf einem Flow, der von Turbine im Test-Thread observed
  wird — alle drei sind der gleiche Singleton, da gleicher Hilt-Graph,
  also kein Subscriber-Issue.

**Wahrscheinlichste Hypothese**, ungetestet: `am broadcast` mit `-p`
adressiert nur Manifest-deklarierte Receiver, NICHT dynamische — der
Code-Pfad für Manifest-Receivers (Component-Resolution) und der für
Dynamic-Receivers (IntentFilter-Match) sind im AMS getrennt. `-p`
scoped die erste, nicht die zweite.

**Drei Optionen für die nächste Iteration:**

1. **`am broadcast` ohne `-p`:** unscoped, hits all subscribers. Riskant,
   weil andere System-Receiver auf PACKAGE_ADDED reagieren könnten und
   den Test-Run langsamer / instabiler machen.
2. **Direkt `receiver.onReceive(context, intent)`:** umgeht das ganze
   Broadcast-Pipeline, exerciziert aber Hilt-EntryPoint + goAsync-Pfad
   (returnt null, was vom Code unterstützt wird) + signal flow in einem
   echten Android-Prozess. Verlust gegenüber Broadcast: real broadcast
   delivery thread (BroadcastReceiver runs on receiving process's main
   thread) wird nicht exerciziert, aber das ist OS-Verhalten, nicht
   App-Code.
3. **`PackageUpdateReceiver` via Manifest deklarieren — nur in androidTest.**
   Dann funktioniert `-p` und `-n`. Aber: das verändert das System
   in einer Weise, die Production nicht widerspiegelt. Vermutlich
   nicht der Wert, weil wir dann den Kern-Insight (dynamische
   Registrierung in Production) eh nicht testen.

**Empfehlung für die nächste Session:** Option 2 (direkter
`onReceive`-Call). Das KDoc des Tests bereits dokumentiert die
Coverage-Lücke (HiltTestApplication ersetzt KolibriLauncherApp ⇒
registerPackageUpdateReceiver wird nie aufgerufen ⇒ Tests nur den
Receiver-Code, nicht die Registrierungs-Site). Mit Option 2 wird
zusätzlich „real broadcast delivery thread" zur Coverage-Lücke. Beides
sollte im KDoc landen.

**Status:** Im Working Tree steckt die Shell-Broadcast-Variante. Sie
muss durch Option 2 ersetzt werden, oder mit weiterer Logcat-
Inspektion nachverfolgt werden.

---

### C. `DefaultLauncherRoleConsistencyTest.afterClearingRole_…`

**Initial-Annahme** (von mir, falsch in der Direction): Der UID-lokale
RoleManager-Cache lagged nach `cmd role remove-role-holder`, während
PackageManager sofort aktualisiert. Test sollte einen Polling-Wait
machen, um den Cache-Lag zu messen.

**Realität (Iteration 1, Budget 5s):** `awaitUntil` timed out mit
`viaRoleManager=false, viaPackageManager=true`. Genau **umgekehrt** zu
meiner Annahme. RoleManager ist sofort korrekt; PackageManager.resolve­
Activity(CATEGORY_HOME) returnt nach 5+ Sekunden immer noch UNS als
Default.

**Realität (Iteration 2, Budget 15s):** Gleiche Auflösung. Auch nach
15s konvergieren die Pfade nicht. Das ist KEIN Cache-Lag, sondern
ein STRUKTURELLES Verhalten:

`PackageManager.resolveActivity(ACTION_MAIN + CATEGORY_HOME)` returnt
nicht „der aktuelle Role-Holder", sondern „die best-match-Activity für
diesen Intent". Wenn nach `remove-role-holder` KEIN neuer Role-Holder
gesetzt ist, hat das System keinen explizit gewählten Default — und
`resolveActivity` returnt eine der candidate-Activities (wir, weil
unsere `MainActivity` als CATEGORY_HOME deklariert ist und als letztes
gesetzt war).

**Implikation für den Test:** Der Test simuliert einen Production-
Zustand, der nie auftritt. In Production setzt der User einen ANDEREN
Launcher als Default — er bleibt nicht in „kein Default"-Limbo. Die
realistische Test-Sequenz ist: **set self → set OTHER → assert both
report OTHER as default**, nicht **set self → clear → assert both
report nothing**.

**Implikation für den Production-Code (TODO.md §17):** Trotzdem ein
echter Befund. `ShortcutRepositoryImpl.isDefaultLauncher()` benutzt
exakt `resolveActivity(CATEGORY_HOME).activityInfo.packageName == ourPkg`.
In dem `cmd role remove`-without-replacement-Zustand würde das `true`
zurückgeben — wir sind nicht der Role-Holder, aber `isDefaultLauncher()`
sagt yes, und das LauncherApps-Permission-Gate verlässt sich darauf.
Konsequenz: **Wenn das System in den „kein HOME-Holder"-Zustand
geriete (System-State-Bug, Knox-Setup-Pause, etc.), würde unser
Shortcut-Lookup fälschlich angefragt und SecurityException-fangen**.
Bestehender Code-Pfad fängt das ohnehin ab; Funktionalität bleibt
korrekt; aber der Status-Indicator in `SettingsFragment` würde
fälschlich „du bist Default" zeigen.

Schlussfolgerung: TODO §17 sollte nicht als „Cache-Lag", sondern als
„`resolveActivity(CATEGORY_HOME)`-Pfad ist nicht mit dem `RoleManager`-
Pfad äquivalent" formuliert sein. Der eigentliche Fix wäre, in
`SettingsFragment.updateDefaultLauncherStatus()` ausschließlich
RoleManager.isRoleHeld zu verwenden und den `resolveActivity`-Pfad
abzuschaffen — oder beide gegeneinander zu validieren.

**Reparatur-Plan für die nächste Session:**

```kotlin
// statt clear:
DefaultHomeRoleHelper.setRoleHolderTo(otherLauncherPkg)
// dann assert both report otherLauncherPkg / not-us
```

Braucht eine neue Helper-Methode. `findAnotherInstalledLauncher()` muss
die HOME-fähigen Apps query'en (`queryIntentActivities(HOME)`) und
einen anderen als uns picken (typischerweise `nexuslauncher` auf Pixel-
Emulatoren).

**Status:** Im Working Tree steckt die 15s-Budget-Variante mit `Log.i`
des gemessenen Lag. Der Test failt deterministisch, weil das Setup
einen Production-State simuliert, der so nie auftritt.

---

### D. `OnboardingToHomeSmokeTest.firstRun_…`

**Symptom (Iteration 1):** `PerformException: Animations or transitions
are enabled on the target device`. Klassiker — Espresso braucht
`window_animation_scale = 0`, sonst werfen RecyclerViewActions.

**Reparatur 1:** `HiltTestRunner.onStart` setzt die drei Animation-
Settings auf 0.0 via Shell. Klappt — der Fehler ist weg.

**Symptom (Iteration 2):** Anderer `PerformException`: `Caused by:
java.lang.IllegalStateException: No view holder at position: 0` —
also der RecyclerView ist beim Klick noch leer.

**Diagnose:** `OnboardingViewModel.loadInitialData()` lädt die App-
Liste asynchron via `installedAppsRepository.getInstalledApps()`
(genau der gleiche `WhileSubscribed(5000)`-StateFlow wie in §3.A).
Wenn der Test sofort auf Position 0 klickt, ist der Adapter noch
nicht populated. Espresso wartet auf View-Idle (Layout, Animation,
AsyncTask-Idle), aber **NICHT auf StateFlow-Emission** — das ist
außerhalb seines Idle-Concept.

**Reparatur-Plan für die nächste Session:** Espresso `IdlingResource`
einsetzen, der den InstalledAppsRepository-StateFlow observiert und
„idle" meldet, sobald `isNotEmpty()`. Alternativ: ein direkter Wait-
Loop im Test (`onView(...).check(matches(... mit hasMinimumChildCount(3)))`),
mit `withTimeout` retried bis populated. Espresso bietet dafür kein
fertiges Pattern out-of-the-box, aber AndroidX hat `RecyclerViewIdling`
in `espresso-contrib`.

Pragmatic-Variante: Vor dem Klick einen `Thread.sleep(1500)` oder
einen polling-Loop. Ersteres widerspricht den Notes („Anti-Patterns");
letzteres ist akzeptabel (vergleiche `awaitUntil`-Pattern aus §3.C).

**Status:** Im Working Tree ist nur der Animation-Fix; das
RecyclerView-empty-on-click-Issue ist unbearbeitet.

---

## 4. Architektur-Findings, die diese Tests aufgedeckt haben

Drei davon sind in `TODO.md` als §15-17 erfasst, das vierte (§3.A
Production-Variante) noch nicht.

### Finding 1: `FavoritesRepository.addFavoriteComponent` validiert nichts

→ `TODO.md §15`. Akzeptiert beliebige Strings, auch wenn sie nicht
dem ComponentName-Format entsprechen. Ein `silentError` bei
`ComponentName.unflattenFromString(s) == null` würde das in DEBUG
loud machen (Rule 9).

### Finding 2: `AppUpdateSignal.events` ohne Replay = Subscriber-Race-Trap

→ `TODO.md §16`. `replay = 1` würde alle Subscriber-vor-Trigger-
Patterns trivialisieren — kein Turbine, kein TestDispatcher-Trick,
kein `delay`-Schätzen. Production-Effekt eines verlorenen Update-
Signals ist sowieso nur „App-Liste refreshed eine Sekunde später".

### Finding 3: `PackageManager.resolveActivity(CATEGORY_HOME)` vs. `RoleManager.isRoleHeld`

→ `TODO.md §17` (Formulierung sollte angepasst werden — siehe §3.C).
Die zwei Pfade sind **strukturell nicht äquivalent**, nicht nur durch
Cache-Lag verschoben. `resolveActivity` macht best-match-Auflösung
auch ohne expliziten Role-Holder; das ist nicht das, was
„isDefaultLauncher" semantisch bedeutet.

### Finding 4 (NEU, sollte ggf. eigenes TODO-Item): `BackupDataAssembler.performImport`s `WhileSubscribed`-Trap

→ Noch nicht in TODO.md. `installedAppsRepository.getInstalledApps().first()`
auf einem WhileSubscribed-StateFlow ohne primed Subscriber returnt
sofort den initialValue (leere Liste), unsubscribed, und der Restore
filtert dann jede Component aus dem Backup als „nicht installiert"
weg. Production unwahrscheinlich (HomeViewModel hält den StateFlow
permanent subscribed), aber ein Direct-Restore-after-Install könnte
die Race treffen. Fix: `.first { it.isNotEmpty() }` mit Timeout.

---

## 5. Was beim ersten Mal nicht so gemacht wurde, wie ich angenommen hatte

Diese Liste ist explizit eine **„meine Annahmen vs. Realität"-Liste**.
Sie ist hier, weil der User in dieser Session zweimal proaktiv meine
voreiligen Annahmen aufgefangen hat und einmal selbst geirrt war —
genau so eine Liste hilft die nächste Session, die gleichen Stolpersteine
nicht erneut zu treten.

| Vermutung | Realität | Ort der Korrektur |
|---|---|---|
| `ClearAppDataRule.starting()` ist sicher, weil Test-Runner und Target-Process getrennt sind | Sind sie nicht. Beide laufen im gleichen UID/PID. `pm clear` ist Selbst-SIGKILL. | Branch-2-Fix, INSTRUMENTED_TESTING_NOTES Punkt 4 |
| `addFavorite("com.example.alpha")` validiert das Format | Tut es nicht. Akzeptiert silent jeden String. | TODO §15 |
| `setPackage(ourPkg)` umgeht protected-broadcast-Check | Tut es nicht. AMS prüft Sender-Permission, setPackage scoped nur Empfänger. | §3.B |
| `am broadcast -p ourPkg` triggert dynamic Receivers | Vermutlich nicht — siehe §3.B. Manifest-Receivers ja, dynamic muss nochmal verifiziert werden. | §3.B |
| `PackageManager.resolveActivity(HOME)` und `RoleManager.isRoleHeld(HOME)` sind äquivalent | Sind sie nicht. resolveActivity macht best-match-Resolution auch ohne Role-Holder. | §3.C |
| `WhileSubscribed`-StateFlow + `.first()` returnt einen echten Wert | Returnt initialValue, wenn vorher kein Subscriber. | §3.A, Finding 4 |
| Espresso wartet auf RecyclerView-populated | Wartet auf View-Idle, nicht auf StateFlow-emission. | §3.D |
| RoleManager-UID-Cache ist die Lag-Quelle nach `cmd role remove` | Ist es nicht. PackageManager-Pfad ist der lagger. Falsche Direction. | §3.C |

Acht Stück. Der Wert dieser Suite ist deutlich höher als das simple
Test-Coverage-Argument: jede einzelne Zeile dieser Tabelle ist eine
Architektur-Erkenntnis, die nur mit echtem Hardware-Testing zu finden
war. Die nächste Session sollte sich nicht überrascht zeigen, wenn
sie auf weitere Items dieser Sorte stößt.

---

## 6. Was im Working Tree liegt (NICHT gemerged)

Branch: `fix/instrumented-test-design-issues` (lokal, noch nicht gepusht).

```
Modifiziert:
  TODO.md                              (3 neue §15/16/17, plus Status-Tabelle)
  app/build.gradle.kts                 (androidTestImplementation Turbine)
  app/src/androidTest/java/.../HiltTestRunner.kt
                                       (Animation-Disable in onStart)
  app/src/androidTest/java/.../INSTRUMENTED_TESTING_NOTES.kt
                                       (3 neue Punkte: HiltTestApp-Override,
                                        Receiver-Test-Lifecycle, Cache-Polling)
  app/src/androidTest/java/.../data/BackupRoundTripSafTest.kt
                                       (real ComponentNames + Warmup)
  app/src/androidTest/java/.../data/PackageUpdateReceiverGoAsyncTest.kt
                                       (eigener Receiver, Turbine, Shell-broadcast,
                                        KDoc-Disclaimer; FAILT noch)
  app/src/androidTest/java/.../ui/DefaultLauncherRoleConsistencyTest.kt
                                       (split + awaitUntil + Lag-Logging;
                                        clear-direction FAILT noch strukturell)
  app/src/androidTest/java/.../ui/OnboardingToHomeSmokeTest.kt
                                       (recreate weg + KDoc updaten;
                                        FAILT wegen RecyclerView-empty-on-click)

Neu:
  app/src/androidTest/java/.../support/AwaitUntil.kt
                                       (Polling-Helper mit informativer Failure-Message)

NICHT geändert, weil Stop-Anweisung kam:
  - Test #2 sollte auf direkten onReceive-Call umgestellt werden (siehe §3.B
    Empfehlung Option 2)
  - Test #4 sollte set→OTHER-launcher statt set→clear simulieren (§3.C)
  - Test #5 sollte einen IdlingResource oder Wait-Loop für RecyclerView haben (§3.D)
  - TODO §17 sollte mit dem konkreten Befund aus §3.C umformuliert werden
  - TODO §15 oder neues §18: BackupDataAssembler-WhileSubscribed-Race
```

Branch ist nicht gepusht und nicht gemerged. Bei `git stash`-artiger
Pause der Arbeit: nichts geht verloren.

---

## 7. Empfohlene Reihenfolge für die nächste Session

1. **Lies §3.A bis §3.D in Ruhe.** Die Diagnosen sind dort drin, mit
   ggf. zwei Iterationsstufen. Speziell §3.B und §3.C haben jeweils
   eine Hauptannahme + die Realitätskorrektur — wenn man die nicht
   kennt, läuft man die gleichen Wand-Fehler nochmal.

2. **Fix Test #2 mit Option 2 (direkter onReceive-Call).** Update
   das KDoc um die zwei Coverage-Lücken (HiltTestApplication-Override
   + real broadcast delivery thread). Klein, sollte in 15 Minuten
   durch sein.

3. **Fix Test #4 mit set→OTHER-launcher.** Helper-Methode
   `DefaultHomeRoleHelper.setRoleHolderTo(pkg)` braucht einen
   `cmd role add-role-holder $USER $ROLE $pkg` Aufruf, vorher den
   eigenen clearen, dann den anderen setzen. `findAnotherInstalledLauncher()`
   query't `queryIntentActivities(HOME)` und filtert uns raus.
   Update TODO §17 mit der neuen Formulierung.

4. **Fix Test #5 mit Wait-Loop oder IdlingResource.** Pragmatic:
   `awaitUntil { onView(withId(...)).check(matches(hasMinimumChildCount(3))) }`
   — die `awaitUntil`-Helper ist da, der View-Check muss in einen
   Boolean übersetzt werden (entweder `try{check();true}catch{false}`
   oder über `ViewMatchers.hasMinimumChildCount` + ViewActions-Pattern).

5. **TODO §17 umformulieren.** Aus „Cache-Lag" wird
   „resolveActivity-CATEGORY_HOME ist semantisch nicht äquivalent
   zu RoleManager.isRoleHeld". Plus die konkrete Empfehlung:
   `SettingsFragment.updateDefaultLauncherStatus()` sollte nur
   RoleManager nutzen.

6. **Optional: TODO §15 erweitern oder §18 anlegen** für die
   `BackupDataAssembler`-`WhileSubscribed`-Trap (Finding 4).

7. **Tests laufen, alles grün?** Dann: Commit, push, ff-merge nach
   `main`, branch löschen mit User-Confirmation. Branch ist
   `fix/instrumented-test-design-issues`.

---

## 8. Methodisches: was bei diesem Bring-up funktioniert hat

Das Iterations-Pattern, das wir in dieser Session etabliert haben,
sollte für künftige instrumented-Test-Arbeit der Default sein:

1. **Tests laufen, Failures sammeln.**
2. **Pro Failure: 3-Zeilen-Reduktion** → {Test-Name, Hauptzeile mit
   expected/was, erste relevante Stack-Frame}. Mehr nicht, sonst
   verschwindet das Signal in Log-Lärm.
3. **Pro Failure: einzeilige Klassifizierung** in
   {Reparieren, Reparieren-mit-Architektur-Hinweis, Löschen,
   @Ignore-mit-TODO}. Selten ist die richtige Antwort „länger nachdenken".
4. **Bei „Reparieren-mit-Architektur-Hinweis": Production-TODO-Item
   dazu**, im KDoc des Tests nur ein Verweis — sonst rotten die
   Hinweise mit der nächsten Code-Änderung.
5. **Bei jedem Iterationsschritt:** quellbasiert prüfen, nicht
   raten. Dieser Bring-up hat 8 mal eine vermeintliche Hypothese
   gegen den Code testen müssen, und 8 mal hat die Hypothese sich
   geändert. „Wahrscheinlich ist es X" ist nie ein Substitut für
   „lass uns das gegen den Code prüfen".

Das ist die TESTING_CONVENTIONS-Methodik adaptiert auf Hardware-Tests:
**stack-driven, klassifikations-getrieben, Production-Findings als
First-Class-Output**.

---

## 9. Konkrete Build-/Setup-Insights

Für jemanden, der diese Suite zum ersten Mal lokal laufen lässt:

- Emulator-Bereitstellung: `~/Android/Sdk/emulator/emulator -avd <name>
  -no-snapshot-save -no-boot-anim` im Hintergrund, dann
  `adb wait-for-device && adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 2; done; echo BOOTED'`.
  Boot dauert ~30-60s je nach Maschine.

- Wenn `INSTALL_FAILED_UPDATE_INCOMPATIBLE` beim ersten gradle-
  Run: einmal manuell `adb install -r app/build/outputs/apk/debug/app-debug.apk`,
  dann gradle nochmal. Die Signatur-Abweichung kommt von Vorinstallationen
  mit anderem Debug-Keystore und überlebt manchmal `adb uninstall`.

- `connectedDebugAndroidTest` braucht ~40s nach gewärmtem Build, und
  ~15s davon sind Emulator-Setup. Pro Iteration also rechnen mit 1
  Minute zwischen Code-Änderung und Test-Resultat. Iterationen
  sparsam.

- Test-Reports liegen in `app/build/outputs/androidTest-results/connected/debug/<DeviceName>/`.
  Spezielle Files:
  - `test-result.textproto` — strukturiert, alle Failures inline mit
    Stack-Trace (kein Logcat-Drilldown nötig). Erste Anlaufstelle.
  - `logcat-<TestClass>-<method>.txt` — vollständiger Logcat während
    des Tests, hilft bei „silent crash"-Symptomen (siehe Branch 2).
  - `testlog/test-results.log` — gradle's eigene FAIL-Liste.

- HTML-Report unter `app/build/reports/androidTests/connected/debug/index.html`
  ist nur für die Übersicht; alles Inhaltliche ist in `test-result.textproto`.

---

*Diese Datei ist als Snapshot gemeint, nicht als living document.
Wenn die nächste Session die offenen Punkte abschließt, ersetzt sie
diese Datei durch eine Lessons-Learned-Sektion in `INSTRUMENTED_TESTING_NOTES.kt`
oder dem `app/src/test/CLAUDE.md` (Memory-Schicht), und dieses File
wird gelöscht.*
