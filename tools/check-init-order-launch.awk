# =============================================================================
# Init-order launch hazard check (awk core)
# =============================================================================
#
# Reports the Kotlin init-order race that produced the FolderIconRenderer NPE:
# an `init { }` block that LAUNCHES a coroutine (launchIn / .launch / async /
# CoroutineScope(...)) while a class-level PROPERTY WITH AN INITIALIZER is
# declared AFTER that init block in the same class.
#
# == WHY THIS EXISTS ==
#
# Kotlin runs property initializers and init blocks in DECLARATION ORDER. A
# coroutine launched inside `init { }` can start running before construction
# finishes (it launches on some dispatcher, and a StateFlow it collects replays
# its value immediately). If the launched code touches a property whose
# initializer is declared BELOW the init block, that property's backing field is
# still null when the coroutine runs → NPE. FolderIconRenderer hit exactly this:
#
#     init { iconLoader.currentStyle.onEach { clear() }.launchIn(scope) }  // launches
#     private val lock = Any()                                             // still null when clear() runs
#     private val cache = object : LinkedHashMap<…>() { … }
#
# clear() did `synchronized(lock)` on a null lock → intermittent startup NPE.
# The fix is to declare lock/cache BEFORE the init block.
#
# == THE RULE (narrow, structural) ==
#
# After an `init { }` block that launches a coroutine, no class-level property
# with an initializer may follow it in the same class body. Move the state above
# the init block (or the launching init to the bottom). This is deliberately a
# STRUCTURAL over-approximation: it does not prove the launched code touches the
# later property (that needs dataflow). But launching in init and then declaring
# state below is a smell regardless, and the fix (reorder) is mechanical and safe.
#
# Used by:
#   - tools/check-conventions.sh (one orchestrator, --app kolibri|nyx)
#     as a positive-list gate (INITORDER_FILES in tools/conventions/<app>.conf) — a regression lock on reviewed
#     files.
#   - tools/scan-init-order-launch.sh — report-only global discovery.
#   - tools/check-init-order-launch-test.sh — regression test on fixtures.
#
# Output format (matches check-conventions.sh expectations):
#   FILENAME:LINE: <offending property line>
#
# Exit code: always 0 (printing alone signals violations to the caller).
# =============================================================================
{ lines[NR] = $0 }
END {
    launch_re = "\\.launch[[:space:]]*[({]|launchIn[[:space:]]*\\(|(^|[^A-Za-z0-9_.])async[[:space:]]*[({]|CoroutineScope[[:space:]]*\\("
    # A class-level property declaration that CARRIES an initializer (`= …`).
    # Optional visibility/modifiers, then val|var NAME, then anything up to `=`.
    prop_re = "^[[:space:]]*(((private|internal|protected|public|lateinit|open|override|final)[[:space:]]+)*)(val|var)[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[^=]*="

    depth = 0
    in_init = 0
    init_member_depth = -1
    init_launches = 0
    armed = 0
    armed_depth = -1

    for (n = 1; n <= NR; n++) {
        raw = lines[n]
        code = raw
        sub(/\/\/.*/, "", code)                                   # strip line comment
        is_comment = (raw ~ /^[[:space:]]*(\/\/|\*|\/\*)/)

        start_depth = depth

        # Entering an init block (only track one at a time; nested launches in a
        # single init still arm it).
        if (!is_comment && !in_init && code ~ /(^|[^A-Za-z0-9_.])init[[:space:]]*\{/) {
            in_init = 1
            init_member_depth = start_depth
            init_launches = 0
        }

        if (in_init && !is_comment && code ~ launch_re) init_launches = 1

        # A class-level property initializer AFTER an armed launching init → flag.
        if (armed && !is_comment && start_depth == armed_depth && code ~ prop_re) {
            print FILENAME ":" n ": " raw
        }

        # Apply this line's brace balance (comment-stripped, same imprecision the
        # sibling awk checks accept for braces inside strings).
        tmp = code; nopen = gsub(/\{/, "", tmp)
        tmp = code; nclose = gsub(/\}/, "", tmp)
        depth += nopen - nclose

        # init block closed?
        if (in_init && depth <= init_member_depth) {
            in_init = 0
            if (init_launches) { armed = 1; armed_depth = init_member_depth }
        }

        # Fell below the class body that armed us → stop watching.
        if (armed && depth < armed_depth) { armed = 0; armed_depth = -1 }
    }
}
