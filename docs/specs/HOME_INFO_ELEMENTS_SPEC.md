# HOME_INFO_ELEMENTS_SPEC — Uhr / Datum / Akku / Event-Indikator als geteiltes Subsystem

> **Erzeugt** gegen `main` @ `<HEAD-Hash einsetzen>`, als Detail-Spec für die eine
> Naht aus `MONOREPO_MERGE_SPEC §3.5`, Zeile „Info-Elemente". Konsumiert dessen
> Klassifizierung (Klasse A) und dessen §3-Port-Doktrin.
>
> **Fokus:** die eingebauten Homescreen-Info-Elemente — **Uhrzeit, Datum,
> Akku(-Prozent + Ladezustand), und der Kalender-/Alarm-Event-Indikator** — so aus
> Kolibri herauszuziehen, dass beide Launcher sie teilen. Also: welche Logik rein
> (JVM-testbar) und welche Android-gebunden ist, die drei/vier Ports an der
> Produktgrenze (`TimeInfoSettings`, Charge-Seam, Tick-Seam, `TimeBasedEventsRepository`),
> die Platzierungs-Naht (Zustand statt Views) und der `READ_CALENDAR`-Permission-Flow.
>
> **Nicht im Fokus:** vom `AppWidgetHost` gehostete Fremd-Widgets (existieren
> nirgends, wären Neubau → eigener `HOME_WIDGETS_SPEC`), das konkrete Nyx-Layout,
> und die generische Monorepo-Mechanik (steht in `MONOREPO_MERGE_SPEC`).
>
> **Status:** ENTWURF v1.2. Die Port-Signaturen in §3 sind Vorschläge auf Basis des
> realen Kolibri-Codes; §10 hält den v1-Scope fest (Event-Indikator inklusive).
> Review-Runde 1 ausstehend.
>
> **Verhältnis zum großen Kolibri:** dies ist das **sauberste** der geteilten
> Subsysteme. `ClockDelegate` (235 LOC) importiert intern nur `core.TimberWrapper`
> plus vier neutrale Domain-Typen — **null** `AppInfo`/`HomeLayout`/Favoriten/Drawer.
> `TimeEventFormatter` ist schon als „PURE LOGIC" gebaut und nimmt `allDayLabel`
> injiziert (`:app` owns display strings). Der Spec formalisiert nur die letzten
> zwei Kopplungen (Produkt-Settings, Layout-Platzierung) zu Ports.

---

## §0 Bestandsaufnahme (was real existiert)

| Rolle | Datei (Kolibri) | Android? |
|---|---|---|
| Orchestrator | `ui/main/delegate/ClockDelegate.kt` (235 LOC) | ja (`Context`, Receiver, `DateFormat`) |
| Scope-Hülle | `ui/main/delegate/DelegateScope.kt` | nein (nutzt `UiEvent` aus `ui.base`) |
| Format-Engine | `ui/home/TimeEventFormatter.kt` | **nein** (nur `java.*`) |
| Event-UseCase | `domain/usecase/ObserveTimeBasedEventsUseCase.kt` | nein (pure-JVM `:domain`) |
| Event-Repo (Port) | `domain/repository/TimeBasedEventsRepository.kt` (`: Purgeable`) | nein |
| Event-Repo (Impl) | `data/TimeBasedEventsRepositoryImpl.kt` | ja (`CalendarContract`, `AlarmManager`) |
| Modelle | `domain/model/{ChargeState,TimeBasedEvent,TimeBasedEventType,CalendarEvent}.kt` | **nein** (reine Wertetypen) |
| Settings-Keys | `core/AppConstants.kt` → `SHOW_ALARM`, `SHOW_CALENDAR_EVENT`, `DEFAULT_SHOW_CALENDAR` | nein |
| Settings-Flows | `domain/repository/SettingsRepository.kt` → `showAlarmFlow`, `showCalendarEventFlow` | nein |
| App-Verdrahtung | `ui/main/MainActivity.kt` (Battery-Receiver → `updateBatteryLevelFromIntent`, `showTimeBasedEventsDialog`), `res/layout/fragment_home.xml` | ja |

Beobachtete Fakten, die den Schnitt bestimmen:

- **Zeit/Datum:** `ClockDelegate.updateTimeAndDate()` formatiert mit gecachten
  `DateTimeFormatter`n (rebuild nur bei Locale-/12-24h-Wechsel), Ticks über
  `callbackFlow` auf `ACTION_TIME_TICK/TIME_CHANGED/TIMEZONE_CHANGED`. Die
  *Formatierung selbst* ist rein; nur der Tick-Empfang und `DateFormat.is24HourFormat`
  sind Android.
