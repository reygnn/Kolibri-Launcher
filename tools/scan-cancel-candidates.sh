#!/usr/bin/env bash
# =============================================================================
# Cancellation-rethrow whitelist — CANDIDATE DISCOVERY (report-only)
# =============================================================================
#
# The cancellation-rethrow linter (tools/check-cancellation-rethrow.awk, driven
# by the `cancel_files` positive list in tools/check-conventions.sh) is BLIND by
# design to any file not on that list. That is the right call for enforcement —
# a suspend call has no syntactic tell, so a whole-codebase gate would either
# drown in false positives or miss the real bug — but it means a refactor that
# introduces a broad `catch (Throwable/Exception)` into a suspend frame in a
# NON-listed file is invisible: neither the linter nor Rule 11 points at it.
#
# This script closes the DISCOVERY half of that gap. It is NOT a gate: it never
# fails the build. It sweeps every :app/:data/:domain main source that is not
# already whitelisted (and not deliberate crash-infra), runs the SAME awk the
# linter uses, and ranks the hits by coroutine density so the files most likely
# to hide a real suspend-frame swallower float to the top.
#
# WHEN TO RUN (see CLAUDE.md Rule 11, "Discovery"): after a refactor/rewrite
# that gives a file coroutine work it did not have before — a new
# launch/async/withContext/suspend fun, or making an existing call inside a
# broad catch `suspend`.
#
# HOW TO TRIAGE the output (same lens as the AUDIT-12 rollout):
#   - broad catch physically inside a suspend frame, no CancellationException
#     arm  ->  fix it (add a `catch (e: CancellationException) { throw e }` arm)
#             and/or add the file to `cancel_files`.
#   - broad catch in a non-suspend fun / plain callback  ->  leave it (it cannot
#     see a CancellationException); it only needs a `no suspension point` marker
#     IF you also decide to whitelist the file for another catch in it.
#
# Run via `./gradlew scanCancelCandidates` or invoke this script directly.
# Exit code is always 0 — printing is informational only.
# =============================================================================
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
conv="$script_dir/check-conventions.sh"
awkf="$script_dir/check-cancellation-rethrow.awk"

for f in "$conv" "$awkf"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: required file not found: $f" >&2
    exit 2
  fi
done

# Basenames already on the cancel_files whitelist — parsed live from the linter
# so this tool can never drift from the enforced list.
mapfile -t whitelisted < <(
  sed -n '/^cancel_files=(/,/^)/p' "$conv" | grep -oE '[A-Za-z0-9_]+\.kt' | sort -u
)

# Crash-infra files keep deliberately broad catches (Rule 9 / Rule 7). They are
# known-intentional, so excluding them keeps the signal high. A genuinely new
# concern in one of these is a design decision, not a lint find.
crashinfra_re='KolibriLauncherApp|CrashReportingBootstrap|PipelineBacklogProbe|RecoveryWatchdog|UncaughtCrashHandler|LoopGuard|TimberWrapper|AcraTree'

is_excluded() {
  local base="$1"
  for w in "${whitelisted[@]}"; do
    [ "$base" = "$w" ] && return 0
  done
  [[ "$base" =~ $crashinfra_re ]] && return 0
  return 1
}

declare -a rows=()
while IFS= read -r file; do
  base="$(basename "$file")"
  is_excluded "$base" && continue

  hits="$(awk -f "$awkf" "$file")"
  [ -z "$hits" ] && continue

  n_hits="$(printf '%s\n' "$hits" | grep -c ':' || true)"
  # Coroutine density: number of lines carrying a builder / suspend marker. A
  # crude proxy — high density means the broad catches are more likely to sit
  # in an actual suspend frame, which is exactly the invisible-flip risk.
  density="$(grep -cE 'launch[ (]|async[ ({]|withContext|(^|[^A-Za-z0-9_])suspend ' "$file" || true)"

  rel="${file#"$repo_root"/}"
  rows+=("${density}	${n_hits}	${rel}")
done < <(find "$repo_root/app/src/main" "$repo_root/data/src/main" "$repo_root/domain/src/main" \
            -name '*.kt' 2>/dev/null)

echo "════════════════════════════════════════════════════════════════════════"
echo " cancel_files candidate scan (report-only — never fails the build)"
echo "════════════════════════════════════════════════════════════════════════"
echo " Whitelisted (skipped): ${#whitelisted[@]} files    Crash-infra: excluded"
echo

if [ "${#rows[@]}" -eq 0 ]; then
  echo " No non-whitelisted file has an unsatisfied broad catch. Nothing to triage."
  echo
  exit 0
fi

echo " Non-whitelisted files with broad catches the linter would flag,"
echo " ranked by coroutine density (higher = more likely a suspend-frame swallow):"
echo
printf ' %-8s %-6s %s\n' "CORO" "HITS" "FILE"
printf ' %-8s %-6s %s\n' "----" "----" "----"
printf '%s\n' "${rows[@]}" | sort -t$'\t' -k1,1nr -k2,2nr | \
  while IFS=$'\t' read -r density n_hits rel; do
    printf ' %-8s %-6s %s\n' "$density" "$n_hits" "$rel"
  done
echo
echo " Triage: a broad catch INSIDE a suspend frame (launch/async/withContext/"
echo " suspend fun) with no CancellationException arm is the real risk — fix it"
echo " and/or add the file to cancel_files. A non-suspend catch is safe to leave."
echo " Detail per file:  awk -f tools/check-cancellation-rethrow.awk <file>"
echo "════════════════════════════════════════════════════════════════════════"
exit 0
