#!/usr/bin/env bash
# =============================================================================
# Kolibri Launcher — project-convention linter
# =============================================================================
#
# Grep-based checks for the CLAUDE.md rules whose drift was the largest
# defect class found by the post-audit-Sweep-Session 2026-05-03 (see
# TODO.md §7 for the full motivation).
#
# Run via:
#   ./gradlew checkConventions
#   ./tools/check-conventions.sh         (standalone, identical output)
#
# Exit code:
#   0   all checks passed
#   1   at least one rule violated — output lists `file:line` and rule
#   2   environment problem (missing source dir, etc.)
#
# Adding a new check:
#   1. Drop a section below following the same shape (heading comment +
#      grep + report-on-non-empty).
#   2. Update the rule list in CLAUDE.md so the linter and the rule
#      stay in sync.
#
# What the linter intentionally does NOT check yet:
#   - Rule 11 in general (try/catch around can't-throw operations) —
#     needs semantic analysis of the catch body, not pure grep. The
#     section below covers a narrower aspect of Rule 11 (annotation
#     discipline on broad catches in opted-in files).
#   - Rule 13 (German comments in newly-added lines) — would need a
#     git-diff integration; not added until the cost is needed.
#   - Hardcoded UI strings — would need string-literal classification.
#
# Those remain catch-by-review until the simpler grep checks
# stop pulling their weight.
# =============================================================================

set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

# Production sources live in three Gradle modules after the §9.2 split.
src_roots=(
  "$repo_root/app/src/main/java"
  "$repo_root/domain/src/main/java"
  "$repo_root/data/src/main/java"
)

for d in "${src_roots[@]}"; do
  if [ ! -d "$d" ]; then
    echo "ERROR: Source directory not found: $d" >&2
    exit 2
  fi
done

violations=0
report() {
  echo
  echo "═══ $1 ═══"
  echo "$2"
  violations=$((violations + 1))
}

# ─────────────────────────────────────────────────────────────────────────────
# Rule 9 — bare `Timber.e(` outside the documented crash-handling-
# infrastructure files. CLAUDE.md Rule 9's exception list:
#   - KolibriLauncherApp.kt, TimberWrapper.kt
#   - BaseActivity.kt, BaseViewModel.kt
#   - ConsentBootstrap.kt (pre-Hilt bootstrap consent read)
#   - CrashReportingBootstrap.kt (ACRA init + ANR drain + watchdog wiring)
#   (UncaughtCrashHandler.kt and AcraTree.kt are crash-infra too but use
#    android.util.Log.e — not Timber.e — precisely to avoid re-entering the
#    process-wide planted AcraTree, so they need no entry here.)
#   - BackupFragment's fragment-level CoroutineExceptionHandler (one
#     line, identified by message text rather than line number so the
#     check stays robust to imports / blank-line shifts).
# Anything else uses TimberWrapper.silentError so the throw-in-DEBUG
# semantic surfaces programmer-error bugs loudly.
# ─────────────────────────────────────────────────────────────────────────────
rule9_allowed_files='KolibriLauncherApp\.kt|TimberWrapper\.kt|BaseActivity\.kt|BaseViewModel\.kt|ConsentBootstrap\.kt|CrashReportingBootstrap\.kt'

rule9_hits=$(
  grep -rn 'Timber\.e(' "${src_roots[@]}" --include='*.kt' \
    | grep -vE "/($rule9_allowed_files):" \
    | grep -vE 'BackupFragment\.kt:[0-9]+:.*Uncaught coroutine exception in BackupFragment' \
    || true
)

if [ -n "$rule9_hits" ]; then
  report "Rule 9 — bare \`Timber.e(\` outside documented crash-infrastructure files" "$rule9_hits"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Rule 12 — `Timber.Forest.*` is the Java-API form. Project standard is
