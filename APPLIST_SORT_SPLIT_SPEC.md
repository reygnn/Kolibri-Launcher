# APPLIST_SORT_SPLIT_SPEC.md — den toten Sort auf dem Hot-Path entfernen (RAL-4)

**Status: ENTWURF v1 (2026-08-13), greenfield — noch nicht gebaut.** Dies ist die
ausgebaute Form von `SPEC-DECISION RAL-4` (REACTIVE_APPLIST_SPEC.md §11), das
bisher nur als „offen, bewusst nicht verfolgt" dastand. Fokus: den in
`applyCustomNames` gebündelten terminalen `sortedBy` für die drei
selbst-nachsortierenden Hot-Path-Consumer (Drawer / Favoriten / Recents)
entfernen, **ohne** die RAL-1-Invariante zu fragmentieren oder eine stille
Fehlsortierung für künftige Consumer zu riskieren.

> **Erst reviewen, dann bauen. Nicht auf `main` mergen, bevor §3
> (Tie-Break-Determiniertheit) und §6 (Test-Impact) bestätigt sind.** Der
> gefährliche Teil ist NICHT die Codeänderung (drei Call-Sites, ein neuer
> Helfer), sondern der Nachweis, dass der Sort für Drawer/Favoriten wirklich
> tot ist und für Recents nur mit einem expliziten Tie-Break tot *wird*.

**Vorgeschichte (die Entscheidung wurde 3× vertagt, nicht aus Unwissen):**
- `SPEC-DECISION RAL-1a` (REACTIVE_APPLIST §11, volle Begründung im
  `applyCustomNames`-KDoc): der Sort bleibt gebündelt, ein Split fragmentiert die
  RAL-1-Invariante „Quell-Grenze liefert die sortierte Post-Condition" für
  Wert ≈ 0. Vertagt AUDIT-14 F1 §5.3, geschlossen AUDIT-15 F3.
- **Was sich seither geändert hat:** der `mapOnly`-JMH-Arm
  (`ApplyCustomNamesBenchmark`) hat den Sort **gemessen** — bei 200 Apps ist er
  ~9,7 µs von ~15,8 µs, also **~62 %** der Funktionskosten, nicht der kleine
  Schwanz. Das kippt die Entscheidung *nicht* (62 % einer vernachlässigbaren
  Größe bleibt vernachlässigbar, off-Main hinter dem DataStore-Read). Dieser
  Spec existiert also NICHT, weil die Zahl das Urteil umgeworfen hätte, sondern
  weil der Nutzer die andere Hälfte der Eskape-Klausel zieht: „*unless
  applyCustomNames is being split for some OTHER reason anyway*". Wenn wir den
  Split ohnehin sauber spezifizieren, fährt die Dead-Sort-Entfernung gratis mit —
  und der Spec ist der Preis dafür, dass „sauber" heißt: keine Invarianten-
  Fragmentierung, kein maskierter Verhaltens-Change.

Schwester-Dokumente: **`REACTIVE_APPLIST_SPEC.md`** (definiert RAL-1/RAL-1a/RAL-4
und die Quell-Grenzen-Inventur §3), der **`applyCustomNames`-KDoc** (die
RAL-1a-Langbegründung + jetzt der Messwert), und
**`ApplyCustomNamesBenchmark.kt`** (`mapOnly`-Arm = die Messung).

---

## 0. Das Problem in einem Satz

`applyCustomNames(apps, names)` endet auf `.sortedBy { it.displayNameLower }`.
Drei Consumer rufen es direkt in ihrem eigenen Transform und **sortieren sofort
danach selbst neu** — für sie ist dieser Sort tote Arbeit, die auf jedem
`settingsDataStore`-Write mitläuft (AUDIT-14 F1). Wir wollen ihn genau dort
weglassen, ohne ihn dort zu verlieren, wo er tragend ist.

---

## 1. Zielbild — zwei Helfer, ein Map-Kern

