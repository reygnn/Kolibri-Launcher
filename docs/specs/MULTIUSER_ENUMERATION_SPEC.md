# MULTIUSER_ENUMERATION_SPEC — Strukturierter ComponentKey, LauncherApps-Enumeration, Work-Profile

> **Erzeugt** gegen `main` @ `d20d87a0`, als **Kolibri-standalone**-Spec
> (kein Nyx-Import, kein Monorepo). Eine zusammenhängende Kette, kein Trio.
>
> **Fokus:** drei Verbesserungen, die aufeinander aufbauen — (1) den flachen
> `ComponentKey`-String durch eine strukturierte Identität mit `userSerial` ersetzen,
> (2) die App-Enumeration von `PackageManager.queryIntentActivities` auf
> `LauncherApps.getActivityList` umstellen, (3) darauf **Work-Profile-Support**
> aufsetzen (Enumeration über alle Profile, Launch mit dem richtigen `UserHandle`,
> user-aware Package-Events und Reconcile, Badging). Inklusive Migration.
>
> **Nicht im Fokus:** Homescreen-Widgets (bewusst out-of-scope). Jede Art von
> Port-/Generalisierungs-/Modul-Sharing-Arbeit (`BackupSchema<T>`, geteiltes `:core`,
> neutrale Namespaces) — die rechtfertigt sich nur bei einem Nyx-Merge und ist hier
> **explizit unerwünscht** (§10). Dies bleibt Kolibri-intern.
>
> **Status:** ENTWURF v1.1. Signaturen auf Basis des realen Kolibri-Codes; §9
> korrigiert (Kolibri ist Text-Launcher → textueller Work-Marker statt Icon-Badge).
> Review-Runde 1 ausstehend.
>
> **Verhältnis / Warnung:** Dies macht Kolibris Identität **multi-user** — das
> Gegenteil der Single-User-Entscheidung, die ein etwaiger Nyx-Merge getroffen hätte.
> Solange Kolibri standalone bleibt: irrelevant. Ein späterer Merge würde die
> Identitätsfrage neu öffnen (Konvergenz dann auf den reicheren Key). Bewusst in Kauf
> genommen. Löst TODO §15 (ComponentName-Format-Fragilität) strukturell mit.

---

## §0 Bestandsaufnahme (Kolibri heute)

