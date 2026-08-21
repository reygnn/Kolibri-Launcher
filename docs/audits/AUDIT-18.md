# AUDIT-18 — UX- & Accessibility-Korrektheit

> **Erzeugt** 2026-08-11 gegen `main` @ `3a90efcc` (Version 0.99.160 / code 180),
> auf die Frage: *„Wo steckt in der Codebasis **UX-/Accessibility-Korrektheits**-
> Debt — echte Defekte für TalkBack-/Großschrift-/Nicht-Englisch-Nutzer oder
> stille Flows, nicht Style?"*
> **Zweite Nicht-Perf-Linse der Serie** (nach AUDIT-17 Korrektheit); die ersten
> drei Audits waren Perf, AUDIT-17 war Coroutine-/Daten-/UI-Korrektheit — UX und
> a11y wurden **nie** systematisch geprüft.
> **Methode:** drei parallele Subsystem-Scans (Accessibility, UX-Flow/State,
> Localization/Text-Robustheit), **danach jeder gemeldete Fund selbst am Code/
> Layout verifiziert**. Gegengeprüft: `ACCEPTED_LIMITATIONS.md`, `KNOWN_ISSUES.md`,
> `CLAUDE.md` — dort als bewusst markierte Punkte sind ausgeschlossen. Für einen
> **bewusst minimalistischen text-only Launcher** wurde „mehr UI/Deko" NICHT als
> Defekt gewertet; nur echte Korrektheits-/a11y-Fehler.
>
> **Status: VOLLSTÄNDIG UMGESETZT.** Drei verifizierte Defekte + Low-Notes; alle
> drei auf Branch `fix/audit-18-ux` gefixt (F1/F3 mit Test, F2 als
> View-Inflation/a11y-Attribut per Compile verifiziert). Low-Notes bewusst nicht
> eingeplant (§2). Compile/Linter/Tests grün.

---

## 0. Ergebnis der drei Scans

| Achse | Ergebnis |
|---|---|
| **Accessibility** | 🔴 1 Defekt (F2, `medium`) + 2 `low`-Notes. Baum sonst gut gelabelt — nahezu jedes interaktive Icon trägt `contentDescription`. |
| **UX-Flow / State** | 🔴 1 Defekt (F3, `low–medium`). Sonst **ungewöhnlich diszipliniert**: Toasts auf jede Aktion, Confirm-Dialoge bei destruktiven Ops, Loading/Disabled-States bei I/O. |
| **Localization / Text** | 🔴 1 Defekt (F1, `medium`) + Low-Notes. RTL, Overflow/Ellipsize, hardcoded-XML alle sauber. |

Die „checked clean"-Liste (§3) ist umfangreich — die App ist im UX-Grundgerüst
solide; die drei Funde sind punktuelle Ausreißer, keine systemische Lücke.

---

## 1. Findings

| # | Achse | Ort | Was | Severity |
|---|---|---|---|---|
| **F1** ✅ | L10n | `OnboardingViewModel` (5 Sites) | hartcodierte **englische** Fehler-Toasts → Deutsch-Nutzer sieht Englisch | `medium` |
| **F2** ✅ | a11y | `item_color_swatch.xml` + `ColorCustomizationDialogFragment` | Farb-Swatches **ohne Label** + **40dp** Touch-Target (<48dp) | `medium` |
| **F3** ✅ | UX | `HiddenAppsViewModel.onDoneClicked` | Save-Fehler **komplett stumm** (bereits übersetzter String nie verdrahtet) | `low–medium` |

### F1 — Onboarding: hartcodierte englische Fehler-Strings · ✅ umgesetzt
`app/.../ui/onboarding/OnboardingViewModel.kt:105,148,160,195,212`

