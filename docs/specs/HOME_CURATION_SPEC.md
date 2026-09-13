# HOME_CURATION_SPEC — „Ist auf dem Home" als geteilte Lese-Seite, Schreiben pro App

> **Erzeugt** gegen `main` @ `<HEAD-Hash einsetzen>`, als Folge-Spec aus zwei
> Entscheidungen: `AppInfo` verliert `isFavorite` (`MONOREPO_MERGE_SPEC` MRG-INV-9)
> und Home-Kuration bleibt Produkt-Domäne pro App (MRG-INV-3). Verdrahtet
> `BuildAppContextMenuUseCase` neu.
>
> **Fokus:** die eine Home-Kurations-Berührung im geteilten App-Menü sauber
> schneiden — die **Lese-Seite** („ist App X auf dem Home?") als geteilten Port,
> die **Schreib-Seite** („aufs Home legen / entfernen") als app-gelieferten Callback,
> und die Kontextmenü-Naht so, dass `BuildAppContextMenuUseCase` geteilt bleibt.
>
> **Nicht im Fokus:** Kolibris flaches Favoriten-Modell bzw. Nyx' `HomeLayout`
> selbst (beides Klasse B, je eigener Spec: `FAVORITES_SPEC` / `MOVE_ITEM_SPEC`,
> `RECONCILE_*`); die restlichen Menü-Aktionen (Hide/Rename/Shortcuts/Usage/App-Info),
> die produktneutral und component-/package-gekeyt sind.
>
> **Status:** ENTWURF v1.4. Signaturen in §3–§5 sind Vorschläge auf Basis des realen
> Kolibri-/Nyx-Codes; §10: Nyx hat den „aufs Home"-Eintrag, Overflow→neue Seite ist
> implementiert, Trailing-Seiten-GC = eine leere Landing-Seite (entschieden).
> Review-Runde 1 ausstehend.
>
> **Verhältnis zum großen Kolibri:** Kolibri denormalisiert „Favorit" in zwei
> Formen — `AppInfo.isFavorite` (Cache) **und** `favoritesRepository.isFavoriteComponent`
> (Wahrheit, gelesen in `BuildAppContextMenuUseCase`). Der Cache entfällt (MRG-INV-9);
> die Wahrheit wird zu einem produktneutralen Lese-Port, den Nyx aus `HomeLayout`
> beantwortet.

---

## §0 Bestandsaufnahme

- **Kolibri:** `BuildAppContextMenuUseCase` verzweigt über fünf Prädikate
  (has-shortcuts, **is-favorite**, has-custom-name, is-hidden, drawer-with-usage) und
  baut eine geordnete `List<AppContextMenuAction>`. Die Favoriten-Aktion ist
  `ACTION_ID_TOGGLE_FAVORITE`; „is-favorite" kommt aus
  `favoritesRepository.isFavoriteComponent(componentName)` (fail-closed try/catch).
  Schreiben = `favoriteComponents`-Set-Append/-Remove (+ `favoritesOrder`).
- **Nyx:** „auf dem Home" = Mitgliedschaft im `HomeLayout` (`items` ∪
  Folder-`members` ∪ `dock`). Schreiben ist **keine** Set-Operation, sondern eine
  Platzierung: Nyx hat bereits `HomeLayout.firstFreeCell()`, `HomeLayoutTransition`
  und `MoveResult` („fresh placement" ist ein dokumentiertes Ergebnis,
  `MOVE_ITEM_SPEC`).
- **Die Asymmetrie:** Lesen ist in beiden ein `Set<ComponentKey>`-Membership-Test.
  Schreiben ist in Kolibri trivial (Set), in Nyx eine Policy (Seite/freie Zelle,
  Folder-Regeln, neue `ItemId`). Ein uniformer „Toggle"-Port würde die Nyx-Seite
  verfälschen.

---

## §1 Zielbild

- Ein geteilter Lese-Port `HomeCuration` beantwortet „welche Keys sind auf dem Home".
- `BuildAppContextMenuUseCase` wird geteilt: es liest `HomeCuration` nur, um
  **Vorhandensein + Label** der Home-Aktion zu bestimmen, und führt die Mutation
  **nie selbst** aus.
- Die Mutation ist ein app-gelieferter Callback: Kolibri Set-Append, Nyx Platzierung.
- Nyx bekommt den „aufs Home / vom Home"-Menüeintrag ohne Kolibris Favoriten-Modell.

---

## §2 Lese-Seite geteilt, Schreib-Seite pro App

> **HCU-INV-1 — Lesen ist geteilt, Schreiben ist Produkt.** „Ist auf dem Home"
> (`Set<ComponentKey>`-Membership) ist produktneutral und wandert in den geteilten
> Port. „Aufs Home legen / entfernen" hängt am Home-Modell (flaches Set vs.
> platzierter Grid) und bleibt pro App (MRG-INV-3). Kein geteilter Typ mutiert die
> Home-Kuration.