| Aspekt | Heute | Datei |
|---|---|---|
| Identität | flacher `"package/class"`-String + Validator-`object` (`isValid`) | `core/ComponentKey.kt` |
| `.className`-Normalisierung | in `AppInfo.normalizedClassName` (relativ→absolut), fragil (TODO §15) | `domain/model/AppInfo.kt` |
| Enumeration | `packageManager.queryIntentActivities` + **1 `loadLabel`-IPC pro App** (getracet als „first drawer open"-Kosten) | `data/InstalledAppsRepositoryImpl.kt` |
| Launch | `LauncherApps.startMainActivity(component, **Process.myUserHandle()**, …)` — richtige API, fixer Nutzer | `ui/main/AppLauncherImpl.kt` |
| Presence-Check (Reconcile-Gate) | `queryIntentActivities` je Package, Match auf flachen String; `Throwable ⇒ true` (fail-closed) | `domain/service/PackagePresenceImpl.kt` |
| Package-Events | `BroadcastReceiver` auf `ACTION_PACKAGE_ADDED/REMOVED` | `KolibriLauncherApp.kt` |
| Stores | keyen flache Strings: Favoriten/Hidden/Swipe = component-String, CustomNames/Usage = packageName | diverse `*RepositoryImpl` |
| Reconcile | fail-closed, jede Entfernung durch `isStillPresent` gegated | `RECONCILE_SPEC`, `FavoritesRepository`/`HiddenAppsRepository` |

Zwei Dinge sind bereits gut: **Launch nutzt schon `startMainActivity`** (nur mit
`myUserHandle()`), und **Presence ist schon fail-closed**. Das verkleinert Punkt 3
erheblich.

---

## §1 Zielbild

- Identität ist ein strukturiertes `ComponentKey(packageName, className, userSerial)`;
  der flache String ist nur noch eine Projektion an der Persistenz-Grenze.
- Enumeration läuft über `LauncherApps.getActivityList` — über alle Nutzerprofile,
  mit Label/Icon direkt aus `LauncherActivityInfo` (kein separates `loadLabel`-IPC).
- Apps aus einem Work-Profile erscheinen, starten (mit ihrem `UserHandle`) und
  überleben ein pausiertes Profil, ohne aus Favoriten/Hidden/Layout gepruned zu werden.
- Bestehende Nutzerdaten und Backups laufen **ohne Migration weiter** (§8).

---

## §2 Die Kette & Sequenz

```
(1) Strukturierter ComponentKey  ─┐
                                  ├─►  (3) Work-Profile
(2) LauncherApps.getActivityList ─┘
```

1+2 verstärken sich: `getActivityList` liefert je Activity den `UserHandle`, aus dem
der strukturierte Key seinen `userSerial` zieht — also zusammen bauen. 3 sitzt
obendrauf. **1+2 lohnen sich auch, falls 3 je rutscht** (Perf, korrekte APIs, weg mit
der String-Fragilität).

> **MUE-INV-1 — Jede Phase lässt die App grün.** Nach jeder §13-Phase kompiliert und
> testet Kolibri, `check-conventions.sh` ist grün; kein nicht-baubarer Zwischenstand.

---

## §3 (1) Strukturierter `ComponentKey`

```kotlin
// :domain (pure-JVM) — ersetzt das Validator-object als IDENTITÄT
data class ComponentKey(
    val packageName: String,
    val className: String,        // immer absolut (normalisiert bei Konstruktion)
    val userSerial: Long = 0L,    // 0 = Primärnutzer
) {
    /** Persistenz-/Backup-Projektion. Primär: "pkg/cls" (rückwärtskompatibel);
     *  sonst "pkg/cls@serial". */
    val flat: String = if (userSerial == 0L) "$packageName/$className"
                       else "$packageName/$className@$userSerial"

    companion object {
        /** Aus LauncherActivityInfo (componentName ist stets absolut) + Serial. */
        fun of(component: ComponentName, userSerial: Long): ComponentKey =
            ComponentKey(component.packageName, component.className, userSerial)
        /** Flache Projektion zurücklesen; null = malformed (früher TODO §15). */
        fun parse(flat: String): ComponentKey?
    }
}
```

- Die `.className`-Normalisierung (führender Punkt) wandert **einmalig** in die
  Konstruktion aus `LauncherActivityInfo.componentName` (schon absolut) — die
  `AppInfo.normalizedClassName`-Fragilität entfällt.
- `AppInfo` hält künftig `key: ComponentKey` statt loser `packageName`/`className`;
  `isFavorite` **bleibt** (Kolibris Favoriten-Modell ist standalone unangetastet).
- Der alte Validator (`isValid`) lebt als `parse()`-Seite weiter und bewacht genau
  den Übergang flach → strukturiert.

> **MUE-INV-2 — Strukturiert ist Identität, flach ist Projektion.** Kein Store hält
> Identität als rohen String; Persistenz benutzt `key.flat`, das Lesen `parse()`. Das
> Wire-/Store-Format formt nie das Modell.

> **MUE-INV-3 — Primärnutzer serialisiert unverändert.** `userSerial == 0` ⇒ `flat`
> ist exakt das alte `"pkg/cls"`. Damit bleiben bestehende Store-Einträge und
> Backups gültig (§8); nur Nicht-Primär-Keys tragen das `@serial`-Suffix.

---

## §4 (2) Enumeration → `LauncherApps.getActivityList`

```kotlin
// :data — ersetzt queryIntentActivities + loadLabel-Schleife
val profiles = userManager.userProfiles                 // Primär + Work-Profile(e)
val apps = profiles.flatMap { user ->
    val serial = userManager.getSerialNumberForUser(user)
    launcherApps.getActivityList(null, user).map { info ->
        AppInfo(
            key = ComponentKey.of(info.componentName, serial),
            originalName = info.label.toString(),        // kein separates loadLabel-IPC
            // …
        )
    }
}
```

- `LauncherActivityInfo` liefert Label und (badged) Icon direkt → das per-App
  `loadLabel`-IPC aus dem heutigen `processResolveInfoList` entfällt: **Perf-Gewinn**
  beim ersten Drawer-Open (der getracete Abschnitt `drawer_apps_enumerate`).
- In Phase B (§13) wird zunächst nur `Process.myUserHandle()` enumeriert (Verhalten
  identisch, nur neue API); die `userProfiles`-Iteration schaltet Phase D frei.

> **MUE-INV-4 — Eine Enumerationsquelle.** `LauncherApps.getActivityList` ist die
> einzige App-Enumeration; `queryIntentActivities` verschwindet aus dem Drawer-Pfad.
> Label/Icon kommen aus `LauncherActivityInfo`, nicht aus separaten IPCs.

---

## §5 (3a) Launch user-aware

`AppLauncherImpl` ist fast fertig — nur der `UserHandle` ist fix:

```kotlin
// VORHER: launcherApps.startMainActivity(componentName, Process.myUserHandle(), null, null)
// NACHHER:
val user = userManager.getUserForSerialNumber(appInfo.key.userSerial)
    ?: Process.myUserHandle()                 // Profil verschwunden → Primär-Fallback
launcherApps.startMainActivity(componentName, user, null, null)
```

> **MUE-INV-5 — Gestartet wird im Nutzer des Keys.** Der `UserHandle` kommt aus
> `getUserForSerialNumber(key.userSerial)`. Existiert das Profil nicht mehr, ist der
> Fallback ein sauberes `Failed`/Primär, nie ein Crash. `ComponentName` nutzt weiter
> `normalizedClassName` (jetzt aus dem Key), sodass Launch und Identität nie
> divergieren.

---

## §6 (3b) Package-Events → `LauncherApps.Callback` + Profil-Lifecycle

Der `ACTION_PACKAGE_*`-BroadcastReceiver in `KolibriLauncherApp` wird durch
`LauncherApps.registerCallback(...)` ersetzt:

```kotlin
launcherApps.registerCallback(object : LauncherApps.Callback() {
    override fun onPackageAdded(pkg: String, user: UserHandle) = refresh(user)
    override fun onPackageRemoved(pkg: String, user: UserHandle) = refresh(user)
    override fun onPackageChanged(pkg: String, user: UserHandle) = refresh(user)
    override fun onPackagesAvailable(...) ; onPackagesUnavailable(...)
    override fun onPackagesSuspended(...) ; onPackagesUnsuspended(...)
})
```

Plus die Profil-Verfügbarkeit über `ACTION_MANAGED_PROFILE_AVAILABLE/UNAVAILABLE`
(bzw. `onPackagesAvailable/Unavailable`), damit der Drawer beim Ein-/Ausschalten des
Work-Profils aktualisiert.

> **MUE-INV-6 — Package-Events tragen den Nutzer.** Jedes Event kennt seinen
> `UserHandle`; ein Refresh betrifft nur den betroffenen Nutzer. Der alte
> nutzer-lose Broadcast-Pfad entfällt.

---

## §7 (3c) Reconcile × pausiertes Work-Profile (der subtile Kern)

Ein pausiertes/gesperrtes Profil lässt seine Apps transient „verschwinden".
`PackagePresenceImpl` (das `isStillPresent`-Gate) wird user-aware und behandelt
*pausiert* als *vorhanden*:

```kotlin
override suspend fun isComponentPresent(key: ComponentKey): Boolean {
    val user = userManager.getUserForSerialNumber(key.userSerial) ?: return true // fail-closed
    // Profil pausiert/nicht verfügbar? → als vorhanden behandeln, NICHT prunen.
    if (!userManager.isUserUnlocked(user) || launcherApps.isProfilePaused(user)) return true
    return launcherApps.getActivityList(key.packageName, user)
        .any { ComponentKey.of(it.componentName, key.userSerial) == key }
}
```

> **MUE-INV-7 — Pausiert ≠ deinstalliert.** Der Reconcile prunt einen Key nur, wenn
> sein Profil **verfügbar** ist *und* die Activity dort fehlt. Ein pausiertes,
> gesperrtes oder verschwundenes Profil ⇒ Key bleibt (fail-closed, `RECONCILE_SPEC`
> R-INV-Geist). Ein gesperrtes Work-Profil darf niemals Favoriten/Hidden/Swipe-
> Zuweisungen wegwischen.

> **MUE-INV-8 — Presence bleibt fail-closed.** Jeder Fehler-/Unsicherheitspfad
> (`Throwable`, Profil-Query scheitert, Serial nicht auflösbar) ⇒ „vorhanden".
> Konserviert das heutige Verhalten (`catch → true`).

---

## §8 Migration (zero-touch für Bestandsdaten)

> **MUE-INV-9 — Bestehende Daten & Backups laufen ohne Migration.** Alle heutigen
> Store-Keys und Backup-Dateien sind Primärnutzer-Keys ohne `@serial` (§3, MUE-INV-3).
> `parse("pkg/cls")` ⇒ `userSerial = 0`. Es gibt **keinen** Migrationsschritt; alte
> Daten sind per Konstruktion bereits im neuen Format. Nur *neu* platzierte
> Nicht-Primär-Apps schreiben `@serial`.

Rückwärts: ein älterer Kolibri, der ein Backup mit `@serial`-Keys liest, ignoriert
diese Einträge (unbekanntes Format → `parse` = null → skip), statt zu crashen — das
`ignoreUnknownKeys`/skip-on-invalid-Verhalten der Stores greift.

---

## §9 (3d) Work-Profile-Kennzeichnung — **textuell** (Kolibri ist Text-Launcher)

Kolibri rendert `displayName` als **Text**, ohne App-Icons — es gibt daher **kein
Icon-Badging** (`getUserBadgedIcon` ist gegenstandslos). Nicht-Primär-Einträge werden
**textuell** gekennzeichnet: ein lokalisiertes Suffix/Glyph (z. B. `"Chrome · Arbeit"`
oder ein vorangestelltes Schloss-Glyph), **am Display-Fold** appliziert — dieselbe
Stelle, an der Custom-Names reaktiv gefaltet werden (`applyCustomNames`), **nicht** in
`AppInfo.displayName` der Enumeration (sonst inkonsistenter Sort-/Such-Schlüssel).
Details: `PHASE_D_IMPL_NOTE §6`.

> **MUE-INV-10 — Nicht-Primär-Einträge sind textuell als Work gekennzeichnet.** Der
> Marker sitzt im Anzeige-Fold, nie im Identitäts-, Sort- oder Such-Schlüssel; die
> Suche matcht weiter den Roh-Namen.

---

## §10 Was hier bewusst NICHT passiert (Standalone-Anker)

- **Keine Ports, keine Generalisierung, kein `BackupSchema<T>`, kein geteiltes `:core`,
  keine neutralen Namespaces.** `ComponentKey`/`AppInfo` bleiben Kolibris eigene Typen
  in `:domain`. Das ist der Punkt eines Standalone-Umbaus: die Kohäsion erhalten, nicht
  für einen hypothetischen Merge vorbauen.
- **`isFavorite` bleibt in `AppInfo`** (Kolibri-Favoriten sind intakt).
- **Kein Single-User-Rückbau** (das war ein Nyx-Thema).

---

## §11 Modul-Zuordnung (Kolibri-intern)

| Einheit | Modul |
|---|---|
| `ComponentKey` (data class, `flat`/`parse`/`of`), `AppInfo` mit `key` | `:domain` |
| `PackagePresence` (Port, jetzt `ComponentKey`-typisiert) | `:domain` |
| Enumeration (`getActivityList` über Profile), `PackagePresenceImpl` (user-aware), `UserManager`-Serial-Map | `:data` |
| Launch (`UserHandle`), `LauncherApps.Callback`-Registrierung, Profil-Lifecycle, Badging | `:app` |

---

## §12 Testplan (Rule 10 + Testkonvention)

- **JVM (schnell):** `ComponentKey.flat`/`parse` (Primär rückwärtskompatibel,
  `@serial`-Roundtrip, malformed → null, TODO-§15-Fälle); `.className`-Normalisierung
  bei Konstruktion; Reconcile-Entscheidungslogik mit **Fake** Presence
  (pausiert ⇒ behalten, verfügbar+fehlt ⇒ prune, Fehler ⇒ behalten).
- **Robolectric/`androidTest` (Gerätewahrheit):** `getActivityList` je Profil,
  `startMainActivity` mit `UserHandle`, `PackagePresenceImpl` gegen echten
  `UserManager`/`LauncherApps`, Callback-Refresh pro Nutzer, Badging.
- **Dispatcher:** ein Dispatcher via `MainDispatcherRule`, kein separater
  `TestScope`/`StandardTestDispatcher`; `TESTING_CONVENTIONS.kt`. Bestehende
  ComponentName-/Reconcile-Tests wandern mit, statt ersetzt zu werden.

---

## §13 Migrationsphasen (grün-bleibend)

- **Phase A — Strukturierter Key.** `ComponentKey` als `data class`; `AppInfo` hält
  `key`; alle Stores schreiben/lesen `key.flat`/`parse`. Verhalten identisch
  (alles Primär, `flat == "pkg/cls"`). Validator → `parse`. Tests grün.
- **Phase B — Enumeration auf `LauncherApps`.** `InstalledAppsRepositoryImpl` auf
  `getActivityList(null, myUserHandle())` (noch Single-User), Label/Icon aus
  `LauncherActivityInfo`. Perf-Gewinn, sonst verhaltensgleich.
- **Phase C — User-aware Plumbing.** Launch über `getUserForSerialNumber`,
  `PackagePresenceImpl` auf `ComponentKey` + Profil-Check, Package-Events auf
  `LauncherApps.Callback` — alles noch mit nur dem Primärprofil enumeriert. Keine
  Verhaltensänderung, aber die Nähte sind da.
- **Phase D — Work-Profile einschalten.** Enumeration über `userManager.userProfiles`,
  Profil-Lifecycle-Events, Badging, Reconcile-Awareness (MUE-INV-7). Ab hier
  erscheinen und starten Work-Apps.

---

## §14 Querschnitt-Konventionen (geerbt)

- **`:domain` bleibt Android-frei** — `ComponentKey` trägt `userSerial: Long`, nie
  `UserHandle`; die Serial↔Handle-Auflösung lebt in `:data`/`:app`.
- **Fail-closed** überall bei Presence/Reconcile (MUE-INV-7/8).
- **Sealed Ergebnis-Identifier** — Launch bleibt `AppLaunchResult` (`shouldReconcile`).
- **Referenz per nacktem Namen** — `MULTIUSER_ENUMERATION_SPEC §7`, nie ein Pfad.

---

## §15 Offene Punkte / Risiken (für Review-Runde 1)

- Flat-Suffix `@serial` vs. alternatives Trennzeichen — `@` kollidiert mit keinem
  Package/Class-Zeichen, aber gegen bestehende Backup-Parser prüfen.
- `isProfilePaused`/Verfügbarkeits-API pro API-Level abklopfen (min-SDK); ggf.
  `UserManager.isQuietModeEnabled(user)` als Quelle.
- CustomNames/Usage sind package-granular (`userSerial` steckt aber im Component-Key)
  — für Work-Profile: soll ein Custom-Name/Score pro (Package, User) oder global pro
  Package gelten? Vorschlag: pro (Package, User), d. h. auch diese Keys tragen den
  Serial. Entscheiden.
- Perf: `userProfiles.flatMap { getActivityList }` bei mehreren Profilen — messen, ob
  der Enumerations-Trace weiter im Budget bleibt.

---

## Review-Log

| Runde | Datum | Reviewer | Ergebnis |
|---|---|---|---|
| v1.1 | `<offen>` | `<offen>` | §9 korrigiert: Kolibri ist Text-Launcher, kein Icon-Badging → textueller Work-Marker im Display-Fold (MUE-INV-10); Detail in PHASE_D_IMPL_NOTE |
| 1 | `<offen>` | `<offen>` | ausstehend |
