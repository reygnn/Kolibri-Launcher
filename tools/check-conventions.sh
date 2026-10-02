#!/usr/bin/env bash
# =============================================================================
# Launcher monorepo — THE project-convention linter (one orchestrator, A3)
# =============================================================================
# Usage:
#   tools/check-conventions.sh --app kolibri|nyx
#   ./gradlew :kolibri:app:checkConventions / :nyx:app:checkConventions
#     (the per-app tools/check-conventions.sh are one-line wrappers)
#
# What is shared and what is per app:
#   * Every check is implemented ONCE, below. Detector logic lives in
#     tools/check-*.awk|sh.
#   * tools/conventions/<app>.conf holds ONLY data: scan roots, positive lists,
#     binding files — and for every registered check exactly one decision:
#     RUN, TASK (runs as its own Gradle task) or "SKIP: <reason>".
#
# Parity gate (SPEC_NYX_REWRITE A3) — exit 2 before any check runs if
#   * a config lacks a decision for a registered check, has an unknown one, or
#     a SKIP without a reason;
#   * a detector file tools/check-*.awk|sh exists that no registered check
#     claims (a new detector must be registered, and thereby decided for BOTH
#     apps).
#
# Scan scope: global checks cover the app's own modules AND every shared
# module (core, common-ui, common-data, feature-crashreporting, feature-backup,
# feature-wallpaper), so a shared file is enforced by both apps.
#
# Exit code: 0 all passed · 1 at least one rule violated · 2 environment/parity
# =============================================================================
set -u
det="$(cd "$(dirname "$0")" && pwd)"
MONO="$(cd "$det/.." && pwd)"

app=""
while [ $# -gt 0 ]; do
  case "$1" in
    --app) app="${2:-}"; shift 2 ;;
    *) echo "ERROR: unknown argument: $1 (usage: --app kolibri|nyx)" >&2; exit 2 ;;
  esac
done
conf="$det/conventions/$app.conf"
[ -n "$app" ] && [ -f "$conf" ] || { echo "ERROR: no config for app '$app' ($conf)" >&2; exit 2; }

# ── Registry: check id → detector files it owns (order = report order) ───────
CHECK_IDS=(rule9 rule12 toast naming rule11 cancel initorder flowcatch sharedflow
           purge arresult adapter oom parity triple keeplist whilesub mirror
           harddisp buildparity legacysunset testconv stalereplay rule13)
declare -A OWNS=(
  [rule9]="check-intent-gate.awk"
  [rule11]="check-rule11-annotation.awk"
  [cancel]="check-cancellation-rethrow.awk"
  [initorder]="check-init-order-launch.awk"
  [flowcatch]="check-flow-catch-rethrow.awk"
  [sharedflow]="check-unbuffered-sharedflow.awk"
  [purge]="check-purge-completeness.awk"
  [arresult]="check-activity-result-placement.awk"
  [adapter]="check-adapter-nulling.awk"
  [oom]="check-exception-breadth.awk"
  [parity]="check-strings-parity.awk"
  [triple]="check-contract-triple.sh"
  [keeplist]="check-settings-keys-registered.awk"
  [whilesub]="check-whilesubscribed-literal.awk"
  [mirror]="check-mirror-comments.awk check-ratchet.sh"
  [harddisp]="check-hardcoded-dispatchers.awk check-ratchet.sh"
  [buildparity]="check-build-parity.awk"
  [testconv]="check-test-conventions.sh check-test-dispatcher.awk check-test-assertions.awk"
  [stalereplay]="check-stale-replay-read.awk check-stale-replay-read.sh"
  [rule13]="check-rule13-german-comments.awk check-rule13-german-comments.sh"
)

SHARED_MODULES=("$MONO/core" "$MONO/common-ui" "$MONO/common-data" "$MONO/feature-crashreporting"
                "$MONO/feature-backup" "$MONO/feature-wallpaper" "$MONO/common-testing-android")
