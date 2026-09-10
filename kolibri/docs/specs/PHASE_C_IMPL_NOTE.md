# PHASE_C_IMPL_NOTE — User-aware Plumbing (Launch, Presence, Package-Events), noch primär-only

> Umsetzungs-Notiz zu `MULTIUSER_ENUMERATION_SPEC §13 Phase C`. Kolibri-standalone.
> Setzt Phase A (strukturierter `ComponentKey`, `AppInfo.key`/`userSerial`) und B
> (`LauncherApps.getActivityList`-Enumeration) voraus. Ziel: die drei user-abhängigen
> Nähte einziehen — Launch mit aufgelöstem `UserHandle`, user-aware `PackagePresence`,
> Package-Events über `LauncherApps.Callback` — **ohne** Verhaltensänderung, weil
> weiterhin **nur das Primärprofil** enumeriert wird (`key.userSerial == 0`). Phase D
> schaltet die Profile ein; hier entsteht nur die Mechanik.

---

## 0. Warum das verhaltensneutral ist

Solange die Enumeration (Phase B) nur `Process.myUserHandle()` liefert, tragen **alle**
`AppInfo.key` den `userSerial = 0`. Damit gilt für jede neue Naht:
`getUserForSerialNumber(0) == myUserHandle()`, der Paused-Profil-Guard greift nie
(der Primärnutzer ist immer verfügbar), und `key.flat` hat kein `@serial`. Die Nähte
sind da, aber inert — bis Phase D echte Work-Profile-Keys produziert.

> **DoD Phase C:** Launch/Presence/Package-Events sind user-parametrisiert
> (`UserManager`-Auflösung, `LauncherApps.Callback`), auf dem Primärgerät verhält sich
> nichts anders, alle Tests grün. Kein `@serial` wird erzeugt, keine `userProfiles`-
> Iteration.

---

## 1. Neuer Hilt-Provider `UserManager` (`app/di/AppModule.kt`)

```kotlin
@Provides
@Singleton
fun provideUserManager(@ApplicationContext context: Context): UserManager =
    context.getSystemService(UserManager::class.java)
        ?: error("UserManager service unavailable")
```
(`LauncherApps`-Provider existiert seit Phase B.)

---

## 2. Launch user-aware (`ui/main/AppLauncherImpl.kt`)

`AppLauncherImpl` hat heute `@Inject constructor()` ohne Deps und holt `LauncherApps`
aus `activity`. Minimal-invasiv bleibt es dabei und holt zusätzlich den `UserManager`
aus `activity` — kein Konstruktor-Umbau:

```kotlin
override fun launch(activity: Activity, appInfo: AppInfo): AppLaunchResult {
    val launcherApps = activity.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        ?: return AppLaunchResult.Failed(IllegalStateException("LauncherApps service unavailable"))
    val userManager = activity.getSystemService(UserManager::class.java)

    return runLaunchCatching {
        val componentName = ComponentName(appInfo.key.packageName, appInfo.key.className)
        // Serial → UserHandle; Serial 0 (Primär) ⇒ myUserHandle(). Profil weg ⇒
        // Fallback Primär (der Launch schlägt dann sauber als ComponentGone fehl).
        val user = userManager?.getUserForSerialNumber(appInfo.key.userSerial)
            ?: Process.myUserHandle()
        LaunchTrace.section(LaunchTrace.Names.START_MAIN_ACTIVITY) {
            launcherApps.startMainActivity(componentName, user, null, null)
        }
    }
}
```

- `ComponentName` kommt jetzt aus `appInfo.key` (das die Phase-A-Normalisierung
  trägt) statt aus `appInfo.normalizedClassName` — identischer Wert, eine Quelle.
- Für Serial 0 ist `user == myUserHandle()` → **bitgleiches Verhalten** wie bisher.

> **Naht MUE-INV-5 (Phase D aktiv):** gestartet wird im Nutzer des Keys. Hier schon
> verdrahtet, aber bis Phase D immer der Primärnutzer.

---

## 3. Presence user-aware (`data/service/PackagePresenceImpl.kt`)

