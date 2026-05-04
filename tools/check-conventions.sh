#!/usr/bin/env bash
# =============================================================================
# Kolibri Launcher — project-convention linter
# =============================================================================
#
# Grep-based checks for the CLAUDE.md rules whose drift was the largest
# defect class found by the post-audit-Sweep-Session 2026-05-03 (see
# TODO.md §7 for the full motivation).
#
# Run via:
#   ./gradlew checkConventions
#   ./tools/check-conventions.sh         (standalone, identical output)
#
# Exit code:
#   0   all checks passed
#   1   at least one rule violated — output lists `file:line` and rule
#   2   environment problem (missing source dir, etc.)
#
# Adding a new check:
#   1. Drop a section below following the same shape (heading comment +
#      grep + report-on-non-empty).
#   2. Update the rule list in CLAUDE.md so the linter and the rule
#      stay in sync.
#
# What the linter intentionally does NOT check yet:
#   - Rule 11 in general (try/catch around can't-throw operations) —
#     needs semantic analysis of the catch body, not pure grep. The
#     section below covers a narrower aspect of Rule 11 (annotation
#     discipline on broad catches in opted-in files).
#   - Rule 13 (German comments in newly-added lines) — would need a
#     git-diff integration; not added until the cost is needed.
#   - Hardcoded UI strings — would need string-literal classification.
#
# Those remain catch-by-review until the simpler grep checks
# stop pulling their weight.
# =============================================================================

set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

# Production sources live in three Gradle modules after the §9.2 split.
src_roots=(
  "$repo_root/app/src/main/java"
  "$repo_root/domain/src/main/java"
  "$repo_root/data/src/main/java"
)

for d in "${src_roots[@]}"; do
  if [ ! -d "$d" ]; then
    echo "ERROR: Source directory not found: $d" >&2
    exit 2
  fi
done

violations=0
report() {
  echo
  echo "═══ $1 ═══"
  echo "$2"
  violations=$((violations + 1))
}

# ─────────────────────────────────────────────────────────────────────────────
# Rule 9 — bare `Timber.e(` outside the documented crash-handling-
# infrastructure files. CLAUDE.md Rule 9's exception list:
#   - KolibriLauncherApp.kt, TimberWrapper.kt
#   - BaseActivity.kt, BaseViewModel.kt
#   - CrashReportLimiter.kt, CrashReportConsent.kt, CrashReportConsentStore.kt
#   - BackupFragment's fragment-level CoroutineExceptionHandler (one
#     line, identified by message text rather than line number so the
#     check stays robust to imports / blank-line shifts).
# Anything else uses TimberWrapper.silentError so the throw-in-DEBUG
# semantic surfaces programmer-error bugs loudly.
# ─────────────────────────────────────────────────────────────────────────────
rule9_allowed_files='KolibriLauncherApp\.kt|TimberWrapper\.kt|BaseActivity\.kt|BaseViewModel\.kt|CrashReportLimiter\.kt|CrashReportConsent\.kt|CrashReportConsentStore\.kt'

rule9_hits=$(
  grep -rn 'Timber\.e(' "${src_roots[@]}" --include='*.kt' \
    | grep -vE "/($rule9_allowed_files):" \
    | grep -vE 'BackupFragment\.kt:[0-9]+:.*Uncaught coroutine exception in BackupFragment' \
    || true
)

if [ -n "$rule9_hits" ]; then
  report "Rule 9 — bare \`Timber.e(\` outside documented crash-infrastructure files" "$rule9_hits"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Rule 12 — `Timber.Forest.*` is the Java-API form. Project standard is
# the short form `Timber.d / .e / .w / .i / .v`. The Forest form sneaks
# in via Android Studio's auto-import or the "Convert Java to Kotlin"
# action.
# ─────────────────────────────────────────────────────────────────────────────
rule12_hits=$(
  grep -rn 'Timber\.Forest\.' "${src_roots[@]}" --include='*.kt' || true
)

