# Build Configuration Backups

Dieser Ordner enthält funktionierende Versionen der `build.gradle.kts`.

## Warum existiert dieser Ordner?

Die `build.gradle.kts` enthält kritische Versionsabhängigkeiten, die nicht 
beliebig aktualisiert werden dürfen. KI-Assistenten (Gemini, Claude, etc.) 
neigen dazu, Dependencies zu "optimieren", was oft zu Build-Fehlern führt.

## Verwendung

Falls die `app/build.gradle.kts` kaputt geht:
```bash
# Backup wiederherstellen
cp backup/build.gradle.kts.YYYY-MM-DD app/build.gradle.kts