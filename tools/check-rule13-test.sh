#!/usr/bin/env bash
# =============================================================================
# Regression test for tools/check-rule13-german-comments.awk
# =============================================================================
#
# Pipes synthetic unified-diff fixtures into the awk core and asserts the
# expected violations are flagged. Catches drift in the regex (e.g. a
# refactor that breaks umlaut detection or word-boundary matching) without
# depending on real diffs.
#
# NOT wired into Gradle by design — manual-rerun, not a CI gate.
# Production lint runs via `./gradlew checkRule13`.
#
# Run via:
#   ./tools/check-rule13-test.sh
#
# Exit code:
#   0   all fixtures matched expectations
#   1   regression — at least one fixture diverged
#   2   environment problem (missing awk script, etc.)
# =============================================================================

set -u

script_dir="$(cd "$(dirname "$0")" && pwd)"
awk_script="$script_dir/check-rule13-german-comments.awk"

if [ ! -f "$awk_script" ]; then
  echo "ERROR: awk script not found: $awk_script" >&2
  exit 2
fi

failures=0
run_case() {
  local name="$1"
  local expected="$2"
  local diff_input="$3"

  local actual
  actual=$(printf '%s' "$diff_input" | awk -f "$awk_script")

  if [ "$actual" = "$expected" ]; then
    echo "✓ $name"
  else
    echo "✗ $name"
    echo "  ── EXPECTED ──"
    printf '%s\n' "$expected" | sed 's/^/  /'
    echo "  ── ACTUAL ──"
    printf '%s\n' "$actual" | sed 's/^/  /'
    failures=$((failures + 1))
  fi
}

# ─────────────────────────────────────────────────────────────────────────────
# Case 1 — English comment added, no German signals → no violation.
# ─────────────────────────────────────────────────────────────────────────────
run_case "case1: English comment added (silent)" "" "$(cat <<'EOF'
diff --git a/Foo.kt b/Foo.kt
--- a/Foo.kt
+++ b/Foo.kt
@@ -1,3 +1,4 @@
 fun foo() {
+    // This is a plain English comment with no German signals.
     work()
 }
EOF
)"

# ─────────────────────────────────────────────────────────────────────────────
# Case 2 — German comment with umlaut → HARD signal triggers.
# ─────────────────────────────────────────────────────────────────────────────
run_case "case2: German with umlaut (hard signal)" \
  "Foo.kt:2:Größe der Liste prüfen — wäre sonst falsch." \
  "$(cat <<'EOF'
diff --git a/Foo.kt b/Foo.kt
--- a/Foo.kt
+++ b/Foo.kt
@@ -1,2 +1,3 @@
 fun foo() {
+    // Größe der Liste prüfen — wäre sonst falsch.
     work()
EOF
)"

# ─────────────────────────────────────────────────────────────────────────────
# Case 3 — German without umlaut, ≥2 function words → SOFT signal triggers.
# ─────────────────────────────────────────────────────────────────────────────
run_case "case3: German no-umlaut ≥2 function words (soft signal)" \
  "Foo.kt:2:eine Liste und kein Set — bereits sortiert." \
  "$(cat <<'EOF'
diff --git a/Foo.kt b/Foo.kt
--- a/Foo.kt
+++ b/Foo.kt
@@ -1,2 +1,3 @@
 fun foo() {
+    // eine Liste und kein Set — bereits sortiert.
     work()
EOF
)"

# ─────────────────────────────────────────────────────────────────────────────
# Case 4 — Single function-word match in English context → must NOT trigger.
# `kann` is German-only but a single match is below threshold.
# ─────────────────────────────────────────────────────────────────────────────
run_case "case4: one function-word, English context (silent)" "" \
  "$(cat <<'EOF'
diff --git a/Foo.kt b/Foo.kt
--- a/Foo.kt
+++ b/Foo.kt
@@ -1,2 +1,3 @@
 fun foo() {
+    // some English text where kann happens to appear once
     work()
EOF
)"

# ─────────────────────────────────────────────────────────────────────────────
# Case 5 — German line as context (` `) → not in scope (legacy, not swept).
# ─────────────────────────────────────────────────────────────────────────────
run_case "case5: German line as context, not added (silent)" "" \
  "$(cat <<'EOF'