# Test-support libraries: their src/main IS test code, so the test-convention gates
# (A7, A12) scan it too. Every module in settings.gradle.kts must be in one of the
# lists or an app's own modules — see the module-coverage guard below.
TEST_SUPPORT_MODULES=("$MONO/common-testing-android")
SHARED_MAIN_ROOTS=(); for m in "${SHARED_MODULES[@]}"; do SHARED_MAIN_ROOTS+=("$m/src/main/java"); done

# shellcheck source=/dev/null
source "$det/shared-lint-files.sh" || { echo "ERROR: tools/shared-lint-files.sh missing" >&2; exit 2; }
declare -A CHECK=()
# shellcheck source=/dev/null
source "$conf"

# ── Parity gate ──────────────────────────────────────────────────────────────
gate=""
declare -A known=(); for id in "${CHECK_IDS[@]}"; do known[$id]=1; done
for id in "${CHECK_IDS[@]}"; do
  d="${CHECK[$id]:-}"
  case "$d" in
    RUN|TASK) ;;
    SKIP:*) r="${d#SKIP:}"; r="${r// /}"; [ -n "$r" ] || gate+="$app.conf: check '$id' is SKIP without a reason"$'\n' ;;
    "") gate+="$app.conf: no decision for registered check '$id' (RUN / TASK / \"SKIP: reason\")"$'\n' ;;
    *) gate+="$app.conf: check '$id' has invalid decision '$d'"$'\n' ;;
  esac
done
for id in "${!CHECK[@]}"; do [ -n "${known[$id]:-}" ] || gate+="$app.conf: decision for unknown check '$id'"$'\n'; done
claimed=" "; for id in "${CHECK_IDS[@]}"; do claimed+="${OWNS[$id]:-} "; done
for f in "$det"/check-*.awk "$det"/check-*.sh; do
  b="$(basename "$f")"
  case "$b" in *-test.sh|check-conventions.sh) continue ;; esac
  case "$claimed" in *" $b "*) ;; *) gate+="tools/$b: detector not claimed by any registered check — register it in tools/check-conventions.sh and decide it in every tools/conventions/*.conf"$'\n' ;; esac
done
for id in "${CHECK_IDS[@]}"; do for b in ${OWNS[$id]:-}; do
  [ -f "$det/$b" ] || gate+="registry: check '$id' claims missing detector tools/$b"$'\n'