Die Schnittstelle `PackagePresence.isComponentPresent(componentName: String)` bleibt
**string-getippt** (das gesamte Reconcile-Wiring — `FavoritesRepository`,
`HiddenAppsRepository`, `SwipeActionsRepository`, `ObserveInstalledAppsUseCase` —
übergibt `isStillPresent: suspend (String) -> Boolean`). Die User-Auflösung passiert
**innen**:

```kotlin
class PackagePresenceImpl @Inject constructor(
    private val launcherApps: LauncherApps,
    private val userManager: UserManager,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PackagePresence {

    override suspend fun isComponentPresent(componentName: String): Boolean =
        withContext(ioDispatcher) {
            try {
                val key = ComponentKey.parse(componentName) ?: return@withContext true // fail-closed
                val user = userManager.getUserForSerialNumber(key.userSerial)
                    ?: return@withContext true                                          // Profil weg ⇒ nicht prunen
                // Profil pausiert/gesperrt ⇒ als vorhanden behandeln (Phase-D-relevant,
                // primär nie wahr): NICHT prunen.
                if (isProfileUnavailable(user)) return@withContext true
                launcherApps.getActivityList(key.packageName, user)
                    .any { it.componentName.className == key.className }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                true   // fail-closed (Verhalten von heute: catch → true)
            }
        }

    // isPackagePresent(packageName): bleibt primär-basiert (CustomNames sind
    // package-granular). User-Granularität dort ist Phase D / §15-Produktfrage.
}
```

- **Behavior-neutral für Primär:** `parse` liefert Serial 0 → `user == Primär` →
  `getActivityList(pkg, primär)` matcht dieselbe Menge, die
  `queryIntentActivities(setPackage)` heute liefert (siehe §6-Caveat, wie in Phase B).
- **Paused-Guard schon drin, aber inert:** der Primärnutzer ist nie „unavailable"; die
  Zeile aktiviert sich erst mit Work-Profile-Keys in Phase D (MUE-INV-7).
- **Fail-closed erhalten:** `parse`-Fehler, Profil weg, `Throwable` ⇒ `true`
  (niemals fälschlich prunen).
- `packageManager` fällt aus dem Konstruktor (nach dem Tausch ungenutzt — prüfen).

`isProfileUnavailable(user)` = `!userManager.isUserUnlocked(user)` bzw.
`userManager.isQuietModeEnabled(user)` (API-Level in §7 prüfen).

---

## 4. Package-Events → `LauncherApps.Callback` (`KolibriLauncherApp.kt`)

Der `packageUpdateReceiver` (`ACTION_PACKAGE_ADDED/REMOVED/CHANGED`, `RECEIVER_EXPORTED`)
wird durch einen `LauncherApps.Callback` ersetzt:

```kotlin
private val launcherAppsCallback = object : LauncherApps.Callback() {
    override fun onPackageAdded(pkg: String, user: UserHandle)   = refreshApps()
    override fun onPackageRemoved(pkg: String, user: UserHandle) = refreshApps()
    override fun onPackageChanged(pkg: String, user: UserHandle) = refreshApps()   // enable/disable, AUDIT-19 F5
    override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean)   = refreshApps()
    override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = refreshApps()
    // onPackagesSuspended/Unsuspended: in Phase D relevant (Work-Profile), hier
    // ebenfalls refreshApps() oder no-op — refresh ist harmlos.
}

private fun registerPackageUpdateReceiver() {   // Name kann bleiben oder umbenennen
    try {
        (getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps)
            .registerCallback(launcherAppsCallback)
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "[LIFECYCLE] Could not register LauncherApps.Callback")
    }
}
// Symmetrisch: unregisterCallback beim Teardown-Pfad (wo heute der Receiver
// unregistriert würde — die App-scope-Registrierung lebt so lange wie der Prozess,
// aber der unregister-Pfad gehört dennoch gespiegelt).
```

`refreshApps()` = das bisherige `applicationScope.launch { repository.triggerAppsUpdate() }`.

