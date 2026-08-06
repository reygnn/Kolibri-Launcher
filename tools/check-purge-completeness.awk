# =============================================================================
# purgeRepository() completeness check (awk core)
# =============================================================================
#
# A repository that persists user state must wipe ALL of it on
# `purgeRepository()` (the "reset all settings" path). AUDIT-4 #1 caught
# `SettingsRepositoryImpl` declaring `APP_DRAWER_MODE` (and the
# wallpaperSurfaceMode it backs) but never removing it in purge — a silent
# "reset" that left a setting behind. This pins the invariant: every
# statically-declared preference key in a `*RepositoryImpl` must be handled by
# that file's `purgeRepository()`.
#
# == WHAT IS A "DECLARED KEY" ==
#   A constant-style declaration `val UPPER_SNAKE = <...>PreferencesKey(...)`
#   (string/boolean/int/long/float/double/stringSet). The UPPER_SNAKE filter is
#   deliberate: dynamic per-entity keys are built inline from a prefix as local
#   `camelCase` vals (`val usageKey = stringSetPreferencesKey(PREFIX + pkg)` in
#   UsageExport/CustomNames) and are purged by iteration, not by a named
#   constant — those must NOT be required here, and the naming split separates
#   them cleanly.
#
# == WHAT COUNTS AS "HANDLED" ==
#   Either the key name appears in a NON-comment line of the `purgeRepository()`
#   body (a real `preferences.remove(PreferenceKeys.NAME)` call), OR it is
#   explicitly exempted by a `purge-exempt NAME` marker inside the body. A
#   comment merely *mentioning* the key does NOT count — that loophole is
#   exactly how a "reset" can silently regress (the AUDIT-4 removal line sat
#   right next to a comment naming the key). A key intentionally kept across a
#   reset (e.g. onboarding-completed) states so with the marker. A wholesale
#   `.clear()` in the body handles every key at once, so such a file is exempt
#   entirely.
#
# Only files that HAVE a `purgeRepository()` are checked; a repo that delegates
# its wipe elsewhere declares no purge here and is skipped.
#
# Output format (matches check-conventions.sh):  FILENAME:LINE: <line content>
# Exit code: always 0 (printing alone signals violations to the caller).
# =============================================================================
{ lines[NR] = $0 }

END {
    # ---- locate purgeRepository() body (brace-balanced) ----
    purge_start = 0
    for (n = 1; n <= NR; n++) {
        if (lines[n] ~ /fun[[:space:]]+purgeRepository[[:space:]]*\(/) { purge_start = n; break }
    }
    if (purge_start == 0) exit   # no purge in this file — nothing to enforce

    depth = 0; started = 0; purge_end = NR
    for (i = purge_start; i <= NR; i++) {
        code = lines[i]
        sub(/\/\/.*$/, "", code)
        nopen  = gsub(/\{/, "{", code)
        nclose = gsub(/\}/, "}", code)
        depth += nopen - nclose
        if (nopen > 0) started = 1
        if (started && depth <= 0) { purge_end = i; break }
    }

    # ---- build the purge-body text ----
    # code_body: comments stripped — the only text a REAL key reference counts
    #   in (a comment mentioning a key must not mask a missing removal).
    # exempt[NAME]: keys explicitly kept across a reset via a `purge-exempt`
    #   marker line (comment or code) naming the key.
    code_body = ""
    for (i = purge_start; i <= purge_end; i++) {
        c = lines[i]
        sub(/\/\/.*$/, "", c)
        code_body = code_body "\n" c

        if (lines[i] ~ /purge-exempt/) {
            tmp = lines[i]
            while (match(tmp, /[A-Z][A-Z0-9_]+/)) {
                tok = substr(tmp, RSTART, RLENGTH)
                exempt[tok] = 1
                tmp = substr(tmp, RSTART + RLENGTH)
            }
        }
    }

    # wholesale clear handles every key
    if (code_body ~ /\.clear\(\)/) exit

    # ---- collect declared keys and flag any absent from the purge body ----
    for (n = 1; n <= NR; n++) {
        line = lines[n]
        if (line !~ /^[[:space:]]*val[[:space:]]+[A-Z][A-Z0-9_]*[[:space:]]*=/) continue

        # confirm the RHS is a preferences-key factory (this line or the next,
        # since some declarations wrap: `val NAME =` \n `booleanPreferencesKey(`)
        rhs = line
        if (n < NR) rhs = rhs " " lines[n + 1]
        if (rhs !~ /[Pp]referencesKey[[:space:]]*\(/) continue

        # extract the key name
        name = line
        sub(/^[[:space:]]*val[[:space:]]+/, "", name)
        sub(/[[:space:]]*=.*$/, "", name)

        # a declaration inside the purge body itself is not a "key to purge"
        if (n >= purge_start && n <= purge_end) continue

        # handled: a real (non-comment) reference in purge, or an explicit exempt
        if (index(code_body, name) > 0) continue
        if (exempt[name]) continue

        print FILENAME ":" n ": " line
    }
}
