package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import timber.log.Timber
import java.io.IOException

/**
 * The shared read-path counterpart to [safePurge]: the `catch → emit empty`
 * envelope the hot-Flow DataStore repositories open-coded before AUDIT-7 #1.
 *
 * A corrupt or unreadable preferences file surfaces as an [IOException] on the
 * upstream flow; recovering it to [emptyPreferences] makes the downstream `map`
 * fall back to per-key defaults instead of the whole flow dying. Any other
 * `Throwable` (including [kotlinx.coroutines.CancellationException], which is
 * deliberately NOT an [IOException]) is re-thrown so cancellation and genuine
 * programmer errors still propagate.
 *
 * This is the single owner of the IOException read-recovery policy for the
 * repositories that share it. Note that `SettingsRepositoryImpl` deliberately
 * keeps its own, *broader* recovery (it also swallows corrupted-type
 * `ClassCastException`s and other read `RuntimeException`s — see its "doomsday"
 * tests), so it does not route through here.
 */
internal fun DataStore<Preferences>.safeReadFlow(errorMessage: String): Flow<Preferences> =
    data.catch { e ->
        if (e is IOException) {
            TimberWrapper.silentError(e, errorMessage)
            emit(emptyPreferences())
        } else {
            throw e
        }
    }

/**
 * Cold, fail-open read flow with the transform baked in (DATASTORE_READ_SPEC
 * Belang A/B). This is the observe half of the "one authoritative read path per
 * key-set" contract: a repository's `xFlow` is `readFlowFailOpen(...)`. Where the
 * repository ALSO exposes a `getXSnapshot()` point-read (Favorites / order, from
 * commits 2-3), that snapshot runs the SAME [transform] over a fresh
 * `dataStore.data.first()`, so flow and snapshot cannot drift (DSR-INV-1). A
 * render-only repository (FabPosition) has no snapshot and just uses this flow.
 *
 * Deliberately NO hot sharing: DataStore already serves decoded preferences from
 * its in-memory cache across all collectors, so a `shareIn(replay=1)` wrapper
 * bought ~nothing while making a `.first()` on the shared flow return a stale
 * replayed value without a warm subscriber — the AUDIT-13 stale-replay class.
 * Without the replay cache, every read is fresh by construction.
 *
 * Error policy is fail-open on [IOException]: recover to [emptyPreferences] so
 * [transform] yields the per-key defaults, then propagate everything else
 * (including [kotlinx.coroutines.CancellationException], which is NOT an
 * [IOException]). Unlike [safeReadFlow] this uses [Timber.w], NOT
 * `silentError`: an [IOException] on a disk read is a real I/O failure, not a
 * programmer error, so it must not throw in DEBUG (DSR-INV-3). [safeReadFlow]
 * stays for the out-of-scope repos that append their own `.map { }`.
 */
internal fun <T> DataStore<Preferences>.readFlowFailOpen(
    errorMessage: String,
    transform: suspend (Preferences) -> T,
): Flow<T> =
    data
        .catch { e ->
            if (e is IOException) {
                Timber.w(e, errorMessage)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map(transform)

/**
 * The decide half of [readFlowFailOpen], fail-open: an authoritative fresh
 * point-read (`dataStore.data.first()`) that runs the SAME [transform] as the
 * repository's cold flow, so a `getXSnapshot()` and its `xFlow` cannot drift
 * (DSR-INV-1). Bypasses no cache — there is none — it simply reads the current
 * store value from a context that has no warm collector (backup export, Settings
 * sort), where the old hot-share replay could have gone stale.
 *
 * Fail-open on [IOException]: the fallback is [transform] over [emptyPreferences]
 * — NOT a separately passed default — so it agrees with [readFlowFailOpen]'s
 * IOException recovery by construction. [Timber.w], not `silentError`: a disk
 * read failure is environmental, not a programmer error, so it must not throw in
 * DEBUG (DSR-INV-3). [CancellationException] is rethrown first, before the
 * IOException arm, so cooperative cancellation always propagates (DSR-INV-5).
 */
internal suspend fun <T> DataStore<Preferences>.snapshotFailOpen(
    errorMessage: String,
    transform: suspend (Preferences) -> T,
): T =
    try {
        transform(data.first())
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Timber.w(e, errorMessage)
        transform(emptyPreferences())
    }

/**
 * Hot-sharing tail shared by the `shareIn`-backed repositories: shares the
 * flow across collectors when an [externalScope] is present (production),
 * or returns the cold flow unchanged when it is `null` (the test path — see
 * `FavoritesRepositoryImplShareInTest`). Replays the latest value to new
 * collectors by default.
 */
internal fun <T> Flow<T>.shareInOrRaw(
    externalScope: CoroutineScope?,
    started: SharingStarted,
    replay: Int = 1,
): Flow<T> =
    if (externalScope != null) {
        shareIn(scope = externalScope, started = started, replay = replay)
    } else {
        this
    }