# the short form `Timber.d / .e / .w / .i / .v`. The Forest form sneaks
# in via Android Studio's auto-import or the "Convert Java to Kotlin"
# action.
# ─────────────────────────────────────────────────────────────────────────────
rule12_hits=$(
  grep -rn 'Timber\.Forest\.' "${src_roots[@]}" --include='*.kt' || true
)

if [ -n "$rule12_hits" ]; then
  report "Rule 12 — \`Timber.Forest.*\` (use short form \`Timber.d\` / \`.e\` / \`.w\`)" "$rule12_hits"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Toast routing — every user-facing toast goes through `Context/Fragment.
# showToastSafe` (ui/util/ToastSafe.kt), which owns the Samsung StrictMode
# relax + the Throwable catch. A bare `Toast.makeText(` (or the rare direct
# `Toast(` constructor) anywhere else silently re-forks that platform
# handling — exactly the drift the de56192 unification removed and this
# sweep finished.
#
# Allowed file: ToastSafe.kt itself (the sole legitimate `Toast.makeText`
# owner). Its KDoc mentions `Toast.makeText(...)` too — same allow entry
# covers it. StrictModeUtil.kt's KDoc names `Toast.makeText` in backticks
# with no trailing `(`, so the pattern never matches it.
#
# Genuine future exceptions (e.g. a toast that must NOT gain the internal
# silentError because it sits inside a crash-safety net — Rule 9's
# transitive form): add the file basename to `toast_allowed_files` with a
# one-line justification, mirroring the Rule 9 exception list.
# ─────────────────────────────────────────────────────────────────────────────
toast_allowed_files='ToastSafe\.kt'

toast_hits=$(
  grep -rnE '\bToast\.makeText\(|\bToast\(' "${src_roots[@]}" --include='*.kt' \
    | grep -vE "/($toast_allowed_files):" \
    || true
)

if [ -n "$toast_hits" ]; then
  report "Toast routing — bare \`Toast.makeText(\` outside \`ToastSafe.kt\` (use \`showToastSafe\`)" "$toast_hits"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Naming — production classes in `data/` use `*RepositoryImpl` since
# commit 0f9e7be. One intentional exception documented in
# app/src/test/CLAUDE.md "Namens-Historie":
#   - WallpaperFileManager (file-helper, not a repository)
# (A second exception used to apply to `DataMigrationManager`; the
# manager has since been removed.)
# ─────────────────────────────────────────────────────────────────────────────
naming_allowed='WallpaperFileManager\.kt'
data_dir="$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data"

naming_hits=$(
  grep -rEn '^(internal |open |abstract |sealed )*class [A-Z][a-zA-Z0-9_]*Manager' \
    "$data_dir" \
    --include='*.kt' 2>/dev/null \
    | grep -vE "/($naming_allowed):" \
    || true
)

if [ -n "$naming_hits" ]; then
  report "Naming — class ending in 'Manager' inside \`data/\` (use \`*RepositoryImpl\`)" "$naming_hits"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Rule 11 (annotation discipline) — broad catches in whitelisted files
# must carry a four-category-frame justification marker nearby.
#
# Scope (positive list, NOT all files):
#   Only files that have explicitly adopted the four-category-frame
#   annotation convention from the file header. Today: MainActivity only
#   (the only file where every kept catch was reviewed and annotated as
#   part of the post-§A audit trail). Add a path here when a new file's
#   catches are reviewed and annotated under the same convention.
#
# What counts as "broad catch":
#   `catch (e: Throwable)` or `catch (e: Exception)`. Narrowed types
#   (`SecurityException`, `ActivityNotFoundException`, `IOException`,
#   etc.) already follow Rule 11 by being narrow — they are not flagged.
#
# The detection logic (regex + window) lives in
# `tools/check-rule11-annotation.awk` so this script and the regression
# test (`tools/check-conventions-test.sh`) share a single source.
# ─────────────────────────────────────────────────────────────────────────────
rule11_files=(
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/main/MainActivity.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/WallpaperEditController.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/HomeFragment.kt"
)
rule11_awk="$script_dir/check-rule11-annotation.awk"

