# Greenfield-Retrospektive: nyx & kolibri

> Eine subjektive Architektur-Einschätzung: *Was würde ich anders machen, wenn nyx und
> kolibri greenfield noch einmal neu geschrieben würden?* Festgehalten am 2026-09-13,
> nach einer längeren Arbeits-Session in nyx (Feature-Parität + zwei Multi-Agent-Reviews).
>
> **Wissensstand-Caveat:** nyx kenne ich aus dieser Session von innen, kolibri
> größtenteils aus Referenzen (BaseActivity, ACRA, der „paranoide" Serializer,
> Contract-Tests) — die kolibri-Aussagen sind also eine Stufe unsicherer. Das meiste am
> bestehenden Code ist *gut*; hier stehen bewusst nur die Stellen, wo ich abbiegen würde.

---

## Wenn ich nur *eine* Sache ändern dürfte: die God-Activity auflösen

`MainActivity` (nyx) ist der wunde Punkt. ~1000+ Zeilen, hält Delegates direkt, baut
Kontextmenüs, verwaltet Folder-/Context-Overlays, Drag-Wiring, Seed, ACRA-Consent,
Wallpaper-Edit, Gesten, Rendering.

**Fast alle echten Bugs dieser Session saßen dort oder in der Orchestrierung drumherum**
(Kontextmenü-Race, Seed/Regrid-Race, Multitouch-Pointer) — *nicht* in den reinen
Domain-Transitions, die tadellos sind. Der Grund: die getestete Logik lebt in `:domain`,
aber die *zustandsbehaftete Orchestrierung* lebt in einer Android-Klasse, die praktisch
nicht JVM-testbar ist. Genau das, was die eigene Test-Philosophie („pure logic outside
Android classes") vermeiden will — bei den Transitions befolgt, bei der Activity nicht.

Greenfield: die Orchestrierung (welche Coroutine wann was schreibt, Overlay-State-Machine,
Seed→Fit-Reihenfolge) in ViewModels/Use-Cases ziehen, sodass die Activity nur noch
View-Binding ist. Dann wäre der Kontextmenü-Race ein Unit-Test statt eines Screenshots.

## nyx

**Die Layout-Normalisierungs-Pipeline hat unklare Ownership.** Diese Session ging mehrfach
um „wer besitzt die Dock-Kapazität" (Reconciler vs. Regridder), Stale-Grid-Races und
Seed-vor-Fit. Das sind drei Komponenten (`HomeLayoutReconciler`, `HomeLayoutRegridder`,
`seedInitialDock`), die um denselben Zustand konkurrieren. Greenfield: **eine** reine
Funktion `normalize(layout, deviceGrid, installedApps) → layout`, die erst läuft, wenn das
Grid bekannt ist — kein Rennen, eine Wahrheit. Die beste Idee, die schon da ist
(`renderedPageCount` — die Landing-Page ist *berechnet, nicht persistiert*), würde ich zum
Prinzip erheben: **minimal persistieren, maximal ableiten.**

**Ein einziger `home_layout`-DataStore für alles** (Layout + Settings + Wallpaper-Display +
FAB + Layer-JSON + Seed-Flag) ist bequem für den Reset, koppelt aber unabhängige Concerns
in eine Datei — und ein Teil der Ordering-Races kommt genau daher. Ich würde pro Bounded
Context trennen (layout / prefs / wallpaper).

## kolibri

Hier bin ich vorsichtiger, aber zwei Dinge: der **„paranoide Multi-Pass-Serializer"**
klingt nach hart erarbeiteter Erfahrung mit hand-editierten Legacy-Backups — greenfield
würde ich das durch **ein versioniertes Schema mit expliziten Migrationen** ersetzen statt
Recovery-Heuristiken. Und die **~28k LOC** deuten an, dass kolibri über die Jahre
Verantwortung angesammelt hat, die heute in die geteilten Module gehörte (time-info,
wallpaper, crash sind schon draußen — vermutlich ginge mehr).

## Beide / geteilt

- **Fehler-/Concurrency-Netz von Tag 1 shared.** kolibris `BaseActivity`/`BaseViewModel`
  (launchSafe/executeSafe + ErrorEventBus) existieren, nyx hat sie *nicht* (steht als
  TODO). Mehrere nyx-Coroutine-Bugs dieser Session hätte eine disziplinierte
  structured-concurrency-Basis teilweise abgefangen. Das gehört in `:common-ui`, beide
  nutzen es ab Commit 1 — nicht nachträglich portiert.
- **Backup als *ein* geteiltes, transaktionales Framework** statt kolibri-paranoid vs.
  nyx-lean divergierend über einem gemeinsamen Schema-Spec. Der F-Fix dieser Session
  (Layout zuletzt schreiben) ist ein Pflaster für fehlende Cross-Store-Atomizität —
  greenfield wäre „apply in one transaction" ein Framework-Garant, kein Kommentar.
- **`:common-ui` bewusster schneiden.** Es ist View-basiert und kolibri-geprägt; nyx zieht
  es rein, nutzt einen Bruchteil und hat FAB-/Wallpaper-Edit-Code *byte-identisch kopiert*
  (WV5d-Entscheid + Dedup-TODO). Greenfield: „echt geteilt" vs. „app-spezifisch" von
  Anfang an trennen.

## Compose — die Frage, die ich *nicht* dogmatisch beantworte

Der Stack-Baseline sagt „Compose, kein XML für neuen Code", beide Launcher sind aber
Views/XML. Verlockend zu sagen „greenfield in Compose" — aber **Launcher sind einer der
wenigen App-Typen, wo Views echte Vorteile behalten**: `AppWidgetHostView` (bei nyx zwar
out-of-scope), präzise Touch-/Window-Kontrolle (genau `DragLayer.dispatchTouchEvent` mit
Window-Ownership übers Statusbar hinweg), RecyclerView-Perf bei riesigen App-Listen, die
Wallpaper-Surface. Die imperative Touch-Maschine, die wir diese Session gefixt haben, wäre
in Compose *anders* schwer, nicht *einfacher*.

Deshalb greenfield: **bewusst hybrid** — Chrome/Settings/Drawer-Chrome in Compose (da spart
es echt), aber Workspace/Drag/Grid als View-Surface, sauber gekapselt hinter einer
Schnittstelle. Und zwar als *Entscheidung*, nicht als Erbe von kolibris `:common-ui`.

## Was ich unverändert ließe (nicht nur Kritik)

Die **reinen, totalen Domain-Transitions mit Invarianten-Specs und 1:1-Truth-Table-Tests**
(`HomeLayoutTransition` & Co.) sind das Beste am Code — die haben diese Session *keine*
echten Bugs produziert. Ebenso die **Monorepo-Aufteilung**, die
**specs-as-directional-intent**-Haltung und der Reflex, Tuning-Werte zu *messen* statt zu
raten (72dp aus One UI). Das Muster stimmt — ich würde nur *mehr* Code in diese testbare
Zone ziehen und weniger in der Activity lassen.

---

## In einem Satz

nyx & kolibri sind an den *reinen* Rändern exzellent und in der *zustandsbehafteten Mitte*
(Activity-Orchestrierung, konkurrierende Layout-Reconciler, geteilte DataStore-Concerns)
fragil — greenfield würde ich diese Mitte in testbare Use-Cases mit klarer Ownership
verschieben und Fehler-/Backup-Disziplin ins geteilte Fundament legen, statt sie pro App
neu zu erfinden.
