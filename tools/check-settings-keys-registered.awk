# =============================================================================
# Settings-store keep-list completeness (awk core)
# =============================================================================
#
# Runs over each `OwnsSettingsStoreKeys` OWNER file. The storage-cleanup feature
# is a blacklist-of-unknown: it deletes every settings-store key that no live
# owner claims. So every settings-store key an owner file DECLARES must be
# registered in that same file's `ownedExactKeys()` / `ownedKeyPrefixes()` — a
# key left unregistered is LIVE data the cleanup would treat as an orphan and
# DELETE. This pins the invariant per owner file (the AnrReporter-straggler axis
# — a whole file forgetting to join — is guarded separately in
# check-conventions.sh).
#
# == DECLARED EXACT KEY ==
#   `[private] val UPPER_SNAKE = <...>PreferencesKey(<arg>)` where <arg> is a
#   plain constant (NO ` + ` concatenation). Its val NAME must appear in the
#   `ownedExactKeys()` body, which references it as `Foo.NAME.name`.
#
# == DECLARED PREFIX KEY ==
#   Any `<...>PreferencesKey(<TOKEN> + …)` — a dynamic per-entity key built from
#   a prefix (e.g. `stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + pkg)`).
#   The prefix's last identifier segment (`KEY_NAME_PREFIX`) must appear in the
#   `ownedKeyPrefixes()` body.
#
# Both bodies are `= setOf(...)` single-expression functions, so the body is the
# text inside that `setOf(...)` (paren-balanced), NOT a brace block.
#
# Output format (matches check-conventions.sh):  FILENAME:LINE: <line content>
# Exit code: always 0 (printing alone signals violations to the caller).
# =============================================================================

# Inner text of the `= setOf(...)` expression of `fun <fname>`, comments
# stripped, newlines flattened to spaces. "" if the function is not overridden.
function collect_setof_body(fname,   n, start, region, i, c, p, depth, j, ch, body, len) {
    start = 0
    for (n = 1; n <= NR; n++) {
        if (lines[n] ~ ("fun[[:space:]]+" fname "[[:space:]]*\\(")) { start = n; break }
    }
    if (start == 0) return ""

    region = ""
    for (i = start; i <= NR; i++) {
        c = lines[i]
        sub(/\/\/.*$/, "", c)
        region = region " " c
    }

    p = index(region, "setOf(")
    if (p == 0) return ""

    # Walk from the '(' of setOf (5 chars after p) until the depth returns to 0.
    depth = 0; body = ""; len = length(region)
    for (j = p + 5; j <= len; j++) {
        ch = substr(region, j, 1)
        if (ch == "(") { depth++; if (depth == 1) continue }
        if (ch == ")") { depth--; if (depth == 0) break }
        body = body ch
    }
    return body
}

{ lines[NR] = $0 }

END {
    exactBody  = collect_setof_body("ownedExactKeys")
    prefixBody = collect_setof_body("ownedKeyPrefixes")

    for (n = 1; n <= NR; n++) {
        line = lines[n]
        code = line
        sub(/\/\/.*$/, "", code)

        # ---- exact constant key: val UPPER = <...>PreferencesKey(<no '+'>) ----
        if (code ~ /^[[:space:]]*(private[[:space:]]+)?val[[:space:]]+[A-Z][A-Z0-9_]*[[:space:]]*=/) {
            rhs = code
            if (n < NR) {
                nx = lines[n + 1]
                sub(/\/\/.*$/, "", nx)
                rhs = rhs " " nx
            }
            if (rhs ~ /[Pp]referencesKey[[:space:]]*\(/) {
                # A prefix-built key ( '+' inside the call ) is handled by the
                # prefix branch below, not here.
                if (rhs !~ /PreferencesKey[[:space:]]*\([^)]*\+/) {
                    name = code
                    sub(/^[[:space:]]*(private[[:space:]]+)?val[[:space:]]+/, "", name)
                    sub(/[[:space:]]*=.*$/, "", name)
                    if (index(exactBody, name) == 0) {
                        print FILENAME ":" n ": " line
                    }
                }
            }
        }

        # ---- dynamic prefix key: <...>PreferencesKey(<TOKEN> + …) ----
        if (code ~ /PreferencesKey[[:space:]]*\([^)]*\+/) {
            tmp = code
            sub(/^.*PreferencesKey[[:space:]]*\(/, "", tmp)   # drop through the '('
            sub(/\+.*$/, "", tmp)                             # keep the prefix operand
            gsub(/[[:space:]]/, "", tmp)
            seg = tmp
            sub(/^.*\./, "", seg)                             # last dotted segment
            if (seg != "" && index(prefixBody, seg) == 0) {
                print FILENAME ":" n ": " line
            }
        }
    }
}
