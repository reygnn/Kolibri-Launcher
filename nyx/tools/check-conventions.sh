#!/usr/bin/env bash
# =============================================================================
# Nyx Launcher — project-convention linter
# =============================================================================
#
# Nyx does NOT re-implement the checks: the battle-tested detector logic lives
# once in kolibri/tools/*.awk (+ the two generalized *.sh). This orchestrator
# reuses those detectors against nyx's own source tree, with nyx's own scan
# roots and positive lists, running only the checks that apply to nyx.
#
# Run via:
#   ./gradlew :nyx:app:checkConventions
#   nyx/tools/check-conventions.sh            (standalone, identical output)
#
# Exit code:
#   0   all checks passed
#   1   at least one rule violated — output lists `file:line` and the rule
#   2   environment problem (missing source dir / detector / whitelist file)
#
# WHY these checks and not others (triage against nyx's code, not kolibri's):
#
#   RUN — product-neutral or nyx already follows it:
#     Rule 9 (intent-gate), Rule 12 (Timber.Forest), Toast routing,
#     Flow.catch rethrow, unbuffered MutableSharedFlow,
#     registerForActivityResult placement, RecyclerView adapter null-out,
#     localization parity, contract-test triple (Rule 2), plus the three
#     opt-in positive lists (Rule 11 annotation, cancellation rethrow,
#     Exception breadth).
#
#   SKIPPED — kolibri-specific, would only mis-fire on nyx:
#     * `Manager`-naming in data/  — nyx uses `*Manager` names deliberately
#       (NyxBackupManager, NyxResetManager, WallpaperFileManager).
#     * purgeRepository() completeness — nyx has no purgeRepository().
#     * Settings-store keep-list (OwnsSettingsStoreKeys / @IntoSet) — hangs on
#       kolibri's storage-cleanup feature, which nyx does not have.
#     * stale-replay hot-flow point-read — deferred (kolibri runs it as its own
#       task; no nyx whitelist yet).
#
# When kolibri adds or changes a detector, nyx inherits it automatically here —
# only the scan roots / positive lists below are nyx-owned.
# =============================================================================

set -u

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"                 # = <repo>/nyx
det="$(cd "$repo_root/../tools" && pwd)"                  # shared detectors (neutral home)
# Shared-module positive lists (neutral home): SHARED_CANCEL_FILES / SHARED_OOM_FILES /
# SHARED_INITORDER_FILES — sourced by both apps so a shared file is enforced by both.
shared_lint="$repo_root/../tools/shared-lint-files.sh"
if [ ! -f "$shared_lint" ]; then
  echo "ERROR: shared lint list not found: $shared_lint" >&2
  exit 2
fi
# shellcheck source=/dev/null
source "$shared_lint"

# Nyx production sources (app / domain / data), mirroring the module split.
src_roots=(
  "$repo_root/app/src/main/java"
  "$repo_root/domain/src/main/java"
  "$repo_root/data/src/main/java"
)
app_root="$repo_root/app/src/main/java"

for d in "${src_roots[@]}"; do
  if [ ! -d "$d" ]; then
    echo "ERROR: Source directory not found: $d" >&2
    exit 2
  fi
done
if [ ! -d "$det" ]; then
  echo "ERROR: shared detectors not found: $det" >&2
  exit 2
fi

violations=0
report() {
  echo
  echo "═══ $1 ═══"
  echo "$2"
  violations=$((violations + 1))
}

require() {
  if [ ! -f "$1" ]; then
    echo "ERROR: detector not found: $1" >&2
    exit 2
  fi
}

# Run a per-file awk detector across a set of source roots (GLOBAL scan).
run_awk_global() {
  local awk="$1"; local title="$2"; shift 2
  require "$awk"
  local hits="" kt h
  while IFS= read -r kt; do
    h=$(awk -f "$awk" "$kt") || true
    if [ -n "$h" ]; then hits="${hits}${h}"$'\n'; fi
  done < <(find "$@" -name '*.kt')
  if [ -n "$hits" ]; then report "$title" "${hits%$'\n'}"; fi
}

