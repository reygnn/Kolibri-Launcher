#!/usr/bin/env bash
# =============================================================================
# Exception-breadth whitelist — CANDIDATE DISCOVERY (report-only)
# =============================================================================
#
# The breadth linter (tools/check-exception-breadth.awk, driven by the
# `oom_files` positive list in tools/check-conventions.sh) is BLIND by design to
# any file not on that list. That is the right call for enforcement: most
# `catch (e: Exception)` in this tree are CORRECT — narrow RecyclerView race
# guards (`notifyItem*` / `submitList` throwing IllegalStateException, where
# widening to Throwable would WRONGLY swallow OOM) or pure I/O probes. A global
# gate would be all-noise. But it means a new `catch (e: Exception)` around a
# genuine allocation boundary in a NON-listed file is invisible.
#
# This script closes the DISCOVERY half of that gap, mirroring
# scan-cancel-candidates.sh for the other axis. It is NOT a gate: it never fails
# the build. It sweeps every :app/:data/:domain main source that is not already
# whitelisted, runs the SAME awk the linter uses, and ranks the hits by
# ALLOCATION density — how many lines carry a bitmap / inflate / JSON / ZIP /
# bulk-read operation — so the files where an `Exception` catch actually risks
# missing an OutOfMemoryError float to the top.
#
# Why allocation density and not raw hit count: hit count is dominated by
# adapters full of legitimate race guards. Density is the proxy for "this file
# allocates", which is the only reason the breadth question exists at all.
#
# WHEN TO RUN (see CLAUDE.md, "Exception-vs-Throwable breadth"): after adding or
# reworking code that decodes/composes a bitmap, inflates a layout, or encodes /
# parses a large JSON or ZIP payload — i.e. whenever a file becomes an
# allocation boundary it was not before.
#
# HOW TO TRIAGE the output (same lens as the AUDIT category-A remediation):
#   - `catch (e: Exception)` wrapped around an ALLOCATION (bitmap decode/compose,
#     layout inflate, large JSON/ZIP encode, readBytes/readText of a capped-but-
#     large file)  ->  widen to `catch (e: Throwable)`. In a suspend frame the
#     resulting broad catch is then handed to the cancellation check, which may
#     want a CancellationException arm — the two ends of one question.
#   - narrow race guard (`notifyItem*`, `submitList`, `getItem` under a list
#     swap)  ->  LEAVE IT. Throwable there would swallow the very OOM this axis
#     exists to surface. Do not whitelist the file for its sake.
#   - pure-I/O probe with no allocation path (openInputStream / openFileDescriptor
#     / toUri)  ->  keep `Exception`, and if the file joins the list, add an
#     `Exception sufficient` marker within ±5 lines. Note that a cancellation
#     comment ("no suspension point", "synchronous I/O only") does NOT count:
#     the two markers are deliberately separate axes, so a catch commented for
#     cancellation reasons never counts as OOM-reviewed.
#
# Run via `./gradlew scanOomCandidates` or invoke this script directly.
# Exit code is always 0 — printing is informational only.
# =============================================================================
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
conv="$script_dir/check-conventions.sh"
awkf="$script_dir/check-exception-breadth.awk"

for f in "$conv" "$awkf"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: required file not found: $f" >&2
    exit 2
  fi
done

# Basenames already on the oom_files whitelist — parsed live from the linter so
# this tool can never drift from the enforced list.
mapfile -t whitelisted < <(
  sed -n '/^oom_files=(/,/^)/p' "$conv" | grep -oE '[A-Za-z0-9_]+\.kt' | sort -u
)

is_excluded() {
  local base="$1"
  for w in "${whitelisted[@]}"; do
    [ "$base" = "$w" ] && return 0
  done
  return 1
}

# Lines that indicate a real allocation / system-callback boundary. Kept
# deliberately close to the shapes named in the linter header.
alloc_re='createBitmap|decodeStream|decodeFile|decodeResource|copy\(Bitmap|Canvas\(|LayoutInflater|\.inflate\(|encodeToString|decodeFromString|JSONObject\(|JSONArray\(|ZipOutputStream|ZipInputStream|readBytes\(|\.readText\(|ByteArrayOutputStream|\.compress\('

declare -a rows=()
while IFS= read -r file; do
  base="$(basename "$file")"
  is_excluded "$base" && continue

  hits="$(awk -f "$awkf" "$file")"
  [ -z "$hits" ] && continue

  n_hits="$(printf '%s\n' "$hits" | grep -c ':' || true)"
  density="$(grep -cE "$alloc_re" "$file" || true)"

  rel="${file#"$repo_root"/}"
  rows+=("${density}	${n_hits}	${rel}")
done < <(find "$repo_root/app/src/main" "$repo_root/data/src/main" "$repo_root/domain/src/main" \
            -name '*.kt' 2>/dev/null)

echo "════════════════════════════════════════════════════════════════════════"
echo " oom_files candidate scan (report-only — never fails the build)"
echo "════════════════════════════════════════════════════════════════════════"
echo " Whitelisted (skipped): ${#whitelisted[@]} files"
echo

if [ "${#rows[@]}" -eq 0 ]; then
  echo " No non-whitelisted file has an unmarked catch (e: Exception). Nothing to triage."
  echo
  exit 0
fi

echo " Non-whitelisted files with a bare \`catch (e: Exception)\`, ranked by"
echo " allocation density (higher = more likely to miss a real OutOfMemoryError):"
echo
printf ' %-8s %-6s %s\n' "ALLOC" "HITS" "FILE"
printf ' %-8s %-6s %s\n' "-----" "----" "----"
printf '%s\n' "${rows[@]}" | sort -t$'\t' -k1,1nr -k2,2nr | \
  while IFS=$'\t' read -r density n_hits rel; do
    printf ' %-8s %-6s %s\n' "$density" "$n_hits" "$rel"
  done
echo
echo " Triage: an Exception catch around an ALLOCATION (bitmap / inflate / JSON /"
echo " ZIP / bulk read) is the real risk — widen it to Throwable. A narrow race"
echo " guard (notifyItem*/submitList/getItem) must STAY Exception: Throwable there"
echo " would swallow the OOM this check exists to surface."
echo " Detail per file:  awk -f tools/check-exception-breadth.awk <file>"
echo "════════════════════════════════════════════════════════════════════════"
exit 0
