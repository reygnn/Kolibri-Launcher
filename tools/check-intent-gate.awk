# check-intent-gate.awk — Rule 9, report-by-intent form (§23).
#
# A bare `Timber.e(` neither reaches ACRA (untagged → dropped by AcraTree's
# intent gate) NOR throws in DEBUG — it silently under-reports. Report-worthy
# errors must go through one of the intent APIs:
#   - TimberWrapper.silentError(t, msg)  → throw-in-DEBUG + report (SILENT_ERROR)
#   - TimberWrapper.reportToAcra(t, msg) → report only, no throw (ACRA_REPORT)
# Local-only diagnostics use Timber.d / Timber.w (never reported).
#
# So a bare `Timber.e(` is forbidden everywhere — EXCEPT on the pre-wiring
# bootstrap path (KolibriLog handlers not yet wired / AcraTree not yet planted),
# where reportToAcra is a no-op and silentError's DEBUG throw would crash the app
# before the reporter exists. Such a site is flagged with a `pre-wiring bare`
# marker within +/-5 lines (today only ConsentBootstrap).
#
# NOTE: crash-infra swallows that use `android.util.Log.e` (AcraTree,
# UncaughtCrashHandler) are intentionally NOT matched — they are not `Timber.e`.
# The `Timber.Forest.e(` form is Rule 12's concern and does not contain the
# `Timber.e(` substring, so it is not matched here either.
#
# Regression-tested via tools/check-intent-gate-test.sh (manual rerun).

{ lines[NR] = $0 }

END {
  win = 5
  for (i = 1; i <= NR; i++) {
    if (lines[i] ~ /Timber\.e\(/) {
      marked = 0
      lo = i - win; if (lo < 1) lo = 1
      hi = i + win; if (hi > NR) hi = NR
      for (j = lo; j <= hi; j++) {
        if (lines[j] ~ /pre-wiring bare/) { marked = 1; break }
      }
      if (!marked) printf "%s:%d:%s\n", FILENAME, i, lines[i]
    }
  }
}
