# AUDIT-8 — Backup Import/Export: „Schrödingers Backup"

> **Erzeugt** 2026-07-25 durch einen Claude-Opus-4.8-Multi-Agent-Audit
> (Version 0.99.113 / code 133, Branch `main`).
> **Fokus:** ausschließlich die **Backup-Import/Export-Pipeline**. Leitfrage,
> aus den Zweifeln von AUDIT-2/-3/-7 destilliert: *Haben wir ein
> „Schrödingers Backup" — eines, das gleichzeitig kaputt und korrekt ist?*
> Anlass: die Backup-Funktionalität trägt **mehr Unit-Tests als eine
> Banking-App** (~259 `@Test`-Methoden über 17 Test-/Contract-/Fake-Dateien),
> und trotzdem haben mehrere frühere Audits ihre Korrektheit angezweifelt.
> **Methode:** Drei Agenten parallel — (1) Skalar-Settings-Round-Trip,
> (2) ZIP/Wallpaper-Multi-Layer-Round-Trip, (3) Test-Coverage-Reality-Check.
> Jeder Agent war angewiesen zu prüfen, ob die AUDIT-3-Funde seit ihrer
> Erstellung bereits behoben wurden (der „tote Kater lebt noch"-Test).
> **Diagnose + eine Test-Ergänzung** (siehe §Maßnahmen); der Produktivcode
> wurde nicht geändert.

---

## Executive summary

**Der Kater lebt. Die Box ist nur an einer Stelle blickdicht.**

Der *Produktionscode* der Backup-Pipeline ist **korrekt**. Alle drei
konkreten Zweifel aus AUDIT-3 waren zum Zeitpunkt ihrer Meldung **reale
Bugs** — und sind **längst behoben**:

| Fund | War real? | Status | Fix-Commit |
|---|---|---|---|
| **AUDIT-3 #3** — Strict-Fallback resettet Skalar-Settings still auf Default (camelCase-Write / snake_case-Lookup) | Ja | ✅ behoben | `b67c5b8` |
| **AUDIT-3 #8** — ZIP-Export stempelt doppelt referenzierte Layer auf nie geschriebene ZIP-Einträge (Orphan-Referenz) | Ja | ✅ behoben | `646e913` |
| **AUDIT-3 #10** — `toLayerState()` mit lauter `null`-IDs → ID-Kollision | Ja | ✅ behoben | `65bd54d` |

Ein normaler Export→Import-Round-Trip ist damit **verlustfrei**.

Die „gleichzeitig kaputt"-Hälfte des Paradoxons steckt **nicht im Code,
sondern in der Testsuite**: Sie ist grün und zahlreich — aber an der
heikelsten Stelle (Multi-Layer-Wallpaper-ZIP-Round-Trip) **komplett blind**,
und der Skalar-Schutz hängt an *einem einzigen künstlichen* Test. Die
Testzahl ist gegenüber dem realen Schutz aufgebläht. Das ist der Kern von
„Schrödingers Backup": ein grüner Testlauf, der über genau dem Pfad, der am
meisten Vertrauen ausstrahlt, blind ist.

---

## #1 — Skalar-Settings-Round-Trip · ✅ korrekt (war AUDIT-3 #3, behoben)

**Frage:** Überlebt beim Export→Import jedes Skalar-Setting, oder kippt der
Import sie still auf Default, während die Tests grün bleiben?

**Befund:** Verlustfrei, doppelt abgesichert.

- Die App **schreibt camelCase** (`BackupSerializer.kt:48-52` — `Json { }` ohne
  `namingStrategy`; `LauncherSettings` trägt nur `@JsonNames("snake_case")`,
  nie `@SerialName` → `@JsonNames` ist read-alias-only, encodiert die
  Property-Namen).
- Es gibt zwei org.json-Pfade. `tryStrictParsing` (voller Ersatz) feuert
  **nur**, wenn der kotlinx-Decode wirft — also nie auf wohlgeformtem
  App-Output. `mergeWithStrictValues` (`BackupSerializer.kt:205-220`) läuft
  **nach jedem** erfolgreichen Decode und legt `extractStrictSettings` über
  das Ergebnis.
- Der ursprüngliche Bug: `extractStrictSettings` las **nur snake_case** —
  also feuerte er auf dem eigenen camelCase-Output und kippte ~20 Skalare auf
  Default. Der Fix (`b67c5b8`) stellte auf **Kandidaten-Paare** um:
  `getStrictString("swipeLeftApp", "swipe_left_app")` etc., camelCase-first
  (`BackupSerializer.kt:324-353`, `vararg`-Helfer `:538-575`).
- Zusätzliche Absicherung: jedes Feld wird als
  `settings.getStrict…(…) ?: base.<field>` geschrieben — ein Null-Read fällt
  auf den kotlinx-Wert zurück, nie auf den Typ-Default.

**Verdict:** KORREKT. AUDIT-3 #3 sollte als RESOLVED (`b67c5b8`) markiert
werden.

---

## #2 — ZIP/Wallpaper-Multi-Layer-Round-Trip · ✅ korrekt (war AUDIT-3 #8/#10, behoben)

**Frage:** Werden bei einem mehrschichtigen Wallpaper beim Restore Layer
still verworfen?

**Befund:** Nein — für wohlgeformte Archive verlustfrei.

- **#8 (Orphan-Referenz):** `writeZipBackup` (`BackupRepositoryImpl.kt:196-265`)
  minted Entry-Namen nur innerhalb `entryByPath.getOrPut(file.absolutePath)`
  (`:210-214`) — jeder Name entsteht *gleichzeitig* mit seinem
  `imageEntries.add`. Zwei Layer auf derselben Datei teilen einen realen
  Eintrag; es kann kein `layer_N.img` gestempelt werden, das nie geschrieben
  wird. Fix `646e913`.
- **#10 (ID-Kollision):** `toLayerState()` (`BackupData.kt:59-74`) nutzt
  `id = id ?: WallpaperLayerState.newId()`, und `newId()`
  (`WallpaperState.kt:44-51`) hängt an `currentTimeMillis()` einen prozessweit
  monoton steigenden `AtomicLong` — eindeutig selbst bei N Layern in derselben
  Millisekunde. Fix `65bd54d`.
- **Restrisiko (kein Regressionsbug, by design):** Ein Layer, dessen ZIP-Eintrag
  **fehlt/beschädigt** ist, fällt in `resolveZipImages` (`BackupSerializer.kt:106-135`)
  auf seine stale Quell-`file://`-URI zurück, scheitert an der
  Accessibility-Probe in `importMultiLayerWallpaper` (`BackupRepositoryImpl.kt:411-427`)
  und wird **still per Warn-Log** verworfen (graceful degradation für korrupte
  Archive, nie für einen normalen Round-Trip). Siehe `ACCEPTED_LIMITATIONS.md`
  als Kandidat, falls dies je user-sichtbar werden soll.

**Verdict:** KORREKT. AUDIT-3 #8/#10 sollten als RESOLVED (`646e913`/`65bd54d`)
markiert werden.

---

## #3 — Test-Coverage-Reality-Check · 🟠 falsche Sicherheit (die „Schrödinger"-Stelle)

**Frage:** Beweisen die ~259 Tests echte Round-Trip-Treue, oder testen sie
beide Hälften isoliert / mit handgeschriebenen Fixtures, sodass ein echter
Datenverlust grün durchginge?

**Befund:** Ungleichmäßiger, teils irreführender Schutz.

- **Skalar camelCase/snake_case:** real gegen den behobenen Bug abgesichert —
  aber nur durch **einen einzigen künstlichen** Test
  (`BackupRepositoryImplStrictTest.kt:289`), der den Strict-Pfad erzwingt,
  indem er `"timestamp": "BREAK_ME"` setzt. Die ~250 anderen Import-Tests
  füttern **handgeschriebenes JSON** — oft mit snake_case-Keys, die die App
  real nie schreibt — und hätten den Bug nicht gefangen. Kein natürlicher
  Round-Trip betritt den Strict-Pfad.
- **Multi-Layer-Wallpaper-ZIP-Round-Trip:** **vor diesem Audit komplett
  ungetestet.** Grep über alle vier Test-Source-Sets nach `wallpaperLayers` /
  `WallpaperLayerBackup` / `resolveZipImages` / `imageFileName` fand **null**
  Backup-Test-Treffer. Der einzige echte-ZIP-androidTest
  (`BackupRoundTripWallpaperTest`) deckt nur den **Single-Layer**-Zweig ab.
  Genau die Export-Dedup-Logik und `resolveZipImages` — der Kern von
  AUDIT-3 #8 — hatte nirgends eine Assertion.
- **androidTest-Blindheit:** Der einzige echte Round-Trip-Schutz lebt in
  `androidTest`, das **nicht unter `./gradlew test`** läuft (und laut Projekt-
  Historie ~6 Monate gelöscht war). JVM-CI hätte einen Multi-Layer-Verlust nie
  gesehen.

**Die eine fehlende Assertion:** ein Round-Trip mit **≥2 befüllten
`wallpaperLayers`** → echtes ZIP → Re-Import → `restored.layers.size ==
original.size` + Per-Layer-Byte-Treue.

**Verdict:** FALSCHE SICHERHEIT bestätigt (partiell). Grün + zahlreich, aber
blind für den Multi-Layer-Pfad.

---

## Maßnahmen

1. **Neuer Test** `app/src/androidTest/.../data/BackupRoundTripMultiLayerWallpaperTest.kt`
   schließt die Kernlücke: zwei Tests —
   (a) 2-Layer-Round-Trip mit distinkten Bytes + Per-Layer-Metadaten
   (scale/translate/alpha/blend/visibility/label) und Kreuz-Verdrahtungs-Check;
   (b) Shared-File-Dedup (die AUDIT-3-#8-Regression) — beide Layer müssen
   überleben. Instrumented, weil der echte ZIP-Write + Byte-Copy nach
   `filesDir/wallpapers/` unter Robolectric gestubbt ist.
2. **Erledigt:** AUDIT-3 #3/#8/#10 in AUDIT-3.md mit Cross-Ref-Notizen als
   RESOLVED annotiert (`b67c5b8` / `646e913` / `65bd54d`), inkl. Verweis auf
   den neuen Test, der die #8/#10-Multi-Layer-Lücke schließt.

## Nicht-Ziele / bewusst nicht getan

- **Kein Produktivcode geändert.** Alle drei Correctness-Zweifel sind bereits
  gefixt; ein weiterer Eingriff wäre Regressionsrisiko ohne Nutzen.
- **Der Skalar-„Full-Field-Sweep"-Round-Trip** (jedes einzelne Setting statt
  einer Handvoll) bleibt offen als Kandidat — niedrigere Priorität, da der
  camelCase-Decode-Pfad strukturell verlustfrei ist. Notiert, nicht umgesetzt.