if [ ! -f "$rule11_awk" ]; then
  echo "ERROR: Rule 11 awk script not found: $rule11_awk" >&2
  exit 2
fi

rule11_hits=""
for file in "${rule11_files[@]}"; do
  if [ ! -f "$file" ]; then
    echo "ERROR: Rule 11 whitelist file not found: $file" >&2
    exit 2
  fi
  hits=$(awk -f "$rule11_awk" "$file")
  if [ -n "$hits" ]; then
    rule11_hits="${rule11_hits}${hits}
"
  fi
done

if [ -n "$rule11_hits" ]; then
  report "Rule 11 — broad catch without four-category-frame annotation in whitelisted file" "${rule11_hits%$'\n'}"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Cancellation-rethrow discipline — the coroutine form of Rule 11. In a
# whitelisted file, every broad catch must either sit behind a
# `catch (…: CancellationException) { throw e }` arm or state that its
# try-block cannot suspend (marker: `no suspension point`).
#
# Why aggressive instead of detecting suspension points: a suspend call has
# no syntactic tell. `bitmapLoader.load(uri)` reads exactly like a blocking
# call, so a detector keyed on `withContext` / `.await()` / `delay` would
# have missed 4c09c30b — the regression that motivated this check, where a
# pre-existing catch turned into a cancellation swallower the moment the
# call inside it became suspend, without the catch line ever changing.
#
# Scope (positive list, NOT all files), same growth model as Rule 11 above:
# a file joins once its broad catches have been reviewed and each one is
# either structurally satisfied or annotated. Adding a file with unreviewed
# catches just turns the build red — that IS the review prompt.
#
# The detection logic lives in `tools/check-cancellation-rethrow.awk`;
# regression-test any regex change via
# `tools/check-cancellation-rethrow-test.sh` (manual rerun, not a CI gate).
# ─────────────────────────────────────────────────────────────────────────────
cancel_files=(
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/wallpaper/WallpaperViewBinder.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/main/MainActivity.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/appdrawer/AppDrawerFragment.kt"
  "$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data/InstalledAppsRepositoryImpl.kt"
  "$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data/BackupRepositoryImpl.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/HomeFragment.kt"
  "$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data/TimeBasedEventsRepositoryImpl.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/flow/FlowCollection.kt"
  "$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data/WallpaperRepositoryImpl.kt"
  "$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data/UsageExportRepositoryImpl.kt"
  "$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data/PackageUpdateReceiver.kt"
  "$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data/DataStoreMaintenanceRepositoryImpl.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/base/BaseActivity.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/base/BaseViewModel.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/main/delegate/ClockDelegate.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/main/delegate/WallpaperDelegate.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/backup/BackupFragment.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/backup/BackupViewModel.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/customnames/CustomNamesActivity.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/settings/SettingsFragment.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/settings/SettingsViewModel.kt"
)
cancel_awk="$script_dir/check-cancellation-rethrow.awk"

if [ ! -f "$cancel_awk" ]; then
  echo "ERROR: Cancellation-rethrow awk script not found: $cancel_awk" >&2
  exit 2
fi

cancel_hits=""
for file in "${cancel_files[@]}"; do
  if [ ! -f "$file" ]; then
    echo "ERROR: Cancellation-rethrow whitelist file not found: $file" >&2
    exit 2
  fi
  hits=$(awk -f "$cancel_awk" "$file")
  if [ -n "$hits" ]; then
    cancel_hits="${cancel_hits}${hits}
"
  fi
done

if [ -n "$cancel_hits" ]; then
  report "Cancellation rethrow — broad catch without a CancellationException arm or a \`no suspension point\` marker" "${cancel_hits%$'\n'}"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Flow.catch cancellation-rethrow — the coroutine-operator form of the check
