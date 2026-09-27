# =============================================================================
# Localization parity check — values/ vs values-de/ (awk core)
# =============================================================================
#
# CLAUDE.md, "Localization": every UI string lives in res/values/strings.xml
# (default, English) AND res/values-de/strings.xml (German). A key added to one
# locale but not the other is invisible at compile time — `stringResource`
# resolves against the default locale, so a missing German entry silently ships
# English text to a German device, and an orphaned German entry is dead weight
# that survives every later rename of its English twin.
#
# Both directions flag, because they are different defects:
#   MISSING-DE  — key exists in values/ but not values-de/  -> untranslated UI
#   ORPHAN-DE   — key exists in values-de/ but not values/  -> dead entry, and
#                 usually the fossil of a renamed/removed English key
#
# `translatable="false"` in the DEFAULT locale exempts a key entirely: that
# attribute is the platform's own way of saying "this is not UI prose"
# (placeholders like `12:34`, brand terms, the blend-mode names that stay
# English by design). 14 keys carry it today and are correctly absent from
# values-de/. An orphan in values-de/ is never exempt — a locale override of a
# non-translatable key is itself the bug.
#
# GLOBAL check, not a positive list: unlike the catch-breadth axes there is no
# judgement call here. A key is either in both files or it is not, and the
# translatable attribute already encodes every legitimate exception. The
# codebase is parity-clean today, so this locks that state rather than
# reporting debt.
#
# Covers <string>, <string-array> and <plurals> — all three are named,
# locale-overridable resources with the same drift mode. Per-quantity coverage
# inside a <plurals> is NOT checked: German and English have the same
# one/other split, so name-level parity is the whole question.
#
# Invocation (order matters — default locale FIRST):
#   awk -f check-strings-parity.awk values/strings.xml values-de/strings.xml
#
# Output format (matches check-conventions.sh):  MISSING-DE: name  /  ORPHAN-DE: name
# Exit code: always 0 (printing alone signals violations to the caller).
# =============================================================================
BEGIN { default_file = "" }

{
    # The first file passed is the default locale; every later one is a
    # translation. Anchored on FNR==1 so the split survives any file name.
    if (FNR == 1 && default_file == "") default_file = FILENAME

    line = $0

    # `<string ` / `<string-array ` / `<plurals ` with a name attribute. Two
    # things this regex has to get right:
    #   - the `[ \t]` after the tag name keeps `<string ` from also matching
    #     `<string-array `, which would otherwise count every array twice.
    #   - it spans the WHOLE opening tag up to `>`, not just as far as the
    #     name attribute. Stopping at the name would put `translatable="false"`
    #     (which follows it) outside the captured text, so the exemption below
    #     would never fire and all 14 non-translatable keys would flag.
    if (match(line, /<(string|string-array|plurals)[ \t][^>]*>/)) {
        tag_and_attrs = substr(line, RSTART, RLENGTH)
        if (tag_and_attrs !~ /name="/) next

        if (!match(tag_and_attrs, /name="[^"]+"/)) next
        name = substr(tag_and_attrs, RSTART + 6, RLENGTH - 7)

        if (FILENAME == default_file) {
            # translatable="false" is the sanctioned opt-out, default locale only.
            if (tag_and_attrs ~ /translatable="false"/) exempt[name] = 1
            else in_default[name] = 1
        } else {
            in_translation[name] = 1
        }
    }
}

END {
    for (name in in_default)
        if (!(name in in_translation)) print "MISSING-DE: " name

    for (name in in_translation)
        if (!(name in in_default) && !(name in exempt)) print "ORPHAN-DE: " name
}