- **Akku:** Prozent = `level*100/scale` (reine Integer-Mathe); Ladezustand wird aus
  `EXTRA_STATUS` + `EXTRA_PLUGGED` abgeleitet (`CHARGING`/`PROTECTED`/`NONE`) — auch
  rein. Das *Intent* kommt von außen: `MainActivity` registriert
  `ACTION_BATTERY_CHANGED` und ruft `updateBatteryLevelFromIntent(intent)`; der
  Kaltstart liest den sticky Intent via `registerReceiver(null, …)`.
- **Events:** `ObserveTimeBasedEventsUseCase` kombiniert `showAlarmFlow` +
  `showCalendarEventFlow` + Refresh-Trigger, `flatMapLatest` → Repo; bei beiden
  Toggles aus ⇒ `emptyList` **ohne** IPC; Fehler ⇒ `emptyList` (nie Crash). Der
  Impl liest den System-Kalender (`CalendarContract.Instances`) und den nächsten
  Alarm (`AlarmManager.getNextAlarmClock`), **gegated** durch
  `checkSelfPermission(READ_CALENDAR)` → ohne Permission leere Kalender-Liste.

---

## §1 Zielbild

- Die **reine** Logik (Modelle, Formatter, UseCase, Charge-/Battery-/Format-Ableitung)
  lebt im geteilten `:core`/`:domain`, als JVM-Wahrheitstabelle testbar.
- Die **Android-gebundene** Ausführung (`ClockDelegate`, `TimeBasedEventsRepositoryImpl`,
  `DelegateScope`) lebt in *einem* geteilten Android-Modul, von beiden Apps konsumiert.
- Die zwei verbleibenden Kopplungen sind Ports: die **Produkt-Settings** (zwei
  Toggles) und die **Layout-Platzierung** (Zustand statt Views).
- Nyx bekommt Uhr/Datum/Akku/Event-Indikator, indem es genau diese Ports
  implementiert — kein duplizierter Formatter, kein duplizierter Kalender-Reader.

---

## §2 Die Schichtung: rein vs. Android

> **HIE-INV-1 — Alle Ableitung ist rein.** Ladezustand aus (`status`, `plugged`),
> Akku-Prozent aus (`level`, `scale`), Zeit/Datum-String aus (`millis`, `zone`,
> `is24Hour`, `locale`) und die gesamte `TimeEventFormatter`-Logik hängen an keinem
> `Context`, keinem `Intent`, keinem Receiver und keiner Uhr außer dem übergebenen
> `millis`. Sie sind totale Funktionen und als Wahrheitstabelle JVM-testbar (§7).
> Android liefert nur die *Rohwerte* an diese Funktionen.

Konkret wandern zwei heute in `ClockDelegate` **inline** liegende Logikstücke in
reine Funktionen (Rule 10 — value bar, not cost bar):

```kotlin
// :core (pure-JVM) — heute inline in ClockDelegate.updateBatteryLevelFromIntent
object BatteryMath {
    /** null ⇔ ungültige Rohwerte (scale ≤ 0). */
    fun percent(level: Int, scale: Int): Int? =
        if (level >= 0 && scale > 0) (level.toLong() * 100 / scale).toInt() else null

    fun chargeState(statusExtra: Int, pluggedExtra: Int): ChargeState = when {
        statusExtra == STATUS_CHARGING || statusExtra == STATUS_FULL -> ChargeState.CHARGING
        pluggedExtra != 0 && statusExtra == STATUS_NOT_CHARGING       -> ChargeState.PROTECTED
        else                                                          -> ChargeState.NONE
    }
    // Die BatteryManager-Konstanten werden als Int hereingereicht (App-Seite),
    // damit :core android-frei bleibt.
}
```

`ClockDelegate` bleibt Android (Receiver-Registrierung, `DateFormat.is24HourFormat`),
delegiert die Rechnung aber an `BatteryMath` + `TimeEventFormatter`. Damit ist der
Delegate gerätefrei bis auf die dünne Receiver-Haut.

---

## §3 Die Ports an der Produktgrenze

### §3.1 `TimeInfoSettings` — Interface-Segregation gegen die fette SettingsRepository

Das geteilte Subsystem braucht aus den Einstellungen genau **zwei** Flags, nicht
Kolibris komplette `SettingsRepository` (die auch Favoriten, Hidden Apps, Custom
Names trägt — reines Produktmodell). Also ein schmaler Read-Port:

