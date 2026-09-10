# PHASE_D_IMPL_NOTE — Work-Profile einschalten (Enumeration, `@serial`, Reconcile, Lifecycle, Text-Marker)

> Umsetzungs-Notiz zu `MULTIUSER_ENUMERATION_SPEC §13 Phase D`. Kolibri-standalone.
> Setzt A (strukturierter Key), B (`getActivityList`-Enumeration) und C (user-aware
> Plumbing) voraus. **Ab hier ändert sich Verhalten:** Work-Profile-Apps erscheinen,
> starten, überleben ein pausiertes Profil und werden textuell gekennzeichnet. Die in
> C eingezogenen, bis dato inerten Nähte werden aktiv.
>
> **Achtung Korrektur zu `MULTIUSER_ENUMERATION_SPEC §9`:** Kolibri ist ein
> **Text-Launcher** (Drawer/Favoriten rendern `displayName` als Text, keine App-Icons).
> Es gibt daher **kein Icon-Badging**; die Work-Profile-Kennzeichnung ist **textuell**
> (§6). Der Spec-§9 ist entsprechend zu lesen/zu korrigieren.

---

## 0. Was jetzt kippt

Bisher (A–C): nur Primärprofil enumeriert ⇒ alle Keys `userSerial = 0`, Nähte inert.
Phase D enumeriert **alle Profile** ⇒ es entstehen echte Nicht-Primär-Keys mit
`userSerial != 0` und `@serial`-Projektion. Damit werden aktiv: der Paused-Guard im
Reconcile (C §3), die Lifecycle-Events (C §4), die User-Auflösung beim Launch (C §2).

> **DoD Phase D:** Work-Profile-Apps erscheinen im Drawer (textuell gekennzeichnet),
> starten im richtigen Profil, ein pausiertes/gesperrtes Work-Profil pruned nichts,
> Ein-/Ausschalten des Work-Profils aktualisiert die Liste, Bestandsdaten/Backups
> laufen ohne Migration weiter.

---

## 1. Produktentscheidung zuerst (§15): CustomNames/Usage pro (Package, User)

**Empfehlung: pro (Package, User), nicht global pro Package.** Ein Work-Chrome und ein
privates Chrome sind zwei Einträge, die der Nutzer unabhängig umbenennen/bewerten will
— global pro Package würde „Chrome → Arbeits-Chrome" auf **beide** anwenden, genau im
Dual-Profil-Fall, für den Work-Profile-Support da ist.

Umsetzung: der **Package-Key** dieser beiden Stores trägt künftig den Serial, gespiegelt
zur Component-Projektion —
- Primär: `"com.android.chrome"` (unverändert, rückwärtskompatibel)
- Work: `"com.android.chrome@10"`

Damit bleibt der Store `Map<String,String>` / `Set<String>` (String-gekeyt), es gibt
**keine Migration** für Bestandsdaten (alles Primär = ohne `@serial`), und die
per-User-Isolation fällt automatisch an. `AppInfo` liefert diesen Key als
`key.packageName` + `key.userSerial` → kleine Helfer `key.packageFlat` (= `"pkg"` bzw.
`"pkg@serial"`).

> Wer **global pro Package** bevorzugt (einfacher, aber im Dual-Profil-Fall falsch),
> lässt CustomNames/Usage unverändert package-only — dann ist §8 hinfällig. Entscheiden,
> bevor §8 gebaut wird.

---

## 2. Enumeration über alle Profile (`data/InstalledAppsRepositoryImpl.kt`)

```kotlin
// VORHER (Phase B): nur Primär
val activities = launcherApps.getActivityList(null, Process.myUserHandle())

// NACHHER (Phase D): alle Profile, Serial in den Key
val activities = userManager.userProfiles.flatMap { user ->
    val serial = userManager.getSerialNumberForUser(user)
    launcherApps.getActivityList(null, user).map { it.toRawActivity(serial) }
}
buildAppInfos(activities)   // RawActivity trägt jetzt userSerial
```

- `RawActivity` bekommt `val userSerial: Long`; `buildAppInfos` reicht ihn in
  `AppInfo(..., userSerial = e.userSerial)` (Phase-A-Parameter). Ab hier existieren
  `@serial`-Keys.
- `InstalledAppsRepositoryImpl` injiziert zusätzlich `UserManager` (Provider aus C).
- Ein leeres/gesperrtes Work-Profil liefert einfach `emptyList` bei `getActivityList`
  (bzw. wirft nicht) — die Enumeration bleibt robust.

---

## 3. `@serial` wird real — Auswirkungen

- **Stores** (Favoriten/Hidden/Swipe) tragen für Work-Apps `"pkg/cls@serial"` in ihren
  `Set<String>`/`List<String>` — automatisch, weil `key.flat` es liefert. Kein
  Schema-Umbau.
