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
#   `[private] val UPPER_SNAKE[: Type] = <...>PreferencesKey(<arg>)` where <arg>
#   is a plain constant (NO ` + ` concatenation). Its val NAME must be registered
#   in the `ownedExactKeys()` body (which references it as `Foo.NAME.name`).
#
# == DECLARED PREFIX KEY ==
#   Any `<...>PreferencesKey(<TOKEN> + …)` — a dynamic per-entity key built from
#   a prefix (e.g. `stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + pkg)`).
#   The prefix's last identifier segment (`KEY_NAME_PREFIX`) must be registered
#   in the `ownedKeyPrefixes()` body.
#
# == REGISTRATION IS A WHOLE-TOKEN MATCH, NOT A SUBSTRING ==
#   The body is tokenised (every non-identifier char -> space) and the name must
#   appear as a WHOLE space-delimited token. A raw `index(body, name)` substring
#   test was a data-loss false-negative: a forgotten key `COLOR` would match
#   inside a registered `TEXT_COLOR.name` and pass unflagged, after which the
#   blacklist cleanup deletes the live `color` key. Realistic collisions already
#   exist among registered keys (SCALE⊂LAYOUT_SCALE, COLOR⊂TEXT_COLOR,
#   ALARM⊂SHOW_ALARM, FAB_X⊂FAB_X_FRACTION), so the token boundary is load-bearing.
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

# " token1 token2 … " — the body reduced to whole identifier tokens padded by
# spaces, so `index(t, " NAME ") > 0` is an exact-token membership test.
function tokenize(body,   t) {
    t = " " body " "
    gsub(/[^A-Za-z0-9_]/, " ", t)
    return t
}

{ lines[NR] = $0 }

END {
    exactTokens  = tokenize(collect_setof_body("ownedExactKeys"))
    prefixTokens = tokenize(collect_setof_body("ownedKeyPrefixes"))

    for (n = 1; n <= NR; n++) {
        line = lines[n]
        code = line
        sub(/\/\/.*$/, "", code)

        # ---- exact constant key: val UPPER[: Type] = <...>PreferencesKey(<no '+'>) ----
        # The optional `:[^=]*` tolerates an explicit type annotation
        # (`val X: Preferences.Key<Boolean> = …`), which would otherwise escape
        # detection entirely and never be required to register.
        if (code ~ /^[[:space:]]*(private[[:space:]]+)?val[[:space:]]+[A-Z][A-Z0-9_]*[[:space:]]*(:[^=]*)?=/) {
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
                    # Robust name extraction: grab the UPPER_SNAKE token after
                    # `val`, whatever follows it (`=`, `:`, whitespace).
                    match(code, /val[[:space:]]+[A-Z][A-Z0-9_]*/)
                    name = substr(code, RSTART, RLENGTH)
                    sub(/^val[[:space:]]+/, "", name)
                    if (index(exactTokens, " " name " ") == 0) {
                        print FILENAME ":" n ": " line
                    }
                }
            }
        }

        # ---- dynamic prefix key: <...>PreferencesKey(<TOKEN> + …) ----
        # Anchor on the line carrying the `PreferencesKey(` opening. Append the
        # NEXT line ONLY when this call does not close on this line (a real wrap
        # between the `(` and its `PREFIX +` argument), so the exact branch's
        # n+1 lookahead is matched WITHOUT (a) grabbing a second call's argument
        # when two keys sit on adjacent lines, or (b) mistaking an unrelated `+`
        # on the next line for this key's prefix. Report stays on line n.
        if (code ~ /PreferencesKey[[:space:]]*\(/) {
            tail = code
            sub(/^.*PreferencesKey[[:space:]]*\(/, "", tail)     # text after the '('
            srcline = code
            if (index(tail, ")") == 0 && n < NR) {               # call wraps to next line
                pnx = lines[n + 1]; sub(/\/\/.*$/, "", pnx)
                srcline = code " " pnx
            }
            if (srcline ~ /PreferencesKey[[:space:]]*\([^)]*\+/) {
                tmp = srcline
                sub(/^.*PreferencesKey[[:space:]]*\(/, "", tmp)  # drop through the '('
                sub(/\+.*$/, "", tmp)                            # keep the prefix operand
                gsub(/[[:space:]]/, "", tmp)
                seg = tmp
                sub(/^.*\./, "", seg)                            # last dotted segment
                if (seg != "" && index(prefixTokens, " " seg " ") == 0) {
                    print FILENAME ":" n ": " line
                }
            }
        }
    }
}