done; done
# Module coverage: every module in settings.gradle.kts is scanned by some list — a
# shared module by SHARED_MODULES, this app's modules by APP_TEST_MODULES /
# APP_TEST_SUPPORT_MODULES. (The other app's modules are that app's run.) A module
# nobody lists is invisible to every gate — that is how :common-testing-android was
# missed until 1a-15.
covered=" "; for m in "${SHARED_MODULES[@]}" "${APP_TEST_MODULES[@]}" "${APP_TEST_SUPPORT_MODULES[@]}"; do covered+="${m#"$MONO"/} "; done
app_rel="${APP_DIR#"$MONO"/}"
while IFS= read -r mod; do
  [ -n "$mod" ] || continue
  case "$mod" in
    kolibri/*|nyx/*) case "$mod" in "$app_rel"/*) ;; *) continue ;; esac ;;
  esac
  case "$covered" in *" $mod "*) ;; *) gate+="settings.gradle.kts: module '$mod' is not covered by any gate — add it to SHARED_MODULES (tools/check-conventions.sh) or to APP_TEST_MODULES / APP_TEST_SUPPORT_MODULES ($app.conf)"$'\n' ;; esac
done < <(grep -oE 'include\("[^"]+"\)' "$MONO/settings.gradle.kts" | sed -E 's/include\(":(.*)"\)/\1/; s#:#/#g')
if [ -n "$gate" ]; then
  echo "ERROR: convention parity gate failed:" >&2; printf '%s' "$gate" >&2; exit 2
fi

for d in "${APP_MAIN_ROOTS[@]}" "${SHARED_MAIN_ROOTS[@]}"; do
  [ -d "$d" ] || { echo "ERROR: Source directory not found: $d" >&2; exit 2; }
done
GLOBAL_ROOTS=("${APP_MAIN_ROOTS[@]}" "${SHARED_MAIN_ROOTS[@]}")
UI_ROOTS=("$APP_UI_ROOT" "$MONO/common-ui/src/main/java")

violations=0
report() { echo; echo "═══ $1 ═══"; echo "$2"; violations=$((violations + 1)); }
need() { [ -f "$1" ] || { echo "ERROR: detector not found: $1" >&2; exit 2; }; }
awk_over_find() { # awk title find-args...
  local awk="$1" title="$2"; shift 2; need "$awk"
  local hits="" kt h
  while IFS= read -r kt; do h=$(awk -f "$awk" "$kt"); [ -n "$h" ] && hits="${hits}${h}"$'\n'; done < <(find "$@")
  [ -n "$hits" ] && report "$title" "${hits%$'\n'}"
  return 0
}
awk_over_list() { # awk title files...
  local awk="$1" title="$2"; shift 2; [ $# -eq 0 ] && return 0; need "$awk"
  local hits="" f h
  for f in "$@"; do
    [ -f "$f" ] || { echo "ERROR: positive-list file not found: $f" >&2; exit 2; }
    h=$(awk -f "$awk" "$f"); [ -n "$h" ] && hits="${hits}${h}"$'\n'
  done
  [ -n "$hits" ] && report "$title" "${hits%$'\n'}"
  return 0
}
run() { [ "${CHECK[$1]}" = RUN ]; }

# ── Checks (bodies ported 1:1 from the former per-app orchestrators) ────────
# Rule 9 — bare `Timber.e(` without an intent tag (report-by-intent, §23).
run rule9 && awk_over_find "$det/check-intent-gate.awk" \
  "Rule 9 — bare \`Timber.e(\` without an intent tag or a \`pre-wiring bare\` marker" \
  "${GLOBAL_ROOTS[@]}" -name '*.kt'

# Rule 12 — `Timber.Forest.*` long form.
if run rule12; then
  h=$(grep -rn 'Timber\.Forest\.' "${GLOBAL_ROOTS[@]}" --include='*.kt' || true)
  [ -n "$h" ] && report "Rule 12 — \`Timber.Forest.*\` (use short form \`Timber.d\` / \`.e\` / \`.w\`)" "$h"
fi

# Toast routing — bare `Toast.makeText(` outside ToastSafe.kt.
if run toast; then
  h=$(grep -rnE '\bToast\.makeText\(|\bToast\(' "${GLOBAL_ROOTS[@]}" --include='*.kt' | grep -vE "/(ToastSafe\.kt):" || true)
  [ -n "$h" ] && report "Toast routing — bare \`Toast.makeText(\` outside \`ToastSafe.kt\` (use \`showToastSafe\`)" "$h"
fi

# Naming — `*Manager` classes inside data/.
if run naming; then
  h=$(grep -rEn '^(internal |open |abstract |sealed )*class [A-Z][a-zA-Z0-9_]*Manager' "$NAMING_DATA_DIR" \
        --include='*.kt' 2>/dev/null | grep -vE "/(${NAMING_ALLOWED:-__none__}):" || true)
  [ -n "$h" ] && report "Naming — class ending in 'Manager' inside \`data/\` (use \`*RepositoryImpl\`)" "$h"
fi

run rule11 && awk_over_list "$det/check-rule11-annotation.awk" \
  "Rule 11 — broad catch without four-category-frame annotation in whitelisted file" \
  "${RULE11_FILES[@]}" "${SHARED_RULE11_FILES[@]}"
run cancel && awk_over_list "$det/check-cancellation-rethrow.awk" \
  "Cancellation rethrow — broad catch without a CancellationException arm or a \`no suspension point\` marker" \
  "${CANCEL_FILES[@]}" "${SHARED_CANCEL_FILES[@]}"
run initorder && awk_over_list "$det/check-init-order-launch.awk" \
  "Init-order launch — property initializer declared after a coroutine-launching init block (move the state above init)" \
  "${INITORDER_FILES[@]}" "${SHARED_INITORDER_FILES[@]}"
run flowcatch && awk_over_find "$det/check-flow-catch-rethrow.awk" \
  "Flow.catch — logging arm without a CancellationException rethrow (swallows upstream cancellation)" \
  "${GLOBAL_ROOTS[@]}" -name '*.kt'
run sharedflow && awk_over_find "$det/check-unbuffered-sharedflow.awk" \
  "Unbuffered MutableSharedFlow — drops emissions with no subscriber (add \`extraBufferCapacity = 1\` / \`replay\`, or a \`rendezvous intended\` marker)" \
  "${GLOBAL_ROOTS[@]}" -name '*.kt'
run purge && awk_over_find "$det/check-purge-completeness.awk" \
  "purgeRepository() completeness — declared preference key not wiped on reset (add a \`preferences.remove(...)\` or a \`purge-exempt\` marker)" \
  "${PURGE_ROOTS[@]}" -name '*RepositoryImpl.kt'
run arresult && awk_over_find "$det/check-activity-result-placement.awk" \
  "registerForActivityResult() placement — called from a lifecycle method (move to a field initializer or onCreate)" \
  "${UI_ROOTS[@]}" -name '*.kt'
run adapter && awk_over_find "$det/check-adapter-nulling.awk" \
  "RecyclerView adapter null-out — Fragment assigns an adapter but never nulls it in onDestroyView (leak)" \
  "${UI_ROOTS[@]}" -name '*.kt'
run oom && awk_over_list "$det/check-exception-breadth.awk" \
  "Exception breadth — bare \`catch (e: Exception)\` at an allocation boundary (use \`Throwable\` for OOM, or an \`Exception sufficient\` marker)" \
  "${OOM_FILES[@]}" "${SHARED_OOM_FILES[@]}"

# Localization parity (values/ vs values-de/).
if run parity; then
  need "$det/check-strings-parity.awk"; ph=""
  for res in strings arrays; do
    dx="$APP_RES_DIR/values/$res.xml"; gx="$APP_RES_DIR/values-de/$res.xml"
    [ -f "$dx" ] && [ -f "$gx" ] || continue
    h=$(awk -f "$det/check-strings-parity.awk" "$dx" "$gx" | sed "s|^|$res.xml — |")
    [ -n "$h" ] && ph="${ph}${h}"$'\n'
  done
  [ -n "$ph" ] && report "Localization parity — key present in only one locale (values/ vs values-de/)" "${ph%$'\n'}"
fi

# Rule 2 — contract-test triple.
if run triple; then
  need "$det/check-contract-triple.sh"
  h=$(CONTRACT_REPO_ROOT="$APP_DIR" bash "$det/check-contract-triple.sh")
  [ -n "$h" ] && report "Rule 2 — incomplete contract-test triple (add the missing test, or an ADR marker in the contract)" "$h"
fi

# Settings-store keep-list (OwnsSettingsStoreKeys / @IntoSet) — four reports.
if run keeplist; then
  need "$det/check-settings-keys-registered.awk"
  owner_marker='override fun ownedExactKeys|override fun ownedKeyPrefixes'
  owner_files=$(grep -rlE "$owner_marker" "${KEEPLIST_ROOTS[@]}" 2>/dev/null || true)
  if [ -z "$owner_files" ]; then
    report "Settings-store keep-list — no OwnsSettingsStoreKeys owner found (interface renamed / override methods gone?)" "expected at least one owner overriding $owner_marker"
  else
    kh=""
    while IFS= read -r f; do [ -n "$f" ] || continue
      h=$(awk -f "$det/check-settings-keys-registered.awk" "$f"); [ -n "$h" ] && kh="${kh}${h}"$'\n'
    done <<< "$owner_files"
    [ -n "$kh" ] && report "Settings-store keep-list — declared key not registered by its owner (add it to ownedExactKeys()/ownedKeyPrefixes(), or storage cleanup will delete it as an orphan)" "${kh%$'\n'}"
  fi
  sh=""
  while IFS= read -r kt; do
    if grep -qE '[A-Za-z]PreferencesKey[[:space:]]*\(' "$kt"; then
      grep -qE "$owner_marker" "$kt" && continue
      case " ${KEEPLIST_NONSETTINGS_WRITERS} " in
        *" $(basename "$kt") "*) : ;;
        *) sh="${sh}${kt}: declares a DataStore key but neither implements OwnsSettingsStoreKeys nor is a known usage/consent writer"$'\n' ;;
      esac
    fi
  done < <(find "${KEEPLIST_ROOTS[@]}" -name '*.kt')
  [ -n "$sh" ] && report "Settings-store keep-list — unclassified DataStore-key writer (implement OwnsSettingsStoreKeys + register its keys, or add it to the non-settings writers list if it targets the usage/consent store)" "${sh%$'\n'}"
  bound_owners=$(awk '
    { code = $0; sub(/\/\/.*/, "", code) }
    code ~ /@IntoSet/ { scan = 6; next }
    scan > 0 {
      if (match(code, /impl:[[:space:]]*[A-Za-z0-9_]+/)) {
        t = substr(code, RSTART, RLENGTH); sub(/impl:[[:space:]]*/, "", t); print t; scan = 0
      } else { scan-- }
    }' "${KEEPLIST_BINDING_FILES[@]}" 2>/dev/null | sort -u)
  owner_classes=$(while IFS= read -r f; do [ -n "$f" ] || continue
      grep -oE '^[[:space:]]*(internal[[:space:]]+)?class[[:space:]]+[A-Za-z0-9_]+' "$f" | head -1 | grep -oE '[A-Za-z0-9_]+$'
    done <<< "$owner_files" | sort -u | sed '/^$/d')
  bh=""
  while IFS= read -r c; do [ -n "$c" ] || continue
    printf '%s\n' "$bound_owners" | grep -qx "$c" || bh="${bh}${c}: implements OwnsSettingsStoreKeys but has no @IntoSet binding — absent from the runtime keep-list, its live keys would be deleted"$'\n'
  done <<< "$owner_classes"
  while IFS= read -r c; do [ -n "$c" ] || continue
    printf '%s\n' "$owner_classes" | grep -qx "$c" || bh="${bh}${c}: @IntoSet-bound into the keep-list but does not implement OwnsSettingsStoreKeys (stale/incorrect binding?)"$'\n'
  done <<< "$bound_owners"
  [ -n "$bh" ] && report "Settings-store keep-list — owner/@IntoSet binding parity broken (every OwnsSettingsStoreKeys owner needs exactly one @IntoSet binding, or the runtime Set silently misses it and its keys are deleted)" "${bh%$'\n'}"