if [ -n "$rule12_hits" ]; then
  report "Rule 12 — \`Timber.Forest.*\` (use short form \`Timber.d\` / \`.e\` / \`.w\`)" "$rule12_hits"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Naming — production classes in `data/` use `*RepositoryImpl` since
# commit 0f9e7be. Two intentional exceptions documented in
# app/src/test/CLAUDE.md "Namens-Historie":
#   - WallpaperFileManager (file-helper, not a repository)
#   - DataMigrationManager (migration-bootstrap, not a repository)
# ─────────────────────────────────────────────────────────────────────────────
naming_allowed='WallpaperFileManager\.kt|DataMigrationManager\.kt'
data_dir="$repo_root/data/src/main/java/com/github/reygnn/kolibri_launcher/data"

naming_hits=$(
  grep -rEn '^(internal |open |abstract |sealed )*class [A-Z][a-zA-Z0-9_]*Manager' \
    "$data_dir" \
    --include='*.kt' 2>/dev/null \
    | grep -vE "/($naming_allowed):" \
    || true
)

if [ -n "$naming_hits" ]; then
  report "Naming — class ending in 'Manager' inside \`data/\` (use \`*RepositoryImpl\`)" "$naming_hits"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Rule 11 (annotation discipline) — broad catches in whitelisted files
# must carry a four-category-frame justification marker nearby.
#
# Scope (positive list, NOT all files):
#   Only files that have explicitly adopted the four-category-frame
#   annotation convention from the file header. Today: MainActivity only
#   (the only file where every kept catch was reviewed and annotated as
#   part of the post-§A audit trail). Add a path here when a new file's
#   catches are reviewed and annotated under the same convention.
#
# What counts as "broad catch":
#   `catch (e: Throwable)` or `catch (e: Exception)`. Narrowed types
#   (`SecurityException`, `ActivityNotFoundException`, `IOException`,
#   etc.) already follow Rule 11 by being narrow — they are not flagged.
#
# Accepted markers (case as in source):
#   - `[Cc]atch[a-z]* kept` — covers "Catch kept", "Inner catch kept",
#     "Triple-catch kept", "Outer Catchall kept" (the convention's
#     standard prefix forms)
#   - `[Rr]ethrow per canonical` — the canonical CancellationException
#     rethrow pattern
#
# Window: ±5 lines around the catch. Marker phrases stay on a single
# line by convention, so per-line checking is enough — no concatenation
# tricks needed.
# ─────────────────────────────────────────────────────────────────────────────
rule11_files=(
  "$repo_root/app/src/main/java/com/github/reygnn/kolibri_launcher/ui/main/MainActivity.kt"
)

rule11_hits=""
for file in "${rule11_files[@]}"; do
  if [ ! -f "$file" ]; then
    echo "ERROR: Rule 11 whitelist file not found: $file" >&2
    exit 2
  fi
  hits=$(awk '
    { lines[NR] = $0 }
    END {
      for (n = 1; n <= NR; n++) {
        if (lines[n] ~ /catch \([a-zA-Z_]+: (Throwable|Exception)\)/ && lines[n] !~ /^[[:space:]]*[*\/]/) {
          found = 0
          for (i = n - 5; i <= n + 5; i++) {
            if (i >= 1 && i <= NR) {
              if (lines[i] ~ /[Cc]atch[a-z]* kept/) { found = 1; break }
              if (lines[i] ~ /[Rr]ethrow per canonical/) { found = 1; break }
            }
          }
          if (!found) print FILENAME ":" n ": " lines[n]
        }
      }
    }
  ' "$file")
  if [ -n "$hits" ]; then
    rule11_hits="${rule11_hits}${hits}
"
  fi
done

if [ -n "$rule11_hits" ]; then
  report "Rule 11 — broad catch without four-category-frame annotation in whitelisted file" "${rule11_hits%$'\n'}"
fi

# ─────────────────────────────────────────────────────────────────────────────

if [ "$violations" -eq 0 ]; then
  echo "✓ All convention checks passed."
  exit 0
fi

echo
echo "$violations rule(s) violated. See above."
exit 1
