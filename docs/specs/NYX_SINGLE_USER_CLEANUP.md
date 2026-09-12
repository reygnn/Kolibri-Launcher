# NYX_SINGLE_USER_CLEANUP — `userSerial`/Multi-User aus Nyx entfernen

> Konkrete Patch-Liste zur Entscheidung „Single-User; keine Work-Profile-Unterstützung"
> (`MONOREPO_MERGE_SPEC §7`, MRG-INV-9). Nyx ist pre-release/greenfield → **keine
> Datenmigration nötig**. Vier Code-Stellen + eine Spec; zwei Dateien bleiben
> bewusst unverändert.

---

## 1. `domain/.../home/model/ComponentKey.kt`

Feld + „WHY userSerial"-Doc-Block entfernen.

```kotlin
// VORHER
/**
 * ...
 * == WHY userSerial ==
 * Present from v1 even though v1 only ever sets 0 (the primary user), so that
 * adding work-profile / cloned-app support later does NOT break the persisted
 * layout blob's schema. It carries the *serial* (a [Long]), never an Android
 * `UserHandle` — the domain stays framework-free (ICON_HOME_MODEL_SPEC §0.2,
 * IHM-INV-1).
 */
data class ComponentKey(
    val packageName: String,
    val className: String,
    val userSerial: Long = 0L,
)

// NACHHER
/**
 * Stable identity of a launchable activity.
 *
 * The durable "which app + activity is this" handle across DataStore round-trips
 * and re-installs of the target package: a placed item keeps its position while
 * its icon is re-resolved. See ICON_HOME_MODEL_SPEC §2.1.
 */
data class ComponentKey(
    val packageName: String,
    val className: String,
)
```

## 2. `data/.../home/HomeLayoutDto.kt`

`userSerial` aus dem serialisierten DTO entfernen.

```kotlin
// VORHER
@Serializable
data class ComponentKeyDto(
    val packageName: String,
    val className: String,
    val userSerial: Long = 0L,
)

// NACHHER
@Serializable
data class ComponentKeyDto(
    val packageName: String,
    val className: String,
)
```

> **Kompat-Hinweis:** Falls ein Entwickler bereits ein lokal persistiertes
> `HomeLayout`-Blob hat, muss der Layout-`Json` `ignoreUnknownKeys = true` haben
> (sonst wirft der Decode über das alte `userSerial`-Feld). Bei einem frischen
> Store irrelevant. Pre-release ⇒ im Zweifel App-Daten löschen.

## 3. `data/.../home/HomeLayoutMappers.kt`

Beide Mapper-Zeilen kürzen (der `userSerial`-Parameter fällt weg).

```kotlin
// VORHER
internal fun ComponentKey.toDto(): ComponentKeyDto =
    ComponentKeyDto(packageName, className, userSerial)
internal fun ComponentKeyDto.toDomain(): ComponentKey =
    ComponentKey(packageName, className, userSerial)

// NACHHER
internal fun ComponentKey.toDto(): ComponentKeyDto =
    ComponentKeyDto(packageName, className)
internal fun ComponentKeyDto.toDomain(): ComponentKey =
    ComponentKey(packageName, className)
```

## 4. `data/.../icon/IconCacheKey.kt`

`userSerial` aus den beiden Key-Zusammensetzungen (Zeile ~25 und ~43) entfernen.

```kotlin
// VORHER (of)
val content = listOf(
    key.packageName, key.className, key.userSerial.toString(),
    sizePx.toString(), variant.name, packId,
).joinToString("|")

// NACHHER
val content = listOf(
    key.packageName, key.className,
    sizePx.toString(), variant.name, packId,
).joinToString("|")

// VORHER (folder)
val content = members.joinToString("|") {
    "${it.packageName}/${it.className}/${it.userSerial}"
} + "@" + sizePx + if (monochrome) "#mono" else ""

// NACHHER
val content = members.joinToString("|") {
    "${it.packageName}/${it.className}"
} + "@" + sizePx + if (monochrome) "#mono" else ""
```

> **Cache-Hinweis:** Das ändert den Hash-Inhalt ⇒ bestehende On-Disk-Icon-Dateien
> werden stale (kein Treffer, harmloses Re-Compositing). Kein Handlungsbedarf.

## 5. Spec `docs/specs/ICON_HOME_MODEL_SPEC.md`

§2.1 (und der §0.2-Verweis): das `userSerial`-Feld + die „0 = primärer User"-Notiz
aus dem `ComponentKey`-Block streichen; ggf. eine Zeile „Single-User by design —
kein Work-Profile-Support (`MONOREPO_MERGE_SPEC` MRG-INV-9)" ergänzen.

```kotlin
// NACHHER (§2.1)
data class ComponentKey(
    val packageName: String,
    val className: String,
)
```

---

## Bewusst UNverändert

- **`data/.../home/InstalledAppsRepositoryImpl.kt`** — konstruiert `ComponentKey`
  schon ohne `userSerial` (nutzte den Default); `getActivityList(null,
  Process.myUserHandle())` ist korrektes Single-User-Verhalten. Keine Änderung.
- **`data/.../icon/LauncherAppsIconSource.kt`** — `getActivityList(key.packageName,
  Process.myUserHandle())` nutzt kein `userSerial`. Keine Änderung.
- **`app/.../PackageEventCoordinator.kt`** — die `LauncherApps.Callback`-Overrides
  tragen `user: UserHandle` (Android-Signatur, nicht entfernbar) und ignorieren ihn
  bereits. Keine Änderung.

## Verifikation

Nach den Patches muss folgendes leer sein (außer den unvermeidbaren
Android-Callback-Signaturen in `PackageEventCoordinator`):

```
grep -rn "userSerial" nyx/**/src        # → leer
grep -rn "userSerial" nyx/**/docs       # → leer
./tools/check-conventions.sh            # grün
```

## Folgefrage (nicht Teil dieses Cleanups)

Geteiltes Komponenten-Identitätsformat: Nyx' strukturiertes
`ComponentKey(packageName, className)` **oder** Kolibris flaches `"package/class"`-
String-Format (auf dem CustomNames/Hidden/Usage keyen)? Offen in
`MONOREPO_MERGE_SPEC §9`.