```kotlin
// :domain (geteilt)
interface TimeInfoSettings {
    val showAlarmFlow: Flow<Boolean>
    val showCalendarEventFlow: Flow<Boolean>
}
```

`ObserveTimeBasedEventsUseCase` dependt künftig auf `TimeInfoSettings` statt auf
`SettingsRepository`. Jede App adaptiert ihren eigenen Settings-Store darauf
(Kolibris `SettingsRepository` implementiert `TimeInfoSettings` direkt; Nyx liefert
eine kleine Adapter-Bindung über die zwei Keys).

> **HIE-INV-2 — Nur zwei Flags, nie der ganze Store.** Kein geteilter Typ dieses
> Subsystems importiert die produktspezifische `SettingsRepository`. Der einzige
> Settings-Berührungspunkt ist `TimeInfoSettings`. Die Key-Strings (`SHOW_ALARM`,
> `SHOW_CALENDAR_EVENT`) liegen bereits produktneutral in `core/AppConstants`
> (Owner via `OwnsSettingsStoreKeys`).

### §3.2 Charge-Seam — App liefert Rohwerte, geteilte Logik leitet ab

Kein `ChargeStateSource`-Interface nötig; die bestehende Naht ist schon richtig
herum: die App **besitzt** den `ACTION_BATTERY_CHANGED`-Receiver (Lifecycle in
`MainActivity.onResume/onPause`) und reicht den Intent herein. Formalisiert:

```kotlin
// geteiltes Android-Modul — ClockDelegate-API (unverändert, nur intern rein gemacht)
fun updateBatteryLevelFromIntent(intent: Intent?)   // App ruft das aus ihrem Receiver
```

> **HIE-INV-3 — Der Battery-Receiver ist app-lokal, die Ableitung geteilt.** Das
> geteilte Modul registriert höchstens den *sticky* Intent für den Kaltstart
> (`registerReceiver(null, …)`); den *laufenden* `ACTION_BATTERY_CHANGED`-Receiver
> hält die App (an ihren Lifecycle gebunden) und füttert `updateBatteryLevelFromIntent`.
> Die Zustands-Ableitung ist `BatteryMath` (HIE-INV-1).

### §3.3 Tick-Seam — der Zeit-Tick als Flow-Port (optional, für Testbarkeit)

`observeSystemTimeChanges()` (callbackFlow auf `TIME_TICK/TIME_CHANGED/TIMEZONE_CHANGED`)
bleibt im geteilten Modul, wird aber hinter `Flow<Unit>` abstrahiert, damit die
Tick→Format-Kette mit einem Fake-Flow JVM-testbar ist:

```kotlin
fun interface TimeTickSource { fun ticks(): Flow<Unit> }   // real: BroadcastReceiver-callbackFlow
```

Kür, nicht Pflicht (Rule 10): lohnt, weil die „nur emittieren wenn sich der
String geändert hat"-Optimierung sonst nur am Gerät prüfbar wäre.

### §3.4 `TimeBasedEventsRepository` — der Kalender/Alarm-Port

Bleibt wie gehabt der Domain-Port; `TimeBasedEventsRepositoryImpl` ist die geteilte
Android-Impl (Calendar + Alarm, permission-gegated). Eine Abhängigkeit muss
mitwandern oder abstrahiert werden:

> **HIE-INV-4 — `Purgeable` kommt mit oder wird zum Port.** `TimeBasedEventsRepository`
> erbt `Purgeable` (Querschnitt-Reset). Entweder `Purgeable` liegt in `:core`
> (bevorzugt, es ist produktneutral), oder das geteilte Modul definiert einen
> eigenen `Purgeable`-Port. Kein geteilter Reader hängt an der App-`Purge`-Registry.

---

## §4 Platzierungs-Naht: Zustand teilen, Views nicht

`ClockDelegate` exponiert bereits fünf `StateFlow`s: `timeString`, `dateString`,
`batteryString`, `chargeState`, `timeBasedEvents`. Genau die sind das geteilte
Interface nach oben. Die App kombiniert sie in ihren eigenen UiState und bindet sie
an ihre eigenen Views (`fragment_home.xml` bei Kolibri; Nyx an sein Grid-Home).

> **HIE-INV-5 — Kein Layout wird geteilt.** Geteilt wird der *Zustand* (die fünf
> StateFlows bzw. ein `HomeInfoUiState`-Bündel), nicht das XML und nicht der
> View-Binder. Jede App entscheidet, *wo* Uhr/Akku/Event-Zeile sitzen und wie der
> Tap den Event-Dialog öffnet (`showTimeBasedEventsDialog` bleibt app-lokal, weil
> er app-eigene Strings/Dialog-Styles + den wallpaper-aware Textfarb-Tint nutzt).

