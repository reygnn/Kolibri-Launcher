# PHASE_A_IMPL_NOTE — Strukturierter `ComponentKey` einführen (additiv, verhaltensgleich)

> Umsetzungs-Notiz zu `MULTIUSER_ENUMERATION_SPEC §13 Phase A`. Kolibri-standalone.
> Ziel: die strukturierte Identität ins Domänenmodell bringen und die `flat`/`parse`-
> Projektion etablieren — **ohne** beobachtbare Verhaltensänderung, ohne
> Persistenz-/Store-Umbau, ohne Multi-User. Setzt nur die Naht, auf der Phase B–D
> aufsetzen.

---

## 0. Leitidee — additiv, nicht umschreibend

`AppInfo.componentName` (der flache `"pkg/cls"`-String) ist heute die de-facto-
Identität: ~30 Lesestellen (Set-Membership, `AppInfoDiffCallback`, Use-Cases) und
alle Stores (`Set<String>`/`Map<String,String>`/`List<String>`) hängen daran.

**Deshalb: nichts davon umtypen.** Phase A

- macht aus dem `object ComponentKey` (Validator) ein `data class ComponentKey`
  (Identität) mit `flat`/`parse`/`of`,
- gibt `AppInfo` einen **abgeleiteten** `key` + einen optionalen `userSerial`-
  Parameter (Default `0`),
- lässt `componentName` als `key.flat` bestehen — für `userSerial == 0` **exakt
  derselbe String** wie heute.

Alle Lesestellen, Stores und die Persistenz bleiben unverändert und
verhaltensgleich. Der `userSerial`-Parameter (Default 0) ist die einzige neue Naht;
in Phase B/D reicht die Enumeration dort den echten Serial herein.

> **DoD Phase A:** `ComponentKey` ist ein `data class`, jedes `AppInfo` hat `.key`,
> `grep` findet keinen `object ComponentKey` mehr, alle bestehenden Tests grün, kein
> DataStore-/Backup-Format geändert.

---

## 1. `core/ComponentKey.kt` — object → data class

```kotlin
// NACHHER (:domain, pure-JVM)
data class ComponentKey(
    val packageName: String,
    val className: String,     // absolut (Aufrufer normalisiert; s. AppInfo §2)
    val userSerial: Long = 0L, // 0 = Primärnutzer
) {
    /** Persistenz-/Backup-Projektion. Primär: "pkg/cls" (rückwärtskompatibel). */
    val flat: String =
        if (userSerial == 0L) "$packageName/$className" else "$packageName/$className@$userSerial"

    companion object {
        /** Wohlgeformter flacher ComponentName? (früher der object-Body, TODO §15). */
        fun isValid(value: String): Boolean {
            val sep = value.indexOf('/')
            return sep > 0 && sep < value.length - 1
        }

        /** Flache Projektion → Key; null = malformed. In Phase A wird '@' nie
         *  erzeugt (alles Primär), aber schon toleriert für Vorwärtskompat. */
        fun parse(value: String): ComponentKey? {
            if (!isValid(value)) return null
            val at = value.lastIndexOf('@')
            val core = if (at > 0) value.substring(0, at) else value
            val serial = if (at > 0) value.substring(at + 1).toLongOrNull() ?: 0L else 0L
            val sep = core.indexOf('/')
            return ComponentKey(core.substring(0, sep), core.substring(sep + 1), serial)
        }
    }
}
```

- Bestehende Aufrufe `ComponentKey.isValid(x)` (z. B. `FavoritesRepositoryImpl:96`)
  bleiben unverändert gültig (Companion-Funktion).
- KDoc des alten `object` (Format-Regel, TODO-§15-Kontext) übernehmen.

## 2. `domain/model/AppInfo.kt` — `key` ergänzen, `componentName` ableiten

```kotlin
data class AppInfo(
    val originalName: String,
    val displayName: String,
    val packageName: String,
    val className: String,
    val isFavorite: Boolean = false,
    val userSerial: Long = 0L,          // NEU, Default 0 → alle Call-Sites unverändert
) {
    val displayNameLower: String = displayName.lowercase()

    val normalizedClassName: String =    // unverändert: relative '.Cls' → absolut
        if (className.startsWith(".")) "$packageName$className" else className

    /** Kanonische Identität. Body-val ⇒ raus aus equals/hashCode/copy (wie bisher). */
    val key: ComponentKey = ComponentKey(packageName, normalizedClassName, userSerial)

    /** Projektion — für userSerial 0 identisch zum bisherigen Wert. */
    val componentName: String = key.flat
}
```

- `userSerial` ist ein **Konstruktor-Parameter mit Default 0**, damit alle heutigen
  `AppInfo(...)`-Aufrufe (InstalledAppsRepositoryImpl:265, GetFavoriteAppsUseCase:334,
  `toProvisionalAppInfo`, `SelectableAppInfo`-Wrapper, …) unverändert kompilieren.
