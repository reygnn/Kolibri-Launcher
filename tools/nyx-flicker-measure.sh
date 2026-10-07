#!/usr/bin/env bash
# =============================================================================
# Nyx wallpaper paint + flicker measurement (SPEC_NYX_REWRITE 3b-0, E3)
# =============================================================================
#
# Records ONE run on a connected device: a Perfetto trace across a fixed sequence of steps, then
# evaluates it into one CSV line (tools/nyx-flicker-eval.py):
#   first_paint_ms   wallpaper_first_paint of the run's cold start
#   change_paint_ms  median of the wallpaper_change_paint spans (one per editor save)
#   missing_frames   DIFFERENCE of the monotonic counter wallpaper_layer_missing_frames, per process
#   counter_track    present / absent (absent = not instrumented / no multi-layer frame)
# The script only reads; on the device it records the trace and force-stops Nyx for the cold
# start — nothing else changes.
#
# Prerequisites (all of them, before the first run):
#   - Host: bash, adb (Android platform-tools), python3 with the Perfetto package pinned, in one of
#     two equivalent ways — use the SAME way for "before" and "after":
#       a) venv:   python3 -m venv build/venv && build/venv/bin/pip install perfetto==0.58.2
#                  then run with PATH="$PWD/build/venv/bin:$PATH" tools/nyx-flicker-measure.sh ...
#       b) no venv possible (PEP 668 / ensurepip missing):
#                  pip install --target build/pyenv perfetto==0.58.2
#                  then run with PYTHONPATH=build/pyenv tools/nyx-flicker-measure.sh ...
#                  (trace_processor_shell comes from the local Perfetto prebuilt cache).
#     The trace_processor binary is fetched on first use; no network → set it up beforehand.
#   - Device: Samsung A17 (the 3a/3b reference device), USB debugging on, display awake
#     (adb shell svc power stayon true), Nyx set as the default home app.
#   - Build: the family-signed RELEASE build of Nyx (profileable since 3b-0, so Perfetto records
#     its trace sections), installed the same way for "before" and "after".
#   - Fixed test wallpaper (Kolibri's benchmark backup is a Kolibri backup; Nyx cannot restore it):
#     ONCE, set the two 3a-8 photos (4000x3000 and 3000x4000, from kolibri-benchmark-wallpaper.zip)
#     as two layers in Nyx's editor and export a Nyx backup as nyx-benchmark-wallpaper.zip; before
#     each session restore that backup in Nyx's settings. Same wallpaper for every run: its SHA-256
#     is recorded in SPEC_NYX_REWRITE (3b-0) once created — check it before each session:
#         sha256sum nyx-benchmark-wallpaper.zip
#
# Steps of one run (the script prompts for the manual ones; gestures are device-specific):
#   1. cold start (automatic: force-stop, then HOME)
#   2. editor: remove one layer, then Save        (manual)
#   3. editor: add a layer back, then Save         (manual)
#   4. open the drawer, back to home               (manual)
#
# Method (SPEC_NYX_REWRITE, from 3b): 3 runs "vorher" and 3 runs "nachher" in ONE session,
# alternating inside and between rounds (A,B / B,A / A,B). Criteria for E3 (SPEC_NYX_REWRITE
# P6, Revision 108): missing_frames per run (after not more than before, plus one as noise) and
# the median of first_paint (more than 10 % worse is a regression); change_paint is recorded for
# information only — not comparable before/after (its span closes on the next NEWLY applied state).
#
# Usage:
#   tools/nyx-flicker-measure.sh <label> <run>      e.g.  vorher 1   — one run, appends to the CSV
#   tools/nyx-flicker-measure.sh --summary          median / min / max per label from the CSV
#   tools/nyx-flicker-measure.sh --dry-run          one run, no evaluation of the result: only checks
#                                                   that all three values appear (acceptance of 3b-0)
# Output: build/nyx-flicker/results.csv and the traces next to it.
# =============================================================================
set -euo pipefail

PKG="com.github.reygnn.nyx_launcher"
script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
out_dir="$repo_root/build/nyx-flicker"
csv="$out_dir/results.csv"
config="$script_dir/perfetto/nyx-wallpaper.pbtx"
device_trace="/data/misc/perfetto-traces/nyx-flicker.pftrace"
mkdir -p "$out_dir"

summary() {
  [ -f "$csv" ] || { echo "no results yet: $csv" >&2; exit 1; }
  python3 - "$csv" <<'PY'
import csv, statistics, sys
rows = list(csv.reader(open(sys.argv[1])))
for label in sorted({r[0] for r in rows}):
    sel = [r for r in rows if r[0] == label]
    print(f"== {label} ({len(sel)} runs)")
    for idx, name in ((2, "first_paint_ms"), (3, "change_paint_ms"), (4, "missing_frames")):
        vals = [float(r[idx]) for r in sel if r[idx] not in ("MISSING", "")]
        if vals:
            print(f"   {name:16s} median {statistics.median(vals):9.2f}   min {min(vals):9.2f}   max {max(vals):9.2f}")
        else:
            print(f"   {name:16s} MISSING")
PY
}

prompt() {
  read -r -p "→ $1  (Enter when done) " _
}

record_run() {
  local label="$1" run="$2"
  local host_trace="$out_dir/${label}-${run}.pftrace"
  adb get-state >/dev/null || { echo "no device" >&2; exit 2; }
  # Start the trace in the background (config via stdin; Perfetto prints its PID).
  local pid
  pid=$(adb shell "perfetto --background --txt -c - -o $device_trace" < "$config" | tr -d '\r' | tail -n1)
  [[ "$pid" =~ ^[0-9]+$ ]] || { echo "perfetto did not start: $pid" >&2; exit 2; }
  sleep 1
  echo "step 1: cold start"
  adb shell am force-stop "$PKG"
  adb shell input keyevent KEYCODE_HOME
  sleep 4
  prompt "step 2: open the editor, remove one layer, tap Save"
  prompt "step 3: open the editor, add a layer back, tap Save"
  prompt "step 4: open the drawer, go back to home"
  sleep 1
  adb shell kill -TERM "$pid" || true
  # Wait until Perfetto has written the trace and exited.
  for _ in $(seq 1 30); do
    adb shell "kill -0 $pid 2>/dev/null" || break
    sleep 0.5
  done
  adb pull "$device_trace" "$host_trace" >/dev/null
  python3 "$script_dir/nyx-flicker-eval.py" "$host_trace" "$label" "$run"
}

case "${1:-}" in
  --summary)
    summary
    ;;
  --dry-run)
    line=$(record_run probe 1)
    echo "$line"
    IFS=, read -r _ _ first change missing track <<<"$line"
    ok=true
    [ "$first" != "MISSING" ] || { echo "MISSING: wallpaper_first_paint" >&2; ok=false; }
    [ "$change" != "MISSING" ] || { echo "MISSING: wallpaper_change_paint" >&2; ok=false; }
    [ "$track" = "present" ] || { echo "MISSING: wallpaper_layer_missing_frames counter (no value in the run)" >&2; ok=false; }
    $ok && echo "dry run OK: all three values appear (not evaluated)" || exit 1
    ;;
  ""|-h|--help)
    sed -n '2,/^set -euo pipefail/p' "$0" | grep '^#'   # the header above, whatever its length
    ;;
  *)
    [ $# -eq 2 ] || { echo "usage: $0 <label> <run> | --summary | --dry-run" >&2; exit 2; }
    record_run "$1" "$2" | tee -a "$csv"
    ;;
esac
