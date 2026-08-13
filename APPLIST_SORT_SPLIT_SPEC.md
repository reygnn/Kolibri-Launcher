# APPLIST_SORT_SPLIT_SPEC.md — den toten Sort auf dem Hot-Path entfernen (RAL-4)

**Status: ENTWURF v3 (2026-08-13), greenfield — noch nicht gebaut. VERDIKT: ein
viertes Mal vertagen (§8, review-bestätigt) — dies ist ein fertiger Bauplan,
keine anstehende Änderung.** Review-Runde 2 (Multi-Agent, 5 Linsen je gegen den
Code verifiziert) eingearbeitet: der §3.2-Favoriten-Beweis war nachweislich
falsch (zwei Fehler-Fallbacks geben unsortiert zurück, M1); der Deadness-Guard
für TIME_WEIGHTED/Favoriten liegt auf Impl-Test-Ebene, nicht Use-Case (M2); die
Recents-Fixture muss nicht-alpha-erst sein, sonst beweist sie nichts (M3);
`SettingsViewModel` ist ordnungs-agnostisch, nicht sort-abhängig (M4); plus
Minors (jmh-Sichtbarkeit, `mapOnly`-Drift, „geteilter Flow"). Runde 1
(Architektur-Selbstprüfung) hatte die `internal`-Variante (§1), das
Recents-Reframe (§3.3) und die abgelehnte Inverse (§8) gebracht.

Dies ist die ausgebaute Form von `SPEC-DECISION RAL-4` (REACTIVE_APPLIST_SPEC.md
§11), das bisher nur als „offen, bewusst nicht verfolgt" dastand. Fokus: den in
`applyCustomNames` gebündelten terminalen `sortedBy` für die drei
selbst-nachsortierenden Hot-Path-Consumer (Drawer / Favoriten / Recents)
entfernen, **ohne** die RAL-1-Invariante zu fragmentieren oder eine stille
Fehlsortierung für künftige Consumer zu riskieren.

> **Erst reviewen, dann bauen. Nicht auf `main` mergen, bevor §3
> (Tie-Break-Determiniertheit) und §6 (Test-Impact) bestätigt sind.** Der
> gefährliche Teil ist NICHT die Codeänderung (drei Call-Sites, ein neuer
> Helfer), sondern der Nachweis, dass der Sort für Drawer/Favoriten wirklich
> tot ist und für Recents nur mit einem expliziten Tie-Break tot *wird*.
> **Runde 2 hat genau hier einen falschen Satz gefunden** (M1, §3.2) — das Gate
> hat funktioniert, aber es zeigt, wie leicht ein tragender „total"-Satz kippt.

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
// `internal` zum :domain-Modul: die Warnung ist NICHT bloß der `Unsorted`-Name,
// sie ist die Sichtbarkeit — alle drei Opt-in-Sites sind :domain-Use-Cases, also
// braucht die Variante keine modulübergreifende Sichtbarkeit, und der :app-Layer
// KANN sie nicht greifen. Der gefürchtete Fehlgriff wird compiler-erzwungen
// unmöglich, nicht nur konvention-unwahrscheinlich.
internal fun applyCustomNamesUnsorted(apps: List<AppInfo>, names: Map<String, String>): List<AppInfo> =
    apps.map { it.copy(displayName = names[it.packageName] ?: it.originalName) }

// Die RAL-1-Grenze: Namensauflösung MIT sortierter Post-Condition.
// Delegiert an den Kern → EINE Map-Implementierung (keine RAL-3-Drift).
// BLEIBT public + der Default-Name → wer den offensichtlichen Helfer greift,
// bekommt die sichere sortierte Garantie gratis.
fun applyCustomNames(apps: List<AppInfo>, names: Map<String, String>): List<AppInfo> =
    applyCustomNamesUnsorted(apps, names).sortedBy { it.displayNameLower }
```

**Warum diese Form die RAL-1a-Einwände neutralisiert (der ganze Grund für den Spec):**

1. **Der sichere Helfer behält den Default-Namen.** `applyCustomNames`
   (sortiert, public) ist weiter der Name, den man reflexartig greift. Ein
   künftiger Site-N-Consumer, der ihn nimmt, erbt RAL-1s sortierte Post-Condition
   automatisch. Die Optimierung ist **explizites Opt-in** — nicht der Default.
   Das dreht das Risiko um: der Default ist sicher, der schnelle Pfad ist die
   bewusste Ausnahme.
2. **Der Hazard ist STRUKTUR, nicht nur Konvention.** `applyCustomNamesUnsorted`
   ist `internal` zu `:domain` (CLAUDE.md: „internal does not cross module
   boundaries"). Alle drei Opt-in-Sites sind Domain-Use-Cases, also reicht
   Modul-Sichtbarkeit — und ein `:app`-Consumer, der genau den Fehlgriff täte,
   den die dreifache Vertagung fürchtete, sieht die Funktion gar nicht. Der
   `Unsorted`-Name ist die Warnung für den, der SIE schon greifen darf; die
   `internal`-Grenze verhindert, dass die Falschen sie überhaupt sehen. Die
   Tests (`:domain/src/test`) sehen `internal` über den automatischen
   Friend-Pfad. **Der `mapOnly`-Benchmark (`:domain/src/jmh`) NICHT
   automatisch** (Runde-2-Korrektur): `me.champeau.jmh` assoziiert seinen
   Source-Set nicht mit `main`, `internal` ist von dort also vermutlich
   unsichtbar. Heute egal (der Benchmark inlint `mapOnly` und ruft die *public*
   `applyCustomNames` nicht die interne Variante) — aber vor einem `mapOnly →
   applyCustomNamesUnsorted`-Dedup (Minor unten) muss ein `associateWith`
   verifiziert werden. v2 behauptete die Sichtbarkeit als Faktum; das war falsch.
3. **Eine Map-Implementierung — im Produktionscode.** `applyCustomNames`
   delegiert an `applyCustomNamesUnsorted`; die Namensauflösungs-Zeile existiert
   in `:domain/main` genau einmal (killt die RAL-3-Drift dort). **Caveat:** der
   `mapOnly`-Benchmark hält weiter eine **inline-Kopie** derselben Zeile
   (`ApplyCustomNamesBenchmark.kt`), weil er sie mangels jmh-Sichtbarkeit (Punkt
   2) nicht aufrufen kann. Es bleiben also zwei Kopien, eine davon im
   Benchmark — bewusst hingenommen, nicht „Drift komplett weg". §6 hält den
   Benchmark unverändert; ein echtes Dedup bräuchte erst den jmh-Friend-Pfad.
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
`GetInstalledAppsUseCase` ist **ein geteilter Use-Case, dessen sortierte
Post-Condition vier Consumer verbrauchen** (präzise: `invoke()` gibt einen
*kalten* `combine(...).distinctUntilChanged()` zurück — kein `shareIn`/`stateIn`;
geteilt ist die Use-Case-Klasse, nicht ein heißer Flow. Fürs Argument unten
unerheblich, aber „geteilter Flow" wäre falsch). Die vier zerfallen in **drei**
Klassen (M4-korrigiert — v2 zählte Settings falsch mit):
- `CustomNamesViewModel` (`collect { fullyProcessedList }`) — **verlässt sich**
  auf den Sort (der einzige, der es tut).
- `HiddenAppsViewModel` (`.sortedBy { displayNameLower }` auf der Master-Liste) — sortiert selbst.
- `SwipeActionsViewModel` (`.sortedBy { displayNameLower }` auf der Master-Liste) — sortiert selbst.
- `SettingsViewModel` — **ordnungs-agnostisch**: speichert die Liste nur
  (`_installedApps.value = apps`) und nutzt sie fürs `isNotEmpty()`/`size`-Gating
  (`SettingsFragment`), nie ordnungssensibel. (Sein `sortFavoriteComponents`-Aufruf
  ist ein anderer Pfad — das Favoriten-Order-Feature, nicht diese Liste.)

Würde man Site 1 auf `Unsorted` stellen, müsste **nur CustomNames** einen Sort
hinzufügen (Hidden/Swipe sortieren eh, Settings ist egal) — die Sort-Arithmetik
ist also günstiger als v2 behauptete (−1, nicht netto-null). **Trotzdem bleibt
Site 1 sortiert**, aber der Grund ist **nicht** die Arithmetik, sondern der
**Safe-Default**: `GetInstalledAppsUseCase` ist ein pre-Veto-Quell-Boundary im
Sinne von RAL-1, dessen sortierte Post-Condition der Vertrag IST. Ihn auf
unsortiert zu stellen, um einem einzigen Consumer (CustomNames) einen Sort
aufzubürden, verlegt genau die „muss selbst sortieren"-Pflicht an die schwächere
Stelle, vor der RAL-1a warnt — für den Gegenwert eines toten Sorts an einem
kalten Pfad. Die Grenze steht auf dem Invarianten-Grund, nicht auf der Zählung.

**Konsequenz — Hidden/Swipe behalten ihren toten Upstream-Sort.** Sie zahlen
weiter den Site-1-Sort *und* ihren eigenen. Das ist strukturell hinter dem
Site-1-Vertrag eingesperrt (er muss für CustomNames sortiert bleiben) und liegt
bewusst außerhalb dieses Specs — der Preis des Teilens, nicht ein übersehener
Gewinn. (Beide VMs öffnen sich nur auf Nutzer-Aktion, kalter Pfad.)

**Onboarding:** `applyCustomNames(load.apps, names).filter { … }` — `filter`
erhält die Reihenfolge, verlässt sich also auf den Sort. Bleibt `applyCustomNames`.

---

## 3. Der Kern-Nachweis: ist der Sort für die drei Ziele WIRKLICH tot?

Das ist die eigentliche Arbeit des Specs. „Selbst-nachsortierend" reicht nicht —
die Frage ist, ob der Downstream-Sort **total** ist oder heimlich die
Input-Reihenfolge (aus dem Alpha-Sort) als Tie-Break / First-Wins nutzt. Ein
nicht-totaler Downstream-Sort würde beim Umstieg auf `Unsorted` still das
Verhalten ändern (die Maskierungs-Umkehr, §4).

> **Präzision der „total"-Behauptungen unten (Runde-2-geschärft).** Für Drawer
> und Favoriten-Steady-State ist der Umstieg auf `Unsorted` **beweisbar
> NULL-Änderung**, nicht nur „kosmetisch": Kotlins `sortedBy`/`sortedWith` sind
> **stabil**, und sowohl der alte (`applyCustomNames`-sortiert) als auch der neue
> (`Unsorted`) Pfad reduzieren einen `displayNameLower`-Gleichstand auf dieselbe
> `rawApps`-Reihenfolge — der stabile Sort verschiebt gleiche Schlüssel nicht.
> Die frühere „könnte zwei namensgleiche Einträge drehen"-Sorge war zu vorsichtig;
> sie tritt hier gar nicht auf. Die **eine** Stelle, wo die Input-Ordnung real
> durchschlägt, ist Recents' Paket-Repräsentant (§3.3, ein `putIfAbsent` statt
> eines stabilen Sorts) — dort wird sie explizit behoben, nicht weggeredet. Die
> zweite reale Stelle sind die Favoriten-**Fehler-Fallbacks** (§3.2, M1): dort
> ist es kein Tie-Flip, sondern ein voller Reorder — akzeptiert, weil OOM-only.

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

### 3.2 Favoriten — Steady-State sicher, ZWEI Fehler-Fallbacks nicht total (M1)
`FavoritesOrderRepositoryImpl.sortAppsWithGivenOrder` + `sortFavoriteComponents`:
- **Steady-State (sicher):**
  - Geordneter Teil: getrieben von `order` über `componentName`-Lookup — input-unabhängig.
  - Rest-Anhang: `.filterNot { … in consumed }.sortedBy { displayNameLower }` — total.
  - `order.isEmpty()`: `sortedBy { displayNameLower }` — total.
  - `putIfAbsent(componentName, app)`: Input-Order zählt nur bei **zwei Apps mit
    identischem `componentName`** — malformed, Dedupe schon heute dokumentiert/getestet;
    `componentName` ist per Konstruktion eindeutig, im Normalbetrieb nie getroffen. ✓
- **NICHT total — die zwei Fehler-Fallbacks (M1, Runde-2-Fund; v2 behauptete hier
  fälschlich „alle Fallbacks total"):**
  - `sortAppsWithGivenOrder`s eigener `catch (Throwable)` gibt `appsToSort`
    **unsortiert** zurück (`FavoritesOrderRepositoryImpl:194`).
  - `sortFavoriteComponents`s Doppel-Fault-`catch` gibt `favoriteApps`
    **unsortiert** zurück (`:148`).
  Auf diesen Pfaden surfaced heute `applyCustomNames`' Alpha-Ordnung; unter
  `Unsorted` surfaced die Enumerations-Ordnung — ein **voller Reorder**, kein
  Namens-Tie-Flip. Der `applyCustomNames`-KDoc dokumentiert genau diesen Caveat
  bereits („Sole caveat: the favorites error-fallback paths … return the list
  unsorted") — v2 hatte ihn hier übersehen.

**Bewertung:** akzeptiert als **OOM-only kosmetischer Degrade.** Beide Catches
umschließen reine Sorts (`sortedBy` auf Non-Null-Strings), die per Rule 11 nicht
werfen *können* — sie feuern nur bei `OutOfMemoryError`. In diesem ohnehin
degenerierten Moment ist „Favoriten kurz in Enumerations- statt Alpha-Ordnung"
gegenüber dem OOM belanglos. Also: Favoriten-Steady-State ist verhaltensneutral,
der Fallback-Reorder ist ein bewusst akzeptierter OOM-Degrade — **nicht** die
blanke „verhaltensneutral"-Behauptung aus v2.

### 3.3 Recents — EIN echter maskierter Effekt, braucht einen expliziten Tie-Break

> **Dieser Fix ist kein Split-*Preis*, er ist ein Klarheits-*Gewinn*.** Der Sort
> tat hier Doppeldienst: Listen-Ordnung (die Recents ohnehin wegwirft — die finale
> Reihenfolge kommt aus `recentPackages`/Recency) UND Dedup-Determiniertheit
> (welche Activity ein Multi-Activity-Paket repräsentiert). Recents nutzte nur die
> zweite Eigenschaft, still, als Nebenwirkung. Diese zwei Eigenschaften zu trennen
> und die Dedup-Determiniertheit **explizit** in den `putIfAbsent` zu schreiben,
> ist architektonisch sauberer als sie aus einem Upstream-Sort zu erben — genau
> die Art impliziter Kopplung, vor der RAL-1a warnte, nur hier VORHER unsichtbar,
> weil sie hinter dem gebündelten Sort steckte. Der Split legt sie frei und macht
> sie lokal; er fragmentiert nichts, er ent-koppelt.

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

1. **Ordnungs-Assertions zuerst — aber auf der RICHTIGEN Ebene (Runde-2, M2).**
   Der Guard „Test bleibt grün ⟺ Sort war tot" hält NUR dort, wo die Ordnung
   **im Use-Case selbst** berechnet wird. Wo der Use-Case an einen Collaborator
   delegiert, ist ein Use-Case-Test mit Fixed-Return-Mock **blind** (bleibt unter
   sortiert UND unsortiert grün → beweist nichts) und mit Identity-Fake **falsch
   rot** (obwohl Produktion sicher ist). Deshalb:
   - **Drawer ALPHABETICAL** (`sortedBy` im Use-Case) + **Recents** (Dedup-Loop im
     Use-Case): Use-Case-Test-Ebene, echte Ordnungs-Assertion. ✓
   - **Drawer TIME_WEIGHTED** + **Favoriten**: die Ordnung lebt im **Impl**
     (`AppUsageRepositoryImpl.sortAppsByTimeWeightedUsage` mit `thenBy`;
     `FavoritesOrderRepositoryImpl.sortAppsWithGivenOrder`). Der Deadness-Nachweis
     liegt hier auf **Impl-Test-Ebene** (`AppUsageRepositoryImplTest`,
     `FavoritesOrderRepositoryImplTest` — beide existieren, §6). Der Use-Case-Test
     darf NICHT gegen einen Fixed-Return-Mock behaupten, die Ordnung zu prüfen;
     wo er die Ordnung modelliert, muss er den ordnungs-*modellierenden* Fake/Impl
     nutzen. Die „Score-Tie → Alpha"-/„Alpha-Rest"-Assertion ist Impl-Verhalten
     und auf Use-Case-Ebene gar nicht formulierbar.
   - **Recents mit einem Multi-Activity-Paket — die Fixture muss NICHT-ALPHA-ERST
     liefern (M3).** Der Test beweist nur dann etwas, wenn die Activities so
     eingespeist werden, dass die enumerations-erste ≠ die alphabetisch-erste ist
     (z.B. Activities „Zed"/„Alpha", „Zed" zuerst geliefert; `getCurrentApps()`
     gibt die Update-Reihenfolge wörtlich zurück). Bei alpha-erst-Fixture bliebe
     naives `Unsorted` grün → Nachweis wertlos, §4-Maskierung ungewacht. Mit einem
     Negativ-Kontroll-Kommentar pinnen, dass die Fixture-Ordnung bewusst von Alpha
     abweicht. Dieser Test IST der Nachweis für die eine reale Kante.
2. **Den Split einführen, verhaltensneutral.** `applyCustomNamesUnsorted`
   hinzufügen; `applyCustomNames` an den Kern delegieren lassen. Noch **kein**
   Call-Site-Wechsel. Bestehende Tests bleiben grün (der Default ist byte-gleich).
3. **Recents-Fix (§3.3).** `putIfAbsent` → expliziter alphabetischer Tie-Break,
   `LinkedHashMap` → `HashMap`. Der Schritt-1-Recents-Test bleibt grün — beweist,
   dass der Fix auf **bereits alpha-sortiertem Input** dieselbe Auswahl trifft.
   (Die eigentliche Order-Unabhängigkeit beweist erst Schritt 4: der `Unsorted`-
   Call-Site füttert den Fix mit der nicht-alpha-Fixture; M3-Korrektur der v2-
   Attribution, die Schritt 3 zu viel zuschrieb.)
4. **Die drei Call-Sites umstellen** auf `applyCustomNamesUnsorted`, jede mit
   einer Re-Sort-Assertion im Kommentar, die auf ihre Downstream-Sortierzeile
   zeigt. Für Recents zusätzlich: den `displayNameLower`-Tie-Break-Kommentar auf
   `applyCustomNames`' Sortier-Schlüssel zurückbinden, damit die zwei nicht still
   divergieren (die eine neue Kopplung, die der Split einführt — §3.3/§8).
5. **Doku angleichen:** `applyCustomNames`-KDoc (RAL-1a → „gelöst via RAL-4,
   siehe Spec"), REACTIVE_APPLIST §11 (RAL-4 → gelöst), die drei „thin pointer
   back"-Kommentare aktualisieren, `mapOnly`-Arm-KDoc ergänzen.

Schritte 2–4 sind einzeln grün und revertierbar. Der einzige verhaltensberührende
Schritt ist 3 (und der ist durch den Schritt-1-Recents-Test + den nicht-alpha-
Call-Site in Schritt 4 abgesichert).

---

## 6. Test-Impact

- **Neu (Pflicht, Schritt 1), nach Ebene getrennt (M2):**
  - *Use-Case-Ebene, echte Order-Assertion:* Drawer-ALPHABETICAL, Recents
    (Multi-Activity, **nicht-alpha-erste** Fixture — der wertvollste Test, die
    einzige reale Verhaltens-Kante, rot ohne §3.3-Fix).
  - *Impl-Ebene (der Deadness-Nachweis für die delegierten Sorts):*
    `AppUsageRepositoryImplTest` (Score-Tie → `thenBy`-Alpha) und
    `FavoritesOrderRepositoryImplTest` (Alpha-Rest-Anhang) — beide existieren
    bereits und modellieren die Ordnung; ein Use-Case-Test mit Fixed-Return-Mock
    kann diese Deadness NICHT nachweisen und darf es nicht vorgeben.
- **Bestehend, muss grün bleiben:** `ApplyCustomNamesBenchmark` (unverändert —
  `mapOnly` misst genau den Pfad, den drei Consumer nehmen; behält aber seine
  Inline-Map-Kopie, §1 Punkt 3), `FavoritesOrderRepositoryImplTest`
  (Dedupe + Fallback-Verhalten), die Drawer/Favoriten/Recents-UseCase-Tests.
- **DistinctUntilChanged bleibt unberührt:** jeder der drei Flows endet in
  `.distinctUntilChanged()`, aber die FINALE (nach-re-sortierte) Liste ist
  byte-gleich zu heute — nur die Zwischenordnung am `applyCustomNames`-Schritt
  ändert sich, die der Downstream-Sort sofort überschreibt. DUC vergleicht das
  Endergebnis → kein verändertes Emissions-Verhalten. (Recents hat ohnehin kein
  DUC, es ist ein Suspend-Point-Read.)
- **Kein neuer `checkConventions`-Gate nötig:** der Split fügt keine `combine`/
  `.catch`/broad-catch-Sites hinzu; `applyCustomNamesUnsorted` ist eine reine
  Funktion ohne Suspension. (Falls ein Call-Site-Umbau eine bestehende Catch in
  einen Suspend-Frame zöge — tut er nicht — wäre `scanCancelCandidates` der
  Trigger. Hier n/a.)

---

## 7. Ehrlich: was dieser Spec NICHT einlöst

- **Kein spürbarer Performance-Gewinn.** ~9,7 µs × 3 Consumer, off-Main, hinter
  dem DataStore-Read. Wer den Spec als Performance-Fix verkauft, hat ihn falsch
  gelesen — das war die ganze RAL-1a-Begründung, und sie bleibt wahr.
- **Der „Klarheits-Gewinn" ist bescheiden, nicht strikt (Runde-2, M5).** Die tote
  Arbeit ist bereits im 70-Zeilen-`applyCustomNames`-KDoc (RAL-1a) exhaustiv
  benannt — die *Verständnis*-Klarheit liefert also schon Prosa. Was der Split
  *hinzufügt*, ist zählbare Fläche: ein public/`internal`-Funktionspaar, drei
  Opt-in-Kommentare, drei bis vier Tests, vier Doku-Rewrites — gegen das Entfernen
  eines `.sortedBy`-Tokens. Netto ist das **lateral**, nicht strikt weniger. Und
  die Recents-„Ent-Kopplung" (§3.3) löst eine Kopplung, die der Split **selbst
  erzeugt** (heute ist Recents durch den Alpha-Input deterministisch, kein Bug) —
  und re-koppelt Recents an `applyCustomNames`' Sortier-Schlüssel an einer dritten
  Stelle. Das ist ehrlich der Grund, warum das Verdikt „vertagen" ist.
- **Hidden/Swipe bleiben auf ihrem toten Upstream-Sort** (§2, hinter dem
  geteilten Flow eingesperrt).
- **`AppInfo` unverändert**, `getInstalledApps`-Vertrag unverändert, keine neue
  Schicht, keine erfundene API.
- **RAL-1 wird NICHT aufgeweicht.** Die sortierte Post-Condition bleibt der
  Default-Helfer; nur drei nachweislich-nachsortierende Sites opten explizit aus.

---

## 8. Offene Entscheidungen + Verdikt

> **VERDIKT (Runde 2, Multi-Agent-Review, alle 5 Linsen): ein viertes Mal
> vertagen.** Der Mechanismus ist korrekt und der §3-Nachweis hält gegen den Code
> (nach den M1–M4-Korrekturen oben) — aber die adversariale Linse hat recht: netto
> ist der Split eine laterale Komplexitäts-Verschiebung (dokumentierte tote Arbeit
> → strukturell entfernte Arbeit + neue API), kein strikter Gewinn (§7, M5). Der
> Spec bleibt als **fertiger, review-geprüfter Bauplan** liegen; die
> `applyCustomNames`-KDoc-Eskape-Klausel („unless applyCustomNames is touched for
> some OTHER reason anyway") zieht ihn gratis ein, sobald diese Datei aus anderem
> Grund geöffnet wird. NICHT als eigenständige Änderung bauen.

- **RAL-4-a — Recents-Fix vs. akzeptierte Limitation (§3.3).** Empfehlung: Fix.
  Bestätigen oder auf `ACCEPTED_LIMITATIONS.md` umschwenken.
- **RAL-4-b — API-Form (zwei entschiedene Achsen).**
  - *Welche Variante ist Default?* Dieser Spec macht **sortiert** zum Default und
    unsortiert zur `internal` Opt-in-Variante. Die **Inverse** — unsortiert als
    Default, die zwei Cold-Path-Consumer (`GetInstalledAppsUseCase`, Onboarding)
    sortieren explizit nach — hat dieselbe Sort-Anzahl, wird aber **abgelehnt**:
    ihr Default wäre der *unsichere* (ein neuer Consumer, der `applyCustomNames`
    greift, bekäme unsortiert → exakt der stille Fehlsortier, den RAL-1a fürchtet).
    Safe-Default schlägt Symmetrie.
  - *Zwei Funktionen vs. ein `sort`-Parameter?* Zwei benannte Funktionen statt
    `applyCustomNames(apps, names, sort = false)`, weil (a) ein `false` am
    Call-Site leiser ist als ein `Unsorted`-Name, und (b) — der stärkere Grund —
    ein Boolean-Parameter sich NICHT `internal`-gaten lässt: die unsichere Achse
    säße dann in derselben public Funktion und wäre modulübergreifend erreichbar.
    Zwei Funktionen erst machen die `internal`-Gate aus §1 (Mitigation 2)
    möglich. Bestätigen.
- **RAL-4-c — lohnt es sich überhaupt? GESCHLOSSEN: nein, nicht eigenständig.**
  Die Review hat genau die Antwort bestätigt, die der Spec sich offengehalten
  hatte (und die schon zwei Gesprächsrunden vorher stand): der Gewinn ist
  bescheidene Klarheit, nicht Speed, und die neue API-Fläche + die selbst-erzeugte
  Recents-Kopplung machen ihn lateral. Vierter dokumentierter Deferral — jetzt mit
  fertigem, gegen-den-Code-geprüftem Bauplan (siehe Verdikt oben).
