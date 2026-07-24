package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.shareIn
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
