#!/usr/bin/env bash
# =============================================================================
# Regression test for tools/check-strings-apostrophe.awk (AAPT2 apostrophes)
# =============================================================================
#
# Runs the awk core against a synthetic strings.xml and asserts that exactly the
# lines carrying SHOULDFLAG are reported — among them the very line of the
# 3b-1b-c finding without its escape (the counter-check) — and none carrying
# NOFLAG (escaped, double-quoted, no apostrophe, CDATA, string-array items).
#
# NOT wired into Gradle by design — manual-rerun test, not a CI gate.
# Production lint runs via `./gradlew checkConventions`.
#
# Run via:  ./tools/check-strings-apostrophe-test.sh
# Exit code: 0 = matched expectations, 1 = regression, 2 = environment problem.
# =============================================================================

set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
awk_script="$script_dir/check-strings-apostrophe.awk"
[ -f "$awk_script" ] || { echo "ERROR: awk script not found: $awk_script" >&2; exit 2; }

tmpdir=$(mktemp -d) || { echo "ERROR: mktemp failed" >&2; exit 2; }
trap 'rm -rf "$tmpdir"' EXIT
fixture="$tmpdir/strings.xml"
cat > "$fixture" <<'XML'
<resources>
    <!-- SHOULDFLAG: the 3b-1b-c line without its escape (counter-check) -->
    <string name="wallpaper_clear_failed_toast">Couldn't remove the wallpaper</string><!-- SHOULDFLAG -->
    <string name="leading">'quoted at the start</string><!-- SHOULDFLAG -->
    <string name="escaped">Couldn\'t remove the wallpaper</string><!-- NOFLAG -->
    <string name="quoted">"Couldn't remove the wallpaper"</string><!-- NOFLAG -->
    <string name="plain">Wallpaper removed</string><!-- NOFLAG -->
    <string name="cdata"><![CDATA[it's fine here]]></string><!-- NOFLAG -->
    <string-array name="arr">
        <item>don't</item><!-- SHOULDFLAG -->
        <item>don\'t</item><!-- NOFLAG -->
    </string-array>
</resources>
XML

actual=$(awk -f "$awk_script" "$fixture" | sed -E 's/^[^:]+:([0-9]+):.*/\1/' | sort -n | tr '\n' ' ')
expected=$(grep -n 'SHOULDFLAG -->' "$fixture" | cut -d: -f1 | sort -n | tr '\n' ' ')
if [ "$actual" = "$expected" ]; then
  echo "PASS: flagged lines $actual"
  exit 0
fi
echo "FAIL: expected lines [$expected], got [$actual]" >&2
awk -f "$awk_script" "$fixture" >&2
exit 1
