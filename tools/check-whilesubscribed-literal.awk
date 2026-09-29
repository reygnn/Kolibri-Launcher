# =============================================================================
# A11 — no number literal in WhileSubscribed( (SPEC_NYX_REWRITE A11)
# =============================================================================
# The sharing timeout is AppConstants.FLOW_SHARING_TIMEOUT_MS, once. Flags code
# (not comments) like `WhileSubscribed(5_000)` or
# `WhileSubscribed(stopTimeoutMillis = 5000)`. Output: path:line: reason: code
# =============================================================================
FNR == 1 { inblock = 0 }
{
  orig = $0; line = $0
  if (inblock) { if (line ~ /\*\//) { sub(/^.*\*\//, "", line); inblock = 0 } else next }
  if (line ~ /\/\*/ && line !~ /\*\//) { sub(/\/\*.*/, "", line); inblock = 1 }
  sub(/\/\/.*/, "", line)
  if (line ~ /^[ \t]*\*/) next
  if (line ~ /WhileSubscribed\([ \t]*(stopTimeoutMillis[ \t]*=[ \t]*)?[0-9]/)
    printf "%s:%d: A11 number literal in WhileSubscribed( (use AppConstants.FLOW_SHARING_TIMEOUT_MS): %s\n", FILENAME, FNR, orig
}