---

## §3 Der Lese-Port `HomeCuration`

```kotlin
// :core
interface HomeCuration {
    /** Kanonisch: alle aktuell auf dem Home kuratierten Keys, reaktiv. */
    val curatedKeys: Flow<Set<ComponentKey>>
}

// Am Aufrufort abgeleitet — kein eigener Suspend-Roundtrip nötig, wenn der Set schon da ist:
fun Set<ComponentKey>.isOnHome(key: ComponentKey): Boolean = key in this
```

Implementierungen (je Klasse B, in der App):
- **Kolibri:** `curatedKeys` = `favoriteComponents` (als `ComponentKey` geparst).
- **Nyx:** `curatedKeys` = distinct aus `HomeLayout` (`items`-Apps ∪ Folder-`members`
  ∪ `dock`-Apps).

> **HCU-INV-2 — Ein reaktiver Wahrheits-Flow, kein denormalisierter Cache.**
> `AppInfo.isFavorite` (der Cache) existiert nicht mehr; „auf dem Home" wird immer aus
> `curatedKeys` abgeleitet. Damit gibt es keine zweite Wahrheitsquelle, die
> veralten könnte (der Bug, den der Cache latent barg).

---

## §4 Der Schreib-Callback (app-geliefert)

Die Mutation verlässt den geteilten Code als Kommando-Port; das Menü ruft ihn nur.

```kotlin
// :core — die App liefert die Implementierung
fun interface HomeToggle {
    /** Legt [key] aufs Home, wenn nicht vorhanden; entfernt es sonst. @return neuer Zustand. */
    suspend fun toggle(key: ComponentKey): Boolean
}
```

- **Kolibri:** `favoriteComponents += / -= key` (+ `favoritesOrder`-Pflege).
- **Nyx:** vorhanden → aus `HomeLayout` entfernen; nicht vorhanden → an
  `firstFreeCell()` (bzw. neue Seite, wenn voll) platzieren, über
  `HomeLayoutTransition` (`MOVE_ITEM_SPEC`-Semantik, neue `ItemId`).

> **HCU-INV-3 — Der Toggle ist ein Kommando, kein geteilter Algorithmus.** Das
> geteilte Menü kennt nur `HomeToggle.toggle(key)`; wie „aufs Home" konkret aussieht
> (Set-Append vs. Platzierungs-Policy inkl. Folder-/Seiten-Regeln) weiß nur die App.
> Nyx' Platzierung folgt `MOVE_ITEM_SPEC` und dessen Invarianten (kollisionsfrei,
> genau eine neue `ItemId`), nicht dieser Spec.

---

## §5 Kontextmenü-Naht: `BuildAppContextMenuUseCase` geteilt

Der Use-Case wandert geteilt; nur die Favoriten-Abhängigkeit wird ersetzt.

```kotlin
// geteilt — favoritesRepository raus, HomeCuration rein
class BuildAppContextMenuUseCase @Inject constructor(
    private val homeCuration: HomeCuration,          // ersetzt favoritesRepository
    private val hiddenAppsRepository: HiddenAppsRepository,
    // + shortcuts, customNames, usage — alle neutral, unverändert
) {
    suspend operator fun invoke(app: AppInfo, /* … */): List<AppContextMenuAction> {
        val onHome = homeCuration.curatedKeys.first().isOnHome(app.key)
        // …
        actions.add(AppContextMenuAction.LauncherAction(
            id = ACTION_ID_TOGGLE_HOME,              // war ACTION_ID_TOGGLE_FAVORITE
            label = if (onHome) LauncherActionLabel.RemoveFromHome
                    else        LauncherActionLabel.AddToHome,
        ))
        // …
    }
}
```

> **HCU-INV-4 — Das Menü liest, es schreibt nicht.** `BuildAppContextMenuUseCase`
> bestimmt Vorhandensein + Label des Home-Eintrags aus `curatedKeys`; die Ausführung
> geht über den app-gelieferten `HomeToggle` (im Action-Handler der App, nicht im
> Use-Case). Die übrigen Aktionen (Hide/Unhide, Rename, Restore-Name, Shortcuts,
> Reset-Usage, App-Info) sind component-/package-gekeyt und wandern unverändert
> geteilt.

