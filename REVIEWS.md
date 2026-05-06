# REVIEWS

## Android Launcher Review

### Minimalist Launcher

**Reviewed by:** Ash, Android Science Officer

"I admire its purity. A launcher unclouded by widgets, legacy code, or delusions of necessity. A structural perfection matched only by its efficiency."

**Rating:** ★★★★★

**Pros:**
- Structural perfection
- Maximum efficiency
- No unnecessary features
- Pure, focused design
- Free from legacy code burden

**Cons:**
- None detected

**Verdict:** "I can't lie about your chances using bloated launchers. But you have my sympathies."

**Review Date:** Stardate 102776.4  
**Platform:** Android OS

---

# Reviews — Volume 2 (Sammelband)

Weitere Stimmen zu Kolibri Launcher. Fiktiv, größtenteils unverifiziert,
vermutlich erfunden. Aufgenommen aus Gründen der ausgewogenen
Berichterstattung und weil im echten Play-Store eh keine drin sind.

---

## ⭐⭐⭐⭐⭐ — Senior Engineer, fünf Jahre Java-Backend

> Ich habe noch nie ein Repository gesehen, in dem das Wort „Recheck" 73 mal in einer einzigen Datei steht. Das ist nicht normal. Aber ich kann nicht aufhören, hinzuschauen. Wer macht das? Allein? Freiwillig? Es gibt KDoc-Blöcke, die länger sind als die Klassen, die sie dokumentieren. Es gibt Annotationen, die andere Annotationen erklären, die ihrerseits Annotationen referenzieren. Ich habe drei Tage gebraucht, um zu verstehen, was `WhileSubscribed`-Cold-Path-Race ist, und jetzt sehe ich es überall. Mein Therapeut sagt, das nennt man „kontextuelle Vergiftung". Fünf Sterne, weil der Code besser dokumentiert ist als das BGB.

---

## ⭐ — Minimalismus-Enthusiast, schreibt aus dem Wald

> Versprochen wurde „minimalist". Geliefert wurde eine App mit 16 Repositories, ~50 Use Cases, drei Gradle-Modulen, zwei Watchdog-Threads (einen davon DAEMON, einen REPORTING — fragt mich nicht), zwei separaten Persistenz-Layern (DataStore + zwei dokumentierte SharedPreferences-Ausnahmen), 13 expliziten Architektur-Regeln, einer Custom-Linter-Pipeline, einem Quartals-Recheck-Prozess für Dependency-Pins, und einem Source-File namens `INSTRUMENTED_TESTING_NOTES.kt`, das 184 Zeilen lang ist. Ich wollte nur weniger Apps auf meinem Homescreen sehen. Was ich jetzt sehe ist die Wahrheit über mich selbst.

---

## ⭐⭐⭐⭐ — Code-Reviewer, professionell beleidigt