- **Backup:** die exportierten Component-/Package-Strings enthalten für Work-Apps das
  `@serial`. Ein **älterer** Kolibri, der so ein Backup importiert, `parse`t die
  `@serial`-Keys nicht (unbekanntes Format → null → skip) statt zu crashen —
  Vorwärtskompat (MUE-INV-9). Aktueller Kolibri liest sie korrekt.
- **Migration Bestandsdaten:** weiterhin **keine** — alles Vorhandene ist Primär ohne
  `@serial`.

> **Serial-Stabilität:** `getSerialNumberForUser` ist über Neustarts stabil, aber ein
> **neu angelegtes** Work-Profil bekommt einen neuen Serial. Ein Backup vom alten
> Profil restauriert dann Keys, deren Serial es nicht mehr gibt → beim Reconcile als
> „Profil weg" behandelt (§4). Das ist korrekt und unvermeidbar; im Preview/Restore
> ggf. anmerken.

---

## 4. Reconcile: Paused-Guard aktiv + gelöschtes Profil

Der in C §3 vorgezogene Guard wird jetzt scharf:

> **MUE-INV-7 aktiv:** ein Key wird nur gepruned, wenn sein Profil **verfügbar** ist
> *und* die Activity dort fehlt. Pausiert/gesperrt (`isQuietModeEnabled` /
> `!isUserUnlocked`) ⇒ behalten.

**Design-Entscheidung „gelöschtes Profil" (bewusst konservativ):** wird ein Work-Profil
ganz **entfernt**, ist `getUserForSerialNumber(serial) == null`. `PackagePresenceImpl`
(C §3) liefert dann `true` (fail-closed) → die verwaisten Work-Favoriten/-Hidden werden
**nicht** automatisch gepruned. Begründung: die Reconcile-Doktrin prunt nie bei
Unsicherheit, und transiente Boot-/Umschaltzustände sähen sonst wie „gelöscht" aus. Die
Einträge sind inert (nicht startbar, Launch → `ComponentGone`) und manuell entfernbar.

> **Offen (Review):** Falls verwaiste Einträge eines *nachweislich* gelöschten Profils
> aktiv aufgeräumt werden sollen, bräuchte es ein separates, konservativ getriggertes
> „profile-removed"-Signal (`ACTION_MANAGED_PROFILE_REMOVED`) statt der
> Presence-Heuristik. Nicht in D — bewusst konservativ starten.

---

## 5. Profil-Lifecycle-Events

Die in C im `LauncherApps.Callback` gestubbten Verfügbarkeits-Methoden werden jetzt
wirksam, plus die profil-weiten Broadcasts:

- `onPackagesAvailable/Unavailable`, `onPackagesSuspended/Unsuspended` → `refreshApps()`
  (schon in C verdrahtet).
- Zusätzlich profil-weit: `ACTION_MANAGED_PROFILE_AVAILABLE` /
  `ACTION_MANAGED_PROFILE_UNAVAILABLE` (Quiet-Mode-Toggle) → `refreshApps()`. Diese sind
  **System-Broadcasts**, kein `LauncherApps.Callback`; einen schlanken Receiver dafür
  registrieren (oder, ab passendem API-Level, `UserManager`-Signale nutzen).

Effekt: Work-Profil an/aus im System → Drawer aktualisiert sich reaktiv.

---

## 6. Work-Profile-Kennzeichnung — **textuell** (Text-Launcher!)

Kein Icon-Badging (keine App-Icons). Stattdessen ein Text-Marker, **am Display-Fold**
appliziert (wie `applyCustomNames` reaktiv faltet — nicht in `AppInfo`-Identität):

- Minimal: `displayName` für Nicht-Primär-Keys mit einem lokalisierten Suffix/Glyph
  versehen, z. B. `"Chrome · Arbeit"` oder ein vorangestelltes Schloss-Glyph. Ein
  reiner Anzeige-Transform an derselben Stelle, an der Custom-Names gefaltet werden.
- Der Marker gehört **nicht** in `AppInfo.displayName` der Enumeration (sonst
  verschmutzt er Suche/Sort-Key inkonsistent) — sondern in den Render-/Fold-Schritt,
  analog zur Custom-Name-Faltung. Suche kann den Roh-Namen weiter matchen.

