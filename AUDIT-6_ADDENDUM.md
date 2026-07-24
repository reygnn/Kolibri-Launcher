# AUDIT-6 Addendum — Zwei zurückgerollte Fixes (Post-Mortem)

> **Erzeugt** 2026-07-24, nach `AUDIT-6.md`.
> **Anlass:** Zwei der aus AUDIT-6 abgeleiteten Fixes (bzw. ihre
> Folge-Fixes) haben in `0.99.103`/`0.99.104` Regressionen ausgeliefert,
> die schlimmer waren als die *Low*-Findings, die sie beheben sollten.
> Beide wurden in **`0.99.105`** komplett zurückgerollt.
> **Zweck dieses Dokuments:** Das ursprüngliche Problem, jeden *nicht
> funktionierenden* Lösungsversuch und den jeweils dadurch eingeführten
> neuen Bug festhalten — damit dieselben Fallen nicht erneut betreten
> werden.

---

## TL;DR

| Fix | Ausgangs-Finding | Schwere | Ergebnis |
|-----|------------------|:-------:|----------|
| `deleteFile` → `Dispatchers.IO` (suspend) | Main-Thread-File-IO (StrictMode) | 🟢 Low | 2 Lösungsversuche, beide mit neuem Bug → **revert auf synchron** |
| ACRA-Custom-Data serialisieren | Telemetrie-Metadaten-Race zweier Reports | 🟢 Low | Lock → Executor, beide mit neuem Nachteil → **revert auf Original** |

**Rote Linie:** Beides waren *Low*, teils theoretische Findings in
**stabilem, empfindlichem Code**. Jeder „Fix" hat den Code perturbiert und
einen neuen Defekt (oder Tradeoff) eingeführt, den erst der *nächste*
Review fand — eine klassische Churn-Spirale. Die korrekte Antwort war am
Ende: **nichts von alledem** — zurück auf den lange stabilen Stand.

---

## Fix 1 — `deleteFile` off-main (`WallpaperFileManager` / `WallpaperDelegate`)

### Das Ausgangs-Finding (Review v0.99.104, #4 · Low)

`WallpaperFileManager.deleteFile(uri)` war synchron:

```kotlin
fun deleteFile(uri: Uri) {
    if (!isInternalUri(uri)) return
    val file = File(uri.path ?: return)
    if (file.exists() && file.delete()) { … }
}
```

Alle Aufrufer (`onRemoveWallpaperLayer`, Commit/Cancel-Schleifen, der
Add-Rollback-Discard) laufen in `launchSafe { … }` auf dem
`mainDispatcher`. Das synchrone `File.delete()` war also **blockierendes
File-IO auf dem UI-Thread** — StrictMode-sensibel.

**Wichtige, damals unterschätzte Eigenschaft:** Das Finding war *Low* und
das Muster war **bereits Bestand** (Commit/Cancel löschten seit jeher
synchron auf dem Main-Dispatcher). Es gab keinen Nutzer-Impact, nur eine
Policy-Verletzung.

### Lösungsversuch 1 — `suspend` + `withContext(IO)`  · Commit `d806b23`  · ❌

```kotlin
suspend fun deleteFile(uri: Uri) = withContext(Dispatchers.IO) { … }
```

Spiegelt `copyToInternal`. Kompiliert, alle Aufrufer sind Coroutinen,
Tests grün, Linter grün. **Ausgeliefert in 0.99.104.**

