# Dependency Migration Strategie für Kolibri Launcher

## Übersicht

Dieses Dokument beschreibt die langfristige Strategie für die Migration der "frozen" Dependencies in der `build.gradle.kts`.

**Aktueller Stand (Oktober 2025):**
- Android Version: 16 (API 36)
- minSdk / compileSdk / targetSdk: 36
- Tests: 106 Unit Tests + 80 Android Tests = **186 Tests passing** ✅
- Alle kritischen Dependencies sind mit `DO NOT UPGRADE/CHANGE` gesichert

---

## Kritische Dependencies und ihre Zukunft

### 🔴 Hohe Migrations-Wahrscheinlichkeit

#### 1. Espresso 3.5.1
```kotlin
val espressoVersion = "3.5.1"  // DO NOT CHANGE !!!
```

**Problem:** Google aktualisiert Espresso für neue Android-Versionen  
**Zeitrahmen:** Wahrscheinlich bei Android 18-19 (2027-2028)  
**Symptome:** 
- Tests laufen nicht mehr auf neueren Android-Versionen
- Compile-Fehler mit neueren AGP-Versionen
- Deprecated API Warnings

**Migrations-Aufwand:** Mittel (1-2 Tage)  
**Lösung:** Schrittweise auf neuere Espresso-Version updaten und alle UI-Tests durchlaufen lassen

---

#### 2. Mockito 5.2.0 / 5.3.1
```kotlin
val mockitoKotlinVersion = "5.3.1"  // DO NOT CHANGE !!!
val mockitoCoreVersion = "5.2.0"   // DO NOT CHANGE !!!
```

**Problem:** Neue Kotlin-Versionen (besonders Kotlin 3.x) könnten inkompatibel sein  
**Zeitrahmen:** Bei Kotlin 2.x → 3.x Migration (vermutlich 2027-2028)  
**Symptome:**
- Compile-Fehler in Tests
- Mocking funktioniert nicht mehr
- Inkompatibilität mit neueren Kotlin-Features

**Migrations-Aufwand:** Mittel-Hoch (2-3 Tage)  
**Lösung:** Mockito auf neuere Version updaten, evtl. MockK als Alternative evaluieren

---

#### 3. Hilt 2.57.1
```kotlin
val hiltVersion = "2.57.1"  // DO NOT UPGRADE !!!
```

**Problem:** Neue Android Gradle Plugin (AGP) Versionen erfordern neuere Hilt-Versionen  
**Zeitrahmen:** Bei Android Studio Major Updates (2026-2027)  
**Symptome:**
- Build-Fehler mit neuen AGP-Versionen
- Kapt-Probleme
- Inkompatibilität mit neuen Kotlin-Versionen

**Migrations-Aufwand:** Mittel (1-2 Tage)  
**Lösung:** Hilt auf neuere stabile Version updaten, alle DI-Tests durchlaufen lassen

---

#### 4. JUnit 4.13.2
```kotlin
val junitVersion = "4.13.2"  // DO NOT UPGRADE !!!
```

**Problem:** Google pusht zu JUnit 5 (Jupiter), JUnit 4 Support könnte enden  
**Zeitrahmen:** Ab 2028 zunehmender Druck, funktioniert aber noch Jahre  
**Symptome:**
- Deprecation-Warnings
- Neue Testing-Features nur in JUnit 5
- Evtl. Support-Ende in zukünftigen Android-Versionen

**Migrations-Aufwand:** Hoch (1-2 Wochen!)  
**Lösung:** 
- Großes Refactoring aller Tests
- JUnit 4 → JUnit 5 Migration
- Neue Assertions und Annotations lernen

---

#### 5. Timber 5.0.1
```kotlin
val timberVersion = "5.0.1"  // DO NOT UPGRADE !!!
```

**Problem:** Timber 6.x hat Breaking Changes  
**Zeitrahmen:** Funktioniert vermutlich noch viele Jahre  
**Symptome:**
- Verpasste neue Features
- API-Änderungen bei Migration

**Migrations-Aufwand:** Niedrig (wenige Stunden)  
**Lösung:** Logging-Calls im Code anpassen, relativ schmerzfrei

---

### ✅ Relativ sichere Dependencies

Diese können regelmäßig aktualisiert werden ohne große Probleme:

```kotlin
val truthVersion = "1.4.5"  // OK to upgrade
val lifecycleVersion = "2.9.4"  // OK to upgrade
val navigationVersion = "2.9.4"  // OK to upgrade
val coroutinesVersion = "1.10.2"  // OK to upgrade
val turbineVersion = "1.2.1"  // OK to upgrade
val testRulesVersion = "1.7.0"  // OK to upgrade
```

**Empfehlung:** Bei jedem Android-Update prüfen und aktualisieren

---

## Zeitplan und Migrations-Wahrscheinlichkeit

