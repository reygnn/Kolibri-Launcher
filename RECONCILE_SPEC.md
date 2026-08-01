# RECONCILE_SPEC.md

**Implementierungs-Spec für den App-Listen-Reconcile-Rewrite — grüne Wiese, noch kein Code.**

Dieses Dokument ist der Plan für einen gezielten Rewrite des Reconcile-Pfads
(Aufräumen stale Favorites/Swipe/Hidden/Custom-Names, wenn eine App
deinstalliert wird). Es folgt dem Muster von `ACRA_SPEC.md`: erst der Ist-Zustand
und der belegte Defekt, dann die Zielinvariante, dann die baubaren Deltas
(Signaturen, Event-Payload, DI, Test-Inventar). Wo `ACRA_SPEC.md` auf ein
separates `ACRA_FLOW.md` aufsetzt, ist diese Spec selbsttragend — Reconcile hat
kein eigenes Architektur-Doc.

**Status:** Entwurf komplett. Zwei bewusst benannte Gabelungen (`SPEC-DECISION
R-1`, `R-2`) sind vor Umsetzung zu entscheiden; beide sind am Doc-Ende
gesammelt. Kein Blocker für den Start.

**Verhältnis zu bereits Gelandetem.**
- Der `EXTRA_REPLACING`-Guard (Commit `4e719ff8`, „`fix(sync)`") ist **bereits
  auf `main`** und entfernt die Update-Zeit-Churn. Er ist Vorarbeit, nicht Teil
  dieses Rewrites — der Rewrite darf ihn voraussetzen.
- Der optionale Interim-Sanity-Guard (#3) ist **nicht** umgesetzt; falls er vor
  dem Rewrite gewünscht wird, ist er in §5 als Teilmenge dieser Spec beschrieben
  und kann isoliert vorgezogen werden.

---

## §0 Ist-Zustand & belegter Defekt

### §0.1 Heutiger Datenfluss

```
PACKAGE_ADDED / PACKAGE_REMOVED (Broadcast)
  → PackageUpdateReceiver.onReceive               (data/PackageUpdateReceiver.kt)
  → AppUpdateSignal.sendUpdateSignal()            (core/AppUpdateSignal.kt, MutableSharedFlow<Unit>)
  → AppManagementDelegate.listenForAppUpdates     (ui/main/delegate/AppManagementDelegate.kt)
  → RefreshAppsUseCase → triggerAppsUpdate()      (domain/usecase/, data/InstalledAppsRepositoryImpl.kt)
  → appsUpdateTrigger.emit → flatMapLatest → queryIntentActivities(IO)
  → List<AppInfo>                                 (InstalledAppsRepositoryImpl.processResolveInfoList)
  → ObserveInstalledAppsUseCase.collect:
        · runCleanup ×4 (favorites/swipe/hidden/custom-names)   ← RECONCILE
        · installedAppsStateRepository.updateApps(list)
        · emit(AppLoadResult.Success)
```

Der Signal-Bus trägt **keine Nutzlast** (`Unit`). Der Receiver weiß, *welches*
Paket sich änderte (`intent.data.schemeSpecificPart`), wirft diese Information
aber weg. Der Reconcile lernt nie, was sich änderte — er vergleicht bei **jeder**
Ladung *alle* Zuweisungen gegen die *gesamte* frisch geladene Liste.

### §0.2 Der Defekt (belegt)

Der Reconcile behandelt jede nicht-leere Ladung als die **vollständige,
autoritative** Liste installierter Apps und löscht Zuweisungen für alles
Abwesende — **permanent und nicht-restaurierend**.

| Beleg | Datei:Zeile | Fakt |
|---|---|---|
| Einziger Guard | `ObserveInstalledAppsUseCase.kt:85` | nur `realApps.isEmpty()` — Alles-oder-Nichts |
| Löschsemantik | `FavoritesRepositoryImpl.kt:240` | `currentFavorites.intersect(installedSet)` — nur entfernen, nie wiederherstellen (Swipe/Hidden/Custom-Names gleiche Form) |
| Partiell-Liste möglich | `InstalledAppsRepositoryImpl.kt:178–224` | Per-Item-Fehler (`activityInfo == null` / unerwarteter Throw) lässt genau *diese* App still weg (`continue`), Rest bleibt → nicht-leere, aber unvollständige Liste |

**Kette:** Eine nicht-leere-aber-unvollständige Ladung erreicht den Reconcile →
die fehlende App ist nicht im Valid-Set → ihre Favorite/Swipe/Hidden/Custom-Name
wird gelöscht. Die nächste vollständige Ladung fügt die App wieder in den Drawer,
**stellt die Zuweisung aber nicht wieder her** (Intersect kann nur entfernen).
Ergebnis: stiller, permanenter, unwiederbringlicher Verlust einer
Nutzer-Zuweisung.

**Widerlegter Nebenverdacht (Protokoll).** Der ursprüngliche Verdacht war ein
Mid-Update-Fenster (App während Replace kurz nicht querybar). Widerlegt:
Replace-Broadcasts feuern post-commit, die App ist querybar. Der Defekt liegt
**nicht** am Update-Fenster, sondern an der fehlenden Partiell-Liste-Verteidigung
— unabhängig vom Auslöser.

### §0.3 Doppelrolle des Reconcile (der Grund, warum es kein Einzeiler ist)

Der heutige Welt-Diff erfüllt **zwei** Aufgaben:

1. **Live-Cleanup** nach einem Paket-Event — *ereignis-gezielt machbar*.
2. **Cold-Start-Aufholung** — fängt Apps, die deinstalliert wurden, *während der
   Prozess tot war* (deren `PACKAGE_REMOVED` der Receiver verpasste). *Inhärent
   welt-weit*, weil kein Event sagt, was während der Totzeit geschah.

Ein reiner Ereignis-Ansatz löst (1) sauber, verliert aber (2). Der Rewrite muss
(2) **bewusst erhalten**. Das ist der Kern der Design-Arbeit.

---

## §1 Zielinvariante

> **R-INV (die eine Invariante):** Eine Zuweisung (Favorite/Swipe/Hidden/
> Custom-Name) wird **nur** gelöscht, wenn ihr Ziel-Paket bzw. -Component per
> **gezielter PackageManager-Verifikation** nachweislich nicht mehr auflösbar
> ist — **nie**, weil es in einem Bulk-Listen-Snapshot fehlt.

Folgerung, die den ganzen Defekt killt: **Entkopple „was anzeigen" von „was
löschen".**

- **Anzeige** (Drawer/Home) — getrieben vom Listen-Snapshot; toleriert Staleness
  und Partiellität (eine fehlende Zeile verschwindet kurz, kommt beim nächsten
  Load zurück; keine Persistenz-Wirkung).
- **Löschung** (Persistenz) — getrieben von einer **pro-Ziel**-Verifikation gegen
  den PackageManager; nie von der Vollständigkeit einer Liste.

Wenn Löschung immer pro-Ziel verifiziert ist, ist die Partiell-Liste-Klasse
strukturell unmöglich — der Sanity-Guard (#3) wird überflüssig (bleibt höchstens
Gürtel-und-Hosenträger, siehe §5).

---

## §2 Event-Payload — `AppUpdateSignal` von `Unit` auf typisiert

Damit Live-Cleanup gezielt werden kann, muss der Bus das **Paket** und den
**Event-Typ** tragen.

### §2.0 Namens- & Paket-Mapping (heute → Rewrite)

| Heute (Ist) | Rewrite | Modul | Änderung |
|---|---|---|---|
| `core.AppUpdateSignal` (`MutableSharedFlow<Unit>`) | `AppUpdateSignal` (`MutableSharedFlow<PackageEvent>`) | :domain (core) | Nutzlast statt `Unit` |
| — | `PackageEvent` (sealed: `Added(pkg)`, `Removed(pkg)`) | :domain (core) | **neu**, pure Kotlin |
| `AppUpdateSignal.sendUpdateSignal()` | `AppUpdateSignal.send(event: PackageEvent)` | :domain | Signatur trägt Event |
| `di.AppUpdateModule` (`appsUpdateTrigger: MutableSharedFlow<Unit>`) | bleibt `Unit` **oder** wird zusammengeführt | :app | siehe `SPEC-DECISION R-2` |

`PackageEvent` ist **pure Kotlin** (`:domain/core`, keine Android-Imports) —
der Receiver mappt `Intent` → `PackageEvent` an der `:data`-Kante, nicht im
Bus. `Removed` wird bereits heute nur für echte Uninstalls gesendet (der
`EXTRA_REPLACING`-Guard aus `4e719ff8` filtert Replace vorher heraus).

### §2.1 Kette-Deltas

- **`PackageUpdateReceiver`** (`:data`): baut aus `action` + `intent.data`
  ein `PackageEvent` und ruft `AppUpdateSignal.send(event)`. Der bestehende
  Privacy-Hash (Log) und die `goAsync`/`withTimeout`-Mechanik bleiben unverändert.
- **`AppManagementDelegate.listenForAppUpdates`** (`:app`): collect'et
  `PackageEvent` statt `Unit`. Bei `Removed(pkg)` → gezielter Cleanup (§3). Bei
  `Added(pkg)` → nur Drawer-Refresh, **kein** Cleanup (ein Add löscht nie etwas).
- **Drawer-Refresh** bleibt getrennt: jedes Event triggert weiterhin einen
  Listen-Reload für die Anzeige (`triggerAppsUpdate`), aber der Reload treibt
  **keine** Löschung mehr.

---

## §3 Ereignis-gezielter Live-Cleanup

Bei `PackageEvent.Removed(pkg)`:

1. **Verifiziere**, dass `pkg` wirklich weg ist — `SPEC-DECISION R-1` (API-Wahl).
   Kandidaten: `PackageManager.getLaunchIntentForPackage(pkg) == null`, bzw. für
   Component-genaue Ziele ein `resolveActivity`/`queryIntentActivities`-Check auf
   die konkrete `ComponentName`. Verifikation läuft auf `Dispatchers.IO`.
2. **Nur wenn verifiziert weg:** entferne gezielt die Zuweisungen, die auf `pkg`
   (bzw. dessen Components) zeigen — über neue *paket-adressierte* Repository-
   Methoden (§6), nicht über den Welt-Intersect.
3. **Wenn noch auflösbar** (false alarm, z. B. Event-Race): **nichts löschen**.

Kein Listen-Snapshot ist an diesem Pfad beteiligt → keine Partiell-Liste-Gefahr.

**Threading/Isolation:** wie heute pro-Store isoliert (das `runCleanup`-Muster aus
`ObserveInstalledAppsUseCase` — bereits durch Test `0ca40ece` gepinnt — wird
übernommen: ein Store-Fehler darf die anderen nicht überspringen).

---

## §4 Cold-Start-Aufholung erhalten (§0.3 Aufgabe 2)

Beim App-Start (und nach `WhileSubscribed`-Resubscribe) gibt es kein Event, das
sagt, was während der Totzeit deinstalliert wurde. Hier ist ein Welt-weiter
Abgleich unvermeidbar — aber er muss R-INV einhalten: **jede Löschung
pro-Ziel-verifiziert**, nicht listen-vertrauend.

Design: Ein Cold-Start-Reconcile, der
1. die Vereinigung aller *zugewiesenen* Pakete/Components sammelt (aus den vier
   Stores — kleine, endliche Menge),
2. für **jedes zugewiesene** Ziel einzeln gegen den PackageManager prüft, ob es
   noch auflösbar ist,
3. nur die verifiziert-abwesenden entfernt.

Das dreht die Frage um: heute „welche installierten Apps fehlen in meinem
Snapshot" (fragil), künftig „ist *dieses* zugewiesene Ziel noch da?" (robust).
Die Kosten sind an die Zahl der *Zuweisungen* gebunden (Favorites/Swipe/Hidden/
Custom-Names — typisch < 50), nicht an die Zahl der installierten Apps.

`SPEC-DECISION R-2`: Läuft der Cold-Start-Reconcile **einmal** beim Start
(getriggert wie heute über den ersten Load), oder bleibt der bestehende
Welt-Diff-Sweep als zusätzlicher Backstop bestehen? Empfehlung: **ersetzen**, da
der pro-Ziel-verifizierte Sweep den listen-vertrauenden strikt dominiert (gleiche
Abdeckung, ohne die Partiell-Gefahr).

---

## §5 Vollständigkeits-/Sanity-Absicherung (optionaler Interim = #3)

Ist R-INV vollständig umgesetzt (§3 + §4 beide pro-Ziel-verifiziert), ist ein
Sanity-Guard **überflüssig** — die Löschung hängt nicht mehr an Listen-
Vollständigkeit.

**Falls** der Rewrite gestaffelt wird und der listen-vertrauende Pfad
übergangsweise bestehen bleibt, ist der Interim-Guard (#3) der billige Schutz:

> In `ObserveInstalledAppsUseCase`, vor `runCleanup`: wenn die frische Liste
> gegenüber dem letzten Known-Good (`installedAppsStateRepository.getCurrentApps()`)
> um mehr als einen Schwellwert (z. B. > 25 % oder > N Einträge) **schrumpft**,
> Cleanup diese Runde **überspringen** (Load/Anzeige laufen weiter), da ein
> plötzlicher Masseneinbruch eher ein partieller Read als ein realer Massen-
> Uninstall ist.

Das ist ein graduierter Empty-Guard: wenige Zeilen + ein Test, isoliert
vorziehbar. Er ersetzt den Rewrite **nicht** (ein Einzel-App-Drop unter dem
Schwellwert rutscht weiter durch), dämpft aber das Massen-Verlust-Risiko sofort.

---

## §6 Repository-Vertragsänderungen

Die vier `cleanup*`-Methoden sind heute **welt-adressiert** („behalte den Schnitt
mit dieser Liste"). Der Rewrite braucht zusätzlich **ziel-adressierte** Löschung.

| Heute | Rewrite (zusätzlich) | Repo |
|---|---|---|
| `cleanupFavoriteComponents(installed: List<String>)` | `removeFavoritesForPackage(pkg)` / `…ForComponent(cn)` | Favorites |
| `cleanupSwipeActions(installed: List<String>)` | `clearSwipeActionsForComponent(cn)` | Swipe |
| `cleanupHiddenComponents(installed: List<String>)` | `removeHiddenForComponent(cn)` | Hidden |
| `cleanupCustomNames(installed: List<String>)` | `removeCustomNameForPackage(pkg)` *(existiert bereits)* | Custom-Names |

- **`SPEC-DECISION R-2`-abhängig:** bleiben die `cleanup*(installed)`-Methoden für
  einen gehärteten Cold-Start-Sweep, oder werden sie durch pro-Ziel-Löschung +
  eine Verifikations-Schleife ersetzt? Wenn ersetzt: die Contract-Test-Tripel der
  vier Stores (`Fake*ContractTest` + `*ImplContractTest`) ziehen mit.
- **Custom-Names ist Paket-adressiert** (Key = `pkg`), die anderen drei
  Component-adressiert (`pkg/class`). Der Rewrite behält diese Asymmetrie —
  Component-Rename über ein Update betrifft Custom-Names daher nicht (Paket
  bleibt), die anderen drei schon (siehe offene Frage `SPEC-DECISION R-1`
  zur Component-vs-Paket-Verifikationsebene).

---

## §7 DI-Deltas

- **`di.AppUpdateModule`** (`:app`): `AppUpdateSignal` liefert nun
  `MutableSharedFlow<PackageEvent>` (Buffer wie heute: `replay=0`,
  `extraBufferCapacity` ausreichend, damit ein Event bei kurzzeitig fehlendem
  Collector nicht suspendiert — heutige Semantik beibehalten).
- **`InstalledAppsRepositoryEntryPoint`** (`:data`): unverändert (liefert weiter
  `AppUpdateSignal` an den nicht-Hilt-Receiver; nur der Signal-Typ ändert sich).
- **Neuer Injektions-Bedarf:** die gezielte Verifikation (§3/§4) braucht
  `PackageManager` — bereits über `di.AppModule` bereitgestellt; der Reconcile-
  Use-Case (bzw. ein neues `PackagePresenceChecker`-Seam) bekommt ihn injiziert.
  `SPEC-DECISION R-1` entscheidet, ob das ein eigenes `:domain`-Interface mit
  `:data`-Impl wird (Testbarkeit, Rule 1) — empfohlen: ja, `PackagePresence`
  Interface in `:domain`, Impl in `:data`, damit der Reconcile-Use-Case
  JVM-testbar bleibt (kein echter PackageManager im Test).

---

## §8 Test-Inventar (an den realen Pfaden)

| Datei | Modul | `@Test` (neu/geändert) |
|---|---|---|
| `PackageEventTest` | :domain | `Removed`/`Added` tragen das Paket; sealed-Exhaustivität |
| `PackageUpdateReceiverTest` (bestehend) | :data | Receiver mappt `Intent` → korrektes `PackageEvent`; Replace bleibt gefiltert (Regression zu `4e719ff8`) |
| `PackagePresenceImplTest` | :data | Robolectric/MockK: `pkg` auflösbar → present; nicht auflösbar → absent; System-API-Fehler → **present** (fail-safe: im Zweifel nicht löschen) |
| `TargetedReconcileUseCaseTest` | :app (oder :domain) | `Removed(pkg)` + verifiziert-weg → nur `pkg`-Zuweisungen gelöscht; verifiziert-**da** → nichts gelöscht; Store-Fehler isoliert (Muster wie `0ca40ece`) |
| `ColdStartReconcileUseCaseTest` | :app | jede zugewiesene, verifiziert-abwesende Zuweisung entfernt; anwesende bleiben; **partielle Liste löscht nichts mehr** (der Kern-Regressionstest gegen §0.2) |
| vier `cleanup*`-Contract-Tripel | :domain/:data | ziehen mit `SPEC-DECISION R-2` mit (ersetzt vs. ergänzt) |
| `AppManagementDelegateTest` (bestehend) | :app | `PackageEvent.Removed` → gezielter Cleanup; `Added` → kein Cleanup, nur Refresh |

**Der eine unverzichtbare Test:** „partielle Liste löscht keine Zuweisung mehr"
— der ausführbare Beweis, dass §0.2 geschlossen ist.

---

## §9 Migration & Reihenfolge

1. `PackageEvent` (:domain) + `AppUpdateSignal`-Typwechsel + Receiver-Mapping +
   Delegate-Collect. Rein mechanisch, verhaltensneutral (Bus trägt jetzt Nutzlast,
   Verhalten noch wie heute wenn Delegate weiter pauschal refresht).
2. `PackagePresence`-Seam (:domain-Interface, :data-Impl) + Test.
3. Gezielter Live-Cleanup (§3) — `Removed`-Pfad auf pro-Ziel-Verifikation
   umstellen; Welt-Diff aus dem Live-Pfad nehmen.
4. Cold-Start-Reconcile (§4) auf pro-Ziel-Verifikation umstellen
   (`SPEC-DECISION R-2`).
5. Welt-adressierte `cleanup*` entfernen **oder** als gehärteten Backstop behalten
   (Folge aus 4).
6. Regressions-Sicherung: der §8-Kern-Test grün, `checkConventions` +
   `checkRule13` grün.

Jeder Schritt ist ein eigener Branch + FF-Merge, wie in dieser Serie üblich. Nach
Schritt 1 ist das System jederzeit auslieferbar.

---

## §10 Offene Entscheidungen

- **`SPEC-DECISION R-1` (Verifikations-Ebene & API).** Paket-genau
  (`getLaunchIntentForPackage(pkg) == null`) oder Component-genau
  (`resolveActivity(component)`)? Paket-genau ist einfacher und deckt den
  häufigen Fall (ganze App weg); Component-genau ist präziser bei Apps mit
  mehreren Launcher-Activities, deren *eine* Component verschwindet (z. B.
  Alias-Toggle). Empfehlung: **Component-genau** für Favorites/Swipe/Hidden
  (die auf `pkg/class` keyed sind), **Paket-genau** für Custom-Names (auf `pkg`
  keyed). Fail-safe-Regel in beiden Fällen: System-API-Fehler ⇒ „present" ⇒
  **nicht löschen**.
- **`SPEC-DECISION R-2` (Welt-Diff behalten oder ersetzen).** Der pro-Ziel-
  verifizierte Cold-Start-Sweep (§4) dominiert den listen-vertrauenden strikt.
  Empfehlung: **ersetzen** und die `cleanup*(installed)`-Methoden entfernen —
  weniger Code, kein Restrisiko. Alternative: behalten als zweiten Backstop,
  aber dann muss auch er §5's Sanity-Guard tragen, sonst reimportiert er den
  Defekt.

---

## Anhang: Warum das die richtige Form ist

Der heutige Code stammt aus früher KI-Unterstützung ohne Remote-Control und
nimmt eine plausible, aber gefährliche Abkürzung: „die geladene Liste *ist* die
Wahrheit". Der Rewrite ersetzt diese eine Annahme durch eine überprüfbare:
Löschung ist eine **Behauptung über ein konkretes Ziel** („dieses Paket ist
weg"), die **verifiziert** wird, bevor sie persistiert. Das ist dieselbe Haltung
wie in den vier Audits davor (Wallpaper-Staleness, Backup-Merge, Launch-Taxonomie,
Cleanup-Isolation): ein durch Design behaupteter Invariant wird ausführbar und
überprüfbar gemacht, statt implizit gehofft.
