# =============================================================================
# AAPT2 apostrophe check (3b, after the 3b-1b-c finding)
# =============================================================================
#
# In res/values*/strings.xml an apostrophe in a value must be escaped (\') or the
# whole value wrapped in double quotes — otherwise AAPT2 rejects the resource
# ("Invalid unicode escape sequence", a misleading message). A plain XML parser
# and kotlinc accept the file, so only this check catches it before Gradle.
#
# Scans single-line <string …>value</string> and <item>value</item> entries
# (string arrays), skips CDATA and values wrapped in double quotes, and reports
# FILENAME:FNR for every value with an apostrophe not preceded by a backslash.
# Multi-line values are out of scope (none exist today); a value split over
# lines would need its own handling.
# =============================================================================
{
    line = $0
    if (line ~ /<!\[CDATA\[/) next
    value = ""
    if (match(line, /<string[^>]*>.*<\/string>/)) {
        value = line
        sub(/^.*<string[^>]*>/, "", value)
        sub(/<\/string>.*$/, "", value)
    } else if (match(line, /<item[^>]*>.*<\/item>/)) {
        value = line
        sub(/^.*<item[^>]*>/, "", value)
        sub(/<\/item>.*$/, "", value)
    } else {
        next
    }
    if (value ~ /^".*"$/) next
    # An apostrophe at the start, or one not preceded by a backslash.
    if (value ~ /^'/ || value ~ /[^\\]'/) {
        sub(/^[ \t]+/, "", line)
        print FILENAME ":" FNR ": " line
    }
}