> **Erledigt** (Branch `fix/audit-18-ux`): `OnboardingEvent.ShowError` trägt jetzt
> ein `@StringRes Int` (wie `UiEvent.ShowToast`), die Activity löst via `getString`
> auf. `error_loading_apps` wiederverwendet; `error_loading_favorites` und
> `onboarding_error_save_failed` in beiden Locales neu. VM-Test asserted den
> resId statt des Literals. Strings-Parity + Rule 13 grün.

**Verifiziert am Code.** `OnboardingEvent.ShowError(val message: String)`
(`OnboardingEvent.kt:4`) trägt einen **Roh-String**, und `OnboardingActivity.kt:251-252`
routet ihn direkt in `showToastSafe(event.message, Toast.LENGTH_LONG)`. Fünf Sites
emittieren drei distinct englische Literale auf echten Fehlerpfaden (in
`catch (Throwable)` nach fehlgeschlagenem App-/Favoriten-Load oder -Save):

- `:105` „Could not load apps. Please try again."
- `:148 / :160 / :195` „Could not load favorites."
- `:212` „Save failed. Please try again."

Ein Deutsch-Locale-Nutzer, der beim Onboarding einen Load-/Save-Fehler trifft,
sieht **englischen** Text — Verstoß gegen die Localization-Regel (UI-Strings in
`strings.xml` + `values-de`). Unsichtbar für den Parity-Linter, weil gar **kein**
Resource-Key existiert. Der Kontrast sitzt eine Zeile darunter:
`ShowLimitReachedToast` (`OnboardingActivity.kt:255`) nutzt korrekt
`getString(R.string.favorites_limit_reached, …)`. (Ein bereits vorhandener,
ungenutzter `error_loading_apps` würde den `:105`-Fall abdecken.)

**Fix:** String-Ressourcen anlegen (beide Locales) und `ShowError` auf ein
`@StringRes Int` umstellen (wie `UiEvent.ShowToast` in `ui/base/UiEvent.kt:10`
es bereits macht), Activity zieht via `getString`.

### F2 — Farb-Swatches: kein Label + Touch-Target unter 48dp · ✅ umgesetzt
`app/.../res/layout/item_color_swatch.xml:9-28` · `app/.../ui/colorcustomization/ColorCustomizationDialogFragment.kt` (`populatePalette`)

> **Erledigt** (Branch `fix/audit-18-ux`): `contentDescription` je Swatch gesetzt
> (Auto → bestehendes `color_automatic`; Farbe → Hex-Wert), und Klick + a11y-Fokus
> von der 40dp-Card auf den 56dp-Zellen-Root verlagert → Touch-Target ≥48dp, ein
> gelabelter TalkBack-Knoten pro Swatch. Keine neuen Strings. View-Inflation/
> a11y-Attribut (Rule 10) → per Compile verifiziert, kein Unit-Test extrahiert.

**Verifiziert am Layout.** Jede Farbwahl ist eine `MaterialCardView`
(`color_swatch_card`), im Code klickbar gemacht, **ohne** Text und **ohne**
`contentDescription` (weder XML noch Code — bestätigt: kein `contentDescription`
im ganzen Item-Layout; das `auto_icon`-`ImageView` hat auch keins). Zwei Defekte:

- **a11y-Label:** TalkBack kündigt beim Fokussieren eines der ~14 Swatches nichts
  Sinnvolles an — ein Screen-Reader-Nutzer kann die Farben nicht unterscheiden
  und „Auto" nicht von einer Farbe trennen (in beiden Paletten: Textfarbe +
  Chip-Hintergrund).
- **Touch-Target:** Die klickbare Card ist `40dp × 40dp` (`:11-12`) im 56dp-Frame,
  der Listener sitzt auf der Card, nicht dem Frame → effektiv 40dp < 48dp-Minimum.

**Fix:** `contentDescription` im Code setzen (Farbwert/Name je Swatch; „Auto" für
den Auto-Eintrag — braucht evtl. neue Strings) und das Touch-Target auf ≥48dp
bringen (Listener auf den 56dp-Frame legen oder `minWidth/minHeight`).