```kotlin
// Der Map-Kern: reine Namensauflösung, KEINE Ordnungs-Post-Condition.
// Der `Unsorted`-Suffix ist die laute Warnung: wer mich ruft, sortiert selbst.
fun applyCustomNamesUnsorted(apps: List<AppInfo>, names: Map<String, String>): List<AppInfo> =
    apps.map { it.copy(displayName = names[it.packageName] ?: it.originalName) }

// Die RAL-1-Grenze: Namensauflösung MIT sortierter Post-Condition.
// Delegiert an den Kern → EINE Map-Implementierung (keine RAL-3-Drift).
// BLEIBT der Default-Name → wer den offensichtlichen Helfer greift, bekommt
// die sichere sortierte Garantie gratis.
fun applyCustomNames(apps: List<AppInfo>, names: Map<String, String>): List<AppInfo> =
    applyCustomNamesUnsorted(apps, names).sortedBy { it.displayNameLower }
```

**Warum diese Form die RAL-1a-Einwände neutralisiert (der ganze Grund für den Spec):**

1. **Der sichere Helfer behält den Default-Namen.** `applyCustomNames`
   (sortiert) ist weiter der Name, den man reflexartig greift. Ein künftiger
   Site-N-Consumer, der ihn nimmt, erbt RAL-1s sortierte Post-Condition
   automatisch. Die Optimierung ist **explizites Opt-in** über den längeren,
   „gefährlicheren" Namen — nicht der Default. Das dreht das Risiko um: der
   Default ist sicher, der schnelle Pfad ist die bewusste Ausnahme.
2. **Eine Map-Implementierung.** Sortiert delegiert an unsortiert; die
   Namensauflösungs-Zeile existiert genau einmal. Das killt die Drift-Sorge
   (RAL-3-Klasse: zwei Kopien, die auseinanderlaufen).
3. **Der Hazard steht laut im Namen.** `Unsorted` im Bezeichner IST die Warnung.
   Ein Aufruf ist eine Behauptung „ich sortiere downstream".
4. **Opt-in nur an nachweislich sicheren Sites**, jede mit einer
   Re-Sort-Assertion (§2), die auf ihre eigene Downstream-Sortierzeile zeigt.
5. **Tests pinnen die finale Ordnung jedes Consumers** (§6) — das Sicherheitsnetz
   gegen die Maskierungs-Umkehr aus §4.

---

## 2. Scope — wer umzieht, wer NICHT, und warum

`applyCustomNames` hat fünf Call-Sites (REACTIVE_APPLIST §3-Inventur). Nur drei
sind saubere Ziele.

| Site | Consumer | Sort danach? | RAL-4-Ziel? |
|---|---|---|---|
| Site 2 | `GetDrawerAppsUseCase.sortVisibleApps` | alpha **oder** time-weighted, sofort | **JA** — `Unsorted` |
| Site 2 | `GetFavoriteAppsUseCase.processApps` | `sortFavoriteComponents(savedOrder)` | **JA** — `Unsorted` |
| Site 3 | `GetRecentAppsUseCase` | Recency-Order (+ §3-Fix) | **JA** — `Unsorted` |
| Site 1 | `GetInstalledAppsUseCase` (geteilt) | gemischt (s.u.) | **NEIN** — bleibt sortiert |
| — | `GetOnboardingAppsUseCase` | nur `.filter{}` (ordnungserhaltend) | **NEIN** — bleibt sortiert |

**Warum Site 1 NICHT umzieht — der entscheidende Nicht-Zug.**
`GetInstalledAppsUseCase` ist **ein geteilter Flow für vier Consumer**:
- `CustomNamesViewModel` (`collect { fullyProcessedList }`) — **verlässt sich** auf den Sort.
- `SettingsViewModel` (`collect { apps }`, kein eigenes `sortedBy`) — **verlässt sich**.
- `HiddenAppsViewModel` (`.sortedBy { displayNameLower }` auf der Master-Liste) — sortiert selbst.
- `SwipeActionsViewModel` (`.sortedBy { displayNameLower }` auf der Master-Liste) — sortiert selbst.

Würde man Site 1 auf `Unsorted` stellen, wäre die Bilanz **netto null, aber
korrektheits-negativ**: Hidden/Swipe sparen je einen Sort (−2), aber CustomNames
und Settings müssten je einen hinzufügen (+2) — und die Sortier-Pflicht wäre
dann in zwei VMs dupliziert statt einmal im geteilten Flow. Mehr Sorts sind es
nicht, aber zwei neue Stellen, an denen ein VM das Nachsortieren vergessen kann.
Also: **Site 1 bleibt sortiert.** Das ist die richtige Grenze, kein Kompromiss.

