# =============================================================================
# Exception-vs-Throwable breadth check (awk core)
# =============================================================================
#
# `OutOfMemoryError` (and every other `Error`) is a `Throwable`, NOT an
# `Exception`. So a `catch (e: Exception)` wrapped around an ALLOCATION or
# system-callback boundary — bitmap decode/compose, layout inflation, large
# JSON/ZIP encoding — silently misses the exact failure it was written to
# survive. AUDIT.md category A was this shape ~14 times (ZoomableImageView
# bitmap paths, BackupRepositoryImpl's JSON/ZIP umbrella, the
# AppContextMenuDialogFragment inflate/bind catches); the fix was to widen
# those umbrellas to `catch (e: Throwable)`.
#
# This is the coroutine-form-free complement to the cancellation checks: those
# guard a catch that is too BROAD (Throwable swallowing CancellationException);
# this guards one that is too NARROW (Exception missing OutOfMemoryError).
# Widening to Throwable satisfies THIS check and, in a suspend frame, hands the
# resulting broad catch to the cancellation check — the two ends of one question.
#
# Positive list (NOT all files), same growth model as the Rule 11 / cancellation
# whitelists: a file joins only once every broad catch on its ALLOCATION /
# system-callback boundaries has been reviewed and is either `Throwable`,
# narrowed to a specific type (`IOException`, `SecurityException`, …), or — where
# `Exception` is genuinely the right breadth (a pure I/O boundary where no
# `Error` can arise, e.g. a `contentResolver.openInputStream` probe) — carries
# an `Exception sufficient` marker within ±5 lines. A bare `catch (e: Exception)`
# with no marker in a whitelisted file flags. Narrowed types and `Throwable` are
# never flagged. Adding a file with unreviewed catches just turns the build red —
# that IS the review prompt.
#
# Deliberately a positive list, not a global scan: most `catch (Exception)` in
# the tree are correct — narrow-ish race guards (RecyclerView `notifyItem*` /
# `submitList` throwing `IllegalStateException`, where `Throwable` would be
# WRONG because you must not swallow OOM there) or pure I/O. A global flag would
# be all-noise; only files that ARE allocation boundaries belong here.
#
# Output format (matches check-conventions.sh):  FILENAME:LINE: <line content>
# Exit code: always 0 (printing alone signals violations to the caller).
# =============================================================================
{ lines[NR] = $0 }
END {
    for (n = 1; n <= NR; n++) {
        # bare `catch (ident: Exception)` — not Throwable, not a narrowed type
        if (lines[n] ~ /catch[[:space:]]*\([[:space:]]*[a-zA-Z_][a-zA-Z0-9_]*[[:space:]]*:[[:space:]]*Exception[[:space:]]*\)/ \
            && lines[n] !~ /^[[:space:]]*[*\/]/) {
            found = 0
            # symmetric ±5-line window; marker stays on one line by convention
            for (i = n - 5; i <= n + 5; i++) {
                if (i >= 1 && i <= NR) {
                    if (lines[i] ~ /[Ee]xception sufficient/) { found = 1; break }
                }
            }
            if (!found) print FILENAME ":" n ": " lines[n]
        }
    }
}
