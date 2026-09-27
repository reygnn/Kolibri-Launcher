# Unity-Launcher (Kolibri + Nyx) — geteilte TODO / Roadmap

Cross-cutting-Themen, die **beide** Launcher betreffen — typischerweise die geteilten
Module (`:core`, `:common-data`, `:common-ui`, `:common-android`,
`:feature-crashreporting`) oder eine Konvention, die für Kolibri *und* Nyx gilt.
Launcher-spezifisches bleibt in `kolibri/TODO.md` bzw. `nyx/TODO.md`; diese Datei ist die
gemeinsame Ebene darüber.

---

## ✅ UMGESETZT: Auto-Prune → lazy-slot (Branch `feature/lazy-slot-validation`)

Die gesamte Auto-Prune-/F7-Gate-Maschinerie ist entfernt. Die kuratierten Stores
(home-layout/nyx, favorites/kolibri, custom names, hidden apps, swipe slots) werden **nie
mehr automatisch geprunt**: eine Referenz auf eine verschwundene App bleibt als „missing"-
Tile/-Row stehen (Windows-Verknüpfungs-Modell), sichtbare Stores bieten „App nicht gefunden.
Entfernen?" an, entfernt wird nur per User-Aktion. Membership ist ein In-Memory
`key in currentApps` — kein IPC, kein Gate.

**Gelöscht:** `core/{AppPresence,InstallSessionInspector,DeletionGatePass}`,
`core/testFixtures/DeletionGateParityContract`, `common-data/PackageManager{Presence,
InstallSessions}` (+ Tests + DI-Binds). **Neu:** `core/LazySlotMembership`,
`core/testFixtures/NoAutoPruneContract`.

**Folge:** Die früher hier geführten Abschnitte (Rule-9-Nuance an den fail-safe-Seams,
Drift-Prävention geteilter Gate-Helfer + Cross-Launcher-Parity-Test, der count-floor,
das nyx F7-Gate) sind **gegenstandslos** — die beschriebenen Nähte existieren nicht mehr.
Nur die zwei wiederverwendbaren Lessen bleiben unten als Kurz-Referenz; die
ACCEPTED_LIMITATIONS-Restore-Restrisiko-Einträge beider Launcher sind ebenfalls moot.

---

## Lesson (bleibt): `silentError` vs. `reportToAcra` an fail-safe-Grenzen

`TimberWrapper.silentError` **wirft in DEBUG** (`crashInDebug`) — Kanal für *gefangene
Programmierfehler*. An einer **fail-safe-to-keep-Grenze** (Vertrag: »gib in jedem Build den
sicheren Default zurück«, z. B. System-API-Boundary) ist das falsch: der DEBUG-Throw bricht
genau den Vertrag. Dort gehört `reportToAcra` hin (RELEASE-Signal via ACRA, kein DEBUG-Throw).

- erwartet, environmental-transient, upstream behandelt → **`reportToAcra`**
- echter Programmierfehler, der laut werden soll → **`silentError`**

Fund/Fix: AUDIT-1 F7 (Branch `fix/f7-partial-enumeration-guard`); die konkreten Seams sind
mit lazy-slot gelöscht. Kanonische Rule-9-Historie: `kolibri/CLAUDE.md` Rule 9 +
`kolibri/TODO.md` §2/§23. Der Intent-Gate-Linter fängt bare `Timber.e(`, aber **nicht** ein
falsch gewähltes `silentError` an einer fail-safe-Grenze — bleibt Review-/Konventions-Frage.

---

## Design-Linse (bleibt): Auto-Prune vs. feste Slots + lazy-Validierung

Reframe für die nächste Entscheidung, kein Task: **bevor ein neuer auto-geprunter Store
dazukommt, erst fragen »muss der überhaupt auto-prunen, oder reicht Slot + lazy?«.** Auto-Prune
(keine Karteileichen, self-healing) erzeugt die stille-Datenverlust-Klasse, gegen die ein
Fail-safe-Gate verteidigen muss; feste Slots + lazy (tote Einträge bleiben sichtbar stehen,
Bereinigung erst bei User-Interaktion) lassen diese Klasse *by design* verschwinden, um den
Preis angesammelter toter Einträge. Pro Store abwägen: Slot-artige Stores (Swipe-Slots) sind
die natürlichen lazy-Kandidaten, offene Mengen (home-layout, favorites, hidden, custom names)
profitieren stärker vom Auto-Cleanup. (Für die kuratierten Stores ist die lazy-Option seit
`feature/lazy-slot-validation` umgesetzt — siehe oben.)

---

## Offen: nyx Perf-Haltung — `WhileSubscribed`-Subscription für missing-Erkennung

Die missing-Erkennung in nyx abonniert den reaktiven Installed-Apps-Loader `WhileSubscribed`,
solange Home sichtbar ist (`HomeViewModel.installedKeys`) — ein bewusster Bruch mit nyx'
bisheriger *pull-on-open*-Enumerations-Haltung (Enumeration nur beim Drawer-Öffnen). Das
Feature verlangt kontinuierliches Wissen über den Installations-Status, damit ein Tile beim
Uninstall live ausgraut.

**Abwägung (noch nicht entschieden):** live-Ausgrauen vs. immer-warme Enumeration. Alternative,
falls die Perf-Haltung höher gewichtet wird: „installed-Set nur auf Paket-Events (via
`PackageEventCoordinator`/`AppUpdateSignal`) + beim ersten Home-Show refreshen" statt der
kontinuierlichen `WhileSubscribed`-Subscription — dann graut ein Tile erst beim nächsten
Paket-Event aus statt sofort.
