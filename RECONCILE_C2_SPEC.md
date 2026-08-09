# RECONCILE_C2_SPEC.md

**Status: ENTWURF / FUNDAMENT (2026-08-09).** Dieser Spec beginnt die „saubere
C2"-Planung: den bereits gebauten typisierten `PackageEvent`-Payload für einen
**ereignis-gezielten Fast-Path** nutzen. Er ist bewusst noch nicht
bau-vollständig — die detaillierten Sigs (§6) und die Migrationsreihenfolge (§7)
werden erst nach der Scope-Entscheidung (§8, `SPEC-DECISION C-0`) ausgeführt.
Erst reviewen, dann bauen; nicht auf `main` mergen, bevor der Rewrite mehrere
Multi-Agent-Reviews überstanden hat.

Schwester-Dokumente: `RECONCILE_SPEC.md` (die as-built **Verifikations-Veto**-
Lösung + der Design-Record der nicht-gebauten event-targeted Variante §2–§6),
`RECONCILE_FIX_SPEC.md`, `INSTALLED_APPS_LOAD_SPEC.md` (Belang C ist der Zeiger
hierher).

---

## 0. Die entscheidende Vorab-Klarstellung: C2 ist Optimierung, nicht Korrektheit

Die frühere Diskussion („auf der grünen Wiese wäre C2 besser") lief unter der
stillen Annahme, event-targeting sei auch ein **Korrektheits**-Gewinn. **Das ist
es nicht mehr** — der Korrektheits-Preis ist bereits eingefahren:

- Der belegte Defekt (`RECONCILE_SPEC §0.2`: eine partielle/unvollständige
  Ladung löscht noch installierte Zuweisungen permanent) ist **gelöst** — durch
  das **Verifikations-Veto** (`7bd7a77e`, §9.3), **nicht** durch event-targeting.
  Der Welt-Diff ist Kandidaten-Finder, jede Löschung geht pro-Ziel durch
  `PackagePresence`. **R-INV** (`§1`) ist erfüllt, die Cold-Start-Aufholung
  (`§0.3` Aufgabe 2) bleibt erhalten und ist jetzt sicher.
- Der typisierte `PackageEvent`(`Added`/`Removed`)-Bus (`de021520`) **ist
  gebaut**, sein Payload aber **ungenutzt** — bewusst gehalten als „billiger Hook
  für eine spätere Effizienz-Optimierung", kein totes Feld. Der Consumer
  (`AppManagementDelegate.listenForAppUpdates`) kollabiert heute jedes Event zu
  einem vollen `refreshAppsUseCase()` (Kommentar: „Step 1 … payload not yet
  consulted").

**Folge für den Wert von C2.** C2 gewinnt nur noch **Effizienz/Latenz**, keine
Korrektheit. Der ehrliche Kosten/Nutzen:

- **Kosten heute pro Paket-Event:** eine volle `queryIntentActivities`-
  Enumeration + `loadLabel` für **alle** Apps + Reconcile ×4. Real teuer
  (hunderte Apps).
- **Aber:** Paket-Events sind selten (nutzergetrieben: ein Install/Uninstall).
  Und das Veto hat den Reconcile-Steady-State bereits billig gemacht
  (Verifikation läuft nur auf Kandidaten, im Normalfall null).
- **Der einzige wirklich unangenehme Fall** ist der **System-Update-Sturm**
  (viele Events → viele Voll-Enumerationen). Der ist aber **auch durch ein
  simples Debounce** killbar — ohne die ganze Targeting-Maschinerie.

→ `SPEC-DECISION C-0` (§8) ist deshalb die erste und wichtigste Frage: **Lohnt
C2 überhaupt, oder reicht Debounce?** Der Rest dieses Specs nimmt „ja, C2" an,
macht die Kosten aber explizit.

---

## 1. Zielbild — Hybrid, nie nacktes Event-Targeting

Das greenfield-beste Design ist ein **Hybrid**, kein Ersatz:

1. **Voll-Reload + Verifikations-Veto bleibt der Korrektheits-Backstop.**
   Selbstheilend: fängt verpasste/gedroppte Events, Cold-Start-Totzeit-
   Uninstalls, Out-of-Order. Wird NICHT entfernt.
2. **Ereignis-gezielter Fast-Path als Optimierung *obendrauf*.** Auf
   `Removed(pkg)`/`Added(pkg)` gezielt reagieren, ohne die volle Enumeration.
3. **Debounce des Reload-Triggers** als billiger Sturm-Schutz — unabhängig
   davon, ob 2. gebaut wird.

Pures Event-Targeting allein wäre eine **Robustheits-Regression** (verlöre die
Selbstheilung). Das ist die Leitplanke des ganzen Specs.

---

## 2. Zwei Scope-Varianten (der Kern von `SPEC-DECISION C-0`)

Der Fast-Path kann zwei verschiedene Kosten angreifen — sie sind unabhängig
wählbar:

### C2a — Cleanup-Targeting (billig, kleiner Gewinn)
Auf `Removed(pkg)`: nur die Zuweisungen **dieses Pakets** pro-Ziel verifizieren
und ggf. löschen, statt Welt-Intersect + Veto-auf-Kandidaten. **Aber:** das Veto
macht den Cleanup-Steady-State schon billig (Kandidaten meist null), also spart
C2a **wenig**. Nutzen v.a. bei sehr vielen Zuweisungen. Berührt **nur** die
Persistenz-Stores (favorites/swipe/hidden/custom-names), **nicht** den Anzeige-
Holder → **keine** Single-Writer-Frage. Niedriges Risiko, dünner Gewinn.

### C2b — Display-Targeting (der eigentliche Kosten-Killer, höheres Risiko)
Auf `Removed(pkg)`/`Added(pkg)`: die **Anzeige-Liste** gezielt mutieren (pkg-
Zeilen entfernen / pkg gezielt nachladen und einsortieren) **ohne** die volle
`queryIntentActivities`-Enumeration. Das spart die teure Enumeration + das
`loadLabel`-für-alle — der reale Gewinn. **Aber** es schreibt den Anzeige-Holder
→ **hier lebt der Single-Writer-Zoll (§3).**

Empfehlung des Fundaments: **C2a ist kaum die Mühe wert** (Veto hat den
Cleanup schon billig gemacht). Der Gewinn, der C2 rechtfertigt, ist **C2b** — und
genau der trägt die eine harte Design-Frage. Wenn C2b zu teuer erscheint, ist
**Debounce allein** die ehrliche Alternative (§0).

---

## 3. Der eine harte Punkt: Single-Writer-Serialisierung (nur C2b)

Heute schreibt **genau eine** Stelle den Anzeige-Holder:
`ObserveInstalledAppsUseCase` → `InstalledAppsStateRepository.updateApps(...)`
(ein Prod-Aufrufer). Diese **Single-Writer-Eigenschaft existiert bereits.**

C2b darf **keinen zweiten** direkten Schreiber einführen (der Fast-Path ruft
`removeApp`/`addApp` direkt auf den Holder) — das reintroduziert ein Rennen, in
dem ein veralteter Voll-Reload ein frisches Delta überschreibt
(`Loaded(vor-dem-Uninstall enumeriert)` überschreibt `Removed(pkg)`).

**Regel (erster Absatz jeder C2b-Umsetzung):** *Ein* serialisierter Schreiber.
Lade-Strom und Paket-Event-Strom werden in **diesen einen** Schreiber
zusammengeführt:

```
merge(loadCommands, packageEventCommands)   // zwei Quellen
    .collect { cmd -> applyToHolder(cmd) }   // weiterhin der EINZIGE Schreiber
```

(`merge` / Actor-Loop / `Mutex`.) Das Stale-Snapshot-Problem wird abgesichert
durch: **Debounce** (Enumeration läuft *nach* dem Event), das bestehende
`flatMapLatest` (latest-wins auf dem Reload-Pfad) und das bestehende
`PackagePresence`-fail-closed-Gate. Denkfigur: **optimistisches Delta (sofortiges
Feedback) + autoritativer Replace (re-liest die Wahrheit); autoritativer Replace
schlägt Delta.**

---

## 4. Prämissen

- **P1 — Backstop bleibt.** Voll-Reload + Verifikations-Veto wird nicht ersetzt,
  nur ergänzt. R-INV (`RECONCILE_SPEC §1`) und die Cold-Start-Aufholung bleiben
  wortwörtlich erhalten.
- **P2 — Payload wird genutzt, nicht neu erfunden.** `PackageEvent`
  (`:domain/core`, pure Kotlin) und der typisierte `AppUpdateSignal` existieren
  bereits (`de021520`). C2 baut den Consumer, nicht den Bus.
- **P3 — Anzeige ≠ Löschung bleibt entkoppelt** (`RECONCILE_SPEC §1`): der
  Fast-Path darf die zwei Achsen nicht wieder verkleben.
- **P4 — Debounce ist eigenständig wertvoll** und sollte unabhängig von der
  C2a/C2b-Entscheidung gebaut werden (billigster Sturm-Schutz).

---

## 5. Invarianten (C2-INV-*) — Entwurf

- **C2-INV-1:** Genau ein Schreiber des Anzeige-Holders (C2b). Kein direkter
  `holder.updateApps`/`addApp`/`removeApp` außerhalb des vereinten Kollektors.
- **C2-INV-2:** Jede Löschung bleibt pro-Ziel `PackagePresence`-gegated — der
  Fast-Path verifiziert genau wie der Backstop, nie „weil das Event es sagt"
  (ein `Removed`-Broadcast ist ein Hinweis, kein Beweis).
- **C2-INV-3:** Der Backstop-Reload feuert weiterhin (debounced) nach jedem
  Event und korrigiert jedes Fast-Path-Ergebnis zur Wahrheit.
- **C2-INV-4:** `Added(pkg)` löscht nie etwas (ein Add ist nie ein Cleanup-
  Trigger) — `RECONCILE_SPEC §2.1`.
- **C2-INV-5:** Ein verpasstes/gedropptes Event darf **nie** zu permanentem
  Drift führen — durch C2-INV-3 strukturell garantiert.

---

## 6. Sigs (Zielzustand) — TODO nach `SPEC-DECISION C-0`

Ausstehend bis Scope fix (C2a / C2b / nur Debounce). Skizze für C2b:
- Inkrementelle Holder-API (`addApp`/`removeApp`, in-order Insert — die Impl
  hält sortiert nach `displayName.lowercase()`).
- Scoped Single-Package-Load (`queryIntentActivities` mit `setPackage(pkg)`).
- Vereinter Kollektor (`merge(loadCommands, eventCommands)`), einziger Schreiber.
- Wiederverwendung der Single-Item-Remover (`removeFavoriteComponent`,
  `removeCustomNameForPackage` existieren; swipe/hidden prüfen).

---

## 7. Migrationsreihenfolge — TODO (je ein revertierbarer Commit)

Grober Rahmen (nach C-0): (1) Debounce isoliert. (2) [falls C2a] gezielter
Cleanup-Pfad. (3) [falls C2b] inkrementelle Holder-API. (4) [falls C2b]
vereinter Single-Writer-Kollektor + Fast-Path. Jeder Schritt mit Contract-/
Regressionstest, jeder für sich mergefähig.

---

## 8. Offene Entscheidungen (spec-neutrale Scope-Regler)

- **`SPEC-DECISION C-0` (die Kernfrage):** Lohnt C2 überhaupt? Optionen:
  **(i) nur Debounce** (billigster Sturm-Schutz, kein Targeting) —
  Fundament-Empfehlung als Minimalgewinn; **(ii) C2a** (Cleanup-Targeting,
  dünner Gewinn, niedriges Risiko); **(iii) C2b** (Display-Targeting, echter
  Gewinn, trägt den Single-Writer-Zoll). *Berührt:* alles.
- **`SPEC-DECISION C-1`:** Verifikations-API für den gezielten Cleanup
  (`getLaunchIntentForPackage` vs `resolveActivity`/`queryIntentActivities` auf
  Component-Ebene) — erbt von `RECONCILE_SPEC §3 SPEC-DECISION R-1`.
- **`SPEC-DECISION C-2`:** Bus-Konsolidierung (`appsUpdateTrigger` `Unit`-Bus vs
  Zusammenführung mit `AppUpdateSignal`) — erbt von `RECONCILE_SPEC R-2`.

---

## 9. Was dieser Spec NICHT ist

- Kein Korrektheits-Fix — das war das Verifikations-Veto (`RECONCILE_SPEC`).
- Kein Ersatz des Voll-Reloads — der bleibt Backstop (P1).
- Keine Bauanleitung, solange `SPEC-DECISION C-0` offen ist (§6/§7 sind TODO).