# Run a per-file awk detector across a positive list (opt-in files). A listed
# file that has gone missing is a hard error, same as kolibri's orchestrator.
run_awk_list() {
  local awk="$1"; local title="$2"; shift 2
  local files=("$@")
  [ ${#files[@]} -eq 0 ] && return 0
  require "$awk"
  local hits="" f h
  for f in "${files[@]}"; do
    if [ ! -f "$f" ]; then
      echo "ERROR: whitelist file not found: $f" >&2
      exit 2
    fi
    h=$(awk -f "$awk" "$f") || true
    if [ -n "$h" ]; then hits="${hits}${h}"$'\n'; fi
  done
  if [ -n "$hits" ]; then report "$title" "${hits%$'\n'}"; fi
}

# ── Rule 9 — bare `Timber.e(` without an intent tag (GLOBAL) ──────────────────
run_awk_global "$det/check-intent-gate.awk" \
  "Rule 9 — bare \`Timber.e(\` without an intent tag or a \`pre-wiring bare\` marker" \
  "${src_roots[@]}"

# ── Rule 12 — `Timber.Forest.*` long form (GLOBAL, grep) ──────────────────────
rule12_hits=$(grep -rn 'Timber\.Forest\.' "${src_roots[@]}" --include='*.kt' || true)
[ -n "$rule12_hits" ] && report \
  "Rule 12 — \`Timber.Forest.*\` (use short form \`Timber.d\` / \`.e\` / \`.w\`)" "$rule12_hits"

# ── Toast routing — bare `Toast.makeText(` outside showToastSafe (GLOBAL, grep) ─
# showToastSafe lives in :common-ui (ToastSafe.kt), not in nyx, so there is no
# in-tree owner to exempt here. Add a basename to `toast_allowed` with a reason
# only for a genuine crash-safety-net exception (mirrors kolibri).
toast_allowed='ToastSafe\.kt'
toast_hits=$(
  grep -rnE '\bToast\.makeText\(|\bToast\(' "${src_roots[@]}" --include='*.kt' \
    | grep -vE "/($toast_allowed):" || true
)
[ -n "$toast_hits" ] && report \
  "Toast routing — bare \`Toast.makeText(\` outside \`ToastSafe.kt\` (use \`showToastSafe\`)" "$toast_hits"

# ── Flow.catch cancellation-rethrow (GLOBAL) ──────────────────────────────────
run_awk_global "$det/check-flow-catch-rethrow.awk" \
  "Flow.catch — logging arm without a CancellationException rethrow (swallows upstream cancellation)" \
  "${src_roots[@]}"

# ── Unbuffered MutableSharedFlow (GLOBAL) ─────────────────────────────────────
run_awk_global "$det/check-unbuffered-sharedflow.awk" \
  "Unbuffered MutableSharedFlow — drops emissions with no subscriber (add \`extraBufferCapacity = 1\` / \`replay\`, or a \`rendezvous intended\` marker)" \
  "${src_roots[@]}"

# ── registerForActivityResult() placement (app/ only) ─────────────────────────
run_awk_global "$det/check-activity-result-placement.awk" \
  "registerForActivityResult() placement — called from a lifecycle method (move to a field initializer or onCreate)" \
  "$app_root"

# ── RecyclerView adapter null-out (app/ only) ─────────────────────────────────
run_awk_global "$det/check-adapter-nulling.awk" \
  "RecyclerView adapter null-out — Fragment assigns an adapter but never nulls it in onDestroyView (leak)" \
  "$app_root"

# ── Rule 11 — broad-catch annotation discipline (positive list) ───────────────
rule11_files=(
)
run_awk_list "$det/check-rule11-annotation.awk" \
  "Rule 11 — broad catch without four-category-frame annotation in whitelisted file" \
  "${rule11_files[@]}"

# ── Cancellation-rethrow discipline (positive list) ───────────────────────────
# Opt-in, same growth model as kolibri: a file joins once its broad catches /
# `runCatching` blocks are reviewed and each is either behind a
# CancellationException arm or carries a `no suspension point` marker.
#   MainActivity — reviewed: the wallpaper bitmap loader's broad catch already
#   sits behind a CancellationException arm; its five `runCatching` sites
#   (unregisterReceiver, getShortcutIconDrawable, startShortcut, two startActivity)
#   are all synchronous and carry `no suspension point` markers. Locks them
#   against an invisible suspend-flip.
cancel_files=(
  # Nyx-specific. Shared-module files (incl. the common-ui BaseActivity nyx adopted) live in
  # tools/shared-lint-files.sh (SHARED_CANCEL_FILES) so both apps enforce them.
  "$app_root/com/github/reygnn/nyx_launcher/home/MainActivity.kt"
  # loadFromDiskOrResolve (suspend) wraps the non-suspend writeDisk in runCatching —
  # safe today (no suspension point marker), locked so a suspend-flip of writeDisk
  # can't silently swallow cancellation. Surfaced by the repo-wide scanCancelCandidates.
  "$repo_root/data/src/main/java/com/github/reygnn/nyx_launcher/data/icon/IconLoaderImpl.kt"
  "${SHARED_CANCEL_FILES[@]}"
)
run_awk_list "$det/check-cancellation-rethrow.awk" \
  "Cancellation rethrow — broad catch without a CancellationException arm or a \`no suspension point\` marker" \
  "${cancel_files[@]}"

# ── Exception-vs-Throwable breadth at allocation boundaries (positive list) ────
oom_files=(
  "${SHARED_OOM_FILES[@]}"
)
run_awk_list "$det/check-exception-breadth.awk" \
  "Exception breadth — bare \`catch (e: Exception)\` at an allocation boundary (use \`Throwable\` for OOM, or an \`Exception sufficient\` marker)" \
  "${oom_files[@]}"

# ── Init-order launch hazard (positive list) ──────────────────────────────────
# A coroutine launched in an `init { }` block that touches a property declared
# BELOW it: Kotlin runs initializers/init blocks in declaration order, so the
# property is still null when the init-launched coroutine runs → NPE. This was
# the FolderIconRenderer bug (its currentStyle collector called clear() →
# synchronized(lock) before `lock` was initialised). Listed files are LOCKED
# against a regression that reorders state below the launching init. Discovery of
# a NEW occurrence in any module is the report-only `./gradlew scanInitOrderLaunch`.
initorder_files=(
  "$repo_root/data/src/main/java/com/github/reygnn/nyx_launcher/data/icon/FolderIconRenderer.kt"
  "${SHARED_INITORDER_FILES[@]}"
)
run_awk_list "$det/check-init-order-launch.awk" \
  "Init-order launch — property initializer declared after a coroutine-launching init block (move the state above init)" \
  "${initorder_files[@]}"

# ── Localization parity — values/ vs values-de/ (GLOBAL) ──────────────────────
parity_awk="$det/check-strings-parity.awk"
require "$parity_awk"
parity_hits=""
for res in strings arrays; do
  default_xml="$repo_root/app/src/main/res/values/$res.xml"
  de_xml="$repo_root/app/src/main/res/values-de/$res.xml"
  [ -f "$default_xml" ] && [ -f "$de_xml" ] || continue
  h=$(awk -f "$parity_awk" "$default_xml" "$de_xml" | sed "s|^|$res.xml — |")
  [ -n "$h" ] && parity_hits="${parity_hits}${h}"$'\n'
done
[ -n "$parity_hits" ] && report \
  "Localization parity — key present in only one locale (values/ vs values-de/)" "${parity_hits%$'\n'}"

# ── Rule 2 — contract-test triple completeness (nyx :domain) ──────────────────
# Reuses kolibri's script with CONTRACT_REPO_ROOT pointed at nyx, so it scans
# nyx/domain for *Repository interfaces and their triples / ADR markers.
triple_sh="$det/check-contract-triple.sh"
require "$triple_sh"
triple_hits=$(CONTRACT_REPO_ROOT="$repo_root" bash "$triple_sh")
[ -n "$triple_hits" ] && report \
  "Rule 2 — incomplete contract-test triple (add the missing test, or an ADR marker in the contract)" "$triple_hits"

# ── Result ────────────────────────────────────────────────────────────────────
echo
if [ "$violations" -gt 0 ]; then
  echo "✗ $violations convention check(s) failed."
  exit 1
fi
echo "✓ All convention checks passed."
