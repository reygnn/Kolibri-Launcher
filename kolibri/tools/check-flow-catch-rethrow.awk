# =============================================================================
# Flow.catch cancellation-rethrow check (awk core)
# =============================================================================
#
# The sibling check-cancellation-rethrow.awk guards `catch (Throwable)` /
# `runCatching`. It is blind to the OTHER cancellation swallower: the
# `Flow.catch { }` operator. `Flow.catch` delivers a CancellationException to
# its lambda when the cancellation originates UPSTREAM (not from the downstream
# emit) — e.g. a `stateIn(SharingStarted.Eagerly)` flow whose sharing scope is
# torn down. If that lambda logs the exception (silentError / KolibriLog.w|e /
# Timber.w|e) without first rethrowing the cancellation, it SWALLOWS normal
# coroutine control flow: a teardown cancellation is turned into a logged
# fallback emission instead of propagating. That is a structured-concurrency
# CORRECTNESS bug, independent of ACRA — the project's SettingsRepositoryImpl /
# DataStoreReadFlow / AppUsageRepositoryImpl arms already guard against it with
# an explicit rethrow.
#
# (Since §23, report-by-intent: only the silentError arm additionally reaches
# ACRA — the Timber.w/KolibriLog.w arms are untagged and no longer reported. The
# correctness bug is identical for every logging arm regardless of level, so the
# guard is still required for ALL of them; ACRA flooding is no longer the reason.)
#
# This makes that discipline enforceable. GLOBAL scan (not a positive list):
# the shape is precise enough — only a `.catch { }` arm that BOTH logs AND lacks
# a rethrow guard is flagged. A `.catch { }` that only emits a fallback without
# logging cannot mask a cancellation as an error and is left alone.
#
# == WHAT COUNTS AS A GUARD ==
#   - an explicit `is CancellationException` test (covers
#     `if (e is CancellationException) throw e` and the
#     `if (e is CancellationException || e !is Exception) throw e` form), or
#   - a rethrow of a caught *variable* — `throw e` / `throw it` /
#     `throw cause` (lowercase identifier, no `(`). This covers the narrowing
#     idiom `if (e is IOException) { … } else throw e`, which propagates every
#     non-IOException (CancellationException included). A `throw NewException(…)`
#     (uppercase, constructed) is NOT a guard — Kotlin naming makes the
#     lowercase-var vs Uppercase-class distinction reliable.
#
# == WHAT COUNTS AS A LOGGING ARM ==
#   silentError(…), KolibriLog.w(…)/.e(…), Timber.w(…)/.e(…). Debug/info/verbose
#   (.d/.i/.v) are treated as quiet local logs and left alone — a precision
#   choice (report-level arms are where a swallowed cancellation masquerading as
#   an error does real harm), not a claim about which level reaches ACRA.
#
# Output format (matches check-conventions.sh):  FILENAME:LINE: <line content>
# Exit code: always 0 (printing alone signals violations to the caller).
# =============================================================================
{ lines[NR] = $0 }
END {
    for (n = 1; n <= NR; n++) {
        line = lines[n]

        # Flow.catch operator: a dot before `catch`, then a brace lambda. The
        # leading dot separates it from a `} catch (…)` try-clause.
        if (line !~ /\.catch[[:space:]]*\{/) continue
        # Skip matches that are only inside a comment / KDoc line.
        if (line ~ /^[[:space:]]*(\/\/|\*|\/\*)/) continue

        depth = 0
        started = 0
        has_log = 0
        has_guard = 0

        for (i = n; i <= NR; i++) {
            code = lines[i]
            sub(/\/\/.*$/, "", code)   # strip line comment for matching + braces

            if (code ~ /silentError\(/ ||
                code ~ /KolibriLog\.[we]\(/ ||
                code ~ /Timber\.[we]\(/) has_log = 1

            if (code ~ /is CancellationException/) has_guard = 1
            # rethrow of a caught variable (lowercase ident, not a constructor)
            if (code ~ /throw[[:space:]]+[a-z][A-Za-z0-9_]*/) has_guard = 1

            nopen  = gsub(/\{/, "{", code)
            nclose = gsub(/\}/, "}", code)
            depth += nopen - nclose
            if (nopen > 0) started = 1
            if (started && depth <= 0) break
        }

        if (has_log && !has_guard) print FILENAME ":" n ": " lines[n]
    }
}
