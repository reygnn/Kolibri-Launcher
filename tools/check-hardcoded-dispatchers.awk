# =============================================================================
# A13 — hard-coded dispatcher detector core (SPEC_NYX_REWRITE A13)
# =============================================================================
# Production code gets its dispatchers injected (@IoDispatcher / @DefaultDispatcher
# / @MainDispatcher from :core), so tests can pass the one test dispatcher. Prints
# every CODE line (comments ignored) that names Dispatchers.IO/Default/Main/
# Unconfined, except in DispatcherModule.kt — the provider itself.
# Output: path<TAB>trimmed-line<TAB>line-number (ratchet: tools/check-ratchet.sh).
# =============================================================================
FNR == 1 { inblock = 0; isprovider = (FILENAME ~ /\/DispatcherModule\.kt$/) }
isprovider { next }
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
  if (line ~ /(^|[^A-Za-z0-9_])Dispatchers\.(IO|Default|Main|Unconfined)([^A-Za-z0-9_]|$)/) {
    t = orig; sub(/^[ \t]+/, "", t); sub(/[ \t]+$/, "", t); gsub(/\t/, " ", t)
    printf "%s\t%s\t%d\n", FILENAME, t, FNR
  }
}