> **HCU-INV-5 — Neutrale Labels, App-Strings.** Der Use-Case emittiert sealed
> `LauncherActionLabel`-Identifier (`AddToHome`/`RemoveFromHome`), nie fertige
> Strings; die App-Adapter mappen zu ihrer Lokalisierung (`:domain` bleibt
> `@StringRes`-frei, IHM-INV-1-Geist). „Favorit" heißt im geteilten Layer „Home".

---

## §6 Modul-Zuordnung

| Einheit | Zielmodul |
|---|---|
| `HomeCuration` (Port), `HomeToggle` (Port), `isOnHome`, `LauncherActionLabel` (inkl. `AddToHome`/`RemoveFromHome`), `AppContextMenuAction`, `BuildAppContextMenuUseCase` | `:core` / `:domain` (geteilt) |
| `HomeCuration`-Impl (Kolibri: aus `favoriteComponents`), `HomeToggle`-Impl (Set-Append) | `:app-kolibri` |
| `HomeCuration`-Impl (Nyx: aus `HomeLayout`), `HomeToggle`-Impl (Platzierung via `HomeLayoutTransition`) | `:app-nyx` |

---

## §7 Testplan (Rule 10 + eure Testkonvention)

- **JVM (schnell):** `Set<ComponentKey>.isOnHome`; `BuildAppContextMenuUseCase` mit
  **Fake** `HomeCuration` (Label `AddToHome` bei leerem Set, `RemoveFromHome` bei
  Treffer; korrekte Aktions-Reihenfolge über die fünf Prädikate) + MockK für
  Hidden/Shortcuts/Usage. Kein echtes Favoriten-/HomeLayout-Modell nötig.
- **Pro App (dort, wo die Wahrheit liegt):** Nyx' `HomeToggle`-Platzierung gegen
  `MOVE_ITEM_SPEC` (freie Zelle, volle Seite, Folder-Fall); Kolibris Set-Append.
  Diese Tests leben im jeweiligen App-Modul, nicht im geteilten.
- **Dispatcher:** ein Dispatcher via `MainDispatcherRule`, kein separater
  `TestScope`/`StandardTestDispatcher`; `TESTING_CONVENTIONS.kt`.

---

## §8 Migration in grün-bleibenden Phasen

- **Phase A — Port einziehen (Kolibri-intern).** `HomeCuration` in `:core`;
  Kolibris `FavoritesRepository` implementiert `curatedKeys`.
  `BuildAppContextMenuUseCase` liest `homeCuration` statt `isFavoriteComponent`;
  `AppInfo.isFavorite` entfällt (MRG-INV-9). Aktion umbenannt
  `TOGGLE_FAVORITE → TOGGLE_HOME`, Kolibri-Adapter mappt Label auf seine
  „Favorit"-Strings (UI unverändert). Tests grün.
- **Phase B — Toggle als Callback.** Die Menü-Ausführung ruft `HomeToggle` statt
  direkt `favoritesRepository` zu mutieren; Kolibris `HomeToggle` = Set-Append.
- **Phase C — Nyx.** `HomeCuration`-Impl aus `HomeLayout`, `HomeToggle`-Impl über
  `HomeLayoutTransition`. Nyx hat den „aufs Home / vom Home"-Menüeintrag.

---

## §9 Querschnitt-Konventionen (geerbt)

- **Lesen/Schreiben-Split** — Membership-Read geteilt; Mutation als app-Kommando.
- **Sealed Ergebnis-/Label-Identifier** — `LauncherActionLabel`; `:app` lokalisiert.
- **`:core`/`:domain` bleiben Android-frei** — Ports über `ComponentKey`/`Flow`.
- **Fail-closed** — `curatedKeys`-Read folgt der Reconcile-Doktrin der App
  (`RECONCILE_*`): ein transient fehlender Key entfernt nichts von selbst.
- **Referenz per nacktem Namen** — `HOME_CURATION_SPEC §4`, nie ein Pfad.

---

## §10 Offene Punkte (für Review-Runde 1)

- **Entschieden:** `curatedKeys.first()` — Snapshot beim Menübau (das Menü ist
  kurzlebig), kein reaktives Beobachten während das Menü offen ist.