### F3 — HiddenApps: Save-Fehler komplett stumm · ✅ umgesetzt
`app/.../ui/hiddenapps/HiddenAppsViewModel.kt:159-164` (`onDoneClicked`-Catch)

> **Erledigt** (Branch `fix/audit-18-ux`): `ShowToast(error_saving_hidden_apps)`
> vor `NavigateUp` (bereits übersetzter String verdrahtet). Die zwei bestehenden
> Failure-Tests asserten jetzt den Toast (mit korrektem String) vor NavigateUp.

**Verifiziert am Code.** Der Catch loggt und navigiert weg — **kein** Toast:

```kotlin
} catch (e: Throwable) {
    TimberWrapper.silentError(e, "Error saving hidden apps")
    sendEvent(UiEvent.NavigateUp)   // navigiert weg, KEIN Fehler-Toast
}
```

**Smoking Gun:** `error_saving_hidden_apps` ist in **beiden** Locales definiert
(`values/strings.xml:170`, `values-de/strings.xml:149`) — aber `grep` bestätigt
**null** Code-Referenzen. Der String wurde für genau diesen Catch geschrieben und
nie verdrahtet. Die zwei Schwester-Screens machen es richtig:
`SwipeActionsViewModel.onDoneClicked` toastet `error_saving_swipe_actions` vor
NavigateUp, `OnboardingViewModel` zeigt `ShowError` und bleibt stehen. HiddenApps
ist der Ausreißer, der weder toastet noch bleibt.

**User-Symptom:** Nutzer tippt Done, der Screen schließt wie bei Erfolg — aber die
Apps sind **nicht** versteckt und es gibt **null** Feedback. Trigger (DataStore-
Write-Fehler) ist selten → `low–medium`. **Fix trivial:**
`sendEvent(UiEvent.ShowToast(R.string.error_saving_hidden_apps))` vor NavigateUp
(bereits übersetzter String, nur zu verdrahten).

---

## 2. Low- / Kosmetik-Notes (festgehalten, nicht als Fix eingeplant)

- **DoubleClick-Aktionen auf Clock/Datum/Akku** (`HomeFragment.kt:1215-1231`) sind
  per TalkBack nicht auslösbar (ein `ACTION_CLICK` erfüllt den Double-Click nie).
  Design-inhärent, die Power-User-Aktion ist versteckt; die Texte selbst werden
  angesagt. `low`.
- **Favoriten-Reorder** (`FavoritesSortFragment`) via `ItemTouchHelper` ohne
  a11y-Move-Actions → für TalkBack/Switch nicht bedienbar; mitigiert durch die
  „Alphabetical"/„Reset"-Buttons. `low`.
- **Battery-`%` mit Latin-Ziffern** (`ClockDelegate.kt:118`, Raw-Template) vs.
  Locale-Ziffern bei Clock/Datum — nur für Arabic-Indic-Locales relevant, die die
  App nicht shippt. Sehr `low`.
- **Datums-Feldreihenfolge fix** (`ClockDelegate.kt:139`, `"E, d MMM"`) — Wörter
  lokalisiert, Reihenfolge nicht; für en/de fine, `DateFormat.getBestDateTimePattern`
  wäre robust. `low`.