# above, and the one the `catch (Type)` walker cannot see. A `Flow.catch { }`
# arm that logs at report level (silentError / KolibriLog.w|e / Timber.w|e)
# without first rethrowing CancellationException floods ACRA on every upstream
# cancellation (SharingStarted.Eagerly teardown). GLOBAL scan, not a positive
# list — the shape is precise (only logging arms without a guard flag). Logic
# in tools/check-flow-catch-rethrow.awk.
# ─────────────────────────────────────────────────────────────────────────────
flowcatch_awk="$script_dir/check-flow-catch-rethrow.awk"

if [ ! -f "$flowcatch_awk" ]; then
  echo "ERROR: Flow.catch rethrow awk script not found: $flowcatch_awk" >&2
  exit 2
fi

flowcatch_hits=""
while IFS= read -r kt; do
  hits=$(awk -f "$flowcatch_awk" "$kt")
  if [ -n "$hits" ]; then
    flowcatch_hits="${flowcatch_hits}${hits}
"
  fi
done < <(find "${src_roots[@]}" -name '*.kt')

if [ -n "$flowcatch_hits" ]; then
  report "Flow.catch — logging arm without a CancellationException rethrow (floods ACRA on upstream cancellation)" "${flowcatch_hits%$'\n'}"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Unbuffered MutableSharedFlow — a `MutableSharedFlow(...)` built with the
# default zero buffer (`replay = 0, extraBufferCapacity = 0`) silently drops an
# emission when no collector is subscribed (AUDIT-9 #5, `AppUpdateSignal`).
# GLOBAL scan over main sources — the shape is precise (only a bare
# construction with a provably-zero buffer flags; type annotations, imports and
# comment mentions never do). Escape hatch for a provably-safe rendezvous flow:
# a `rendezvous intended` marker within +/-2 lines. Test buffering is out of
# scope (unbuffered-with-immediate-collector is legitimate and fails fast);
# see TESTING_CONVENTIONS.kt §2. Logic in tools/check-unbuffered-sharedflow.awk.
# ─────────────────────────────────────────────────────────────────────────────
sharedflow_awk="$script_dir/check-unbuffered-sharedflow.awk"

if [ ! -f "$sharedflow_awk" ]; then
  echo "ERROR: Unbuffered-SharedFlow awk script not found: $sharedflow_awk" >&2
  exit 2
fi

sharedflow_hits=""
while IFS= read -r kt; do
  hits=$(awk -f "$sharedflow_awk" "$kt")
  if [ -n "$hits" ]; then
    sharedflow_hits="${sharedflow_hits}${hits}
"
  fi
done < <(find "${src_roots[@]}" -name '*.kt')

if [ -n "$sharedflow_hits" ]; then
  report "Unbuffered MutableSharedFlow — drops emissions with no subscriber (add \`extraBufferCapacity = 1\` / \`replay\`, or a \`rendezvous intended\` marker)" "${sharedflow_hits%$'\n'}"
fi

# ─────────────────────────────────────────────────────────────────────────────
# purgeRepository() completeness — a "reset all settings" must wipe every
# statically-declared preference key of the repo. AUDIT-4 #1 caught
# SettingsRepositoryImpl declaring APP_DRAWER_MODE but never removing it in
# purge. Per-file scan over data/*RepositoryImpl.kt: every constant-style
# `val UPPER_SNAKE = <...>PreferencesKey(...)` must be referenced in a
# non-comment line of purgeRepository() (a real remove) or carry a
# `purge-exempt NAME` marker. Dynamic camelCase per-entity keys are excluded;
# a wholesale `.clear()` exempts the file. Logic in
# tools/check-purge-completeness.awk.
# ─────────────────────────────────────────────────────────────────────────────
purge_awk="$script_dir/check-purge-completeness.awk"

if [ ! -f "$purge_awk" ]; then
  echo "ERROR: purge-completeness awk script not found: $purge_awk" >&2
  exit 2
fi

purge_hits=""
while IFS= read -r kt; do
  hits=$(awk -f "$purge_awk" "$kt")
  if [ -n "$hits" ]; then
    purge_hits="${purge_hits}${hits}
"
  fi
done < <(find "$repo_root/data/src/main/java" -name '*RepositoryImpl.kt')