**Konsequenz — Hidden/Swipe behalten ihren toten Upstream-Sort.** Sie zahlen
weiter den Site-1-Sort *und* ihren eigenen. Das ist strukturell hinter dem
geteilten Flow eingesperrt (er muss für CustomNames/Settings sortiert bleiben)
und liegt bewusst außerhalb dieses Specs — der Preis des Teilens, nicht ein
übersehener Gewinn. (Beide VMs öffnen sich nur auf Nutzer-Aktion, kalter Pfad.)

**Onboarding:** `applyCustomNames(load.apps, names).filter { … }` — `filter`
erhält die Reihenfolge, verlässt sich also auf den Sort. Bleibt `applyCustomNames`.

---

## 3. Der Kern-Nachweis: ist der Sort für die drei Ziele WIRKLICH tot?

Das ist die eigentliche Arbeit des Specs. „Selbst-nachsortierend" reicht nicht —
die Frage ist, ob der Downstream-Sort **total** ist oder heimlich die
Input-Reihenfolge (aus dem Alpha-Sort) als Tie-Break / First-Wins nutzt. Ein
nicht-totaler Downstream-Sort würde beim Umstieg auf `Unsorted` still das
Verhalten ändern (die Maskierungs-Umkehr, §4).

### 3.1 Drawer — sicher, kein Fix nötig
- **ALPHABETICAL:** `visibleApps.sortedBy { it.displayNameLower }` — total,
  input-unabhängig. ✓
- **TIME_WEIGHTED_USAGE:** `AppUsageRepositoryImpl.sortAppsByTimeWeightedUsage`
  sortiert `compareByDescending { score }.thenBy { it.displayNameLower }` —
  **expliziter** Tie-Break auf `displayNameLower`. Score-Gleichstände brechen
  also schon heute deterministisch alphabetisch, NICHT über die Input-Ordnung.
  Fallback-Pfad ist `apps.sortedBy { displayNameLower }` — ebenfalls total. ✓

Drawer hängt an keiner Stelle von der Input-Reihenfolge ab → `Unsorted` ist
verhaltensneutral.

### 3.2 Favoriten — sicher, kein Fix nötig
`FavoritesOrderRepositoryImpl.sortAppsWithGivenOrder`:
- Geordneter Teil: getrieben von `order` über `componentName`-Lookup — input-unabhängig.
- Rest-Anhang: `.filterNot { … in consumed }.sortedBy { displayNameLower }` — total.
- `order.isEmpty()` und alle Fehler-Fallbacks: `sortedBy { displayNameLower }` — total.
- Einzige Input-Order-Abhängigkeit: `putIfAbsent(componentName, app)` bei **zwei
  Apps mit identischem `componentName`** — ein malformed Zustand, dessen Dedupe
  bereits als gewolltes Verhalten dokumentiert und getestet ist. `componentName`
  ist per Konstruktion eindeutig, also im Normalbetrieb nie getroffen. ✓

Favoriten → `Unsorted` verhaltensneutral (bis auf den malformed-Duplikat-Edge,
der schon heute undefiniert-aber-dedupet ist).

### 3.3 Recents — EIN echter maskierter Effekt, braucht einen expliziten Tie-Break
`GetRecentAppsUseCase`:
```kotlin
val visibleByPackage = LinkedHashMap<String, AppInfo>()
for (app in currentApps) {                       // currentApps = applyCustomNames(...) → heute alpha-sortiert
    if (app.componentName !in hidden) visibleByPackage.putIfAbsent(app.packageName, app)
}
recentPackages.mapNotNull { visibleByPackage[it] }.take(limit)
```
`putIfAbsent(packageName, app)` behält die **erste** `AppInfo` pro **Paket**. Ein
Paket kann mehrere launchbare Activities haben (mehrere `AppInfo`, gleiches
`packageName`, verschiedene `className`). „Erste" = Iterationsreihenfolge von
`currentApps`. Heute alpha-sortiert → die alphabetisch erste Activity gewinnt,
**deterministisch**. Der Code-Kommentar sagt es selbst: „*putIfAbsent keeps that
deterministic*" — die Determiniertheit kommt aus dem Alpha-Sort. Mit `Unsorted`
entscheidet die Enumerations-Reihenfolge (rawApps / PackageManager) → für
Multi-Activity-Pakete ein **realer, wenn auch kosmetischer** Verhaltens-Change.

