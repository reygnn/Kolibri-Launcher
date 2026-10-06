#!/usr/bin/env python3
"""Evaluate one Nyx trace for tools/nyx-flicker-measure.sh (SPEC_NYX_REWRITE 3b-0, E3).

Prints one CSV line: label,run,first_paint_ms,change_paint_ms,missing_frames,counter_track
  first_paint_ms   duration of wallpaper_first_paint (the cold start of this run)
  change_paint_ms  median duration of the wallpaper_change_paint spans (one per editor save)
  missing_frames   difference of the monotonic counter wallpaper_layer_missing_frames over the
                   measuring window: value at the end minus the value just before the window.
                   Every affected frame raises the counter by exactly one and reports it, so the
                   value before the window is the first reported value minus one; with no
                   reported value in the window the difference is 0. Never a sum of the values.
  counter_track    "present" if the counter was reported at all in this run, else "absent" — so a
                   dry run can tell "0 affected frames" from "the counter never reaches the trace".
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
        counter = [row.value for row in tp.query(
            "select c.value as value from counter c join process_counter_track t on c.track_id = t.id "
            "join process p using(upid) "
            f"where t.name = 'wallpaper_layer_missing_frames' and p.name like '{PKG}%' order by c.ts"
        )]
        first_ms = f"{first[0]:.2f}" if first else "MISSING"
        change_ms = f"{statistics.median(change):.2f}" if change else "MISSING"
        # Difference end - (first - 1); the counter track exists once the first frame is affected.
        missing = f"{int(counter[-1] - (counter[0] - 1))}" if counter else "0"
        track = "present" if counter else "absent"
        print(f"{label},{run},{first_ms},{change_ms},{missing},{track}")
        if not counter:
            print("note: no wallpaper_layer_missing_frames value in this run (0 affected frames)", file=sys.stderr)
    finally:
        tp.close()


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit("usage: nyx-flicker-eval.py <trace.pftrace> <label> <run>")
    main(sys.argv[1], sys.argv[2], sys.argv[3])