if [ -n "$purge_hits" ]; then
  report "purgeRepository() completeness — declared preference key not wiped on reset (add a \`preferences.remove(...)\` or a \`purge-exempt\` marker)" "${purge_hits%$'\n'}"
fi

# ─────────────────────────────────────────────────────────────────────────────
# registerForActivityResult() placement — must be a field initializer or run in
# onCreate; called from onViewCreated / onResume / etc. it throws
# "LifecycleOwners must call register before they are STARTED" on the next
# config change (AUDIT-5 #2). Global scan over app/ main sources: only a call
# whose innermost enclosing function is a forbidden lifecycle method flags.
# Logic in tools/check-activity-result-placement.awk.
# ─────────────────────────────────────────────────────────────────────────────
arresult_awk="$script_dir/check-activity-result-placement.awk"

if [ ! -f "$arresult_awk" ]; then
  echo "ERROR: registerForActivityResult-placement awk script not found: $arresult_awk" >&2
  exit 2
fi

arresult_hits=""
while IFS= read -r kt; do
  hits=$(awk -f "$arresult_awk" "$kt")
  if [ -n "$hits" ]; then
    arresult_hits="${arresult_hits}${hits}
"
  fi
done < <(find "$repo_root/app/src/main/java" -name '*.kt')

if [ -n "$arresult_hits" ]; then
  report "registerForActivityResult() placement — called from a lifecycle method (move to a field initializer or onCreate)" "${arresult_hits%$'\n'}"
fi

# ─────────────────────────────────────────────────────────────────────────────
# RecyclerView adapter null-out — a Fragment that assigns a non-null
# `recyclerView.adapter` must clear it (`adapter = null`) in onDestroyView, or
# the view hierarchy leaks across the view-recreation cycle (AUDIT-3 #13,
# AUDIT-4 #3). Per-file scan over app/ main sources; Fragments only (Activities
# clear in onDestroy). Escape hatch: an `adapter-nulling n/a` marker on the
# assignment. Logic in tools/check-adapter-nulling.awk.
# ─────────────────────────────────────────────────────────────────────────────
adapter_awk="$script_dir/check-adapter-nulling.awk"

if [ ! -f "$adapter_awk" ]; then
  echo "ERROR: adapter-nulling awk script not found: $adapter_awk" >&2
  exit 2
fi

adapter_hits=""
while IFS= read -r kt; do
  hits=$(awk -f "$adapter_awk" "$kt")
  if [ -n "$hits" ]; then
    adapter_hits="${adapter_hits}${hits}
"
  fi
done < <(find "$repo_root/app/src/main/java" -name '*.kt')

if [ -n "$adapter_hits" ]; then
  report "RecyclerView adapter null-out — Fragment assigns an adapter but never nulls it in onDestroyView (leak)" "${adapter_hits%$'\n'}"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Exception-vs-Throwable breadth — `OutOfMemoryError` is an Error/Throwable, not
# an Exception, so `catch (e: Exception)` around an allocation / system-callback
# boundary (bitmap, inflate, large JSON/ZIP) misses the failure it exists for
# (AUDIT.md category A, ~14×). In a whitelisted allocation-boundary file every
# broad catch must be `Throwable`, a narrowed type, or (pure-I/O only) carry an
# `Exception sufficient` marker; a bare `catch (e: Exception)` flags.
#
# Positive list, same growth model as Rule 11 / cancellation above — a file
# joins once its allocation-boundary catches are reviewed. NOT a global scan:
# most `catch (Exception)` in the tree are correct (race guards where Throwable
# would wrongly swallow OOM, or pure I/O). Logic in
# tools/check-exception-breadth.awk.
# ─────────────────────────────────────────────────────────────────────────────
oom_files=(
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/ZoomableImageView.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/appcontextmenu/AppContextMenuDialogFragment.kt"
  "$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data/BackupRepositoryImpl.kt"
  "$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data/UsageExportRepositoryImpl.kt"
  "$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data/WallpaperFileManager.kt"
  "$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data/WallpaperRepositoryImpl.kt"
  "$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data/wallpaper/WallpaperBitmapLuminanceImpl.kt"
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/crashreporting/ingestion/AnrReporter.kt"
)
oom_awk="$script_dir/check-exception-breadth.awk"

