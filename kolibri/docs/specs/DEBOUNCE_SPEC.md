# DEBOUNCE_SPEC.md

**Status: ENTWURF (2026-08-09), Spec-Review-Runde 1 eingearbeitet — Verdikt SOUND
(nur der ADR-Marker-Fix in §6).** Fokus: Bursts von App-Listen-Reload-Triggern
zu **einem** Reload zusammenfassen; den initialen Load nicht verzögern. Erst
reviewen, dann bauen; nicht auf `main` mergen, bevor mehrere Multi-Agent-Reviews
bestanden sind.

Schwester-Dokumente: `RECONCILE_C2_SPEC.md` (das größere C2b-Display-Targeting —
Debounce ist dessen **Commit 1**), `RECONCILE_SPEC.md` (Verifikations-Veto,
Korrektheit).

---

## 0. Scope & Beziehung zu C2

Dies ist **Option (i)** der Kern-Entscheidung (`RECONCILE_C2_SPEC SPEC-DECISION
C-0`): der billige Sturm-Schutz **allein**, ohne Event-Targeting. Zwei Eigen­
schaften machen es attraktiv:

- **Für sich wertvoll und minimal-invasiv** — eine Datei, reine Timing-Änderung,
  kein Vertrag ändert sich.
- **Voraussetzung für C2b** — jeder C2b-Weg braucht Debounce sowieso, es ist dort
  Commit 1. Debounce jetzt zu bauen wirft also nichts weg, falls später doch C2b
  kommt.

---

## 1. Problem

Datenfluss heute (`RECONCILE_SPEC §0.1`): jedes Paket-Event **und** jede
Custom-Name-Änderung (`CustomNamesRepositoryImpl.kt:204`) →
`appsUpdateTrigger.emit(Unit)` → `flatMapLatest { loadAppsFromPackageManager() }`
→ volle `queryIntentActivities` + `loadLabel` für **alle** Apps
(`InstalledAppsRepositoryImpl.kt:89–129`).

Bei einem **System-Update / Batch-(De)Install** feuert ein **Sturm** von
`PACKAGE_*`-Broadcasts → ein Sturm von Voll-Enumerationen.

**Ehrliche Einordnung des Gewinns (partiell, nicht total).** Eine Teil-Ent­
schärfung existiert bereits: `flatMapLatest` ist latest-wins — ein neuer Trigger
**canceled** die laufende Enumeration. Der Sturm *startet* also viele
Enumerationen, aber nur die letzte läuft durch; die anderen werden früh
abgebrochen. Debounce verbessert das, indem die Zwischen-Enumerationen **gar
nicht erst gestartet** werden (kein Start-/Cancel-Churn, keine halb-gelaufene
`loadLabel`-Arbeit). Der Löwenanteil ist durch `flatMapLatest` schon gedeckt;
Debounce killt den Rest-Churn. Das ist der 20%-Aufwand für den Großteil des
verbleibenden Sturm-Schmerzes.

---

## 2. Zielbild

- Einen Burst von Triggern innerhalb eines Fensters **T** zu **einem** Reload
  zusammenfassen.
- Den **initialen** Load (Priming) **NICHT** um T verzögern — sonst wird der
  Cold-Start-Drawer langsamer.
- **Korrektheit unberührt:** reine Timing-Änderung. Das Verifikations-Veto
  (`RECONCILE_SPEC §9.3`) und R-INV (`§1`) bleiben wortwörtlich.

---

## 3. Die Änderung

Heute (`InstalledAppsRepositoryImpl.kt:89`):

```kotlin
appsUpdateTrigger
    .onStart { emit(Unit) }        // Priming — soll SOFORT laden
    .flatMapLatest { loadAppsFromPackageManager() }
    …
```

Ein naives `.debounce(T)` vor `flatMapLatest` verzögert **auch** das
`onStart`-Priming → langsamerer erster Load. Falsch (verletzt DBNC-INV-1).

Korrekt: Priming vom debounced Trigger trennen (Priming sofort, spätere Trigger
coalesced):

```kotlin
merge(
    flowOf(Unit),                        // Priming — sofort, ungedebounced
    appsUpdateTrigger.debounce(T),       // spätere Trigger — coalesced
)
    .flatMapLatest { loadAppsFromPackageManager() }
    …
```

(Formfrage — alternativ das `onStart` behalten und nur den Trigger-Zweig
debouncen. Der Punkt ist DBNC-INV-1: das Priming darf nie durch das Fenster.)

---

## 4. Invarianten (DBNC-INV-*)

- **DBNC-INV-1:** Der initiale/Priming-Load wird **nie** um T verzögert.
- **DBNC-INV-2:** latest-wins (`flatMapLatest`) bleibt erhalten.
- **DBNC-INV-3:** Reine Timing-Änderung — keine Löschung, keine Korrektheits-
  Wirkung. Der `getInstalledApps(): Flow<AppLoad>`-Vertrag ist unverändert.
- **DBNC-INV-4:** Ein einzelnes (nicht-Sturm) Event führt weiterhin verlässlich
  zu genau einem Reload (nach ≤ T).

---

## 5. Offene Entscheidung

- **`SPEC-DECISION D-1` — das Fenster T.** Kandidat **200–400 ms**. Trade-off:
  größer = besserer Sturm-Schutz, aber ein einzelner Install/Uninstall
  aktualisiert die Anzeige um T später. **Vorschlag: 250 ms**, als benannte
  Konstante in `AppConstants` (nicht hartcodiert).

---

## 6. Test-Auswirkung

Zeit-basiert → **virtual-time** (`TESTING_CONVENTIONS`: `MainDispatcherRule` +
`testScheduler`; **kein** `advanceUntilIdle()` gegen den
`stateIn(WhileSubscribed)`-Flow — `testScheduler.runCurrent()` / gezieltes
`advanceTimeBy` stattdessen). Zu pinnen:

- Ein Burst von N Triggern in < T → **genau ein** Reload.
- Priming/Initial-Load feuert bei **t = 0** (nicht t = T) — DBNC-INV-1.
- Ein einzelner Trigger → Reload nach **≤ T** — DBNC-INV-4.

Das Repo steht auf **`NO IMPL CONTRACT TEST (ADR)`** (nicht `NO CONTRACT TEST`) —
das exemptet nur die **Impl**-Hälfte; der abstrakte Contract + die Fake-Contract-
Tests (`FakeInstalledAppsRepositoryContractTest`,
`ReactiveFakeInstalledAppsRepositoryContractTest` — u.a. „`getInstalledApps` gibt
dieselbe Flow-Instanz über Aufrufe" und „Initial-Emit ohne Trigger") bleiben
**verpflichtend**. Der Debounce-Umbau muss sie grün halten. Der Impl-Test selbst
(die virtual-time-Punkte oben) ist zusätzlich.

---

## 7. Migration

**Ein** Commit, revertierbar. Kein Vertrag ändert sich. Risiko: **minimal**
(Timing).

---

## 8. Was es NICHT ist

- Kein Event-Targeting (das ist C2b, `RECONCILE_C2_SPEC.md`).
- Kein Korrektheits-Fix (das war das Veto).
- Keine Vertrags- oder Downstream-Änderung.
