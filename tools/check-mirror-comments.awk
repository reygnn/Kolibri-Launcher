# =============================================================================
# A8 — mirror-comment detector core (SPEC_NYX_REWRITE A8)
# =============================================================================
# Prints every COMMENT line (//, /* */, KDoc) that claims a hand-kept copy of the
# other app: one of "mirror"/"parity"/"port of" AND one of "kolibri"/"nyx" on the
# same line (case-insensitive). Output: path<TAB>trimmed-line<TAB>line-number.
# The ratchet (allowlist, stale entries) is the generic tools/check-ratchet.sh.
# A8 measures comments, not copies: removing a comment without replacing the copy
# fools it — the allowlist rule in the spec covers that.
# =============================================================================
FNR == 1 { inblock = 0 }
{
  line = $0; iscomment = 0
  if (inblock) { iscomment = 1; if (line ~ /\*\//) inblock = 0 }
  else if (line ~ /^[ \t]*(\/\/|\*)/) iscomment = 1
  else if (line ~ /^[ \t]*\/\*/) { iscomment = 1; if (line !~ /\*\//) inblock = 1 }
  if (!iscomment) next
  l = tolower(line)
  if (l ~ /(mirror|parity|port of)/ && l ~ /(kolibri|nyx)/) {
    t = line; sub(/^[ \t]+/, "", t); sub(/[ \t]+$/, "", t); gsub(/\t/, " ", t)
    printf "%s\t%s\t%d\n", FILENAME, t, FNR
  }
}
