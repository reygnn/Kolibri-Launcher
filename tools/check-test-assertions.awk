# =============================================================================
# A12 — one assertion library (SPEC_NYX_REWRITE, TESTING_CONVENTIONS.kt)
# =============================================================================
# Per-file scan of TEST sources: Truth only; the two exceptions are the
# Kotlin-native typed assertions kotlin.test.assertFailsWith and
# kotlin.test.assertIs (both return the checked type / smart-cast — Truth
# can't). Flags JUnit Assert (imports and qualified calls), every other
# kotlin.test assertion import, and @Test(expected = ...).
# Allowlisting (the migration ratchet) is done by the caller, not here.
# Comments are ignored. Output: path:line: reason: code
# =============================================================================
function flag(reason) { printf "%s:%d: %s: %s\n", FILENAME, FNR, reason, orig }
FNR == 1 { inblock = 0 }
{
  orig = $0; line = $0
  if (inblock) { if (line ~ /\*\//) { sub(/^.*\*\//, "", line); inblock = 0 } else next }
  if (line ~ /\/\*/ && line !~ /\*\//) { sub(/\/\*.*/, "", line); inblock = 1 }
  sub(/\/\/.*/, "", line)
  if (line ~ /^[ \t]*\*/) next
  if (line ~ /^[ \t]*import[ \t]+org\.junit\.Assert/)                  flag("A12 JUnit Assert import (use Truth assertThat)")
  else if (line ~ /(^|[^A-Za-z_.])(org\.junit\.)?Assert\.(assert|fail)/) flag("A12 JUnit Assert call (use Truth assertThat)")
  if (line ~ /^[ \t]*import[ \t]+kotlin\.test\./ && line !~ /^[ \t]*import[ \t]+kotlin\.test\.(assertFailsWith|assertIs)[ \t]*$/ && line !~ /^[ \t]*import[ \t]+kotlin\.test\.Test[ \t]*$/)
    flag("A12 kotlin.test assertion import (only assertFailsWith and assertIs are allowed)")
  if (line ~ /@Test[ \t]*\([ \t]*expected/)                            flag("A12 @Test(expected = ...) (use assertFailsWith)")
}
