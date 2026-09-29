#!/usr/bin/env bash
# =============================================================================
# Regression test for A8 (check-mirror-comments.awk/.sh) and A11
# (check-whilesubscribed-literal.awk). Same shape as the sibling *-test.sh:
# NOT wired into Gradle; rerun when a detector changes. Exit 0 ok, 1 regression.
# =============================================================================
set -u
d="$(cd "$(dirname "$0")" && pwd)"
tmp=$(mktemp -d) || exit 2; trap 'rm -rf "$tmp"' EXIT; fail=0
lines() { cut -d: -f2 | tr '\n' ' ' | sed 's/ $//'; }
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected [$3], got [$2]"; fail=1; else echo "ok   $1"; fi; }

cat > "$tmp/A11.kt" <<'KT'
val a = flow.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)                 // 1 flag
val b = flow.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000), 0) // 2 flag
val c = flow.stateIn(scope, SharingStarted.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS), 0) // 3 ok
// SharingStarted.WhileSubscribed(5_000) in a comment                                    // 4 ok
val e = flow.stateIn(scope, SharingStarted.WhileSubscribed(), 0)                         // 5 ok
KT
check "A11" "$(awk -f "$d/check-whilesubscribed-literal.awk" "$tmp/A11.kt" | lines)" "1 2"

cat > "$tmp/A8.kt" <<'KT'
// Mirrors Kolibri's AppManagementDelegate                        1 flag
/**
 * Kolibri parity: same toast                                     3 flag
 * mirrors the drawer slide duration                              4 ok (no app name)
 */
val nyxMirror = 1 // not a comment line start                     6 ok
/* port of nyx §Audit-3 A3-05 */                                  7 flag
KT
check "A8 core" "$(awk -f "$d/check-mirror-comments.awk" "$tmp/A8.kt" | cut -f3 | tr '\n' ' ' | sed 's/ $//')" "1 3 7"

# ratchet: run the .sh against a private copy of tools/ with a crafted allowlist
mkdir -p "$tmp/mono/tools" "$tmp/mono/app/src/main/java"
cp "$d/check-mirror-comments.sh" "$d/check-mirror-comments.awk" "$tmp/mono/tools/"
printf '// mirrors Kolibri A\n// mirrors Kolibri B\n' > "$tmp/mono/app/src/main/java/X.kt"
printf '#\napp/src/main/java/X.kt\t// mirrors Kolibri A\napp/src/main/java/X.kt\t// mirrors Kolibri GONE\n' > "$tmp/mono/tools/mirror-allowlist.txt"
out=$(bash "$tmp/mono/tools/check-mirror-comments.sh" "$tmp/mono/app/src/main/java")
check "A8 ratchet: new comment flagged" "$(printf '%s\n' "$out" | grep -c 'Kolibri B')" "1"
check "A8 ratchet: stale entry reported" "$(printf '%s\n' "$out" | grep -c 'Kolibri GONE')" "1"
check "A8 ratchet: allowlisted stays quiet" "$(printf '%s\n' "$out" | grep -c 'Kolibri A$')" "0"

[ "$fail" -eq 0 ] && echo "✓ A8/A11 detectors OK" || exit 1
