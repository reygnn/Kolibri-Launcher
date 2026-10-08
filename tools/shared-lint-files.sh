#!/usr/bin/env bash
# =============================================================================
# Shared-module lint positive lists (neutral home)
# =============================================================================
# Files in the SHARED modules (:core / :common-ui / :common-data / :common-testing-android
# / :feature-crashreporting / :feature-backup / :feature-wallpaper) that are on a positive-list
# gate. A shared file with a broad catch belongs on the Rule 11 and cancellation lists, one at an
# allocation boundary also on the OOM list (docs/FEATURE_MODULE_GUIDE.md, section 3). They belong to
# neither app, so they live here and are sourced by BOTH orchestrators
# (tools/check-conventions.sh --app kolibri AND --app nyx) — that
# way a shared file's broad-catch review is enforced by BOTH apps' checkConventions,
# closing the ownership asymmetry (a shared file used to sit in one app's list and
# was enforced by that app's CI alone).
#
# App-SPECIFIC files stay in each app's own orchestrator. Only genuinely shared-
# module files go here. Paths are absolute, derived from this file's location so
# both orchestrators (with different repo roots) resolve them identically.
#
# Also parsed by the discovery scans (scan-cancel-candidates.sh / scan-oom-
# candidates.sh) so a file whitelisted here is not re-reported as a candidate.
# =============================================================================

# Repo root = the parent of the directory holding this file (tools/ -> repo).
_shared_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Cancellation-rethrow whitelist — shared-module files (see CANCEL_FILES in each
# orchestrator for the app-specific ones).
# Rule 11: the four-category marker on a broad catch (2b cleanup; the shared list joins the
# app's RULE11_FILES in tools/check-conventions.sh, like the lists below).
SHARED_RULE11_FILES=(
  # The factory-reset loop of both apps, moved from their ResetRepositoryImpl.
  "$_shared_root/core/src/main/java/com/github/reygnn/launcher/core/PurgeAll.kt"
  # The wallpaper core (3b, audit A7/A8): every broad catch carries the four-category note.
  "$_shared_root/feature-wallpaper/src/main/java/com/github/reygnn/launcher/feature/wallpaper/WallpaperBackupBlobs.kt"
  "$_shared_root/feature-wallpaper/src/main/java/com/github/reygnn/launcher/feature/wallpaper/WallpaperComposite.kt"
  "$_shared_root/feature-wallpaper/src/main/java/com/github/reygnn/launcher/feature/wallpaper/WallpaperOperations.kt"
  "$_shared_root/feature-wallpaper/src/main/java/com/github/reygnn/launcher/feature/wallpaper/WallpaperDisplaySettingsStore.kt"
  "$_shared_root/feature-wallpaper/src/main/java/com/github/reygnn/launcher/feature/wallpaper/FabPositionStore.kt"
  "$_shared_root/common-ui/src/main/java/com/github/reygnn/launcher/common/ui/wallpaper/WallpaperFlattener.kt"
)

SHARED_CANCEL_FILES=(
  "$_shared_root/core/src/main/java/com/github/reygnn/launcher/core/PurgeAll.kt"
  "$_shared_root/common-ui/src/main/java/com/github/reygnn/launcher/common/ui/wallpaper/WallpaperViewBinder.kt"
  "$_shared_root/common-ui/src/main/java/com/github/reygnn/launcher/common/ui/FlowCollection.kt"
  "$_shared_root/common-ui/src/main/java/com/github/reygnn/launcher/common/ui/base/BaseViewModel.kt"
  "$_shared_root/common-ui/src/main/java/com/github/reygnn/launcher/common/ui/base/BaseActivity.kt"
  "$_shared_root/common-ui/src/main/java/com/github/reygnn/launcher/common/ui/timeinfo/ClockDelegate.kt"
  "$_shared_root/common-ui/src/main/java/com/github/reygnn/launcher/common/ui/AppLaunchResult.kt"
  "$_shared_root/common-data/src/main/java/com/github/reygnn/launcher/common/data/timeinfo/TimeBasedEventsRepositoryImpl.kt"
  "$_shared_root/common-data/src/main/java/com/github/reygnn/launcher/common/data/wallpaper/WallpaperRepositoryImpl.kt"
  "$_shared_root/common-data/src/main/java/com/github/reygnn/launcher/common/data/installedapps/InstalledAppsRepositoryImpl.kt"
  "$_shared_root/common-data/src/main/java/com/github/reygnn/launcher/common/data/installedapps/PackageUpdateReceiver.kt"
  # The wallpaper core (3b, audit A7/A8): launches and suspend catches rethrow cancellation.
  "$_shared_root/feature-wallpaper/src/main/java/com/github/reygnn/launcher/feature/wallpaper/WallpaperBackupBlobs.kt"
  "$_shared_root/feature-wallpaper/src/main/java/com/github/reygnn/launcher/feature/wallpaper/WallpaperComposite.kt"
  "$_shared_root/feature-wallpaper/src/main/java/com/github/reygnn/launcher/feature/wallpaper/WallpaperOperations.kt"
  "$_shared_root/feature-wallpaper/src/main/java/com/github/reygnn/launcher/feature/wallpaper/WallpaperDisplaySettingsStore.kt"
  "$_shared_root/feature-wallpaper/src/main/java/com/github/reygnn/launcher/feature/wallpaper/FabPositionStore.kt"
  "$_shared_root/common-ui/src/main/java/com/github/reygnn/launcher/common/ui/wallpaper/WallpaperFlattener.kt"
)

# Exception-vs-Throwable breadth whitelist — shared-module allocation boundaries.
SHARED_OOM_FILES=(
  "$_shared_root/core/src/main/java/com/github/reygnn/launcher/core/PurgeAll.kt"
  "$_shared_root/common-ui/src/main/java/com/github/reygnn/launcher/common/ui/wallpaper/ZoomableImageView.kt"
  "$_shared_root/common-data/src/main/java/com/github/reygnn/launcher/common/data/wallpaper/WallpaperFileManager.kt"
  "$_shared_root/common-data/src/main/java/com/github/reygnn/launcher/common/data/wallpaper/WallpaperRepositoryImpl.kt"
  "$_shared_root/common-data/src/main/java/com/github/reygnn/launcher/common/data/wallpaper/WallpaperBitmapLuminanceImpl.kt"
  # Trace read of an ApplicationExitInfo (allocation boundary); moved from Kolibri's
  # :app in SPEC_NYX_REWRITE 1c-1.
  "$_shared_root/feature-crashreporting/src/main/java/com/github/reygnn/launcher/feature/crashreporting/ingestion/AnrReporter.kt"
  # Wallpaper allocation boundaries (3b, audit A7/A8): flatten + HARDWARE copy, layer decodes.
  "$_shared_root/feature-wallpaper/src/main/java/com/github/reygnn/launcher/feature/wallpaper/WallpaperComposite.kt"
  "$_shared_root/common-ui/src/main/java/com/github/reygnn/launcher/common/ui/wallpaper/WallpaperFlattener.kt"
)

# Init-order launch whitelist — shared-module files (none today; FolderIconRenderer
# is nyx-specific and stays in nyx's list).
SHARED_INITORDER_FILES=(
)