Optionaler Komfort: ein `HomeInfoUiState`-Datenklassen-Bündel im geteilten Modul,
damit beide Apps dieselbe Kombinier-Logik erben:

```kotlin
data class HomeInfoUiState(
    val time: String, val date: String,
    val battery: String, val charge: ChargeState,
    val events: List<TimeBasedEvent>,
)
```

---

## §5 Permission- & Settings-Flow (app-lokal)

> **HIE-INV-6 — Fehlende Permission ⇒ leere Liste, nie Crash, nie Prompt aus dem
> geteilten Modul.** `TimeBasedEventsRepositoryImpl` prüft `READ_CALENDAR` selbst und
> gibt ohne Grant eine leere Kalender-Liste zurück (Alarme brauchen keine Permission).
> Das *Anfordern* der Permission und die Manifest-Deklaration sind **app-lokal** —
> das geteilte Modul zeigt nie einen System-Dialog.

Nyx-Aufgaben für dieses Subsystem (die einzige echte Nyx-Arbeit):

1. `<uses-permission android:name="android.permission.READ_CALENDAR" />` ins
   Nyx-Manifest.
2. Runtime-Permission-UX in Nyx' Settings (Toggle „Kalender-Events anzeigen" fragt
   bei Aktivierung die Permission an — spiegelt Kolibris `SettingsFragment`).
3. Die zwei Settings-Keys (`SHOW_ALARM`, `SHOW_CALENDAR_EVENT`) in Nyx' Store
   registrieren und `TimeInfoSettings` darauf implementieren.
4. Battery-Receiver (`ACTION_BATTERY_CHANGED`) in Nyx' Home-Activity registrieren →
   `updateBatteryLevelFromIntent`.
5. Die fünf StateFlows an Nyx' Grid-Home-Views binden + Tap → Event-Dialog.

> **HIE-INV-7 — Settings aus ⇒ kein IPC.** `!showAlarm && !showCalendar` ⇒
> `emptyList` ohne Calendar-Query oder `getNextAlarmClock`-Binder-IPC (schon im
> UseCase). Gilt unverändert nach der Extraktion.

---

## §6 Modul-Zuordnung

| Einheit | Zielmodul (Minimal-Schnitt aus `MONOREPO_MERGE_SPEC §4.1`) |
|---|---|
| `ChargeState`, `TimeBasedEvent`, `TimeBasedEventType`, `CalendarEvent` | `:core` (bzw. `:domain`) |
| `TimeEventFormatter`, `BatteryMath`, Format-Ableitung | `:core` |
| `ObserveTimeBasedEventsUseCase`, `TimeInfoSettings`, `TimeBasedEventsRepository`, `Purgeable` | `:domain` (pure-JVM) |
| `ClockDelegate`, `DelegateScope`, `TimeTickSource`-Impl | `:common-ui` (android-library) |
| `TimeBasedEventsRepositoryImpl` (Calendar/Alarm) | `:common-data` (android-library) |
| Layout, View-Binder, `showTimeBasedEventsDialog`, Permission-UX, Battery-Receiver, Manifest, `TimeInfoSettings`-Impl | **`:app-nyx` / `:app-kolibri`** |

Bei Bedarf später zu einem eigenständigen `:feature-timeinfo` bündeln
(`MONOREPO_MERGE_SPEC §4.2`); startet aber in `:common-*` (MRG-INV-5).

---

## §7 Testplan (Rule 10 + eure Testkonvention)

- **JVM-Wahrheitstabellen (schnell, kein Gerät):** `BatteryMath.chargeState` über
  alle (`status`, `plugged`)-Kombis inkl. `FULL`-am-Ladegerät und
  `NOT_CHARGING`-plugged (PROTECTED); `BatteryMath.percent` inkl. `scale = 0`;
  `TimeEventFormatter` (Alarm-Aufrunden bei Sekunden > 0, All-Day-Label,
  Alarm-vs-Calendar-Zeile); `ObserveTimeBasedEventsUseCase` (beide-aus ⇒ leer & kein
  Repo-Aufruf, Fehler ⇒ leer, `distinctUntilChanged`-Dedupe).
- **Robolectric/`androidTest` (nur wo das Gerät die Wahrheit trägt):**
  `TimeBasedEventsRepositoryImpl` gegen `CalendarContract` (existiert schon:
  `TimeBasedEventsRepositoryImplCalendarTest`), Permission-Gate, `getNextAlarmClock`-
  Phantom-Alarm-Filter, der Tick-Receiver.
