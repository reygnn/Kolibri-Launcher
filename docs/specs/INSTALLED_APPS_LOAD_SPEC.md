# INSTALLED_APPS_LOAD_SPEC — Der App-Loader kollabiert Fehler zu leer (Plan + Sigs)

> **Erzeugt** 2026-08-08 gegen `main` @ `cd49dbcc`, als Nachfolge-Kandidat des
> DATASTORE_READ_SPEC-Refactors (auf Nachfrage: „weitere Grüne-Wiese-Kandidaten").
> **Fokus:** der Lade-Pfad der installierten Apps —
> `InstalledAppsRepositoryImpl.getInstalledApps()`, sein zweiter State-Holder
> `InstalledAppsStateRepositoryImpl`, der Consumer `ObserveInstalledAppsUseCase`,
> und die zwei Event-Busse (`AppUpdateSignal` / `appsUpdateTrigger`).
> **Nicht im Fokus:** der Reconcile-/Cleanup-Pfad (bereits grüne-Wiese-saniert,
> `RECONCILE_SPEC.md` / `RECONCILE_FIX_SPEC.md`, R-INV-2) und der Drawer-Filter/Sort
> (`GetDrawerAppsUseCase`, sauber).
>
> **Kein akuter Datenverlust.** Der destruktive Teil (Reconcile prunet bei leer)
> ist doppelt verhindert (Empty-Guard + fail-closed Reconcile). Offen ist ein
> **Kohärenz-/Observability-Defekt**: eine ganze Recovery-Apparatur ist produktiv
> **tot**, die Tests bescheinigen sie als funktionierend, und es gibt keinen
> lebenden „App-Laden-fehlgeschlagen"-Fehlerpfad. Severity `moderate`.
>
> **Verhältnis zu den zwei Gewinnern:** Dies ist dieselbe Familie wie ACRA
> (`ConsentReadResult`) und DataStore (Belang C, DSR-INV-4): *„a caught failure
> must stay a failure — return it, don't erase it."* Das Vokabular existiert hier
> sogar schon (`AppLoadResult`) — es stoppt nur am Use-Case-Boundary, während der
> Repo-Boundary weiter zu `emptyList` kollabiert. Der Unterschied zu den Gewinnern:
> deren Defekt-Klasse war **offen und destruktiv**; hier ist der destruktive Teil
> zu, es bleibt tote/maskierte Recovery + fehlendes Feedback.

---

## 1. Das Problem

### 1.1 Der Kollaps (Fehler wird zum Wert)

`getInstalledApps()` gibt `appsStateFlow` zurück — einen `stateIn`-StateFlow, dessen
Upstream **jeden** Fehler zu `emit(emptyList())` fängt:

| Ort | Fehler | Ergebnis |
|---|---|---|
| `InstalledAppsRepositoryImpl:158-163` | `queryIntentActivities` wirft | `emptyList()` (silent) |
| `:165-172` | `processResolveInfoList` wirft | `emptyList()` (silent) |
| `:177-182` | äußerer `catch(Throwable)` | `emit(emptyList())` |
| `:184-197` | `loadAppsFromPackageManager` `.catch` | `emit(emptyList())` |
| `:106-121` | `appsStateFlow` `.catch` | `emit(emptyList())` |

Ein **transienter** PackageManager-Fehler (RemoteException während eines
System-Updates, loadLabel-Sturm) kollabiert die ganze Liste zu `emptyList` — für
den Consumer **ununterscheidbar** von „das Gerät hat wirklich null startbare Apps".

### 1.2 Die tote Recovery-Apparatur (der eigentliche Fund)

`ObserveInstalledAppsUseCase` (`:45-84`) hängt an genau diesen StateFlow eine
Recovery-Kette:

```
getInstalledApps()                       // StateFlow — wirft NIE upstream
  .retry(MAX) { cause is IOException }    // (A) — feuert nie
  .catch { … Cache-Fallback / emit(AppLoadResult.Error(NotLoaded)) }  // (B) — feuert nie
  .collect { realApps -> if (realApps.isEmpty()) { skip reconcile } … }  // (C) — der einzige lebende Zweig
```

**Ein StateFlow propagiert Upstream-Exceptions nie an den Kollektor** (die wurden
vor `stateIn` gefangen). Also feuern **(A)** und **(B)** produktiv nie:

- `.retry(IOException)` — toter Code.
- `.catch`-Fallback (Cache-Rückgriff **und** `emit(AppLoadResult.Error)`) — toter
  Code.
- → **`AppLoadResult.Error` erreicht das UI über diesen Pfad nie.**
  `AppManagementDelegate:98-99` (`if (result is AppLoadResult.Error)` → Toast
  `error_app_list_not_loaded`, `DomainMessageMappers:33`) ist ein **produktiv toter
  Fehlerpfad**. Präzise (Review-Fund S7): tot sind die `NotLoaded`-Mapping-Stelle in
  `DomainMessageMappers` **und** der `AppManagementDelegate`-Toast — die
  String-Resource `error_app_list_not_loaded` selbst ist **nicht** tot (sie lebt über
  `SettingsViewModel.onAppListNotLoaded()` beim Sort-Favorites). Der Fehler-*Pfad*
  des App-Ladens ist tot, nicht der String.

Nur **(C)** lebt: der `realApps.isEmpty()`-Guard überspringt reconcile bei leer
und ruft `updateApps(emptyList())`.

### 1.3 Die Tests green-washen es

`ObserveInstalledAppsUseCaseTest` treibt die Apparatur mit einem **werfenden**
Fake:

```kotlin
private class ErrorThrowingInstalledAppsRepository(val error: Throwable) : … {
    override fun getInstalledApps(): Flow<List<AppInfo>> = flow { throw error }   // :460-461
}
```

Der Test asserted `AppLoadResult.Error(NotLoaded)` (`:345`) und Retry-Backoff
(`:367-390`) — **grün**. Aber die echte Impl **wirft nie** (StateFlow → Leer).
Klassische Fake/Impl-Divergenz, die genau die tote Recovery als funktionierend
bescheinigt. `InstalledAppsRepository` steht auf der `NO CONTRACT TEST (ADR)`-
Ausnahmeliste (CLAUDE.md Regel 2) → **kein Contract-Test fängt die Divergenz.**

### 1.4 Zwei State-Holder, der zweite am kritischen Pfad vorbei

- `InstalledAppsRepositoryImpl.appsStateFlow` — `stateIn`-Hot-Share (Holder #1).
- `InstalledAppsStateRepositoryImpl` — zweiter Holder: `_rawAppsFlow`
  (`MutableStateFlow`) **plus** `@Volatile lastSuccessfulAppList` (Cache).

`GetDrawerAppsUseCase:32` kombiniert `rawAppsFlow` — und bei einem Kollaps setzt
(C) `updateApps(emptyList())` → `_rawAppsFlow = leer` → **Drawer flackert leer**.
`updateApps` schützt nur die **Cache-Var** (`if (newApps.isNotEmpty())
lastSuccessfulAppList = …`), nicht `_rawAppsFlow`. Und `lastSuccessfulAppList`
wird ausschließlich von `getCurrentApps()` konsultiert — das steckt im **toten**
(B)-`.catch`. Der Zweitcache schützt also den kontinuierlichen Drawer-View gar
nicht.

### 1.5 Zwei Busse, der typisierte Payload tot

- **Bus 1:** `AppUpdateSignal.events` (typisiert `PackageEvent`,
  `extraBufferCapacity = 1`, AUDIT-9 #5). `PackageUpdateReceiver` sendet.
- **Consumer:** `AppManagementDelegate.listenForAppUpdates:263-277` — kollabiert
  jedes Event zu `refreshAppsUseCase()`; **„the payload is not yet consulted"**
  (`:264-265`). Event-getarget-Reconcile ist RECONCILE_SPEC §3 und **nicht gebaut**.
- **Bus 2:** `appsUpdateTrigger` (`MutableSharedFlow<Unit>`,
  `AppUpdateModule:16`). `RefreshAppsUseCase` → `triggerAppsUpdate` emittiert,
  **und** `CustomNamesRepositoryImpl:204` emittiert **direkt** (an Bus 1 vorbei).
- **Consumer:** `appsStateFlow` (`flatMapLatest` → reload).

Der typisierte `PackageEvent` wird also einen Operator später zu einem nackten
„lade alles neu" — reines totes Gewicht.

---

## 2. Zielbild / These

**Der Loader gibt Fehler als unterscheidbaren Wert zurück, nicht als
`Loaded(emptyList)`.** Dann wird die Recovery lebendig *oder* fällt als unnötig
weg, der tote Fehlerpfad wird real, und der Empty-Guard trifft nur noch den
echt-leeren Fall.

Das Vokabular existiert (`AppLoadResult`), stoppt aber am Use-Case-Boundary. Die
Bewegung ist dieselbe wie DataStore Belang C: den typisierten Result **eine Ebene
tiefer** ziehen — an den Repo-Boundary, wo der Kollaps sitzt.

### Prämisse P1 — die Result-Form ist gesetzt (stark bevorzugt, nicht offen)

`getInstalledApps()` liefert `Flow<AppLoad>` mit `Loaded/Failed` — **der Fehler als
Wert**, nicht der Loader, der Exceptions durchreicht. Das ist eine begründete
Prämisse, keine offene Wahl. Die Herleitung (Review-Fund: hält als Schluss, nur die
frühere „einzige Shape"-Formulierung war zu stark):

- **Alt-Shape 1 (Exceptions propagieren):** den Kollaps entfernen, damit die
  *bestehende* `retry`/`.catch`-Apparatur lebendig wird. Scheitert **hart**: ein
  `stateIn`-StateFlow reicht keine Upstream-Exceptions an den Kollektor durch —
  man müsste die `stateIn`-Struktur aufbrechen und den **teuren Hot-Share aufgeben**
  (PM-Enumeration), den der Refactor behalten muss.
- **Alt-Shape 2 (kalter Loader + universeller Hot-Holder):** ein kalter Loader, der
  wirft/retryt, speist einen separaten Hot-Holder, der den letzten guten Stand
  cached. **Strikt schlechter, keine echte Alternative:** der Holder hält nur
  last-good und exponiert *keinen* Fehlerzustand — die sechs `getInstalledApps()`-
  Kollektoren sähen also weiterhin **keinen** unterscheidbaren `Failed`. Und er
  entweder läuft die teure PM-Enumeration pro Kollektor neu **oder** zementiert genau
  den Zwei-Holder-Split, den Belang B **entfernen** will.

Nur die **Wert-Form** erfüllt „Fehler unterscheidbar" **und** „Hot-Share behalten"
gleichzeitig, ohne den Zwei-Holder-Split zu verfestigen. Die gesamte Spec (§4 Sigs,
IAL-INV-1/2, Migration) baut darauf; sie steht deshalb nicht mehr unter §11.

**Wichtig — der Hot-Share bleibt.** Anders als bei den drei DataStore-Repos ist
der Upstream hier **teuer** (PackageManager-Enumeration + per-App `loadLabel`).
Der DATASTORE_READ_SPEC-Anhang nennt genau `InstalledAppsRepositoryImpl` als den
legitimen, teuren Hot-Share. Präzisierung (Review-Fund S6, verfeinert N1): „nie
punktgelesen" stimmt nicht ganz — es gibt drei `.first { it.isNotEmpty() }`-Primings.
Zwei davon (`HiddenAppsViewModel`, `SwipeActionsViewModel`) lesen über
`GetInstalledAppsUseCase` und laufen nach dem Unwrap weiter über das
`List`-Interface (§3 Belang A (a)). Der dritte, `BackupDataAssembler:197`, liest das
**Repo direkt** und wird auf `first { it is Loaded && it.apps.isNotEmpty() }`
umgestellt (§3 Belang A (b)). Alle drei sind **kein** Stale-Replay-Risiko (der
Hot-Share ist warm/aktuell, kein Cross-Activity-Cache) und liegen außerhalb des
`check-stale-replay-read`-Gates (leere `hot_flows`).
Dieser Refactor tastet die `stateIn`-Entscheidung **nicht** an — er ändert nur,
**was** der Hot-Share hält: `Loaded/Failed` statt `List` mit Kollaps.

---

## 3. Belange

### Belang A — Typisierter Loader-Vertrag (der Kern, IAL-INV-1/2)

`getInstalledApps()` liefert einen Strom eines **sealed** Result statt
`Flow<List<AppInfo>>`:

```kotlin
sealed interface AppLoad {
    data class Loaded(val apps: List<AppInfo>) : AppLoad   // kann leer sein = echt leer
    data class Failed(val cause: Throwable) : AppLoad       // Ladefehler, NICHT Loaded(empty)
}
```

Der Loader fängt seine eigenen I/O-Fehler weiterhin (er darf nicht crashen), aber
repräsentiert sie als `Failed(cause)` — ein **Wert, unterscheidbar von
`Loaded(emptyList)`**. `CancellationException` propagiert weiter (IAL-INV-6). Der
Retry wandert **in den Loader** (N Versuche der PM-Query, bevor `Failed` emittiert
wird) — dort, wo der Fehler tatsächlich entsteht. **Prädikat breiter fassen**
(Review-Fund S5): die motivierenden Fehler (`RemoteException` / `DeadObjectException`
/ `TransactionTooLargeException` beim PM-Zugriff während eines System-Updates) sind
**keine** `IOException`; der heutige `retry { cause is IOException }`
(`ObserveInstalledAppsUseCase:53`) würde für genau sein Motiv nicht feuern. Der
Loader-Retry klassifiziert die PM-typischen transienten Throwables — **oder** der
Retry entfällt und der Loader emittiert `Failed` beim ersten Fehler (offene
Umsetzungswahl, im Commit zu entscheiden).

Damit:
- Der `stateIn`-Hot-Share hält `AppLoad`; `initialValue = Loaded(emptyList)` **oder**
  ein eigener `AppLoad.Loading`-Zustand (offene Entscheidung §11).
- `ObserveInstalledAppsUseCase` branch't auf den Typ **und behält den
  Empty-Guard** (Review-Fund S2 — der `isEmpty()`-Guard ist der *einzige lebende*
  Zweig heute, §1.2, und darf NICHT mit der toten Apparatur mitgelöscht werden):
  `Loaded(nonEmpty)` → reconcile + State-Update + `AppLoadResult.Success`;
  `Loaded(empty)` → **kein** reconcile, nur State-Update (echt-leeres Gerät /
  `stateIn`-Cold-Start-Init); `Failed` → Keep-last-good + (falls kein Cache)
  `AppLoadResult.Error(NotLoaded)`. Nur der tote `.retry`/`.catch` **entfällt** (die
  Recovery ist jetzt typ-getrieben, nicht exception-getrieben) — der Empty-Guard
  bleibt und wird von `is Loaded` **nicht** ersetzt.

**Containment — der Typwechsel wird begrenzt, aber NICHT auf die Reconcile-Pipeline
allein** (Review-Fund S1, in Runde 2 / N1 korrigiert: der Adapter schützt weniger
als ursprünglich behauptet). Die Consumer teilen sich in **zwei** Gruppen:

**(a) Adapter-geschützt — bleiben unverändert.** Die vier ViewModels
(`SettingsViewModel`, `CustomNamesViewModel`, `HiddenAppsViewModel`,
`SwipeActionsViewModel`) lesen über `GetInstalledAppsUseCase`.
`GetInstalledAppsUseCase` **unwrappt `AppLoad` intern** und liefert weiter
`Flow<List<AppInfo>>` (bei `Failed` → leere Liste; der Adapter ist **zustandslos**,
Keep-last-good lebt NICHT hier, sondern in der Reconcile-Pipeline / im Holder).
`Failed→empty` ist hier eine bewusste, verlustbehaftete Compat-Grenze —
**verhaltensgleich zu heute** (heute kollabiert der Read ebenfalls zu leer), also
kein Regress. Die `.first { it.isNotEmpty() }`-Primings in Hidden/Swipe laufen über
dieses `List`-Interface weiter; bei persistentem `Failed` timen sie via
`withTimeoutOrNull ?: error(…)` aus — exakt wie heute (kein „ewiges Hängen").

**(b) Direkte Repo-Consumer — MÜSSEN in Commit 1 mit** (sie injizieren
`InstalledAppsRepository` direkt, der Adapter erreicht sie NICHT):
- `BackupDataAssembler:60,197` (`.first { it.isNotEmpty() } ?: error(…)`) — der
  **heikelste** Pfad (ein `Failed`/leerer Read würde beim Restore alle Komponenten
  verwerfen bzw. ins harte `error()` laufen und den User-Restore abbrechen).
  Umstellen auf `first { it is AppLoad.Loaded && it.apps.isNotEmpty() }`, damit die
  „warte auf erstes `Loaded(nonEmpty)`"-Priming-Semantik (Kommentar :187-195)
  erhalten bleibt.
- `GetOnboardingAppsUseCase:15,19-34` (`.map { … }.catch { emit(emptyList()) }`) —
  der `.map` bricht am Typ; und der `.catch { emit(emptyList()) }` darf **nicht**
  naiv als `Failed→empty` bleiben — das wäre der gebannte IAL-INV-1-Kollaps, hier
  neu eingeführt. **Explizite Entscheidung nötig** (kein beiläufiger Unwrap): ist
  „leer bei Ladefehler" für den Onboarding-Editor eine *akzeptierte* Exemption
  (populated-or-nothing) — oder fail-closed wie DataStore Belang C? (Vgl. dort die
  Editor-Preselect-Frage.)

### Belang B — Ein State-Holder + eine Keep-last-good-Heimat (IAL-INV-3/4)

Mit `Loaded/Failed` unterscheidbar bekommt „behalte den letzten guten Stand bei
Fehler" **genau eine** Heimat statt der heutigen Aufteilung über zwei Holder mit
einem toten Cache. Zwei Formen (offene Entscheidung §11):

- **B1 (Merge):** `InstalledAppsStateRepositoryImpl` entfällt; der Loader-Hot-Share
  ist der einzige State, und `ObserveInstalledAppsUseCase` hält das
  Keep-last-good in seinem eigenen `stateIn`/`scan`. **Deutlich größerer
  Blast-Radius als „ein Repoint"** (Review-Fund S3/N3): **sechs** Interface-Consumer
  injizieren den Holder — inkl. `ObserveInstalledAppsUseCase` (das B1-Merge-Ziel)
  selbst; die anderen fünf sind `GetDrawerAppsUseCase`, `GetFavoriteAppsUseCase`,
  `GetRecentAppsUseCase`, `HandleSwipeActionUseCase`, `ResetRepositoryImpl`. Unter
  B1 absorbiert `ObserveInstalledAppsUseCase` das Keep-last-good → **fünf Consumer
  zu repointen + das `@Binds` zu löschen**. B1 muss dabei eine gemeinsame
  Lese-Fläche für die fünf benennen, sonst behält es faktisch einen Holder unter
  neuem Namen. Das stärkt die „B2 zuerst"-Empfehlung. (Nebenbei: B1 ist **nicht**
  die von P1 abgelehnte Alt-Shape 2 — es hält `AppLoad`, exponiert `Failed`, teilt
  eine PM-Enumeration; P1s Ablehnung bleibt gültig.)
- **B2 (Behalten, entrümpeln):** `InstalledAppsStateRepositoryImpl` bleibt als der
  UI-facing State-Holder, aber die Keep-last-good-Logik wird explizit
  typ-getrieben (bei `Failed` **nicht** `updateApps(emptyList())`, sondern den
  letzten guten Stand halten) — der Drawer flackert dann nicht mehr leer. Kleinerer
  Diff.

### Belang C — Bus-Konsolidierung (separierbar, IAL-INV-5-nah)

Der typisierte `PackageEvent` wird einen Operator später verworfen. Zwei Optionen
(offene Entscheidung §11), **beide out-of-scope für event-getarget-Reconcile**
(das ist RECONCILE_SPEC §3):

- **C1 (Payload droppen):** Bus 1 trägt `Unit` (oder wird entfernt und
  `PackageUpdateReceiver` emittiert in Bus 2). Ehrlicher: keine typisierte
  Infrastruktur vortäuschen, die niemand nutzt.
- **C2 (Payload konsumieren):** event-getarget-Reconcile bauen (RECONCILE_SPEC §3)
  — **eigenes, größeres Projekt**, hier nur als Verweis, nicht Teil dieses Specs.

Empfehlung: **C1**, oder Belang C ganz aus diesem Spec herauslassen und separat
entscheiden — er ist vom Loader-Vertrag unabhängig.

### Belang D — Der tote Fehlerpfad wird lebendig (die Verhaltens-Zugabe, IAL-INV-5)

Nach Belang A feuert `Failed` ohne Cache real den `AppLoadResult.Error(NotLoaded)` →
`AppManagementDelegate:99` → Toast `error_app_list_not_loaded`. Damit gibt es zum
ersten Mal ein **echtes** User-Feedback bei App-Ladefehler. Das ist die einzige
sichtbare Verhaltensänderung dieses Specs (analog Belang C beim DataStore-Refactor)
und verdient eine eigene, klar benannte Revert-Einheit.

---

## 4. Sigs (Zielzustand)

```kotlin
// domain/model/AppLoad.kt  (neu)
sealed interface AppLoad {
    data class Loaded(val apps: List<AppInfo>) : AppLoad
    data class Failed(val cause: Throwable) : AppLoad
    // ggf. data object Loading : AppLoad   // §11 Entscheidung: eigener initialValue?
}

// domain/repository/InstalledAppsRepository.kt
interface InstalledAppsRepository : Purgeable {
    fun getInstalledApps(): Flow<AppLoad>        // war: Flow<List<AppInfo>>
    suspend fun triggerAppsUpdate()
}

// data/InstalledAppsRepositoryImpl.kt — der Loader fängt I/O intern, retryt die
// PM-Query (breites Prädikat, S5), und emittiert Failed(cause) statt zu Leer zu
// kollabieren. stateIn bleibt.

// ObserveInstalledAppsUseCase — branch't auf AppLoad; der isEmpty-Guard BLEIBT:
//   is Loaded -> if (apps.isNotEmpty()) reconcile(apps) ; state.update(apps) ; emit(Success)
//               // Loaded(empty): kein reconcile (Datenverlust-Schutz), nur update
//   is Failed -> keepLastGood() ; if (no cache) emit(Error(NotLoaded))
// NUR die toten .retry / .catch entfallen — der isEmpty-Guard NICHT (S2).

// GetInstalledAppsUseCase — Unwrap-Adapter (S1): bleibt Flow<List<AppInfo>>.
// Hält den Typwechsel aus den 4 ViewModels heraus (NUR die — Onboarding + Backup
// injizieren das Repo direkt und ändern sich in Commit 1, §3 Belang A (b)).
// Zustandslos: Failed -> emptyList (KEIN keepLastGood hier; das lebt in der
// Reconcile-Pipeline / im Holder). Failed->empty ist verhaltensgleich zu heute.
operator fun invoke(): Flow<List<AppInfo>> =
    installedAppsRepository.getInstalledApps().map { load ->
        when (load) {
            is AppLoad.Loaded -> load.apps
            is AppLoad.Failed -> emptyList()   // Compat-Grenze, wie heute
        }
    }
```

---

## 5. Invarianten (IAL-INV-*)

- **IAL-INV-1 — Kein Fehler-zu-Leer-Kollaps.** Der Loader repräsentiert einen
  echten Ladefehler als `Failed`, **nie** als `Loaded(emptyList)` (die
  Antipattern-Sperre; spiegelt DSR-INV-4 / Regel 11 „a caught failure must stay a
  failure").
- **IAL-INV-2 — `Loaded(empty)` heißt echt-leer.** Reserviert für „PM lieferte
  null startbare Apps"; von `Failed` unterscheidbar.
- **IAL-INV-3 — Reconcile läuft nur auf einem `Loaded` mit *nicht-leerer* Liste.**
  Ein `Failed` **und** ein `Loaded(empty)` lösen keinen Cleanup aus
  (Datenverlust-Schutz). Der Typ (`Failed`) **und** der bestehende `isEmpty()`-Guard
  bleiben beide erhalten — der Guard wird nicht durch `is Loaded` ersetzt (S2).
- **IAL-INV-4 — Keep-last-good hat genau eine Heimat.** Nicht über zwei Holder mit
  einem toten Cache verteilt.
- **IAL-INV-5 — Der Fehlerpfad ist lebendig.** Ein `Failed` ohne Cache-Fallback
  erreicht `AppLoadResult.Error` und das UI-Feedback. Keine tote Recovery-
  Apparatur, keine maskierenden Tests.
- **IAL-INV-6 — Cancellation propagiert immer.** Auf jedem Pfad, unverändert.
- **IAL-INV-7 (Test) — Fake == Impl-Vertrag.** Der Test-Fake bildet dieselbe
  `Failed`-Semantik ab wie die Impl (kein „Fake wirft, Impl schluckt" mehr). Der
  Retry/Fehlerpfad wird gegen das echte Kontrakt-Verhalten getestet, nicht gegen
  einen werfenden Fake, den die Impl nie erreicht.

---

## 6. Migrationsreihenfolge (je ein Commit, revertierbar)

1. **`AppLoad` + Loader-Vertrag (Belang A).** `getInstalledApps(): Flow<AppLoad>`,
   Loader emittiert `Failed` statt Kollaps, Retry in den Loader (breites Prädikat,
   S5). `ObserveInstalledAppsUseCase` branch't auf den Typ; **nur** die toten
   `.retry`/`.catch` raus — der `isEmpty()`-Guard **bleibt** (S2). **Containment
   (S1, korrigiert N1):** `GetInstalledAppsUseCase` als Unwrap-Adapter (bleibt
   `Flow<List<AppInfo>>`) schützt **nur** die vier ViewModels
   (`Settings`/`CustomNames`/`Hidden`/`Swipe`). **`GetOnboardingAppsUseCase` und
   `BackupDataAssembler` injizieren das Repo direkt → ändern sich in DIESEM Commit
   mit** (§3 Belang A (b)): Backup auf `first { it is Loaded && it.apps.isNotEmpty() }`,
   Onboarding mit der expliziten `Failed`-Entscheidung. Test-Fakes auf `AppLoad`
   umstellen (IAL-INV-7).
2. **State-Holder (Belang B).** Je nach §11-Entscheidung B1 (merge — 5 Consumer
   repointen + `@Binds` löschen, `ObserveInstalledAppsUseCase` absorbiert
   Keep-last-good, N3) oder B2 (entrümpeln, kleiner). B2 empfohlen.
3. **Belang D — der lebende Fehlerpfad** als eigener, letzter Commit (die eine
   Verhaltensänderung), mit UI-Test, dass `Failed`-ohne-Cache den Toast zeigt.
4. **(Optional/separat) Bus-Konsolidierung (Belang C1).** Nur falls in Scope.

---

## 7. Contract- / Test-Auswirkung

- **Marker korrekt: `NO IMPL CONTRACT TEST (ADR)`, nicht `NO CONTRACT TEST (ADR)`**
  (Review-Fund S4). Es gibt bereits einen **lebenden** Fake-Contract
  (`InstalledAppsRepositoryContract` + `Fake`/`ReactiveFake`-Subklassen) — nur die
  Impl-Hälfte ist exempt. Die Signaturänderung rippelt also in **den Contract + beide
  testFixtures-Fakes + beide Fake-Contract-Tests + `CustomNamesViewModelTest`s
  Inline-Objekte** (in §6/§7 zu budgetieren). Der Schluss „kein Test pinnt das
  *Fehler*-Verhalten" bleibt trotzdem gültig (die Impl-Hälfte ist echt exempt).
  Nebenbei: CLAUDE.md Regel 2 listet das Repo selbst mit dem falschen Marker — dort
  auch korrigieren (eigener Mini-Commit).
- **`ObserveInstalledAppsUseCaseTest`** wird umgeschrieben: der `ErrorThrowingInstalledAppsRepository`-
  Fake liefert künftig `AppLoad.Failed` statt `flow { throw }`; die Retry-Tests
  wandern zum Loader (falls der Retry dort landet). Der `AppLoadResult.Error`-Test
  wird zum **echten** Regressionstest des lebenden Fehlerpfads (statt eines
  green-washing gegen einen nie erreichten Fake).
- **Weitere Churn (Review-Fund N4, ≥5 Dateien):** `GetInstalledAppsUseCaseTest`,
  `BackupDataAssemblerColdImportTest`, `BackupDataAssemblerImportOrderReuseTest`,
  `GetOnboardingAppsUseCaseTest` brechen/ändern sich am Typwechsel. **Substanziell:**
  `HiddenAppsViewModelTest:204/243` mockt `getInstalledApps()` als
  `flow { throw IOException }` — ein **Repo-Level-Überlebender** desselben
  Green-washing-Musters (Fake wirft, wo die Impl nie wirft); sobald das Repo
  `Failed` liefert statt zu werfen, muss diese Erwartung mit (IAL-INV-7 gilt auch
  hier).
- **Neu:** ein UI/Delegate-Test, dass `Failed` ohne Cache den
  `error_app_list_not_loaded`-Toast auslöst (Belang D).

---

## 8. Gate-Auswirkung

- **`cancel_files`:** `InstalledAppsRepositoryImpl.kt` steht drauf (die
  `emit`-in-`.catch`-Arme, AUDIT-12 #7/#8). Der Loader-Umbau ändert diese Arme
  (Failed statt emptyList) — die `CancellationException`-first-Struktur muss
  erhalten bleiben; nach dem Umbau `./gradlew checkConventions` + `scanCancelCandidates`.
- **`unbuffered-sharedflow` / stale-replay:** unberührt (Hot-Share bleibt, kein
  neuer SharedFlow, keine Punktlesung eingeführt).
- **Localization-Parität:** `error_app_list_not_loaded` existiert schon in beiden
  Locales (der Toast war nur tot) → keine neuen Strings, Parität unberührt.

---

## 9. Verifikation

```bash
./gradlew :domain:test :data:test :app:test
./gradlew checkConventions checkRule13
./gradlew scanCancelCandidates   # Loader-Catch-Arme geändert
```

Erwartung: Suite grün; der `AppLoadResult.Error`-Test ist jetzt ein echter
Fehlerpfad-Test; kein `cancel_files`-Neuzugang nötig (Arme behalten
`CancellationException`-first).

---

## 10. Außerhalb des Scopes (bewusst nicht mitgezogen)

- **Reconcile/Cleanup** — bereits grüne-Wiese-saniert (RECONCILE_SPEC / R-INV-2),
  fail-closed, PackagePresence-Veto. Der `Loaded`-Guard bleibt als Datenverlust-
  Schutz; die Reconcile-Interna nicht angefasst.
- **Event-getarget-Reconcile** (Payload konsumieren, C2) — RECONCILE_SPEC §3,
  eigenes größeres Projekt.
- **Rename → Full-PM-Requery** (`CustomNamesRepositoryImpl:204` triggert ein
  komplettes `queryIntentActivities` für eine Ein-App-Umbenennung) — eine echte
  Ineffizienz, aber orthogonal zum Fehler-Vertrag; separater Kandidat.
- **Drawer-Filter/Sort** (`GetDrawerAppsUseCase`) — sauber, nur ggf. das
  `rawAppsFlow`-Repointing (Belang B1).

---

## 11. Offene Entscheidungen (spec-neutrale Scope-Regler)

> Die **Result-Form** steht NICHT hier — sie ist die begründete **Prämisse P1**
> (§2), technisch nahezu erzwungen. Die verbleibenden vier sind Scope-/Detail-
> Regler: sie ändern *welche* Teile gemacht werden und den Blast-Radius, aber nicht
> den Kern-Vertrag (Belang A/D, IAL-INV-1/2/5/6/7) — jeder ist zur
> Implementierungszeit pro Commit entscheidbar, ohne die Spec umzuschreiben.

1. **`Loading`-Zustand?** Eigener `AppLoad.Loading` als `initialValue` (sauberer
   Erst-Render) vs. `Loaded(emptyList)` als initial (kleiner). *Berührt (korrigiert
   N2):* ein **dritter Arm in JEDEM `AppLoad`-`when`** — der `when` im Unwrap-Adapter
   (§4) ist ein *literales* `when` → **harter Exhaustiveness-Compile-Fehler** ohne
   den Arm; dazu der Observe-Branch. Und die Semantik muss definiert werden: Observe
   → kein reconcile / kein `Error`; Unwrap → **Keep-last-good, nicht `emptyList`**
   (sonst Cold-Start-Flacker, IAL-INV-2). Also nicht „eine Case, ein Branch". Falls
   nicht jetzt entschieden: **explizit auf später vertagen** (dann bleibt es bei
   `Loaded(emptyList)` als initial).
2. **State-Holder B1 (merge) vs B2 (entrümpeln).** B2 ist der kleinere,
   risikoärmere Einstieg und behebt den Drawer-Flacker allein; B1 ist die sauberere
   Endform (ein Holder). Empfehlung: **B2 zuerst**, B1 optional später. *Berührt:*
   §3 Belang B, §6 Commit 2, Blast-Radius.
3. **Den bestehenden Fake-Contract um `Loaded`/`Failed` erweitern?** Der
   Fake-Contract lebt bereits (nur die Impl-Hälfte ist `NO IMPL CONTRACT TEST`, S4);
   `AppLoad` macht die `Loaded`-vs-`Failed`-Unterscheidung pinbar. Empfehlung: die
   Unterscheidung in Fake+Contract aufnehmen. *Berührt:* §7.
4. **Belang C (Bus) in Scope?** Empfehlung: **raus** aus diesem Spec (unabhängig),
   oder nur C1 (Payload droppen) als letzter Aufräum-Commit. *Berührt:* §3 Belang C,
   §6 Commit 4.

---

## Review-Log

- **2026-08-08 — Multi-Agent-Spec-Review Runde 1 (5 Dimensionen + adversarialer
  Verify, 11 Agents).** Verdict **GO-WITH-CHANGES**. **Tragende Behauptung
  CONFIRMED** (die `retry`/`.catch`-Recovery ist produktiv tot, weil ein
  `stateIn`-StateFlow keine Upstream-Exception durchreicht; die Tests green-washen
  es über einen werfenden Fake, den die Impl nie erreicht). **Severity ehrlich
  moderate** — kein ungeschützter Prune-Pfad (Voll-Kollaps doppelt, Partial-Load
  einfach via PackagePresence-Veto, das fail-closed auf *present* geht). **P1 hält
  als Schluss** (die dritte Shape ist strikt schlechter). Sieben Funde eingearbeitet:
  **S1** Blast-Radius unterschätzt → Containment via `GetInstalledAppsUseCase`-Unwrap;
  **S2** (Commit-1-Blocker) der `isEmpty()`-Guard ist LEBEND, nur `.retry`/`.catch`
  sind tot → §4/§6/IAL-INV-3 korrigiert; **S3** B1 = 6 Consumer; **S4** Marker ist
  `NO IMPL CONTRACT TEST`, Fake-Contract lebt; **S5** Retry-Prädikat `IOException`
  feuert nicht für `RemoteException` → breiter fassen/droppen; **S6** „nie
  punktgelesen" falsch (3 Primings); **S7** String lebt, nur Pfad+Mapping tot.
- **2026-08-08 — Multi-Agent-Spec-Review Runde 2 (4 Dimensionen + Verify, 9
  Agents).** Verdict **GO-WITH-CHANGES**, **starkes Konvergenz-Signal**: alle vier
  Dimensionen landeten unabhängig auf **demselben** Primärdefekt, nichts strukturell
  Neues. 6 von 7 Runde-1-Fixes **CLOSED**; **S1 PARTIAL** — mein Containment-Fix war
  überzogen (**N1**): `GetOnboardingAppsUseCase` und `BackupDataAssembler` injizieren
  das Repo **direkt**, der Unwrap-Adapter schützt sie NICHT → sie ändern sich in
  Commit 1. Eingearbeitet: N1 (Consumer in „adapter-geschützt (4 VMs)" vs „direkt →
  Commit 1" gesplittet, §3/§4/§6/§2), N3 (B1 = 6 Interface-Consumer inkl. Observe →
  5 Repoints + `@Binds`), N4 (§7 Test-Churn ≥5 Dateien + der Repo-Throw-Fake in
  `HiddenAppsViewModelTest`), N2 (`Loading` erzwingt einen dritten `when`-Arm →
  Impact korrigiert), N5 (Adapter ist zustandslos, `keepLastGood`-Kommentar
  entfernt; Signatur `invoke()`). **Kein Hang** (Primings timen via
  `withTimeoutOrNull ?: error()` aus, wie heute); **kein Datenverlust** bestätigt.
  Runde 2 vermerkt: sobald N1/N3/N4 landen, ist die Spec **konvergiert**.

---

## 12. Abschluss

Dies ist ein **unverbindlicher Entwurf** zum Entscheiden. Severity `moderate`: kein
Live-Datenverlust, aber ein realer Kohärenz-/Observability-Gap (tote Recovery,
maskierende Tests, kein Ladefehler-Feedback) in der ACRA/DataStore-Familie. Wenn
umgesetzt: gleicher Prozess wie DATASTORE_READ_SPEC (Spec-Reviews → Commits →
je-Commit-Code-Review → Merge), und danach diese Spec auf „umgesetzt in `<commits>`"
setzen.