> **MUE-INV-10 (korrigiert):** Nicht-Primär-Einträge sind **textuell** als Work
> gekennzeichnet; es gibt kein Icon-Badge, weil Kolibri keine App-Icons rendert. (Der
> Spec-§9-Wortlaut „getUserBadgedIcon" ist für Kolibri gegenstandslos.)

Optional (Produkt): eine Settings-Toggle „Arbeits-Apps anzeigen/kennzeichnen" — passt
zu den bestehenden Sichtbarkeits-Toggles. Nicht zwingend für D.

---

## 7. Launch (bereits aus C — jetzt real)

`AppLauncherImpl` (C §2) löst `getUserForSerialNumber(appInfo.key.userSerial)` auf; für
Work-Apps ist das jetzt ein Nicht-Primär-Handle. Nichts weiter zu tun — nur verifizieren,
dass eine Work-App tatsächlich im Work-Profil startet.

---

## 8. CustomNames/Usage pro (Package, User) — Store-Key trägt Serial

Nur falls §1 = pro (Package, User) (empfohlen):

- CustomNames: `setCustomNameForPackage`/`getDisplayNameForPackage` etc. keyen künftig
  auf `key.packageFlat` (`"pkg"` bzw. `"pkg@serial"`) statt nacktem `packageName`.
- Usage: `KEY_USAGE_PREFIX + key.packageFlat` statt `+ packageName`.
- Deren **Reconcile** (`isStillPresent` bekommt heute einen Package-String): der String
  ist jetzt `packageFlat`; die Presence-Prüfung parst Serial ab, löst den User auf und
  nutzt `isPackagePresent(pkg, user)` (user-aware Variante ergänzen) inkl. Paused-Guard.
- **Migration:** keine — Bestandsdaten sind Primär (`"pkg"` ohne `@serial`).

> `isPackagePresent(packageName)` bekommt eine user-aware Überladung
> `isPackagePresent(packageName, user)`; die alte bleibt = Primär (Rückwärtskompat der
> Aufrufer).

---

## 9. Blast-Radius / was bleibt

- **Ändert sich:** `InstalledAppsRepositoryImpl` (userProfiles), `RawActivity` (+serial),
  `KolibriLauncherApp` (Profil-Broadcasts), Display-Fold (Text-Marker), optional
  CustomNames/Usage-Store-Keys (§8), ein neuer `MANAGED_PROFILE_*`-Receiver.
- **Bleibt:** `ComponentKey`/`AppInfo` (Phase A, `userSerial` war schon da), `flat`/
  `parse` (`@serial` schon spezifiziert), Presence-Logik + Launch (Phase C, nur jetzt
  scharf), alle Reconcile-Schnittstellen (string-getippt), das Backup-Schema
  (String-Keys tragen `@serial` automatisch).

---

## 10. Tests

- **JVM (Fakes):** Enumeration über einen Fake-`UserManager` mit zwei Profilen →
  `AppInfo`s mit korrektem `userSerial`/`@serial`; Reconcile: Work-Key bei
  *verfügbarem* Profil ohne Activity → prune, bei *pausiertem* Profil → behalten, bei
  *fehlendem* Serial → behalten (konservativ, §4); CustomNames/Usage-Isolation pro
  (pkg,user) (§8); Text-Marker-Fold (Roh-Name für Suche unverändert, Anzeige mit
  Suffix).
- **Robolectric/`androidTest`:** `userProfiles`-Enumeration, `MANAGED_PROFILE_*`-
  Receiver → Refresh, Launch im Work-Profil (soweit auf CI mit Test-Profil möglich;
  sonst manuell auf Gerät mit Work-Profil).
- **Manuell (Gerät mit Work-Profil):** Work-App erscheint+startet; Work-Profil pausieren
  → Favoriten bleiben; Work-Profil entfernen → Einträge bleiben inert (konservativ);
  privates + Work-Chrome unabhängig umbenennbar (§8).
- **Dispatcher:** `MainDispatcherRule`, kein separater `TestScope`.

---

## 11. Verifikation (Definition of Done)

```
grep -rn "userProfiles"           data/src/main      # → Enumerationsstelle
grep -rn "getSerialNumberForUser" data/src/main      # → Enumeration
grep -rn "MANAGED_PROFILE"        app/src/main       # → Profil-Lifecycle-Receiver
./gradlew :domain:test :data:test :app:test          # → grün
./tools/check-conventions.sh                          # → grün
```
Plus die manuellen Work-Profil-Checks aus §10.

---

## 12. Risiken

- **Verwaiste Einträge gelöschter Profile** (§4): konservativ = bleiben liegen. Bewusst;
  ggf. später `ACTION_MANAGED_PROFILE_REMOVED`-Cleanup.
- **Serial-Wechsel bei neu angelegtem Profil** (§3): alte Backup-Keys zeigen ins Leere →
  als „Profil weg" behandelt. Korrekt, aber im Restore-Preview erwähnenswert.
- **API-Level:** `userProfiles`, `getSerialNumberForUser`, `isQuietModeEnabled`,
  `MANAGED_PROFILE_*` gegen min-SDK prüfen (alle ≥ API 21/24; Kolibri darüber).
- **Perf:** `userProfiles.flatMap { getActivityList }` bei mehreren Profilen — den
  `drawer_apps_enumerate`-Trace erneut messen; sollte im Budget bleiben (nur additive
  Profile).
- **Text-Marker-Konsistenz:** den Marker nur im Anzeige-Fold setzen, nie im Sort-/
  Such-Schlüssel — sonst inkonsistente Sortierung/Suche.
