# =============================================================================
# RecyclerView adapter null-out check (awk core)
# =============================================================================
#
# A Fragment that sets `recyclerView.adapter = someAdapter` but never clears it
# in `onDestroyView` leaks: the RecyclerView (and through it the whole view
# hierarchy) stays reachable from the adapter's registered observers across the
# fragment's view-recreation cycle (AUDIT-3 #13, AUDIT-4 #3). The canonical
# done-right reference is `AppDrawerFragment` (assign in setup, `= null` in
# onDestroyView). This pins the invariant for Fragments.
#
# Scope: FRAGMENTS only (a class whose superclass name ends in `Fragment` /
# `PreferenceFragmentCompat`). Activities clear their adapter in `onDestroy`
# instead and are out of scope.
#
# Flags a Fragment that assigns a non-null adapter but has no `adapter = null`
# inside its `onDestroyView` body (or has no `onDestroyView` at all). A `.clear`
# is not accepted — the property must be nulled. Escape hatch for a fragment
# that provably cannot leak (e.g. adapter owned elsewhere): an
# `adapter-nulling n/a` marker on the assignment line.
#
# Output format (matches check-conventions.sh):  FILENAME:LINE: <line content>
# Exit code: always 0 (printing alone signals violations to the caller).
# =============================================================================
{ lines[NR] = $0 }

END {
    # ---- is this a Fragment? ----
    is_fragment = 0
    for (n = 1; n <= NR; n++) {
        if (lines[n] ~ /^[[:space:]]*(internal |open |abstract |sealed )*class [A-Za-z0-9_]+[[:space:]]*:[^{]*(Fragment|PreferenceFragmentCompat)/) {
            is_fragment = 1
            break
        }
    }
    if (!is_fragment) exit

    # ---- collect non-null adapter assignments ----
    assign_line = 0
    for (n = 1; n <= NR; n++) {
        code = lines[n]
        sub(/\/\/.*$/, "", code)
        # `<x>.adapter = <rhs>` or a bare `adapter = <rhs>` (inside apply{}),
        # where <rhs> is not `null`.
        if (code ~ /(^|[.[:space:]])adapter[[:space:]]*=[[:space:]]*[^ ]/ &&
            code !~ /adapter[[:space:]]*=[[:space:]]*null/) {
            # escape hatch on the raw line
            if (lines[n] ~ /adapter-nulling n\/a/) continue
            if (assign_line == 0) assign_line = n
        }
    }
    if (assign_line == 0) exit   # no adapter to worry about

    # ---- locate onDestroyView body and check for a null-out ----
    ody_start = 0
    for (n = 1; n <= NR; n++) {
        if (lines[n] ~ /fun[[:space:]]+onDestroyView[[:space:]]*\(/) { ody_start = n; break }
    }

    nulled = 0
    if (ody_start > 0) {
        depth = 0; started = 0
        for (i = ody_start; i <= NR; i++) {
            code = lines[i]
            sub(/\/\/.*$/, "", code)
            if (code ~ /adapter[[:space:]]*=[[:space:]]*null/) nulled = 1
            nopen  = gsub(/\{/, "{", code)
            nclose = gsub(/\}/, "}", code)
            depth += nopen - nclose
            if (nopen > 0) started = 1
            if (started && depth <= 0) break
        }
    }

    if (!nulled) print FILENAME ":" assign_line ": " lines[assign_line]
}