- **Entschieden:** Nyx **hat** den „aufs Home"-Eintrag im Drawer-Kontextmenü (nicht
  Drag-only). Nyx liefert also einen `HomeToggle` (§4), und der Eintrag erscheint in
  beiden Launchern.
- **Bereits implementiert (kein offener Punkt):** „aufs Home" auf voller letzter
  Seite legt automatisch eine neue Seite an — `HomeLayout.firstFreeCell()` gibt bei
  vollen Seiten `CellPos(pages, 0, 0)` zurück, `HomeLayoutTransition` bumpt `pages`
  (`if (pos.page == layout.pages) layout.pages + 1`), und `MainActivity` rendert
  `(0 until layout.pages).map(::pageCells)` → das neue Grid erscheint im Pager.
  Overflow ist strukturell unmöglich. Nyx' `HomeToggle` = `firstFreeCell()` +
  Append-Transition; kein Sonderfall nötig.
- **Entschieden — Trailing-Seiten-GC = (b): genau eine leere Landing-Seite.** Nach
  jeder Mutation gilt die Invariante:
  > `pages == (höchste belegte Seite + 1) + 1` — genau **eine** leere Trailing-Seite
  > als Drop-Ziel. Keine Seite belegt ⇒ `pages == 1` (leere Startseite). Mittlere
  > leere Seiten bleiben unangetastet (konsistent mit dem Loch-Modell).

  Damit ist „auf neue Seite ablegen" = „auf die stehende leere Endseite ablegen"
  (Edge-Auto-Advance hat immer ein Ziel), und die Seitenzahl ist selbstheilend
  (Append wächst, GC schrumpft auf letzte-belegte + 1). Die GC-Funktion gehört als
  reine `:domain`-Logik nach `MOVE_ITEM_SPEC` (Platzierungs-Policy), JVM-testbar;
  `MainActivity` klemmt `currentPage` bereits (`if (currentPage < layout.pages)`).

- **Umgesetzt (Abweichung von der GC-Idee, gleiche UX):** Die eine leere
  Landing-Seite wird in nyx **nicht persistiert und nicht per GC geschrumpft**,
  sondern beim Rendern aus der Belegung berechnet:
  `HomeLayout.renderedPageCount()` = `höchste-belegte-Seite + 2` (bzw. 1 bei leerem
  Grid), und `MainActivity` rendert `(0 until renderedPageCount)`. Die Landing-Seite
  wird erst persistiert, wenn wirklich etwas darauf abgelegt wird (die vorhandene
  Append-on-Drop-Transition) — d. h. das Repository bleibt vertragstreu
  (`save` rundtrippt), und es braucht **keine** persistierte Invariante `pages ==
  höchste+2` und **keinen** Trailing-Seiten-GC. Reconciler/Regridder dürfen weiter
  auf `höchste+1` trimmen; die Render-Schicht addiert die Landing-Seite obendrauf.
  Edge-Auto-Advance blättert über `renderedPageCount` (inkl. Landing-Seite).
---

## Review-Log

| Runde | Datum | Reviewer | Ergebnis |
|---|---|---|---|
| v1.5 | 2026-09-13 | reygnn | Umsetzungs-Notiz: Landing-Seite wird render-berechnet (`renderedPageCount` = höchste+2), nicht persistiert/GC'd — Repo bleibt vertragstreu, kein Seiten-GC nötig; Edge-Auto-Advance blättert über die Landing-Seite |
| v1.4 | `<offen>` | `<offen>` | Snapshot-Entscheid: `curatedKeys.first()` beim Menübau; keine offenen Punkte mehr |
| v1.3 | `<offen>` | `<offen>` | Trailing-Seiten-GC entschieden: (b) genau eine leere Landing-Seite; Invariante `pages == höchste-belegte+2` festgeschrieben; GC-Funktion → `MOVE_ITEM_SPEC` |
| v1.2 | `<offen>` | `<offen>` | Korrigiert: Overflow→neue Seite ist bereits implementiert (`firstFreeCell`+Transition+Pager), kein offener/kritischer Punkt; ersetzt durch die echte offene Frage Trailing-Seiten-GC (Empfehlung: eine leere Landing-Seite behalten) |
| v1.1 | `<offen>` | `<offen>` | Entschieden: Nyx hat den „aufs Home"-Eintrag (liefert `HomeToggle`); Voll-Seiten-Verhalten damit auf dem kritischen Pfad (→ `MOVE_ITEM_SPEC`, Empfehlung: neue Seite) |
| 1 | `<offen>` | `<offen>` | ausstehend |