| Jahr | Android Version | API Level | Migrations-Wahrscheinlichkeit | Aufwand |
|------|----------------|-----------|-------------------------------|---------|
| 2025 | Android 16 | 36 | ✅ Keine Migration nötig | 0 Tage |
| 2026 | Android 17 | 37 | 🟡 Optional (Tests durchführen) | 0-1 Tag |
| 2027 | Android 18 | 38 | 🟠 Wahrscheinlich (Espresso/Hilt) | 2-4 Tage |
| 2028 | Android 19 | 39 | 🔴 Sehr wahrscheinlich (JUnit 5) | 1-2 Wochen |
| 2029+ | Android 20+ | 40+ | 🔴 Unvermeidbar | 2+ Wochen |

---

## Migrations-Strategie: Schritt für Schritt

### Phase 1: Vorbereitung (vor jeder Android-Version)

```bash
# 1. Neuen Feature-Branch erstellen
git checkout -b feature/android-XX-migration

# 2. Backup erstellen
DATE=$(date +%Y-%m-%d)
cp app/build.gradle.kts backup/build.gradle.kts.app.$DATE-aXX

# 3. Baseline dokumentieren
# Notiere: Welche Tests laufen? Welche Warnings gibt es?
./gradlew test connectedAndroidTest > test-baseline.txt
```

### Phase 2: Einzelne Dependency Updates

**Wichtig:** Immer NUR EINE Dependency pro Test-Zyklus updaten!

```kotlin
// Beispiel: Espresso updaten
// VORHER:
val espressoVersion = "3.5.1"  // DO NOT CHANGE !!!

// Schritt 1: Kommentar anpassen
val espressoVersion = "3.5.1"  // Testing upgrade to 3.7.0

// Schritt 2: Version ändern
val espressoVersion = "3.7.0"  // Testing upgrade

// Schritt 3: Testen
./gradlew clean
./gradlew test
./gradlew connectedAndroidTest

// Schritt 4a: Bei Erfolg committen
git add app/build.gradle.kts
git commit -m "Update espresso: 3.5.1 → 3.7.0 (all tests passing)"

// Schritt 4b: Bei Fehler zurückrollen
git checkout -- app/build.gradle.kts
# Fehler dokumentieren in migration-notes.md
```

### Phase 3: Reihenfolge der Updates

**Empfohlene Update-Reihenfolge (von einfach zu komplex):**

1. **Timber** (einfach, wenige Änderungen)
2. **Truth** (meist problemlos)
3. **Lifecycle/Navigation** (gut getestet von Google)
4. **Coroutines** (stabiles API)
5. **Turbine** (kleine Library)
6. **Hilt** (mittlerer Aufwand, gut dokumentiert)
7. **Espresso** (kann knifflig sein)
8. **Mockito** (Verhaltensänderungen möglich)
9. **JUnit 4→5** (ZULETZT, größter Aufwand!)

### Phase 4: Test-Driven Migration

```bash
# Vor jedem Update:
./gradlew test connectedAndroidTest --info > before-update.log

# Nach jedem Update:
./gradlew clean
./gradlew test connectedAndroidTest --info > after-update.log

# Vergleichen:
diff before-update.log after-update.log

# Bei Fehlern:
# 1. Fehler in migration-notes.md dokumentieren
# 2. Dependency-Version zurückrollen
# 3. Alternative Lösungen recherchieren
# 4. Später erneut versuchen
```

---

## Backup-Strategie

### Backup vor jeder Migration

```bash
# Vollständiges Backup aller Build-Files
DATE=$(date +%Y-%m-%d)
ANDROID_VERSION="a17"  # Anpassen!

cp app/build.gradle.kts backup/build.gradle.kts.app.$DATE-$ANDROID_VERSION
cp build.gradle.kts backup/build.gradle.kts.project.$DATE-$ANDROID_VERSION
cp gradle.properties backup/gradle.properties.$DATE-$ANDROID_VERSION
cp settings.gradle.kts backup/settings.gradle.kts.$DATE-$ANDROID_VERSION

# Git-Tag setzen
git tag "build-stable-$ANDROID_VERSION-$DATE"
git push --tags
```

### Backup-Struktur im Projekt

```
backup/
├── build.gradle.kts.app.2025-10-01-a16       # Android 16 - Baseline
├── build.gradle.kts.app.2026-XX-XX-a17       # Android 17
├── build.gradle.kts.app.2027-XX-XX-a18       # Android 18
├── build.gradle.kts.project.2025-10-01-a16
├── gradle.properties.2025-10-01-a16
├── settings.gradle.kts.2025-10-01-a16
├── migration-notes.md                         # Dokumentation aller Migrations-Versuche
└── README.md
```

---

## JUnit 4 → JUnit 5 Migration (Spezialfall)

Dies ist die größte Migration und sollte separat geplant werden.

### Vorbereitung

```bash
# Separater Branch für JUnit 5 Migration
git checkout -b feature/junit5-migration

# Dokumentation lesen
# https://junit.org/junit5/docs/current/user-guide/
```

### Schritte