if [ ! -f "$oom_awk" ]; then
  echo "ERROR: exception-breadth awk script not found: $oom_awk" >&2
  exit 2
fi

oom_hits=""
for file in "${oom_files[@]}"; do
  if [ ! -f "$file" ]; then
    echo "ERROR: exception-breadth whitelist file not found: $file" >&2
    exit 2
  fi
  hits=$(awk -f "$oom_awk" "$file")
  if [ -n "$hits" ]; then
    oom_hits="${oom_hits}${hits}
"
  fi
done

if [ -n "$oom_hits" ]; then
  report "Exception breadth — bare \`catch (e: Exception)\` at an allocation boundary (use \`Throwable\` for OOM, or an \`Exception sufficient\` marker)" "${oom_hits%$'\n'}"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Localization parity — every key in res/values/ must have a res/values-de/
# twin and vice versa. A missing German entry silently ships English text to a
# German device (stringResource falls back to the default locale, so nothing
# fails at compile time); an orphaned German entry is dead weight, usually the
# fossil of a renamed English key. `translatable="false"` in the default locale
# exempts. GLOBAL, not a positive list — there is no judgement call here, and
# the codebase is parity-clean today, so this locks that state.
# Logic in tools/check-strings-parity.awk.
# ─────────────────────────────────────────────────────────────────────────────
parity_awk="$script_dir/check-strings-parity.awk"

if [ ! -f "$parity_awk" ]; then
  echo "ERROR: strings-parity awk script not found: $parity_awk" >&2
  exit 2
fi

parity_hits=""
for res in strings arrays; do
  default_xml="$repo_root/app/src/main/res/values/$res.xml"
  de_xml="$repo_root/app/src/main/res/values-de/$res.xml"
  # Only compare pairs that exist — a resource file with no German counterpart
  # at all is a different (and louder) problem than key-level drift.
  [ -f "$default_xml" ] && [ -f "$de_xml" ] || continue
  hits=$(awk -f "$parity_awk" "$default_xml" "$de_xml" | sed "s|^|$res.xml — |")
  if [ -n "$hits" ]; then
    parity_hits="${parity_hits}${hits}
"
  fi
done

if [ -n "$parity_hits" ]; then
  report "Localization parity — key present in only one locale (values/ vs values-de/)" "${parity_hits%$'\n'}"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Rule 2 — contract-test triple completeness. Every `*Repository` interface in
# :domain needs XyzRepositoryContract + FakeXyzRepositoryContractTest +
# XyzRepositoryImplContractTest, or a canonical `NO CONTRACT TEST (ADR)` /
# `NO IMPL CONTRACT TEST (ADR)` marker in the contract file. Rule 2 already
# allowed justified gaps; what this adds is one greppable token for them.
# Logic in tools/check-contract-triple.sh.
# ─────────────────────────────────────────────────────────────────────────────
triple_sh="$script_dir/check-contract-triple.sh"

if [ ! -f "$triple_sh" ]; then
  echo "ERROR: contract-triple script not found: $triple_sh" >&2
  exit 2
fi

triple_hits=$(bash "$triple_sh")
if [ -n "$triple_hits" ]; then
  report "Rule 2 — incomplete contract-test triple (add the missing test, or an ADR marker in the contract)" "$triple_hits"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Settings-store cleanup keep-list (OwnsSettingsStoreKeys). Storage cleanup
