# =============================================================================
# A7 — one test dispatcher (SPEC_NYX_REWRITE, TESTING_CONVENTIONS.kt)
# =============================================================================
# Per-file scan of TEST sources. Allows exactly what TESTING_CONVENTIONS.kt
# allows, nothing more:
#   - StandardTestDispatcher(testScheduler)                  exceptions 1, 2
#   - UnconfinedTestDispatcher(mainDispatcherRule.testDispatcher.scheduler)
#                                                            exception 3
#   - TestScope( / StandardTestDispatcher() / Dispatchers.setMain( only inside
#     the one MainDispatcherRule.kt
#   - UnconfinedTestDispatcher(testScheduler) only inside RecordEmissions.kt,
#     the one flow recorder (§5/§6); everywhere else collectors use
#     recordEmissions(flow, into = list) or Turbine
# Everything else that creates a test dispatcher or scope is flagged.
# Comments (//, /* */, KDoc) are ignored. Output: path:line: reason: code
# =============================================================================
function flag(reason) { printf "%s:%d: %s: %s\n", FILENAME, FNR, reason, orig }
FNR == 1 { inblock = 0; isrule = (FILENAME ~ /\/MainDispatcherRule\.kt$/); isrec = (FILENAME ~ /\/RecordEmissions\.kt$/) }
{
  orig = $0; line = $0
  if (inblock) { if (line ~ /\*\//) { sub(/^.*\*\//, "", line); inblock = 0 } else next }
  while (match(line, /\/\*/)) {
    rest = substr(line, RSTART + 2)
    if (match(rest, /\*\//)) { line = substr(line, 1, index(line, "/*") - 1) substr(rest, RSTART + 2) }
    else { line = substr(line, 1, index(line, "/*") - 1); inblock = 1; break }
  }
  sub(/\/\/.*/, "", line)
  if (line ~ /^[ \t]*\*/) next

  if (!isrule) {
    if (line ~ /(^|[^A-Za-z_])TestScope\(/)            flag("A7 TestScope( outside MainDispatcherRule (use CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()))")
    if (line ~ /StandardTestDispatcher\(\)/)           flag("A7 parameterless StandardTestDispatcher() (use mainDispatcherRule.testDispatcher)")
    if (line ~ /Dispatchers\.setMain\(/)               flag("A7 Dispatchers.setMain( outside MainDispatcherRule")
  }
  t = line
  while (match(t, /StandardTestDispatcher\([^)]+\)/)) {
    arg = substr(t, RSTART + 23, RLENGTH - 24); gsub(/[ \t]/, "", arg)
    if (arg != "testScheduler") flag("A7 StandardTestDispatcher(" arg ") — only (testScheduler) is allowed (exceptions 1, 2)")
    t = substr(t, RSTART + RLENGTH)
  }
  t = line
  while (match(t, /UnconfinedTestDispatcher\([^)]*\)/)) {
    arg = substr(t, RSTART + 25, RLENGTH - 26); gsub(/[ \t]/, "", arg)
    before = substr(t, 1, RSTART - 1)
    if (arg == "") {
      flag("A7 UnconfinedTestDispatcher() — observe flows with recordEmissions(flow, into = list) or Turbine (§5/§6)")
    } else if (arg == "testScheduler" && isrec) {
      # the one flow recorder (RecordEmissions.kt)
    } else if (arg != "mainDispatcherRule.testDispatcher.scheduler") {
      flag("A7 UnconfinedTestDispatcher(" arg ") — only exception 3 (mainDispatcherRule.testDispatcher.scheduler); collectors use recordEmissions")
    }
    t = substr(t, RSTART + RLENGTH)
  }
}
