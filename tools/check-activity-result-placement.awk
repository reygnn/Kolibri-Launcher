# =============================================================================
# registerForActivityResult() placement check (awk core)
# =============================================================================
#
# `registerForActivityResult(...)` registers with the Activity/Fragment result
# registry and MUST run before the host reaches STARTED — i.e. as a field
# initializer or in `onCreate`. Called from `onViewCreated` / `onCreateView` /
# `onResume` (etc.) it throws `LifecycleOwners must call register before they
# are STARTED` on the next config change (AUDIT-5 #2). This pins the placement.
#
# Detection: the ENCLOSING function of each `registerForActivityResult(` call is
# resolved by brace-depth tracking. A call whose innermost enclosing function is
# a forbidden lifecycle method flags. A call sitting directly in the class body
# (a `val x = registerForActivityResult(...)` field) has no enclosing function
# and is fine; a call inside `onCreate` or a plain helper is fine too.
#
# Comment / KDoc lines never flag (WallpaperImagePicker.kt and ConsentDialog.kt
# mention the call in KDoc).
#
# Output format (matches check-conventions.sh):  FILENAME:LINE: <line content>
# Exit code: always 0 (printing alone signals violations to the caller).
# =============================================================================
BEGIN {
    forbidden = "onViewCreated onCreateView onViewStateRestored onResume onStart onActivityCreated onAttach"
    split(forbidden, farr, " ")
    for (x in farr) forbid[farr[x]] = 1
}
{
    line = $0
    code = line
    sub(/\/\/.*$/, "", code)   # strip line comment

    # A registerForActivityResult call is evaluated against the CURRENT enclosing
    # function (depth before this line's braces are applied), since the call sits
    # inside already-open braces.
    if (line !~ /^[[:space:]]*(\/\/|\*|\/\*)/ && code ~ /registerForActivityResult[[:space:]]*\(/) {
        enc = ""
        for (d = depth; d >= 1; d--) {
            if (fname[d] != "") { enc = fname[d]; break }
        }
        if (enc != "" && forbid[enc]) print FILENAME ":" NR ": " line
    }

    # Remember a function header until its opening brace assigns it a depth.
    # NB: awk has no `\b` word boundary (it means backspace), so anchor `fun`
    # on a non-identifier char or line start.
    if (match(code, /(^|[^A-Za-z0-9_])fun[[:space:]]+[A-Za-z0-9_]+[[:space:]]*\(/)) {
        h = substr(code, RSTART, RLENGTH)
        sub(/^.*fun[[:space:]]+/, "", h)
        sub(/[[:space:]]*\(.*$/, "", h)
        pending = h
    }

    # Walk braces to maintain depth and the function-at-depth map.
    L = length(code)
    for (i = 1; i <= L; i++) {
        c = substr(code, i, 1)
        if (c == "{") {
            depth++
            if (pending != "") { fname[depth] = pending; pending = "" }
            else fname[depth] = ""
        } else if (c == "}") {
            fname[depth] = ""
            if (depth > 0) depth--
        }
    }
}
