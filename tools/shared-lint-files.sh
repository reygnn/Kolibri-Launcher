#!/usr/bin/env bash
# =============================================================================
# Shared-module lint positive lists (neutral home)
# =============================================================================
# Files in the SHARED modules (:core / :common-ui / :common-data / :common-android
# / :feature-crashreporting) that are on a positive-list gate. They belong to
# neither app, so they live here and are sourced by BOTH orchestrators
# (kolibri/tools/check-conventions.sh AND nyx/tools/check-conventions.sh) — that
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

# Cancellation-rethrow whitelist — shared-module files (see cancel_files in each
# orchestrator for the app-specific ones).
SHARED_CANCEL_FILES=(
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
)

# Exception-vs-Throwable breadth whitelist — shared-module allocation boundaries.
SHARED_OOM_FILES=(
  "$_shared_root/common-ui/src/main/java/com/github/reygnn/launcher/common/ui/wallpaper/ZoomableImageView.kt"
  "$_shared_root/common-data/src/main/java/com/github/reygnn/launcher/common/data/wallpaper/WallpaperFileManager.kt"
  "$_shared_root/common-data/src/main/java/com/github/reygnn/launcher/common/data/wallpaper/WallpaperRepositoryImpl.kt"
  "$_shared_root/common-data/src/main/java/com/github/reygnn/launcher/common/data/wallpaper/WallpaperBitmapLuminanceImpl.kt"
)

# Init-order launch whitelist — shared-module files (none today; FolderIconRenderer
# is nyx-specific and stays in nyx's list).
SHARED_INITORDER_FILES=(
)