- **`backup_and_more`** ist `%1$d`-formatiert statt `<plurals>`. **Nachgeprüft:**
  Zähler = 1 ist zwar erreichbar (`maxDisplayed + 1` fehlende Apps), aber der
  gerenderte Text ist in **beiden** ausgelieferten Locales grammatisch korrekt —
  weder das englische „more" noch das deutsche „weitere" (feminin, elidiertes
  „App") flektiert hier, ein `<plurals>` hätte also `one == other` und wäre ein
  No-op. **Kein Defekt** (die frühere „liest schief"-Einschätzung war für en/de
  falsch); nur der KDoc in `MissingAppsFormatter.kt` behauptete fälschlich
  „inkl. Pluralisierung" — korrigiert. Eine `<plurals>`-Umstellung bliebe rein
  strukturelles Future-proofing für eine dritte Sprache, bewusst nicht gemacht.
- **`CustomNamesUiState.isLoading`** wird gesetzt aber nie gerendert
  (`CustomNamesActivity.updateUi` liest es nicht) — Load ist sub-Sekunde, kein
  hängender State. Kosmetik.

---

## 3. Gegengeprüft & sauber (Auszug)

- **a11y-Baum:** Home-Favoriten sind echte Text-`Button`s; App-Drawer/Onboarding/
  Hidden/Swipe/CustomNames haben Such-Hints + Text-Rows; alle FABs / 48dp-Icon-
  Buttons im Wallpaper-Edit-Cluster + Commands-Panel tragen `contentDescription`;
  `slot_indicator_icon` ist explizit `contentDescription="@null"` (dekorativ);
  Context-Menu nutzt Materials `BottomSheetDragHandleView`.
- **UX-Feedback:** Factory-Reset, Backup-Ex/Import, Usage-Ex/Import, Favoriten-
  Sort/Reset, Toggle-Favorite/Hide/Show — alle mit Toast-Feedback + Confirm/
  Optionen-Dialog + Disabled-Buttons während I/O. Kein Ein-Tap-irreversibler-Wipe
  ohne Dialog. Home-Back bewusst ignoriert (es ist der Launcher), kein Trap.
- **RTL:** Favoriten-Alignment mappt das `FavoritesAlignment`-Enum (START/CENTER/
  END) auf `Gravity.START/CENTER_HORIZONTAL/END`; **kein** `Gravity.LEFT/RIGHT`,
  `paddingLeft/Right`, `marginLeft/Right` in Code oder Layouts.
- **Overflow/Clipping:** App-Namen-Rows nutzen `ellipsize="end"`; jedes
  `maxLines="1"` sitzt auf einem einzeiligen Suchfeld; Titel/Subtitel wrappen.
- **Hardcoded-Strings:** kein `android:text="literal"` / literales
  `contentDescription` in Layouts; alle anderen Toasts/Dialoge/Snackbars lösen
  via `getString(R.string.…)` auf, Backup nutzt `<plurals>` + locale-aware
  `DateUtils.getRelativeTimeSpanString`, Zeit respektiert `is24HourFormat`.
- **Empty-States** (leerer Drawer/kein Suchtreffer/keine Favoriten) bewusst blank
  — minimalistischer text-only Launcher, kein „looks broken"-Defekt.

---

## 4. Fazit

Erste UX-/a11y-Linse: das Grundgerüst ist solide (§3 ist lang), die drei Funde
sind punktuelle Ausreißer, jeder mit einem klaren Kontrast zu einer Stelle, die es
richtig macht:

- **F1** (`medium`) — Onboarding-Fehler auf Englisch, während der Nachbar-Event
  `getString` nutzt.
- **F2** (`medium`) — Farb-Swatches ohne Label + 40dp-Target, während der Rest des
  Baums gelabelt ist.
- **F3** (`low–medium`) — HiddenApps stiller Save-Fehler, während beide Schwester-
  Screens toasten (der übersetzte String liegt ungenutzt bereit).

Alle drei sind **user-sichtbar** (Nicht-Englisch- / TalkBack- / Fehlerpfad-
Nutzer), keine Kosmetik.

**Umgesetzt:** alle drei auf Branch `fix/audit-18-ux`, in der Reihenfolge F3 → F1
→ F2. F1/F3 mit Regressions-Test; F2 als View-Inflation/a11y-Attribut per Compile
verifiziert (Rule 10). Compile/Linter/Tests grün.

**Offen:** keine der drei Findings. Die Low-Notes (§2) bleiben bewusst offen
(design-inhärent bzw. kosmetisch) — Re-Evaluierung nur bei Bedarf.