**Warum es fehlschlug (Review v0.99.104, #1 · correctness):**
`withContext(Dispatchers.IO)` gibt den single-threaded Main-Dispatcher
frei — es fügt jedem Aufrufer einen **Suspend-Punkt** hinzu. In
`onRemoveWallpaperLayer` lag dieser Punkt genau zwischen dem *Lesen* und
dem *Schreiben* von `_wallpaperState`:

```kotlin
val current = _wallpaperState.value            // READ
…
wallpaperFileManager.deleteFile(layerUri)      // SUSPEND (IO-Hop, Main frei)
val newState = current.withRemovedLayer(idx)   // aus STALE current
_wallpaperState.value = newState               // WRITE → clobbert
saveWallpaperStateUseCase(newState)
```

Während des IO-Hops konnte eine andere, auf dem Main-Dispatcher
eingereihte Coroutine (`onSaveLayerTransform`, oder der Observe-Collector
bei einer DataStore-Emission) `_wallpaperState` mutieren. `onRemove`
überschrieb diese Änderung anschließend mit dem aus dem *veralteten*
`current` abgeleiteten `newState` → **Lost Update**. Vorher (synchron) lief
die Methode atomar bis zum `save` — genau dieselbe Interleaving-Klasse wie
AUDIT-6 #2, hier über den neuen Suspend-Punkt reaktiviert.

### Lösungsversuch 2 — Reihenfolge umdrehen · Commit `71f8596` (nie gemergt) · ❌

Idee: das State-Read-Modify-Write atomar halten, indem `deleteFile` ans
Ende wandert (Löschen ist reihenfolgeunabhängige Aufräumarbeit):

```kotlin
val current = _wallpaperState.value
val layerUri = current.getLayer(idx)?.imageUri
val newState = current.withRemovedLayer(idx)
_wallpaperState.value = newState               // atomar bis hier
saveWallpaperStateUseCase(newState)            // SUSPEND
if (layerUri != null) {
    if (_isWallpaperEditMode.value) pendingRemovalsOnCommit.add(layerUri)
    else wallpaperFileManager.deleteFile(layerUri)
}
```

Fixte die Race — **führte aber einen neuen, schlimmeren Bug ein.** Der
`_isWallpaperEditMode`-Check und die `pendingRemovalsOnCommit`-Buchhaltung
lagen jetzt **hinter** dem suspendierenden `saveWallpaperStateUseCase`:

1. Layer L im Edit-Mode entfernen → State gesetzt, `save` **suspendiert**.
2. Nutzer tippt **Cancel** → Snapshot (mit L) wird wiederhergestellt,
   Edit-Mode → `false`.
3. `onRemove` resumt → `_isWallpaperEditMode.value` ist jetzt `false` →
   Else-Zweig → `deleteFile(L)`.
4. **Cancel hatte L im State wiederhergestellt, aber die Datei ist
   gelöscht.** Der State referenziert eine fehlende Datei — die
   *„Cancel stellt die Datei auf der Platte wieder her"*-Invariante der
   `WallpaperDelegate`-ARCHITECTURAL-NOTE ist verletzt. Schlimmer als die
   Lost-Update-Race, die dieser Versuch beheben sollte.

Dieser Versuch wurde **nie gemergt** — der Branch wurde verworfen.

### Auflösung — Revert auf synchron · Commit `40053a1` · ✅

`deleteFile` wieder synchron. Damit ist `onRemoveWallpaperLayer` in seiner
ursprünglichen Reihenfolge automatisch wieder **atomar** (kein Suspend-Punkt
zwischen Read und Write), ganz ohne Umstellung. Beide Bugs (die Race *und*
der Cancel-Defekt) verschwinden auf einen Schlag.

Das *Low*/StrictMode-Finding wird bewusst als **nicht behebenswert**
akzeptiert: es ist ein einzelnes `File.delete()`, und es entspricht ohnehin
dem bestehenden Commit/Cancel-Muster.

---

## Fix 2 — ACRA-Custom-Data serialisieren (`KolibriLauncherApp.AcraTree`)

### Das Ausgangs-Finding (AUDIT-6 #4 · Low)

`reportErrorToAcra` schrieb drei Pro-Report-Felder in den **prozess-globalen**
`ACRA.errorReporter` und rief danach `handleSilentException`:

```kotlin
ACRA.errorReporter.putCustomData("log_priority", …)
ACRA.errorReporter.putCustomData("log_tag", …)
ACRA.errorReporter.putCustomData("log_message", …)
ACRA.errorReporter.handleSilentException(t)     // snapshottet die Map
```

`putCustomData` mutiert die geteilte `customData`-Map, die
`handleSilentException` beim Report-Bau snapshottet. Der
`CrashReportLimiter` dedupt nach Exception-**Typ**, also passieren zwei
*verschiedene* Typen beide. Nebenläufig könnten sie ihre
`log_tag`/`log_message`/`log_priority` vertauschen.

**Wichtige, damals unterschätzte Eigenschaft:** Rein
**Telemetrie-Metadaten** — der Stacktrace ist per-call und immer korrekt.
Und `KolibriLauncherApp` ist per **CLAUDE.md Rule 7** ausdrücklich als
crash-safe-by-design markiert: *„do not clean it up or simplify"*.

### Lösungsversuch 1 — `synchronized`-Lock · Commit `81ac7f4` · ❌

```kotlin
synchronized(customDataLock) {
    putCustomData ×3
    handleSilentException(t)
}
```

**Warum es fehlschlug (Review v0.99.104, #2):** Der Lock wird über
`handleSilentException` gehalten — und ACRA baut den Report auf dem
**aufrufenden Thread** zusammen (Collector-Durchlauf). Ein nebenläufiges
`Timber.e` auf dem **Main-Thread** blockiert dann für die gesamte
Assembly-Dauer auf dem Lock. Für ein *Low*-Telemetrie-Finding wurde
Main-Thread-Kontention in die Crash-Infrastruktur eingebaut — gegen den
Geist von Rule 7.

### Lösungsversuch 2 — Single-Thread-Daemon-Executor · Commit `b0f77c1` · ❌

```kotlin
reportExecutor.execute {           // Executors.newSingleThreadExecutor, daemon
    putCustomData ×3
    handleSilentException(t)
}
```

Ein Thread → put+handle jedes Reports atomar (kein Lock nötig); der
Aufrufer kehrt sofort zurück (kein Main-Block). **Ausgeliefert in
0.99.104.** Löste die Punkte von Versuch 1 — **schuf aber neue:**

- **Reliability (async Verlust-Fenster):** Ein Report, der kurz vor dem
  Prozess-Tod geloggt wird (z. B. non-fataler `Timber.e`, dann ein fataler
  Crash über den globalen Handler → Prozess-Exit), wird u. U. nie
  assembled/persistiert — der Daemon-Thread stirbt mit der Task in der
  Queue. Vorher lief die Assembly synchron *vor* der Rückkehr von `log()`.
- **Efficiency (unbounded Queue):** `newSingleThreadExecutor` nutzt eine
  unbegrenzte `LinkedBlockingQueue`; ein Burst verschiedener
  Exception-Typen (die am Typ-Limiter vorbeikommen) reiht Throwables ohne
  Backpressure auf.
- **Erneut Rule 7:** Ein Hintergrund-Thread + async-Semantik in der
  Crash-Infra — für ein *Low*-Telemetrie-Finding.

### Auflösung — Revert auf das Original · Commit `e382599` · ✅

Zurück auf schlichtes `put×3 + handleSilentException` mit nur dem
ACRA-nicht-initialisiert-Failsafe-Catch. Kein Lock, kein Executor, keine
Companion. Die theoretische Telemetrie-Metadaten-Race wird bewusst als
**nicht behebenswert** akzeptiert.

---

## Muster & Lehren

1. **Reviews bei „high effort, err toward surfacing" finden *immer* etwas.**
   Zwangsläufig kommen *Low*-/theoretische Punkte zurück. Werden sie
   reflexartig gefixt, perturbiert man stabilen Code — und stabiler Code,
   den man anfasst, bricht manchmal.

2. **Jeder in ein Read-Modify-Write eingefügte Suspend-Punkt ist per
   Konstruktion eine Race.** Selbst eine „harmlose" off-main-Änderung
   (`deleteFile` → `withContext(IO)`) tut das *transitiv* bei allen
   Aufrufern. Vor dem Off-Loading einer bisher synchronen Operation:
   prüfen, ob ein Aufrufer zwischen einem State-Read und -Write steht.

3. **Rule 7 existiert genau für diesen Fall.** Die ACRA-Kette hat die
   Regel („Crash-Infra simpel halten, nicht aufräumen") zweimal ignoriert
   und sich damit zweimal in einen schlechteren Zustand manövriert.

4. **Fix-für-den-Fix ist ein Warnsignal.** Sowohl bei `deleteFile` als
   auch bei ACRA hat der jeweils *zweite* Versuch neue Probleme geschaffen.
   Wenn ein Fix einen Folge-Fix braucht, ist Zurückrollen oft billiger und
   sicherer als weiterzustapeln.

5. **Verwandter dritter Fall (nicht zurückgerollt, aber gleiche Klasse):**
   Der AUDIT-6-#2-Guard gegen einen mid-copy-Add (`editSessionToken`,
   `19b7aa8`) hat in 0.99.103 den **Commit-Fall** kaputt gemacht (eine
   gepickte Layer ging bei Commit-während-Kopie verloren), weil er *jede*
   Session-Grenze wie einen Rollback behandelte. Hier war der Folge-Fix
   sauber und blieb: `editRollbackGeneration` zählt **nur bei Cancel** hoch
   (`3f4cb56`). Lehre: Der ursprüngliche Guard war zu grob — die
   Unterscheidung Commit (behält State) vs. Cancel (restauriert State) ist
   fundamental.

6. **Neue Latte (Konsequenz):** In stabilem/empfindlichem Code nur noch
   **Medium+ Correctness mit echtem Nutzer-Impact** oder **trivial-und-sicher**
   anfassen. *Low*/theoretische Findings → dokumentieren, nicht anfassen.

---

## Was aus AUDIT-6 *drin* blieb (bewusst)

Nicht alles war schlecht — diese Fixes sind sauber, abgeschlossen und
verbleiben im Code:

- **#1 Layer-Remap** (`64ea6b2`) — echter Medium-Correctness-Bug
  (verrutschte Wallpaper-Layer bei Load-Fehler), id-basiertes Remap, gut
  JVM-getestet.
- **#2 Add-Session-Guard** in der *korrigierten* Form
  (`editRollbackGeneration`, `3f4cb56`) — unabhängig von `deleteFile`,
  schützt nur den `copyToInternal`-Suspend.
- **#3 Double-Click-Reset** (`bca8c3c`) — reine Logik, Einzeiler.
- **#5 Toast-Lokalisierung** (`dfe110c`).
- **remap-Helper `internal`** (`37038e3`).