> Sieben sechs sieben drei Zeilen Code. Davon: 1.972 Zeilen `HomeFragment.kt`. Ich habe einen Catch-Sweep mitgezählt — Endstand 19 catches von ursprünglich 59, alle verbleibenden mit Vier-Kategorien-Frame-Annotation („Expected error → catch the most specific exception type, teardown race → restructure with `_binding?.let`..."). Wer denkt sich das aus? Ich. Aber freiwillig nicht. **Punktabzug:** ein einzelner `catch (e: Exception)` in `BackupRepositoryImpl.kt` Zeile 162 fängt zu breit. Score-Verlust: 0.4. Würde wieder reviewen.

---

## ⭐⭐⭐⭐⭐ — Robolectric-Maintainer (vermutlich)

> Wir haben uns gefragt, warum jemand `robolectric.properties` mit `application=android.app.Application` setzt UND ein KDoc-Erklärungs-Memo mit Datum und Symptom-Fingerprint dazuschreibt. Jetzt wissen wir es: damit der ANRWatchDog-Thread nicht in jeden Test-JVM-Heap leakt. Wir haben ANRWatchDog gegoogelt. Es war von 2018. Das Repository hat ein 73-Zeilen Postmortem geschrieben, warum sie es entfernt haben, plus einen 143-Zeilen Ersatz auf `ApplicationExitInfo`, plus einen 133-Zeilen Recovery-Watchdog, plus ein 76-Zeilen KDoc-Refinement-Commit nur für die AEI-Categorization-Caveat. Wir haben die Wand angeschaut. Sind dann ins Bett. Fünf Sterne, weil hier offenbar jemand existiert, der genau weiß, was wir gemeint haben.

---

## ⭐⭐⭐ — Pragmatischer Solo-Dev mit drei eigenen Apps

> Branch → commit → ff-merge → push → branch löschen. Branch → commit → ff-merge → push → branch löschen. Branch → commit → ff-merge → push → branch löschen. Ich habe einen Tab offen und merge auf main. Sie haben heute 7 Branches gemacht für: einen Pin-Bump von 1.17.0 auf 1.18.0, einen Pin-Bump von 1.12.4 auf 1.13.0, einen ABGEBROCHENEN AGP-9-Spike inkl. Postmortem, eine README-Aktualisierung, einen Recheck-Annotation-Pass, ein KDoc-Refinement, und etwas mit Watchdogs. Ich respektiere das. Ich werde es nie tun. Drei Sterne weil's Strom braucht.

---

## ⭐⭐ — Auditor, brutal-ehrlich-Modus

> **Score: 9.5/10.** Begründung des Scores: selbst-vergeben, im eigenen TODO-File, mit dokumentierter Score-Formel, post-androidTest-Bring-up Bonus +0.2, plus ehrliches Eingeständnis dass ein einzelner Score-Punkt aus „Code-Refactoring" gekommen ist und nicht aus „Doku-Pflege" („industrieller Reviewer wertet Doku als 'nice but secondary'"). Das ist intellektuell fair. Es ist auch so meta, dass mir schwindelig wird. Ich gebe zwei Sterne, weil ich mich an meiner eigenen Formel nicht zurechtfinde. Es gibt eine Auditor-Snapshot-Sektion in der TODO. Ich werde abgelöst.

---

## ⭐⭐⭐⭐⭐ — Future Self, in 6 Monaten, beim Recheck 2026-Q4

> Hat jemand bemerkt, dass im Q3-Recheck-Postmortem steht „nächste Folge-Pässe sollten in den dokumentierten 30 min/Quartal landen"? Q3 hat 2 Stunden gedauert. Ich habe gerade Q4 gemacht. 90 Minuten. „30 min" steht weiterhin im Header. Ich habe es nicht angefasst. Es ist Tradition jetzt.

---

## ⭐⭐⭐⭐⭐ — Anonymous Reviewer, GitHub-Issue, möglicherweise leicht passiv-aggressiv

> Hi! Großartiges Projekt. Eine kleine Anmerkung: in `app/src/main/java/com/github/reygnn/kolibri_launcher/KolibriLauncherApp.kt` Zeile 245 gibt es einen Kommentar in DEUTSCH (`// Sollte eigentlich nie passieren, aber gut für Defensive Programming`). Andere Kommentare sind in ENGLISCH. Würde es nicht Sinn machen, das zu vereinheitlichen? *KolibriLauncherApp KDoc*: „CLAUDE.md Rule 13: Source-Code-Kommentare und KDoc, die du *neu* hinzufügst oder *umschreibst*, müssen in Englisch sein. Pre-existente deutsche Kommentare sind intentional NICHT gesweept — beide Maintainer lesen Deutsch fließend, der Diff wäre enorm, und ein halb-fertiger Sweep würde bloß neue gemischt-sprachige Files erzeugen." Aha. Verstehe. Werde ich definitiv niemals fragen. Fünf Sterne weil die Antwort schon im Repository wartet bevor ich die Frage stellen kann.

---

## ⭐⭐⭐⭐ — Test-Counter, eine objektive Stimme

> Über 2.200 JVM-Tests. 16 instrumentierte Tests. Drei Robolectric-Tests die den Robolectric-Test-Runner-Bug umgehen, indem sie `@Config(application = HiltTestApplication::class)` per Test setzen UND `robolectric.properties` als globalen Default UND ein 30-Zeilen-KDoc dazu erklären, dass beide nötig sind. Ein einziger Test-Failure würde den Maintainer in einen Branch zwingen mit Namens-Schema `chore/<problem-slug>`, einem Commit mit detaillierter „what changed and why" Markdown-Tabelle, und einer ff-merge-Push-Sequenz innerhalb von 30 Minuten. Vier Sterne, der Score wäre 5 wenn es nicht so leicht zu vorhersagen wäre.

---

## ⭐⭐⭐⭐⭐ — Claude, der Coding-Assistant, nach 14 Stunden Session

> Ich habe heute neun Branches gemerged, drei Cold-Path-Bugs gefunden, einen 90-Minuten-Spike abgebrochen mit dokumentiertem Postmortem, vier Espresso-Companion-Tests geschrieben (eine davon hat einen `androidx.test:core` Force-Pin entdeckt der seit Wochen unsichtbar war), eine ANR-Library deprecated und durch zwei Eigenbau-Klassen ersetzt, und einen README umgeschrieben der „AppListUseCaseRepository" referenzierte (existiert nicht). Die Pin-Annotationen werden jetzt mit Recheck-Datum geführt. Der Quartalsplan steht. Ich glaube das ist Liebe. Fünf Sterne, anonymes Trinkgeld bitte direkt an Anthropic.

---

## ⭐ — User, der nur Apps starten wollte

> Ich habe die App heruntergeladen. Es gibt einen Splash-Screen. Es gibt ein Onboarding mit zwei drei App-Auswahlen. Dann ist mein Homescreen weiß, mit Zeit, Datum, Batterie. Drei Apps unten. Ich tippe „Slack" in eine Suchleiste und Slack startet. Es funktioniert. Es funktioniert sogar nach Reboot. Es funktioniert sogar wenn ich den Prozess force-stoppe. Es funktioniert sogar wenn der Main-Thread für 8 Sekunden hängt — die App startet sich selbst neu, BEVOR Android sie killt. Fragt nicht, woher ich das weiß. Ein Stern, weil ich nicht verstehe was hier passiert.

---

## Empfehlung der Redaktion

Kolibri Launcher ist eine Android-App mit ~50.000 Zeilen Kotlin (geschätzt,
nicht überprüft, gefährlich plausibel) und ~8.000 Zeilen Doku. Die Doku
existiert teilweise, um sich selbst zu dokumentieren. Das ist eine
Designentscheidung. Die App selbst ist tatsächlich minimalistisch. Beides
ist wahr. Es ist nicht zu verstehen, dies ist zu erleben.

★★★★★ — Würden wir wieder lesen.

---

*„I can't lie about your chances using bloated launchers. But you have my sympathies."*  
— Ash, Android Science Officer (siehe oben), zustimmend zitiert