- `componentName` bleibt ein Body-`val` (raus aus `equals`/`hashCode`) und liefert für
  Primärnutzer denselben String → `AppInfoDiffCallback`, Set-Membership, `distinctUntil-
  Changed` verhalten sich identisch.
- `normalizedClassName` speist den Key; die „single source of truth"-Normalisierung
  bleibt an genau einer Stelle.

> **Regressions-Anker (Test):** `AppInfo(...).componentName == "$pkg/$normalizedCls"`
> für `userSerial = 0` — pinnt, dass die Projektion den alten String exakt trifft.

---

## 3. Was NICHT angefasst wird (bewusst)

- **Kein Store wird umgetypt.** Favoriten/Hidden/Swipe (`Set<String>`/`List<String>`),
  CustomNames/Usage (`Map`/packageName) bleiben string-gekeyt — der String ist die
  Projektion (`key.flat`). Kein DataStore-Write, keine Migration.
- **Kein Backup-Format ändert sich** (alles Primär ⇒ `"pkg/cls"` wie gehabt).
- **Die ~30 `app.componentName`-Lesestellen** (SwipeActions/Onboarding/HiddenApps/
  Favorites-VMs, DiffCallbacks, `GetDrawerAppsUseCase`, `GetFavoriteAppsUseCase`, …)
  bleiben unverändert.
- **`AppLauncherImpl`** kann bleiben (`normalizedClassName` existiert weiter);
  optionaler Tidy: `ComponentName(appInfo.key.packageName, appInfo.key.className)`.

---

## 4. Optionale Tidies (NICHT Teil von Phase A — nur wenn eh in der Datei)

Reine Verschönerung, verhaltensgleich, kann warten:
- `substringBefore('/')`-Package-Extraktionen (`FavoritesRepositoryImpl:119/122/193`,
  `BackupDataAssembler:211/238`, `PackagePresenceImpl:36`, `ComponentLabelResolverImpl:37`,
  `ToggleFavoriteUseCase:54/55`) → `ComponentKey.parse(x)?.packageName`.
- `toProvisionalAppInfo` (`GetFavoriteAppsUseCase`) splittet den String manuell →
  könnte `ComponentKey.parse` nutzen. **Vorsicht:** hier muss der round-trip exakt
  bleiben (der Kommentar dort betont es); erst mit Test absichern, sonst lassen.

---

## 5. Migration

**Keine.** `userSerial == 0` ⇒ `key.flat == "pkg/cls"` == bestehender Wert. Alle
persistierten Keys und Backups sind per Konstruktion bereits gültig. Das `@serial`-
Suffix entsteht frühestens in Phase D.

---

## 6. Tests

- **Neu (JVM):** `ComponentKeyTest` — `flat` (Primär = "pkg/cls", `@serial`-Roundtrip
  via `parse`), `parse` (malformed → null, bare package → null [TODO §15], `@`-Suffix),
  `isValid` (Verhalten des alten object 1:1). `AppInfoTest` — `componentName ==` alter
  String für serial 0; `key` trägt normalisierten Klassennamen; `.key` bleibt aus
  `equals` (zwei AppInfo mit gleichem key aber anderem displayName sind `!=` wie bisher).
- **Bestehend:** alle ComponentName-/Favoriten-/Hidden-/Diff-Tests laufen **unverändert**
  grün (kein Ersetzen). Wenn einer bricht, ist die Projektion nicht exakt — Bug in §2.
- **Dispatcher:** unverändert; ein Dispatcher via `MainDispatcherRule`, kein separater
  `TestScope` (`TESTING_CONVENTIONS.kt`).

---

## 7. Verifikation (Definition of Done)

```
grep -rn "object ComponentKey" .            # → leer (ist jetzt data class)
grep -rn "\.componentName" app data domain  # → unverändert viele Treffer, alle kompilieren
./gradlew :domain:test :data:test :app:test # → grün
./tools/check-conventions.sh                # → grün
```
Plus: ein Debug-Build installieren, App öffnen, Favoriten/Hidden/Swipe prüfen —
nichts darf sich verhalten haben.

---

## 8. Risiken

- **Projektions-Drift** (der einzige echte): wenn `key.flat` für serial 0 nicht
  Byte-für-Byte dem alten `componentName` entspricht, brechen Set-Membership und
  DiffUtil-Identität. → Der Regressions-Anker in §2/§6 pinnt genau das; zuerst
  schreiben.
- **`@` im Klassennamen?** Kein legaler Java-Identifier enthält `@`, also ist das
  Suffix-Trennzeichen kollisionsfrei — trotzdem `parse` mit `lastIndexOf('@')` (nicht
  `indexOf`) gebaut, falls je exotische Strings auftauchen.
- **Naming:** `ComponentKey` ist jetzt Typ **und** hatte Companion-Funktionen —
  `ComponentKey.isValid(...)` bleibt gültig; keine Import-Änderung nötig.