- **Behavior-parity:** „irgendwas an Paketen hat sich geändert → neu enumerieren" —
  identischer Effekt wie der Broadcast-Receiver, nur über die Launcher-API und **mit
  `UserHandle` pro Event** (für Phase D nutzbar; primär ignoriert man ihn noch).
- Der `IntentFilter`/`addDataScheme("package")`-Block entfällt.

---

## 5. Was NICHT angefasst wird

- **Enumeration bleibt primär-only** (Phase B unverändert; **keine**
  `userManager.userProfiles`-Iteration — das ist Phase D).
- **Reconcile-Schnittstellen bleiben string-getippt** (`isStillPresent: (String)->Boolean`);
  keine Signatur-Änderung in Favorites/Hidden/Swipe/CustomNames.
- **Kein `@serial`** wird erzeugt (kein Nicht-Primär-Key existiert).
- **`isPackagePresent`** bleibt primär-basiert (CustomNames-User-Granularität = §15).
- **Badging** ist Phase D.

---

## 6. Verhaltens-Diff, der zu verifizieren ist

Wie in Phase B: `PackagePresenceImpl` wechselt intern von `queryIntentActivities`
auf `getActivityList(pkg, primär)`. Für den Primärnutzer dieselbe Menge — aber
**prüfen**: ein Reconcile-Testlauf, dass eine installierte Favoriten-App weiterhin als
`present` gilt und eine deinstallierte als `absent` (kein fälschliches Prunen). Der
fail-closed-Pfad (`catch → true`) fängt Unsicherheit ab.

---

## 7. Tests

- **JVM (Fakes):** `isComponentPresent`-Logik mit Fake-`UserManager`/`LauncherApps`:
  Serial 0 → Primär-Match; `parse`-Fehler → true; „Profil weg" → true;
  „Profil unavailable" → true (Paused-Guard, für Phase D vorgezogen); vorhanden →
  true, fehlt → false. Launch-User-Auflösung analog mit Fake-`UserManager`.
- **Robolectric/`androidTest`:** `LauncherApps.Callback`-Registrierung + Refresh-Trigger;
  `getActivityList(pkg, user)`-Anbindung; `startMainActivity` mit aufgelöstem `UserHandle`.
- **Bestehend:** Reconcile-Tests (fail-closed, R-INV) laufen unverändert grün — die
  Semantik ist identisch, nur die Presence-Quelle wechselt.
- **Dispatcher:** ein Dispatcher via `MainDispatcherRule`, kein separater `TestScope`.

---

## 8. Verifikation (Definition of Done)

```
grep -rn "queryIntentActivities" data/src/main/.../PackagePresenceImpl.kt   # → leer
grep -rn "ACTION_PACKAGE_ADDED\|packageUpdateReceiver" app/src/main          # → leer
grep -rn "registerCallback"      app/src/main                                # → KolibriLauncherApp
grep -rn "getUserForSerialNumber" app data                                   # → Launch + Presence
./gradlew :domain:test :data:test :app:test   # → grün
./tools/check-conventions.sh                    # → grün
```
Auf Gerät: App installieren/deinstallieren während offener App → Liste aktualisiert
(Callback greift); Favorit einer noch installierten App bleibt nach Reload erhalten.

---

## 9. Risiken

- **Presence-Set-Diff** (der einzige echte, wie Phase B): mit dem Reconcile-Testlauf
  aus §6 absichern, bevor `queryIntentActivities` aus `PackagePresenceImpl` fliegt.
- **`LauncherApps.Callback`-Lifecycle:** app-scoped Registrierung; den unregister-Pfad
  spiegeln, sonst Callback-Leak über Config-Changes hinweg (der alte Receiver hatte
  denselben Vertrag).
- **`isUserUnlocked`/`isQuietModeEnabled` API-Level** gegen min-SDK prüfen; primär ist
  der Guard ohnehin inert, aber die Referenz muss compilen.
- **`RECEIVER_EXPORTED` weg:** der Wechsel auf `LauncherApps.Callback` entfernt einen
  exportierten Receiver — minimal kleinere Angriffsfläche, kein Funktionsverlust.
