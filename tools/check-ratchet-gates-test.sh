#!/usr/bin/env bash
# =============================================================================
# Regression test for the ratchet gates: A8 (check-mirror-comments.awk),
# A13 (check-hardcoded-dispatchers.awk), the shared ratchet (check-ratchet.sh)
# and A11 (check-whilesubscribed-literal.awk). NOT wired into Gradle; rerun when
# one of them changes. Exit 0 ok, 1 regression.
# =============================================================================
set -u
d="$(cd "$(dirname "$0")" && pwd)"
tmp=$(mktemp -d) || exit 2; trap 'rm -rf "$tmp"' EXIT; fail=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected [$3], got [$2]"; fail=1; else echo "ok   $1"; fi; }
col3() { cut -f3 | tr '\n' ' ' | sed 's/ $//'; }

# ---- A11 ---------------------------------------------------------------------
cat > "$tmp/A11.kt" <<'KT'
val a = flow.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)                 // 1 flag
val b = flow.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000), 0) // 2 flag
val c = flow.stateIn(scope, SharingStarted.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS), 0) // 3 ok
// SharingStarted.WhileSubscribed(5_000) in a comment                                    // 4 ok
val e = flow.stateIn(scope, SharingStarted.WhileSubscribed(), 0)                         // 5 ok
KT
check "A11" "$(awk -f "$d/check-whilesubscribed-literal.awk" "$tmp/A11.kt" | cut -d: -f2 | tr '\n' ' ' | sed 's/ $//')" "1 2"

# ---- A8 core -----------------------------------------------------------------
cat > "$tmp/A8.kt" <<'KT'
// Mirrors Kolibri's AppManagementDelegate                        1 flag
/**
 * Kolibri parity: same toast                                     3 flag
 * mirrors the drawer slide duration                              4 ok (no app name)
 */
val nyxMirror = 1 // not a comment line start                     6 ok
/* port of nyx §Audit-3 A3-05 */                                  7 flag
KT
check "A8 core" "$(awk -f "$d/check-mirror-comments.awk" "$tmp/A8.kt" | col3)" "1 3 7"

# ---- A13 core ----------------------------------------------------------------
cat > "$tmp/A13.kt" <<'KT'
import kotlinx.coroutines.Dispatchers                                   // 1 ok (import)
val a = withContext(Dispatchers.IO) { }                                 // 2 flag
scope.launch(Dispatchers.Main.immediate) { }                            // 3 flag
// withContext(Dispatchers.Default) in a comment                        // 4 ok
val b = withContext(ioDispatcher) { }                                   // 5 ok
val c = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default) // 6 flag
val d = MyDispatchers.IO                                                // 7 ok (other name)
KT
check "A13 core" "$(awk -f "$d/check-hardcoded-dispatchers.awk" "$tmp/A13.kt" | col3)" "2 3 6"
mkdir -p "$tmp/p"; printf 'fun provideIo() = Dispatchers.IO\n' > "$tmp/p/DispatcherModule.kt"
check "A13 provider exempt" "$(awk -f "$d/check-hardcoded-dispatchers.awk" "$tmp/p/DispatcherModule.kt" | wc -l | tr -d ' ')" "0"

# ---- shared ratchet (check-ratchet.sh), exercised with the A13 detector ----------
mkdir -p "$tmp/mono/tools" "$tmp/mono/app/src/main/java"
cp "$d/check-ratchet.sh" "$d/check-hardcoded-dispatchers.awk" "$tmp/mono/tools/"
X="$tmp/mono/app/src/main/java/X.kt"
printf 'a(Dispatchers.Main)\na(Dispatchers.Main)\nb(Dispatchers.IO)\n' > "$X"
printf '#\napp/src/main/java/X.kt\ta(Dispatchers.Main)\napp/src/main/java/X.kt\ta(Dispatchers.Main)\napp/src/main/java/X.kt\tgone(Dispatchers.IO)\n' > "$tmp/mono/tools/allow.txt"
run() { bash "$tmp/mono/tools/check-ratchet.sh" "$tmp/mono/tools/check-hardcoded-dispatchers.awk" "$tmp/mono/tools/allow.txt" "T" "$tmp/mono/app/src/main/java"; }
out=$(run)
check "ratchet: new line flagged"          "$(printf '%s\n' "$out" | grep -c 'b(Dispatchers.IO)')" "1"
check "ratchet: stale entry reported"      "$(printf '%s\n' "$out" | grep -c 'gone(Dispatchers.IO)')" "1"
check "ratchet: allowlisted twice is quiet" "$(printf '%s\n' "$out" | grep -c 'a(Dispatchers.Main)')" "0"
printf 'a(Dispatchers.Main)\n' >> "$X"        # a third identical line — only two are allowlisted
out=$(run)
check "ratchet: extra identical line flagged" "$(printf '%s\n' "$out" | grep -c '1 more than allowlisted')" "1"

[ "$fail" -eq 0 ] && echo "✓ ratchet gates (A8/A11/A13) OK" || exit 1
