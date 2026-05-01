# Known StrictMode Violations

This document tracks known `StrictMode` violations seen in DEBUG. Two categories:

1. **Framework / OEM / library violations** — caused by code outside the
   application (Android, Samsung OneUI, Knox, third-party libraries).
   We cannot fix these in app code; one is mitigated via a workaround.
2. **Intentional app-side violations** — code we wrote that knowingly
   triggers StrictMode because the alternative would compromise a
   stronger guarantee (privacy, correctness, etc.). These are not bugs;
   the rationale lives in the KDoc of the offending method.

---

# Framework / OEM / library violations

## 1. Samsung Framework: IdsController & SharedPreferences

- **Status:** 🔴 Ignored / Unavoidable
- **Context:** App Resume (`handleResumeActivity`)
- **Affected Devices:** Samsung (OneUI)

### Explanation
These violations are caused by internal Android framework components and OEM (Samsung) specific lifecycle hooks, specifically `android.app.IdsController`.

During the `handleResumeActivity` phase, the system internally calls `getSharedPreferences`. The legacy `SharedPreferences` API performs synchronous disk I/O operations on the UI thread when initializing or when `awaitLoadedLocked` is triggered to retrieve values before the background load completes.

1.  **Violation 1 (BlockGuardOs.access):** The system is verifying the existence of the preferences file/directory on disk.
2.  **Violation 2 (SharedPreferencesImpl.awaitLoadedLocked):** The main thread is forced to block and wait for the XML file to be fully read from the disk into memory.

These operations occur within `ActivityThread` and are outside the control of the application code.

### Reference Stacktraces

**Violation 1: Disk Check**
StrictMode policy violation; ~duration=3 ms: android.os.strictmode.DiskReadViolation
    at android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1722)
    at libcore.io.BlockGuardOs.access(BlockGuardOs.java:74)
    at java.io.File.exists(File.java:829)
    at android.app.ContextImpl.getSharedPreferencesPath(ContextImpl.java:1037)
    at android.app.ContextImpl.getSharedPreferences(ContextImpl.java:628)
    at android.app.IdsController.getIdsSharedPreference(IdsController.java:172)
    at android.app.IdsController.doIds(IdsController.java:138)
    at android.app.ActivityThread.handleResumeActivity(ActivityThread.java:6472)


StrictMode policy violation; ~duration=3 ms: android.os.strictmode.DiskReadViolation
    at android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1722)
    at android.app.SharedPreferencesImpl.awaitLoadedLocked(SharedPreferencesImpl.java:283)
    at android.app.SharedPreferencesImpl.getInt(SharedPreferencesImpl.java:328)
    at android.app.IdsController.doIds(IdsController.java:140)
    at android.app.ActivityThread.scheduleVsyncSS(ActivityThread.java:6302)
    at android.app.ActivityThread.handleResumeActivity(ActivityThread.java:6472)



## 2. Samsung Knox: ResolveActivity Hijacking

- **Status:** 🟢 Mitigated (Workaround applied)
- **Context:** AppContextMenu -> ShortcutManager -> resolveActivity
- **Affected Devices:** Samsung (Knox-enabled devices)

### Explanation

Samsung modifies the standard PackageManager.resolveActivity method to perform a synchronous database check for "Kiosk Mode" via KnoxCustomManagerService.

When checking if our app is the default launcher (using resolveActivity), this triggers a DiskReadViolation (SQLite query) on the thread calling it. If called on the Main Thread, this causes UI jank and StrictMode crashes.

### Implemented Solution

We moved the loadActions() call in AppContextMenuDialogFragment to the Dispatchers.IO thread using withContext. This ensures the Samsung database query happens in the background.

### Reference Stacktrace (Pre-Fix)

StrictMode policy violation; ~duration=10 ms: android.os.strictmode.DiskReadViolation
    at android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1722)
    at android.database.sqlite.SQLiteConnection.executeForCursorWindow(SQLiteConnection.java:1376)
    ...
    at com.samsung.android.knox.custom.KnoxCustomManagerService.getProKioskState(...)
    at com.android.server.pm.ResolveIntentHelper.chooseBestActivity(...)
    at android.app.ApplicationPackageManager.resolveActivity(...)
    at com.github.reygnn.kolibri_launcher.data.ShortcutManager.isDefaultLauncher(...)
    at com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuDialogFragment.loadActions(...)


---

# Intentional app-side violations

## 3. ACRA consent read in `attachBaseContext`

- **Status:** 🟡 Intentional / Documented
- **Context:** `KolibriLauncherApp.attachBaseContext`
- **Affected Devices:** All

### Explanation

`attachBaseContext` synchronously reads the ACRA crash-report consent
flag from disk via `runBlocking { CrashReportConsent.hasConsent(base) }`.
StrictMode reports this as a `DiskReadViolation` on the main thread.

This is deliberate. ACRA is initialised here and immediately disabled
(privacy-by-default per CLAUDE.md Rule 8). Re-enabling it asynchronously
in `onCreate` would open a micro-window where ACRA stays disabled even
though the user has consented — and the consent decision is the source
of truth, not "consented but the launcher missed it". The full rationale
lives in the KDoc on `attachBaseContext`.

### Cost vs. benefit

The read is a single SharedPreferences key, effectively free on warm
starts. Switching to `runBlocking(Dispatchers.IO)` would not help —
the inner suspend already moves I/O off the calling thread; the outer
`runBlocking` always blocks its caller regardless.

### Reference Stacktrace

StrictMode policy violation: android.os.strictmode.DiskReadViolation
    at android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(...)
    at android.app.SharedPreferencesImpl.awaitLoadedLocked(...)
    at com.github.reygnn.kolibri_launcher.ui.util.CrashReportConsent$hasConsent$2.invokeSuspend(...)
    at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(...)
    at com.github.reygnn.kolibri_launcher.KolibriLauncherApp.attachBaseContext(KolibriLauncherApp.kt)
