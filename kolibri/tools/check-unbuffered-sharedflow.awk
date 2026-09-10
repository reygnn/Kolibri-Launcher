# =============================================================================
# Unbuffered MutableSharedFlow check (awk core)
# =============================================================================
#
# A `MutableSharedFlow(...)` constructed with NO buffer — the default
# `replay = 0, extraBufferCapacity = 0` rendezvous configuration — silently
# DROPS an emission whenever no collector is currently subscribed: `emit`
# suspends until a subscriber appears (or, under `withTimeout`, is cancelled
# and the signal is lost). For an event bus / update-signal flow that is
# emitted from a lifecycle-independent producer (a BroadcastReceiver, a
# process just being started by the very broadcast it must react to), that is
# a latent lost-update bug — exactly AUDIT-9 #5 (`AppUpdateSignal`), whose
# correctly-buffered siblings (`AppUpdateModule` extraBufferCapacity = 1,
# `ErrorEventBus` replay = 5) show the intended shape.
#
# This makes the buffering discipline enforceable. GLOBAL scan over main
# sources (not a positive list): the shape is precise — only a bare
# CONSTRUCTION with a provably-zero buffer flags; a type annotation
# (`: MutableSharedFlow<Unit>`), an import, or a comment mention never does.
#
# == WHAT COUNTS AS BUFFERED (not flagged) ==
#   - `replay = <n>` with n != 0, or a positional first arg `<n>` (replay), or
#   - `extraBufferCapacity = <n>` with n != 0.
#   A non-literal value (`extraBufferCapacity = SOME_CONST`) counts as buffered
#   too — only a literal `= 0` (or an empty arg list) is treated as unbuffered,
#   so a constant reference is never a false positive.
#
# == ESCAPE HATCH ==
#   A genuinely-intended rendezvous flow (a collector is provably always active
#   before any emit, so a drop cannot happen) carries the marker
#   `rendezvous intended` within +/-2 lines of the construction. Same model as
#   the cancellation check's `no suspension point`: the red build IS the review
#   prompt; the marker is how you record "reviewed, drop is impossible here".
#
# Test buffering is deliberately OUT of scope (this scans main sources only):
# an unbuffered test flow with an immediate turbine collector is legitimate,
# and the misuse there fails loudly and fast (the test hangs / times out on
# first emit) — already documented in TESTING_CONVENTIONS.kt §2.
#
# Output format (matches check-conventions.sh):  FILENAME:LINE: <line content>
# Exit code: always 0 (printing alone signals violations to the caller).
# =============================================================================

# Consume a balanced `<...>` generic starting at position 1 of s; return the
# remainder after the closing `>` (leading whitespace trimmed). If s does not
# start with `<`, s is returned unchanged.
function skip_generic(s,    d, i, L, c) {
    if (substr(s, 1, 1) != "<") return s
    d = 0; L = length(s); i = 1
    while (i <= L) {
        c = substr(s, i, 1)
        if (c == "<") d++
        else if (c == ">") { d--; if (d == 0) { i++; break } }
        i++
    }
    s = substr(s, i)
    sub(/^[[:space:]]+/, "", s)
    return s
}

{
    raw[NR] = $0
    code = $0
    sub(/\/\/.*$/, "", code)   # strip line comment for the join
    stripped[NR] = code
}

END {
    span = 8   # max lines a construction's arg list may span in the lookahead

    for (n = 1; n <= NR; n++) {
        line = raw[n]

        # skip comment-only / KDoc / import lines
        t = line
        sub(/^[[:space:]]+/, "", t)
        if (t ~ /^(\/\/|\*|\/\*)/) continue
        if (line ~ /^[[:space:]]*import[[:space:]]/) continue
        if (stripped[n] !~ /MutableSharedFlow/) continue

        # join comment-stripped lines n..n+span so a multi-line arg list
        # (replay / extraBufferCapacity on their own lines) is seen as one.
        buf = stripped[n]
        for (k = n + 1; k <= NR && k <= n + span; k++) buf = buf " " stripped[k]

        # position just past the "MutableSharedFlow" token
        idx = index(buf, "MutableSharedFlow")
        rest = substr(buf, idx + 17)          # length("MutableSharedFlow") == 17
        sub(/^[[:space:]]+/, "", rest)

        rest = skip_generic(rest)             # optional <Type> / <A<B>>

        # a construction requires a call paren here; anything else
        # (a type annotation, a bare reference) is not a construction.
        if (substr(rest, 1, 1) != "(") continue

        # capture the balanced outer (...) argument text
        d = 0; L = length(rest); i = 1; args = ""; closed = 0
        while (i <= L) {
            c = substr(rest, i, 1)
            if (c == "(") {
                d++
                if (d == 1) { i++; continue }   # drop the outer opening paren
            } else if (c == ")") {
                d--
                if (d == 0) { closed = 1; break }   # drop the outer closing paren
            }
            args = args c
            i++
        }
        # arg list did not close within the lookahead window — be conservative
        # and do not flag (avoids a false positive on an odd multi-line shape).
        if (!closed) continue

        # buffered?
        buffered = 0
        if (args ~ /replay[[:space:]]*=/ &&
            args !~ /replay[[:space:]]*=[[:space:]]*0([^0-9]|$)/) buffered = 1
        if (args ~ /extraBufferCapacity[[:space:]]*=/ &&
            args !~ /extraBufferCapacity[[:space:]]*=[[:space:]]*0([^0-9]|$)/) buffered = 1
        if (args ~ /^[[:space:]]*[1-9]/) buffered = 1   # positional replay > 0
        if (buffered) continue

        # escape hatch: `rendezvous intended` marker within +/-2 lines
        marked = 0
        for (k = n - 2; k <= n + 2; k++) {
            if (k < 1 || k > NR) continue
            if (raw[k] ~ /rendezvous intended/) { marked = 1; break }
        }
        if (marked) continue

        print FILENAME ":" n ": " raw[n]
    }
}
