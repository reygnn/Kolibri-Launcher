#!/usr/bin/env bash
# =============================================================================
# Regression test for check-init-order-launch.awk
# =============================================================================
# Feeds synthetic fixtures to the awk core and asserts which lines it flags.
# Manual rerun (not a CI gate), mirroring the other check-*-test.sh scripts.
#
#   bash tools/check-init-order-launch-test.sh   → prints PASS/FAIL per case,
#                                                   exit 0 all-pass else 1.
# =============================================================================
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
awkf="$script_dir/check-init-order-launch.awk"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fails=0
# assert_hits <fixture-file> <expected-hit-count> <label>
assert_hits() {
  local file="$1" expected="$2" label="$3"
  local got
  got="$(awk -f "$awkf" "$file" | grep -c ':' || true)"
  if [ "$got" = "$expected" ]; then
    echo "PASS  $label (hits=$got)"
  else
    echo "FAIL  $label — expected $expected hit(s), got $got"
    awk -f "$awkf" "$file" | sed 's/^/        /'
    fails=$((fails + 1))
  fi
}

# CASE 1 — the FolderIconRenderer bug: launch in init, then property initializers.
cat > "$tmp/Buggy.kt" <<'EOF'
class Buggy(dispatcher: CoroutineDispatcher) {
    init {
        flow.onEach { clear() }.launchIn(CoroutineScope(SupervisorJob() + dispatcher))
    }
    private val lock = Any()
    private val cache = object : LinkedHashMap<K, V>(16, 0.75f, true) { }
    fun clear() = synchronized(lock) { cache.clear() }
}
EOF
assert_hits "$tmp/Buggy.kt" 2 "launch-in-init then two property initializers → 2"

# CASE 2 — the fix: properties declared BEFORE the launching init.
cat > "$tmp/Clean.kt" <<'EOF'
class Clean(dispatcher: CoroutineDispatcher) {
    private val lock = Any()
    private val cache = object : LinkedHashMap<K, V>(16, 0.75f, true) { }
    init {
        flow.onEach { clear() }.launchIn(CoroutineScope(SupervisorJob() + dispatcher))
    }
    fun clear() = synchronized(lock) { cache.clear() }
    private companion object { const val MAX = 64 }
}
EOF
assert_hits "$tmp/Clean.kt" 0 "properties before launching init → 0"

# CASE 3 — init block that does NOT launch a coroutine → not a hazard.
cat > "$tmp/NoLaunch.kt" <<'EOF'
class NoLaunch {
    init { require(field != null) }
    private val x = 5
}
EOF
assert_hits "$tmp/NoLaunch.kt" 0 "non-launching init then property → 0"

# CASE 4 — launch(job) builder form (scope.launch { }) + a later var initializer.
cat > "$tmp/LaunchBuilder.kt" <<'EOF'
class LaunchBuilder(private val scope: CoroutineScope) {
    init {
        scope.launch { seed() }
    }
    private var state = mutableListOf<Int>()
    fun seed() { state.add(0) }
}
EOF
assert_hits "$tmp/LaunchBuilder.kt" 1 "scope.launch in init then var initializer → 1"

# CASE 5 — a later member that is a FUNCTION or a property WITHOUT an initializer
#          must NOT flag (only initialized backing fields race).
cat > "$tmp/NoInitializer.kt" <<'EOF'
class NoInitializer(dispatcher: CoroutineDispatcher) {
    init {
        flow.onEach { work() }.launchIn(CoroutineScope(dispatcher))
    }
    abstract val handle: String            // no initializer
    fun work() { }
    private companion object { const val MAX = 64 }
}
EOF
assert_hits "$tmp/NoInitializer.kt" 0 "no class-level initializer after launching init → 0"

echo
if [ "$fails" -eq 0 ]; then
  echo "ALL PASS"
  exit 0
else
  echo "$fails case(s) FAILED"
  exit 1
fi