- **Dispatcher:** ein Dispatcher via `MainDispatcherRule`, **kein** separater
  `TestScope`/`StandardTestDispatcher`; Repos als MockK-Fakes. Siehe
  `TESTING_CONVENTIONS.kt` im Test-Root. Bricht sonst die `tools/`-Checks.

> **HIE-INV-8 — Verschoben, nicht verwässert.** Kolibris bestehende Tests für dieses
> Subsystem wandern mit in die geteilten Module und bleiben grün; die Extraktion
> fügt nur die neuen reinen Funktionen (`BatteryMath`, Tick-Fake) als JVM-Tests hinzu.

---

## §8 Migration in grün-bleibenden Phasen

Jede Phase hält `MRG-INV-2` (beide Apps grün). Voraussetzung: `:core` existiert
schon (`MONOREPO_MERGE_SPEC §6`, Phase 1).

- **Phase A — reine Kerne.** Modelle + `TimeEventFormatter` + `ObserveTimeBasedEventsUseCase`
  + neue `BatteryMath`/Format-Funktionen → `:core`/`:domain`. `TimeInfoSettings`
  einführen, `SettingsRepository` implementiert es, UseCase auf den schmalen Port
  umhängen. Kolibri baut, Tests grün.
- **Phase B — Android-Ausführung.** `ClockDelegate` (intern auf `BatteryMath`
  umgestellt), `DelegateScope`, `TimeBasedEventsRepositoryImpl` → `:common-ui`/
  `:common-data`, neutraler Namespace (`com.github.reygnn.launcher.…`). `Purgeable`
  nach `:core` heben. Kolibri zeigt auf die geteilten Fassungen, alte Kopien weg.
- **Phase C — Nyx andockt.** Die fünf §5-Aufgaben in `:app-nyx`. Ab hier hat Nyx
  Uhr/Datum/Akku/Event-Indikator — ohne eine Zeile duplizierter Formatter- oder
  Kalender-Logik.

---

## §9 Querschnitt-Konventionen (geerbt)

- **Policy/IO-Split** — reine Ableitung (`BatteryMath`, Formatter, UseCase-Kombinatorik)
  getrennt von IO (Receiver, `CalendarContract`, `AlarmManager`).
- **Sealed/enum Ergebnis statt String in der Domäne** — `ChargeState`/`TimeBasedEventType`
  sind enums; Anzeige-Strings (`allDayLabel`, Dialog-Texte) reicht `:app` herein.
- **„Nur bei echter Änderung emittieren"** — `_timeString.update { … }` gibt bei
  gleichem Wert nicht neu aus; Settings-Dedupe per `distinctUntilChanged`.
- **`:domain` bleibt Android-frei** — `BatteryManager`-Konstanten als `Int`
  hereingereicht, nicht importiert.
- **Referenz per nacktem Namen** — `HOME_INFO_ELEMENTS_SPEC §3.1`, nie ein Pfad.

---

## §10 Offene Punkte (für Review-Runde 1)

**Entschieden:**
- **`TimeTickSource`-Port (§3.3) wird eingeführt** — billig, macht die
  Smart-Update-Logik JVM-prüfbar.
- **`HomeInfoUiState`-Bündel lebt im geteilten Modul** (§4), damit beide Apps die
  Kombinier-Logik erben; jede App bindet es nur an ihre Views.
- **`Purgeable` wird nach `:core` gehoben** (§3.4) statt eines eigenen Ports — es ist
  produktneutral.
- Nyx zeigt den Event-Indikator **von Anfang an** (v1) ⇒ `READ_CALENDAR` +
  Runtime-Permission-UX (§5) ab Tag 1; Uhr/Datum/Akku bleiben permissionfrei.
- Icon-Assets (bolt/shield, Event-Typ-Icons) sind app-Ressourcen (`:app`); geteilt
  wird nur `ChargeState`/`TimeBasedEventType`, nicht das Drawable.

---

## Review-Log

| Runde | Datum | Reviewer | Ergebnis |
|---|---|---|---|
| v1.2 | `<offen>` | `<offen>` | Empfehlungen als Entscheide: `TimeTickSource` einführen, `HomeInfoUiState` geteilt, `Purgeable` nach `:core`; keine offenen Punkte mehr |
| v1.1 | `<offen>` | `<offen>` | v1-Scope entschieden: Event-Indikator (Kalender/Alarm) von Anfang an in Nyx ⇒ `READ_CALENDAR`-Flow ab Tag 1 |
| 1 | `<offen>` | `<offen>` | ausstehend |
