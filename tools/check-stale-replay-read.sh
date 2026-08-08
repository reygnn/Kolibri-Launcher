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
# `.first()`-readable.
#
# EMPTY as of commit 3 (DATASTORE_READ_SPEC Belang A). All three DataStore-backed
# flows were flipped to cold flows across commits 1-3: fabPositionFlow (1),
# favoriteComponentsOrderFlow (2), favoriteComponentsFlow (3). A cold flow has no
# replay cache, so no `.first()` on it can return a stale value — the entire
# stale-replay class this gate guards is now structurally ABSENT. The gate is kept
# DORMANT (nothing to check), not deleted: if a DataStore repo ever re-introduces
# a `shareIn(replay>=1)` hot flow, its name goes back into hot_flows and its
# point-readers into stale_files.
#
# NOTE: this gate detects hot-flow point-reads BY NAME; it does NOT see the
# `shareIn` construction itself, so it is no lock against re-introducing a hot
# share. A construction-shape check ("shareIn(replay>=1) in a DataStore repo")
# would be a separate gate — see DATASTORE_READ_SPEC §8.
#
# NOTE: keep the `(` and `)` on their own lines even while empty — the report-only
# companion scan-stale-replay-candidates.sh extracts this block with
# `sed '/^hot_flows=(/,/^)/p'`, which needs a `^)` terminator line.
hot_flows=(
)

# -----------------------------------------------------------------------------
# WHITELIST — files that legitimately point-read a hot flow. EMPTY as of commit 3
# (see above): the three former point-readers
# (FavoritesRepositoryImpl.isFavoriteComponent, ToggleFavoriteUseCase,
# BackupDataAssembler) now read cold flows, so their `stale-replay ok` markers
# were removed in the same commit. (Kept `(`/`)` on their own lines — see the
# hot_flows note above about the sed-based companion extraction.)
stale_files=(
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