# deletes every settings-store key that NO live owner claims (blacklist-of-
# unknown). The failure mode is inverted vs. the old whitelist-of-dead — a
# forgotten LIVE key is DELETED, not left as a harmless orphan — so two gates
# guard it:
#
#  (a) per-owner completeness — every settings-store key an owner file declares
#      must be registered in that file's ownedExactKeys()/ownedKeyPrefixes().
#      awk core in tools/check-settings-keys-registered.awk.
#  (b) straggler guard — any file that DECLARES a DataStore key but is neither an
#      owner nor a known usage/consent writer flags. That is a new settings-store
#      writer (the AnrReporter case) that must join the keep-list, or the cleanup
#      silently deletes its key.
#
# Owners are discovered STRUCTURALLY: a file is an owner iff it overrides an
# OwnsSettingsStoreKeys method. There is deliberately no hand-maintained owner
# list — a second list drifts, and (worse) a new owner could be added only to a
# straggler-allowlist to green the build while escaping completeness gate (a).
# Deriving owners from the override closes that: joining the keep-list (which is
# what silences the straggler) IS implementing the interface, which subjects the
# file to (a). Regression-tested via tools/check-settings-keys-registered-test.sh.
# ─────────────────────────────────────────────────────────────────────────────
settings_keys_awk="$script_dir/check-settings-keys-registered.awk"

if [ ! -f "$settings_keys_awk" ]; then
  echo "ERROR: settings-keys-registered awk script not found: $settings_keys_awk" >&2
  exit 2
fi

owner_marker='override fun ownedExactKeys|override fun ownedKeyPrefixes'

# (a) per-owner completeness — over every structural owner.
owner_files=$(grep -rlE "$owner_marker" \
  "$repo_root/app/src/main/java" "$repo_root/data/src/main/java" 2>/dev/null || true)

if [ -z "$owner_files" ]; then
  report "Settings-store keep-list — no OwnsSettingsStoreKeys owner found (interface renamed / override methods gone?)" "expected at least one owner overriding $owner_marker"
else
  settings_keys_hits=""
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    hits=$(awk -f "$settings_keys_awk" "$f")
    if [ -n "$hits" ]; then
      settings_keys_hits="${settings_keys_hits}${hits}
"
    fi
  done <<EOF
$owner_files
EOF

  if [ -n "$settings_keys_hits" ]; then
    report "Settings-store keep-list — declared key not registered by its owner (add it to ownedExactKeys()/ownedKeyPrefixes(), or storage cleanup will delete it as an orphan)" "${settings_keys_hits%$'\n'}"
  fi
fi

# (b) straggler guard — a DataStore-key writer that is neither a structural owner
# (overrides an OwnsSettingsStoreKeys method -> completeness-checked above) nor a
# known non-settings-store (usage / consent) writer.
settings_nonsettings_writers=" AppUsageRepositoryImpl.kt UsageExportRepositoryImpl.kt ConsentBootstrap.kt "
straggler_hits=""
while IFS= read -r kt; do
  # A real key DECLARATION contains `xxxPreferencesKey(` — imports have no paren.
  if grep -qE '[A-Za-z]PreferencesKey[[:space:]]*\(' "$kt"; then
    # Structural owner? Then (a) already checks it.
    if grep -qE "$owner_marker" "$kt"; then continue; fi
    base="$(basename "$kt")"
    case "$settings_nonsettings_writers" in
      *" $base "*) : ;;   # known non-settings writer — fine
      *) straggler_hits="${straggler_hits}${kt}: declares a DataStore key but neither implements OwnsSettingsStoreKeys nor is a known usage/consent writer
" ;;
    esac
  fi
done < <(find "$repo_root/app/src/main/java" "$repo_root/data/src/main/java" -name '*.kt')

if [ -n "$straggler_hits" ]; then
  report "Settings-store keep-list — unclassified DataStore-key writer (implement OwnsSettingsStoreKeys + register its keys, or add it to the non-settings writers list if it targets the usage/consent store)" "${straggler_hits%$'\n'}"
fi

# ─────────────────────────────────────────────────────────────────────────────

if [ "$violations" -eq 0 ]; then
  echo "✓ All convention checks passed."
  exit 0
fi

echo
echo "$violations rule(s) violated. See above."
exit 1