diff --git a/Foo.kt b/Foo.kt
--- a/Foo.kt
+++ b/Foo.kt
@@ -1,3 +1,4 @@
 fun foo() {
     // Größe ist wichtig und kann nicht geändert werden
+    // English replacement-adjacent line
     work()
EOF
)"

# ─────────────────────────────────────────────────────────────────────────────
# Case 6 — German line removed (`-`) → not in scope (deletions are fine).
# ─────────────────────────────────────────────────────────────────────────────
run_case "case6: German line deleted (silent)" "" \
  "$(cat <<'EOF'
diff --git a/Foo.kt b/Foo.kt
--- a/Foo.kt
+++ b/Foo.kt
@@ -1,3 +1,3 @@
 fun foo() {
-    // Größe ist wichtig und kann nicht geändert werden
+    // English replacement
     work()
EOF
)"

# ─────────────────────────────────────────────────────────────────────────────
# Case 7 — Code line containing German-looking identifier → not a comment,
# must NOT trigger. (German var names are rare but the linter must not
# flag them — Rule 13 covers comments, not identifiers.)
# ─────────────────────────────────────────────────────────────────────────────
run_case "case7: German identifier in code, not comment (silent)" "" \
  "$(cat <<'EOF'
diff --git a/Foo.kt b/Foo.kt
--- a/Foo.kt
+++ b/Foo.kt
@@ -1,2 +1,3 @@
 fun foo() {
+    val größenAngabe = list.size
     work()
EOF
)"

# ─────────────────────────────────────────────────────────────────────────────
# Case 8 — KDoc continuation line with German → triggers (KDoc-aware).
# ─────────────────────────────────────────────────────────────────────────────
run_case "case8: KDoc continuation with German (triggers)" \
  "Foo.kt:3:Diese Methode prüft, ob die Eingabe gültig ist." \
  "$(cat <<'EOF'
diff --git a/Foo.kt b/Foo.kt
--- a/Foo.kt
+++ b/Foo.kt
@@ -1,3 +1,5 @@
 /**
  * Description line.
+ * Diese Methode prüft, ob die Eingabe gültig ist.
  */
 fun foo() {
EOF
)"

# ─────────────────────────────────────────────────────────────────────────────
# Case 9 — Inline comment after code with German → triggers.
# ─────────────────────────────────────────────────────────────────────────────
run_case "case9: inline comment with German (triggers)" \
  "Foo.kt:2:Größe muss positiv sein" \
  "$(cat <<'EOF'
diff --git a/Foo.kt b/Foo.kt
--- a/Foo.kt
+++ b/Foo.kt
@@ -1,2 +1,3 @@
 fun foo() {
+    require(size > 0) // Größe muss positiv sein
     work()
EOF
)"

# ─────────────────────────────────────────────────────────────────────────────
# Case 10 — URL containing `//` in code line → must NOT trigger as comment.
# (The `://` guard in extract_comment skips URLs.)
# ─────────────────────────────────────────────────────────────────────────────
run_case "case10: URL with // in code line (silent)" "" \
  "$(cat <<'EOF'
diff --git a/Foo.kt b/Foo.kt
--- a/Foo.kt
+++ b/Foo.kt
@@ -1,2 +1,3 @@
 fun foo() {
+    val url = "https://example.com/path"
     work()
EOF
)"

# ─────────────────────────────────────────────────────────────────────────────
# Case 11 — Two violations in one diff, line numbers must track correctly.
# ─────────────────────────────────────────────────────────────────────────────
run_case "case11: two violations, line counter correctness" \
  "Foo.kt:2:Erste deutsche Zeile mit Größe.
Foo.kt:5:Zweite deutsche Zeile, auch mit Umlaut wäre nett." \
  "$(cat <<'EOF'
diff --git a/Foo.kt b/Foo.kt
--- a/Foo.kt
+++ b/Foo.kt
@@ -1,3 +1,6 @@
 fun foo() {
+    // Erste deutsche Zeile mit Größe.
     work()
     middleStuff()
+    // Zweite deutsche Zeile, auch mit Umlaut wäre nett.
     more()
EOF
)"

# ─────────────────────────────────────────────────────────────────────────────
# Summary.
# ─────────────────────────────────────────────────────────────────────────────
echo
if [ "$failures" -eq 0 ]; then
  echo "✓ All Rule 13 fixture tests passed."
  exit 0
fi
echo "$failures fixture(s) regressed."
exit 1
