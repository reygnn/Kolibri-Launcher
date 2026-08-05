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
# Timber.w|e — all WARN+ and therefore delivered to ACRA via AcraTree) without
# first rethrowing the cancellation, every teardown files a bogus report: the
# exact ACRA-flood the project's SettingsRepositoryImpl / DataStoreReadFlow /
# AppUsageRepositoryImpl arms already guard against with an explicit rethrow.
#
# This makes that discipline enforceable. GLOBAL scan (not a positive list):
# the shape is precise enough — only a `.catch { }` arm that BOTH logs at
# report level AND lacks a rethrow guard is flagged. A `.catch { }` that only
# emits a fallback without logging cannot flood ACRA and is left alone.
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
# == WHAT COUNTS AS A REPORT-LEVEL LOG ==
#   silentError(…), KolibriLog.w(…)/.e(…), Timber.w(…)/.e(…). Debug/info/verbose
#   (.d/.i/.v) do NOT reach AcraTree's WARN gate, so they are not flood sources
#   and not required to guard.
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