1. **Dependency-Änderungen**
```kotlin
// Entfernen:
testImplementation("junit:junit:4.13.2")

// Hinzufügen:
testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
testRuntimeOnly("org.junit.platform:junit-platform-launcher")
```

2. **Import-Änderungen in allen Test-Dateien**
```kotlin
// Alt (JUnit 4):
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Assert.*

// Neu (JUnit 5):
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
```

3. **Annotations-Änderungen**
```kotlin
// Alt:
@Before fun setup() { }
@After fun tearDown() { }

// Neu:
@BeforeEach fun setup() { }
@AfterEach fun tearDown() { }
```

4. **Testen in Etappen**
- 10% der Tests migrieren → testen
- 25% der Tests migrieren → testen  
- 50% der Tests migrieren → testen
- 100% der Tests migrieren → testen

**Aufwand:** 1-2 Wochen für ~100 Unit Tests

---

## Monitoring und Warnings

### Vor jeder Migration: Dependency-Check

```bash
# In Android Studio: 
# Analyze → Inspect Code → Dependency Analysis

# Oder via Gradle:
./gradlew dependencyUpdates

# Prüfe auf:
# - Deprecated APIs
# - Security Vulnerabilities
# - Breaking Changes in Release Notes
```

### Regelmäßige Checks (quartalsweise)

```bash
# Checklist:
☐ Neue Android-Version angekündigt?
☐ Neue AGP-Version verfügbar?
☐ Neue Kotlin-Version stabil?
☐ Deprecation-Warnings im Build-Log?
☐ Security-Updates für Dependencies?

# Bei "Ja" → Migration planen
```

---

## Notfall-Rollback

Falls nach einem Update alles schief geht:

```bash
# Schritt 1: Sofort zurück zum letzten funktionierenden Zustand
git checkout backup/stable-build-aXX
cp backup/build.gradle.kts.app.YYYY-MM-DD-aXX app/build.gradle.kts

# Schritt 2: Tests laufen lassen
./gradlew clean test connectedAndroidTest

# Schritt 3: Wenn Tests grün → committen
git add app/build.gradle.kts
git commit -m "ROLLBACK: Revert to stable build config from YYYY-MM-DD"

# Schritt 4: Migration-Fehler dokumentieren
# In migration-notes.md: Was ist schief gegangen? Warum?
```

---

## Dokumentation während Migration

Erstelle `backup/migration-notes.md`:

```markdown
# Migration Log

## Android 16 → Android 17 (2026-XX-XX)

### Versuch 1: Espresso Update
- **Von:** 3.5.1
- **Zu:** 3.7.0
- **Ergebnis:** ✅ Erfolgreich
- **Probleme:** Keine
- **Tests:** 186/186 passing

### Versuch 2: Hilt Update
- **Von:** 2.57.1
- **Zu:** 2.60.0
- **Ergebnis:** ❌ Fehlgeschlagen
- **Probleme:** Kapt-Fehler mit Kotlin 2.0.20
- **Lösung:** Kotlin erst auf 2.1.0 updaten
- **Tests:** Build failed

### Versuch 3: Hilt Update (Retry)
- **Von:** 2.57.1
- **Zu:** 2.60.0
- **Ergebnis:** ✅ Erfolgreich
- **Probleme:** Nach Kotlin-Update keine
- **Tests:** 186/186 passing
```

---

## Zusammenfassung

### ✅ Du hast jetzt (2025):
- Perfekt funktionierende Build-Config
- 186 Tests als Sicherheitsnetz
- Klare Dokumentation
- Backup-Strategie

### ⏰ In 1-2 Jahren (2026-2027):
- Erste kleine Updates (Espresso, Hilt)
- Aufwand: 2-4 Tage
- Gut planbar

### 🔴 In 3-4 Jahren (2028-2029):
- Größere Migration (JUnit 5)
- Aufwand: 1-2 Wochen
- Aber: Du hast Zeit zur Vorbereitung!

### 💡 Wichtigste Regel:
**Immer nur EINE Dependency pro Test-Zyklus updaten!**

---

## Checkliste für jede Migration

```
☐ Feature-Branch erstellen
☐ Backup der aktuellen Config
☐ Baseline-Tests dokumentieren (alle 186 Tests grün?)
☐ Release Notes der neuen Dependency-Version lesen
☐ NUR EINE Dependency updaten
☐ Clean Build durchführen
☐ Alle Tests laufen lassen
☐ Bei Erfolg: Committen und zur nächsten Dependency
☐ Bei Fehler: Rollback, dokumentieren, später retry
☐ Nach allen Updates: Neues Backup erstellen
☐ Git-Tag setzen für stabile Version
```

---

**Erstellt:** 2025-10-01  
**Baseline:** Android 16 (API 36), 186 Tests passing  
**Nächstes Review:** 2026-Q3 (vor Android 17)

---

*Viel Erfolg bei den zukünftigen Migrations! Mit dieser Strategie und deinen 186 Tests als Sicherheitsnetz bist du bestens vorbereitet.* 🚀