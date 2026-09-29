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
  sub(/\/\/.*/, "", line)             # line comments first: a "/*" inside one opens no block
  while (match(line, /\/\*/)) {       # block comments: drop closed ones, open the rest
    rest = substr(line, RSTART + 2)
    if (match(rest, /\*\//)) { line = substr(line, 1, index(line, "/*") - 1) substr(rest, RSTART + 2) }
    else { line = substr(line, 1, index(line, "/*") - 1); inblock = 1; break }
  }
  if (line ~ /^[ \t]*\*/) next
  if (line ~ /WhileSubscribed\([ \t]*(stopTimeoutMillis[ \t]*=[ \t]*)?[0-9]/)
    printf "%s:%d: A11 number literal in WhileSubscribed( (use AppConstants.FLOW_SHARING_TIMEOUT_MS): %s\n", FILENAME, FNR, orig
}
