#!/usr/bin/env python3
"""Evaluate one Nyx trace for tools/nyx-flicker-measure.sh (SPEC_NYX_REWRITE 3b-0, E3).

Prints one CSV line: label,run,first_paint_ms,change_paint_ms,missing_frames,counter_track
  first_paint_ms   duration of wallpaper_first_paint (the cold start of this run)
  change_paint_ms  median duration of the wallpaper_change_paint spans (one per editor save)
  missing_frames   difference of the monotonic counter wallpaper_layer_missing_frames, PER PROCESS
                   (upid): max(value) - min(value), then summed over the Nyx processes in the
                   trace. The app reports the current value on every multi-layer frame (3b-0, 12b),
                   so a process's first reported value is its starting point. Per process because
                   each run force-stops the old process and starts a new one with its own counter;
                   one difference across both would mix two counters. Never a sum of the values.
  counter_track    "present" as soon as at least one Nyx process has the counter track, else
                   "absent" — which then unambiguously means "not instrumented / no multi-layer
                   frame", never "nothing flickered" (that is present with missing_frames 0).
The slices (first_paint, change_paint) are taken across the processes: atrace_apps records only
Nyx, and the sections arise only in the freshly started process.
A missing value is written as "MISSING" (the dry run checks that none is).
"""
import statistics
import sys

from perfetto.trace_processor import TraceProcessor

PKG = "com.github.reygnn.nyx_launcher"


def main(trace_path: str, label: str, run: str) -> None:
    tp = TraceProcessor(trace=trace_path)
    try:
        def durations(name: str):
            q = tp.query(f"select dur from slice where name = '{name}' and dur > 0 order by ts")
            return [row.dur / 1e6 for row in q]

        first = durations("wallpaper_first_paint")
        change = durations("wallpaper_change_paint")
        per_process = [(row.mx, row.mn) for row in tp.query(
            "select max(c.value) as mx, min(c.value) as mn from counter c "
            "join process_counter_track t on c.track_id = t.id join process p using(upid) "
            f"where t.name = 'wallpaper_layer_missing_frames' and p.name like '{PKG}%' "
            "group by p.upid"
        )]
        first_ms = f"{first[0]:.2f}" if first else "MISSING"
        change_ms = f"{statistics.median(change):.2f}" if change else "MISSING"
        # Per process max - min (the first reported value is the starting point), summed.
        missing = f"{int(sum(mx - mn for mx, mn in per_process))}" if per_process else "0"
        track = "present" if per_process else "absent"
        print(f"{label},{run},{first_ms},{change_ms},{missing},{track}")
        if not per_process:
            print("note: no wallpaper_layer_missing_frames track (not instrumented / no multi-layer frame)", file=sys.stderr)
    finally:
        tp.close()


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit("usage: nyx-flicker-eval.py <trace.pftrace> <label> <run>")
    main(sys.argv[1], sys.argv[2], sys.argv[3])
