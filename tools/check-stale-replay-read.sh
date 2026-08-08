#!/usr/bin/env bash
# =============================================================================
# Stale-replay point-read linter  (AUDIT-13 favorites class)  — GATE
# =============================================================================
# A `.first()` / `.firstOrNull()` on a HOT-SHARED, replay-caching flow
# (`shareIn/stateIn(replay>=1, WhileSubscribed)`) returns the cache's last
# *observed* value, not the current one, when it runs with no warm subscriber
# (a separate Activity, or a background write). On a DECISION path — "which
# favorites to sort", "which app to launch" — that is a real bug (the swipe
# regression f1a1464c, then the two favorites UI consumers in AUDIT-13).
#
# POSITIVE LIST OF FILES, exactly like `cancel_files` / `oom_files`. This gate is
# BLIND by design to any file not in `stale_files`: it verifies only the reviewed
# files that legitimately point-read a hot flow. Enforcement on them is the same
# shape as the sibling gates — every hot-flow point-read in a listed file must
# carry a `stale-replay ok` marker within ±5 lines (the recorded ingredient-3
# judgement: "this read runs warm / writes nothing relevant"), or be converted to
# the repository's authoritative `getXSnapshot()`. An UNMARKED hot-flow read in a
# listed file fails the build.
#
# The DISCOVERY half — a NEW hot-flow point-read in a file NOT on this list — is
# NOT gated here (a whole-tree hard gate would be the wrong shape). It is surfaced
# by the report-only companion `./gradlew scanStaleReplayRead`
# (tools/scan-stale-replay-candidates.sh), which sweeps every non-listed file and
# ranks the hits. Triage a scan hit into either a `getXSnapshot()` fix or a
# `stale-replay ok` marker + an entry here.
#
# Run via `./gradlew checkStaleReplayRead` (also runs as a dependsOn of
# checkConventions). Exit 1 on an unmarked hit in a listed file, 0 when clean.
# Set STALE_REPLAY_REPORT_ONLY=1 to never fail (discovery mode).
# =============================================================================
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
awkf="$script_dir/check-stale-replay-read.awk"

[ -f "$awkf" ] || { echo "ERROR: detector not found: $awkf" >&2; exit 2; }

# -----------------------------------------------------------------------------
# HOT FLOWS — properties exposed as a hot, replay-caching Flow that are also
# `.first()`-readable. Kept as an explicit list (not auto-discovered) because the
# multi-line `= … .shareIn(…)` initializer has no reliable one-line shape. The
# scan companion reads this same list to know which names to look for.
#
#   favoriteComponentsFlow       FavoritesRepositoryImpl  (sharedReadFlow, replay=1)
#
# fabPositionFlow (commit 1) and favoriteComponentsOrderFlow (commit 2) were
# removed here as each repo was flipped to a cold flow (DATASTORE_READ_SPEC
# Belang A): a cold flow is no longer a hot, replay-caching flow, so listing it
# would be untruthful. favoriteComponentsFlow follows in commit 3; the whole
# hot_flows list retires once FavoritesRepositoryImpl is flipped.
hot_flows=(
  favoriteComponentsFlow
)

# -----------------------------------------------------------------------------
# WHITELIST — files that legitimately point-read a hot flow, REVIEWED as
# warm-context (or write-free) reads. Each hot-flow read inside these files must
# carry a `stale-replay ok` marker; this gate verifies that. Full paths, like the
# sibling `cancel_files` / `oom_files`.
#
#   FavoritesRepositoryImpl.kt  isFavoriteComponent(): runs from the Home path
#                               (AppManagementDelegate / Home UI), warm.
#   ToggleFavoriteUseCase.kt    :44 count read; invoked from AppManagementDelegate
#                               (Home foreground), warm.
#   BackupDataAssembler.kt      import-order FALLBACK — taken only when the run
#                               imports NO favorites, so that branch performs no
#                               favorites write and the cache cannot lag it.
stale_files=(
  "$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data/FavoritesRepositoryImpl.kt"
  "$repo_root/domain/src/main/java/com/github/reygnn/kolibri_launcher/domain/usecase/ToggleFavoriteUseCase.kt"
  "$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data/BackupDataAssembler.kt"
)

hot_alt="$(IFS='|'; echo "${hot_flows[*]}")"

hits=0
for f in "${stale_files[@]}"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: stale_files entry not found (stale whitelist?): ${f#"$repo_root"/}" >&2
    exit 2
  fi
  out="$(awk -v hot="$hot_alt" -f "$awkf" "$f" 2>/dev/null)" || true
  [ -z "$out" ] && continue
  printf '%s\n' "$out"
  hits=$((hits + $(printf '%s\n' "$out" | grep -c .)))
done

echo "---"
if [ "$hits" -gt 0 ]; then
  echo "FAIL: $hits unmarked hot-flow point-read(s) in a whitelisted file. Add a \`stale-replay ok\` marker (±5 lines) or convert to getXSnapshot()." >&2
  [ -n "${STALE_REPLAY_REPORT_ONLY:-}" ] && { echo "(STALE_REPLAY_REPORT_ONLY set — not failing.)"; exit 0; }
  exit 1
fi
echo "OK: every hot-flow point-read in the ${#stale_files[@]} whitelisted file(s) carries a \`stale-replay ok\` marker."
echo "    (New reads in non-whitelisted files are surfaced by \`./gradlew scanStaleReplayRead\`.)"
exit 0
