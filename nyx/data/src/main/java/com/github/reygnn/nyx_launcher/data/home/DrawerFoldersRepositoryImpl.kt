package com.github.reygnn.nyx_launcher.data.home

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.common.data.readFlowFailOpen
import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.github.reygnn.nyx_launcher.home.repository.DrawerFoldersRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject

/**
 * DataStore-backed [DrawerFoldersRepository]. Membership is one JSON blob under
 * [KEY], (de)serialized via [DrawerFoldersSerializer]. Shares Nyx's single
 * `DataStore<Preferences>` with the other repositories but under its OWN key, so the
 * drawer-folder state is independent of the home layout (DRAWER_FOLDERS_SPEC §4 / D-5).
 *
 * A missing key or an undecodable blob reads as [DrawerFolders.EMPTY] rather than
 * crashing the read path; the next [update] write heals it.
 */
class DrawerFoldersRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val serializer: DrawerFoldersSerializer,
) : DrawerFoldersRepository {

    // Serializes the read-modify-write of [update] so concurrent writers can't
    // clobber each other on a stale read (mirrors HomeLayoutRepositoryImpl, A1-03).
    private val writeMutex = Mutex()

    override fun folders(): Flow<DrawerFolders> =
        dataStore.readFlowFailOpen("Error reading drawer folders") { parseFolders(it) }

    private fun parseFolders(prefs: Preferences): DrawerFolders {
        val raw = prefs[KEY] ?: return DrawerFolders.EMPTY
        return serializer.deserialize(raw) ?: DrawerFolders.EMPTY
    }

    override suspend fun update(transform: suspend (DrawerFolders) -> DrawerFolders?) =
        writeMutex.withLock {
            // Fail-CLOSED read for the destructive RMW: read the raw store (IOException aborts the
            // write) instead of the fail-open [folders] flow, which would recover to EMPTY and let
            // the write wipe all folders on a transient read failure.
            val current = parseFolders(dataStore.data.first())
            transform(current)?.let { writeRaw(it) }
            Unit
        }

    override suspend fun seedInitialFolders(resolveFolders: suspend () -> List<DrawerFolder>): Boolean =
        writeMutex.withLock {
            // Contained fail-CLOSED (see HomeLayoutRepositoryImpl.seedInitialLayout): a store
            // read/write failure skips the seed and leaves SEEDED_KEY unset for a retry next
            // launch — never assume "no folders" (would seed over a transiently-unreadable
            // store) and never crash the startup coroutine.
            try {
                val prefs = dataStore.data.first()
                // One-shot, mirroring HomeLayoutRepositoryImpl's dock seed: [SEEDED_KEY]
                // records that the first-run decision was made. Gated FIRST so a returning
                // install never resolves the app list again. A blank KEY can't gate this —
                // the user may legitimately have deleted all their folders — so the decision
                // needs its own flag, never touched by update().
                if (prefs[SEEDED_KEY] == true) return@withLock false
                // Folders already exist (e.g. an import landed first): decision established,
                // mark done and leave untouched (still without resolving).
                val current = prefs[KEY]?.let { serializer.deserialize(it) } ?: DrawerFolders.EMPTY
                if (current.folders.isNotEmpty()) {
                    dataStore.edit { it[SEEDED_KEY] = true }
                    return@withLock false
                }
                // Drop anything below the ≥ 2-member folder invariant (DFOLD-INV-1).
                val seedFolders = resolveFolders().filter { it.members.size >= 2 }
                dataStore.edit {
                    it[SEEDED_KEY] = true
                    if (seedFolders.isNotEmpty()) {
                        it[KEY] = serializer.serialize(DrawerFolders(seedFolders))
                    }
                }
                seedFolders.isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Environmental IOException, not a programmer error: WARN (never crash in DEBUG) + skip.
                Log.w("DrawerFoldersRepositoryImpl", "Skipping first-run folder seed: store unavailable", e)
                false
            }
        }

    private suspend fun writeRaw(folders: DrawerFolders) {
        dataStore.edit { it[KEY] = serializer.serialize(folders) }
    }

    private companion object {
        val KEY = stringPreferencesKey("drawer_folders_v1")

        // First-run seed one-shot (see seedInitialFolders). Separate from KEY so a user
        // who deleted all their folders doesn't read as "never seeded" and get re-seeded.
        val SEEDED_KEY = booleanPreferencesKey("drawer_folders_seeded_v1")
    }
}
