# =============================================================================
# Rule 13 — German-comment detector for git-diff-style input
# =============================================================================
#
# Reads unified-diff input on stdin (typically from `git diff`) and reports
# `+` lines that look like a comment containing German prose. Pre-existing
# German comments — context lines (` `) or removed lines (`-`) — are ignored:
# Rule 13 explicitly does not sweep those.
#
# Detection signals (OR'd together):
#   1. Hard signal: any of `ä ö ü ß Ä Ö Ü`. German-only character set,
#      single match is enough.
#   2. Soft signal: ≥2 distinct German function words from a high-precision
#      whitelist (no English overlap; full-word match with regex word
#      boundaries). The threshold of 2 keeps coincidental hits from going
#      red.
#
# Scope:
#   Only single-line `//` comments, KDoc/`/*` openers, and KDoc continuations
#   (`* …`). Multi-line `/* … */` interior lines without the `*` prefix are
#   not detected — rare in idiomatic Kotlin and not worth the complexity.
#
# Output (matches tools/check-conventions.sh style):
#   <path>:<line>:<comment-text>
#
# Caller is responsible for wrapping the output in a header / counting
# violations.
# =============================================================================

BEGIN {
    # German function words — high-precision (no English overlap with
    # word-boundary matching). Keep the list short and conservative;
    # umlaut-bearing comments are caught by the hard signal.
    n_words = split("und nicht oder aber auch noch schon weil damit dass " \
                   "sich sind wurde wurden werden eine einen keine kein " \
                   "dieser diese dieses gleich bereits werfen wenn kann " \
                   "muss soll zwischen ohne sondern beim vom zur zum " \
                   "dem des deren denen dessen jener jene jenes welche " \
                   "welcher welches", german_words, " ")

    # Build a single alternation for fast matching. Word-boundary regex
    # is constructed per match site (POSIX awk has no \b).
    word_pattern = ""
    for (i = 1; i <= n_words; i++) {
        word_pattern = (word_pattern == "" ? "" : word_pattern "|") german_words[i]
    }

    current_file = ""
    current_line = 0
}

# +++ b/<path>: file header. Capture path; reset line counter.
/^\+\+\+ b\// {
    current_file = substr($0, 7)
    current_line = 0
    next
}

# Skip the --- a/<path> header.
/^--- / { next }

# Hunk header: @@ -<old>,<n> +<new>,<m> @@
# We track the new-file line counter starting at <new> minus 1 so that the
# first incoming `+` or ` ` line lands on <new>.
/^@@/ {
    if (match($0, /\+[0-9]+/)) {
        current_line = substr($0, RSTART + 1, RLENGTH - 1) - 1
    }
    next
}

# Removed line: ignored (not in the new file).
/^-/ { next }

# Context line: increments line counter, but not flagged.
/^ / {
    current_line++
    next
}

# Added line: this is what we check.
/^\+/ {
    current_line++
    line_content = substr($0, 2)
    check_added_line(line_content)
    next
}

function check_added_line(line,    comment_text) {
    comment_text = extract_comment(line)
    if (comment_text == "") return

    if (is_german(comment_text)) {
        printf "%s:%d:%s\n", current_file, current_line, comment_text
    }
}

# Returns the comment portion of `line` if the line is (or contains) a
# comment that is a candidate for prose; empty string otherwise.
function extract_comment(line,    stripped, idx) {
    stripped = line
    sub(/^[ \t]+/, "", stripped)

    # KDoc opener: /** …  or /* …
    if (match(stripped, /^\/\*\*?[ \t]*/)) {
        return strip_trailing_close(substr(stripped, RLENGTH + 1))
    }

    # KDoc continuation: * …  (but not */ alone)
    if (stripped ~ /^\*[ \t]/) {
        return strip_trailing_close(substr(stripped, 3))
    }

    # Bare close marker — no prose.
    if (stripped ~ /^\*\/[ \t]*$/) return ""

    # Line comment: // …
    if (match(stripped, /^\/\/+[ \t]*/)) {
        return substr(stripped, RLENGTH + 1)
    }

    # Inline `//` after code. Find the first `//` not inside a `://` (URL).
    # Heuristic: take the substring after the last `//` if it doesn't look
    # like a URL fragment. False-positives here are tolerable.
    idx = index(line, "//")
    if (idx > 0) {
        # Skip if the // is part of `://` (URL)
        if (idx > 1 && substr(line, idx - 1, 1) == ":") return ""
        stripped = substr(line, idx + 2)
        sub(/^[ \t]+/, "", stripped)
        return stripped
    }

    return ""
}

function strip_trailing_close(text) {
    sub(/[ \t]*\*\/[ \t]*$/, "", text)
    return text
}

function is_german(text,    count, i, w, re) {
    # Hard signal: umlaut or eszett.
    if (text ~ /[äöüßÄÖÜ]/) return 1

    # Soft signal: count distinct German function-word matches.
    count = 0
    for (i = 1; i <= n_words; i++) {
        w = german_words[i]
        # Word-boundary match: surrounded by start/end or non-letter.
        re = "(^|[^a-zA-Z])" w "([^a-zA-Z]|$)"
        if (text ~ re) {
            count++
            if (count >= 2) return 1
        }
    }

    return 0
}