fi

# A11 — no number literal in WhileSubscribed( (one sharing timeout constant).
run whilesub && awk_over_find "$det/check-whilesubscribed-literal.awk" \
  "A11 — number literal in WhileSubscribed( (use AppConstants.FLOW_SHARING_TIMEOUT_MS)" \
  "${GLOBAL_ROOTS[@]}" -name '*.kt'

# Shrink-only ratchets (tools/check-ratchet.sh): findings beyond the allowlisted count
# fail, allowlisted entries that disappeared are reported as stale.
ratchet() { # detector.awk allowlist title
  [ -x "$det/check-ratchet.sh" ] || { echo "ERROR: tools/check-ratchet.sh missing or not executable" >&2; exit 2; }
  local out rc
  out=$("$det/check-ratchet.sh" "$1" "$2" "$3" "${GLOBAL_ROOTS[@]}"); rc=$?
  [ "$rc" -eq 2 ] && exit 2
  if [ -n "$out" ]; then echo "$out"; violations=$((violations + $(printf '%s\n' "$out" | grep -c '^═══'))); fi
  return 0
}
# A8 — mirror comments (tools/mirror-allowlist.txt).
run mirror && ratchet "$det/check-mirror-comments.awk" "$det/mirror-allowlist.txt" \
  "A8 — new mirror comment (a hand-kept copy of the other app): replace the copy with the shared implementation instead"