**Behebung (verhaltenserhaltend, ersetzt den O(N·log N)-Sort durch O(1)/Kollision):**
```kotlin
val visibleByPackage = HashMap<String, AppInfo>()     // Iterations-Order egal: finale Order kommt aus recentPackages
for (app in currentApps) {
    if (app.componentName in hidden) continue
    val existing = visibleByPackage[app.packageName]
    // Determiniert die alphabetisch erste Activity pro Paket — input-unabhängig,
    // reproduziert die heutige (vom Alpha-Sort gelieferte) Auswahl ohne die Liste
    // zu sortieren.
    if (existing == null || app.displayNameLower < existing.displayNameLower) {
        visibleByPackage[app.packageName] = app
    }
}
recentPackages.mapNotNull { visibleByPackage[it] }.take(limit)
```
Die finale Reihenfolge kommt ohnehin aus `recentPackages` (Recency), also ist die
interne Map-Ordnung irrelevant → `LinkedHashMap` wird zu `HashMap`. Der einzige
ordnungssensible Punkt (welche Activity ein Paket repräsentiert) ist jetzt
**explizit** alphabetisch, nicht implizit über den Input.

> **Alternative (falls der Fix zu viel ist):** den Multi-Activity-Auswahl-Change
> akzeptieren und in `ACCEPTED_LIMITATIONS.md` notieren (Re-Eval-Trigger:
> Nutzer meldet „falsches Icon/Label" für ein Mehr-Activity-Paket in Recents).
> Empfehlung dieses Specs: **den Fix nehmen** — er ist drei Zeilen, streng
> billiger als der Sort und lässt „verhaltensneutral" wörtlich stimmen.

---

## 4. Die Maskierungs-Umkehr (warum §6-Tests Pflicht sind)

Heute **maskiert** der gebündelte Sort einen latenten Bug: würde man aus Drawer
versehentlich den Downstream-Sort entfernen, käme die Liste immer noch
alpha-sortiert heraus (aus `applyCustomNames`) — der Fehler bliebe unsichtbar.
Mit `Unsorted` produziert derselbe Fehler **sichtbar unsortierten** Output.

Das schneidet in beide Richtungen:
- **Pro Split:** eine Maskierungs-Schicht verschwindet; echte Bugs werden lauter.
- **Contra / Risiko:** der Split könnte eine *heute maskierte* Abhängigkeit
  **freilegen**. §3 hat genau danach gesucht und genau eine gefunden (Recents).
  Aber der Beweis ist nur so gut wie die Tests, die die finale Ordnung
  **vor** dem Umstieg festnageln.

Deshalb ist die Reihenfolge in §5 nicht verhandelbar: erst die
Ordnungs-Assertions, dann der Helfer-Split, dann der Recents-Fix.

---

## 5. Migrationsreihenfolge (je ein revertierbarer Commit)

1. **Ordnungs-Assertions zuerst (rot-machbar).** Für jeden der drei Consumer je
   ein Test, der die finale Ausgabe-Reihenfolge an einer Eingabe mit
   nicht-trivialer Ordnung pinnt:
   - Drawer alpha **und** time-weighted (inkl. Score-Gleichstand → alpha-Tie-Break).
   - Favoriten mit partieller `savedOrder` (geordneter Kopf + alpha-Rest).
   - Recents mit einem **Multi-Activity-Paket** (die alphabetisch erste Activity
     muss das Paket repräsentieren) — dieser Test ist rot, sobald man naiv auf
     `Unsorted` stellt, und grün nach dem §3.3-Fix. Er IST der Nachweis.
2. **Den Split einführen, verhaltensneutral.** `applyCustomNamesUnsorted`
   hinzufügen; `applyCustomNames` an den Kern delegieren lassen. Noch **kein**
   Call-Site-Wechsel. Bestehende Tests bleiben grün (der Default ist byte-gleich).
3. **Recents-Fix (§3.3).** `putIfAbsent` → expliziter alphabetischer Tie-Break,
   `LinkedHashMap` → `HashMap`. Test aus Schritt 1 bleibt grün — beweist, dass
   der Fix die vom Sort gelieferte Auswahl reproduziert, *bevor* der Sort weg ist.
4. **Die drei Call-Sites umstellen** auf `applyCustomNamesUnsorted`, jede mit
   einer Re-Sort-Assertion im Kommentar, die auf ihre Downstream-Sortierzeile
   zeigt. Die Schritt-1-Tests sind der Wächter: bleiben sie grün, ist der Sort
   nachweislich tot gewesen.
5. **Doku angleichen:** `applyCustomNames`-KDoc (RAL-1a → „gelöst via RAL-4,
   siehe Spec"), REACTIVE_APPLIST §11 (RAL-4 → gelöst), die drei „thin pointer
   back"-Kommentare aktualisieren, `mapOnly`-Arm-KDoc ergänzen.

Schritte 2–4 sind einzeln grün und revertierbar. Der einzige verhaltensberührende
Schritt ist 3 (und der ist durch den Schritt-1-Test abgesichert).

---

## 6. Test-Impact

- **Neu (Pflicht, Schritt 1):** die drei Ordnungs-Assertions oben. Der
  Recents-Multi-Activity-Test ist der wertvollste — er ist der einzige, der ohne
  den §3.3-Fix rot würde, und pinnt damit die einzige reale Verhaltens-Kante.
- **Bestehend, muss grün bleiben:** `ApplyCustomNamesBenchmark` (unverändert;
  `mapOnly` misst jetzt genau den Pfad, den drei Consumer nehmen),
  `FavoritesOrderRepositoryImplTest` (Dedupe-Verhalten), die Drawer/Favoriten/
  Recents-UseCase-Tests.
- **Kein neuer `checkConventions`-Gate nötig:** der Split fügt keine `combine`/
  `.catch`/broad-catch-Sites hinzu; `applyCustomNamesUnsorted` ist eine reine
  Funktion ohne Suspension. (Falls ein Call-Site-Umbau eine bestehende Catch in
  einen Suspend-Frame zöge — tut er nicht — wäre `scanCancelCandidates` der
  Trigger. Hier n/a.)

---

## 7. Ehrlich: was dieser Spec NICHT einlöst

- **Kein spürbarer Performance-Gewinn.** ~9,7 µs × 3 Consumer, off-Main, hinter
  dem DataStore-Read. Der Wert ist **Klarheit** (tote Arbeit benannt und weg,
  eine Maskierungs-Schicht entfernt), nicht Geschwindigkeit. Wer den Spec als
  Performance-Fix verkauft, hat ihn falsch gelesen — das war die ganze
  RAL-1a-Begründung, und sie bleibt wahr.
- **Hidden/Swipe bleiben auf ihrem toten Upstream-Sort** (§2, hinter dem
  geteilten Flow eingesperrt).
- **`AppInfo` unverändert**, `getInstalledApps`-Vertrag unverändert, keine neue
  Schicht, keine erfundene API.
- **RAL-1 wird NICHT aufgeweicht.** Die sortierte Post-Condition bleibt der
  Default-Helfer; nur drei nachweislich-nachsortierende Sites opten explizit aus.

---

## 8. Offene Entscheidungen (für die Review-Runde)

- **RAL-4-a — Recents-Fix vs. akzeptierte Limitation (§3.3).** Empfehlung: Fix.
  Bestätigen oder auf `ACCEPTED_LIMITATIONS.md` umschwenken.
- **RAL-4-b — Helfer-Naming.** `applyCustomNamesUnsorted` vs. z.B. ein
  `sort`-Parameter (`applyCustomNames(apps, names, sort = false)`). Dieser Spec
  wählt **zwei benannte Funktionen**, weil der Default-Name dann die sichere
  Variante bleibt (§1.1) — ein Boolean-Default `sort = true` täte das auch, aber
  ein `false` am Call-Site ist leiser als ein `Unsorted`-Name. Bestätigen.
- **RAL-4-c — lohnt es sich überhaupt?** Der Spec ist bewusst so geschrieben,
  dass die Antwort „nein, nicht als eigenständige Änderung" legitim bleibt: der
  Gewinn ist Klarheit, nicht Speed. Wenn die Review zum Schluss kommt „nicht
  jetzt", wird dieser Spec der vierte dokumentierte Deferral — aber dann mit
  einem fertigen Bauplan, falls `applyCustomNames` je aus anderem Grund
  angefasst wird (die Eskape-Klausel greift dann sofort).