# A13 — hard-coded dispatchers (tools/dispatcher-allowlist.txt).
run harddisp && ratchet "$det/check-hardcoded-dispatchers.awk" "$det/dispatcher-allowlist.txt" \
  "A13 — hard-coded dispatcher: inject @IoDispatcher / @DefaultDispatcher / @MainDispatcher (:core) instead"

# A10 — build parity: module build scripts leave build-wide values to build-logic and
# apply a launcher.* convention plugin (shared modules + this app's modules).
if run buildparity; then
  need "$det/check-build-parity.awk"; bp=""
  for m in "${SHARED_MODULES[@]}" "${APP_TEST_MODULES[@]}" "${APP_TEST_SUPPORT_MODULES[@]}"; do
    f="$m/build.gradle.kts"; [ -f "$f" ] || { bp="${bp}${f#"$MONO"/}:1: A10 module has no build.gradle.kts"$'\n'; continue; }
    h=$(awk -f "$det/check-build-parity.awk" "$f"); [ -n "$h" ] && bp="${bp}${h#"$MONO"/}"$'\n'
  done
  [ -n "$bp" ] && report "A10 — build-wide values belong to build-logic (launcher.* convention plugins), not to a module" "${bp%$'\n'}"
fi

# A7 + A12 — test conventions (own modules + all shared modules).
if run testconv; then
  [ -x "$det/check-test-conventions.sh" ] || { echo "ERROR: tools/check-test-conventions.sh missing or not executable" >&2; exit 2; }
  out=$("$det/check-test-conventions.sh" "${APP_TEST_MODULES[@]}" "${SHARED_MODULES[@]}" \
          --test-support "${TEST_SUPPORT_MODULES[@]}" "${APP_TEST_SUPPORT_MODULES[@]}"); rc=$?
  [ "$rc" -eq 2 ] && exit 2
  if [ -n "$out" ]; then echo "$out"; violations=$((violations + $(printf '%s\n' "$out" | grep -c '^═══'))); fi
fi
# stalereplay / rule13: TASK = own Gradle task; only their decision is gated here.

# Legacy-module sunset (SPEC_NYX_REWRITE E5a): a WARNING, never a failure. From the date in
# <app>/backup-legacy/SUNSET on, the pre-E5a reader is due for removal (see SUNSET.md there).
if run legacysunset; then
  sunset_file="$APP_DIR/backup-legacy/SUNSET"
  if [ -f "$sunset_file" ]; then
    sunset=$(tr -d '[:space:]' < "$sunset_file")
    if [ -n "$sunset" ] && [[ ! "$(date +%F)" < "$sunset" ]]; then
      echo; echo "═══ ⚠ WARNING (not a failure): legacy backup reader past its sunset ($sunset) ═══"
      echo "Remove ${APP_DIR#"$MONO"/}/backup-legacy — steps in its SUNSET.md."
    fi
  fi
fi

echo
if [ "$violations" -gt 0 ]; then
  echo "✗ $violations convention check(s) failed ($app)."
  exit 1
fi
echo "✓ All convention checks passed ($app)."
