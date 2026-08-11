package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.coerceAtMostSafe
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONException
import timber.log.Timber
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for the custom ordering of favorite apps, persisted as a JSON array
 * in DataStore. Works in tandem with `FavoritesRepositoryImpl`, which owns the
 * SET of favorites (what is favorited); this owns their SEQUENCE (how they are
 * ordered).
 *
 * **Cold read + snapshot, one authoritative path (DATASTORE_READ_SPEC Belang A).**
 * [favoriteComponentsOrderFlow] is a plain cold flow via [readFlowFailOpen];
 * [getFavoriteComponentsOrderSnapshot] is a fresh point-read via
 * [snapshotFailOpen]. Both run the SAME [parseOrderString] over the store, so
 * they cannot drift (DSR-INV-1), and there is no `shareIn(replay=1)` cache to go
 * stale — the AUDIT-13 hazard the snapshot was originally bolted on to dodge is
 * gone by construction. The constructor takes just the [DataStore]; no
 * `externalScope` / `sharingStrategy` / test factory.
 *
 * **JSON persistence.** DataStore Preferences has no native `List<String>`, so
 * the order is a JSON array string — compact, debuggable, trivially
 * (de)serialized via `JSONArray`. `MAX_ORDER_LIST_SIZE` is a defensive ceiling
 * (see the companion), applied on both save and load, guarding only against a
 * bug producing a runaway list — not an expected size.
 *
 * **Sorting.** `sortAppsWithGivenOrder` is a two-phase sort: apps in the saved
 * order first (in sequence), remaining apps appended alphabetically by
 * `displayName`. This preserves the user's custom order while placing
 * newly-favorited apps (not yet in the order) at the end, and gracefully
 * ignores order entries for apps that are no longer favorites.
 *
 * **Error handling.** Reads are fail-open (empty on `IOException`); JSON parse
 * failures fall back to empty; sort failures cascade to alphabetical then
 * unsorted; save failures return false without crashing.
 * [java.util.concurrent.CancellationException] is always re-thrown.
 *
 * @see FavoritesRepositoryImpl for favorite membership (what is favorited)
 * @see HiddenAppsRepositoryImpl for a similar Flow-based state repository
 */
@Singleton
class FavoritesOrderRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : FavoritesOrderRepository {

    // distinctUntilChanged: shared settingsDataStore re-emits on every write to
    // ANY key; dedupe the decoded order List so unrelated writes (usage tick on
    // each launch, etc.) no longer re-fire the favorites combine (AUDIT-14 F1c).
    override val favoriteComponentsOrderFlow: Flow<List<String>> =
        dataStore.readFlowFailOpen("Error reading favorites order") { preferences ->
            parseOrderString(preferences[PreferencesKeys.ORDER_LIST])
        }
            .distinctUntilChanged()

    private object PreferencesKeys {
        val ORDER_LIST = stringPreferencesKey("favorites_order_components_list_json")
    }

    companion object {
        /**
         * Maximum size of the order list as a safety limit.
         *
         * Defensive upper bound derived from the favorites cap: at most
         * MAX_FAVORITES_ON_HOME (500) favorited packages, allowing up to
         * AVG_COMPONENTS_PER_PACKAGE (6) component entries each, plus a small
         * buffer — i.e. 500 × 6 + 2 = 3002 entries. Not an expected size (real
         * favorite counts are far lower); purely a ceiling that guards against
         * bugs producing runaway lists (storage bloat, JSON-parse cost, OOM).
         */
        private const val AVG_COMPONENTS_PER_PACKAGE = 6
        private const val SAFETY_BUFFER = 2
        private const val MAX_ORDER_LIST_SIZE =
            AppConstants.MAX_FAVORITES_ON_HOME * AVG_COMPONENTS_PER_PACKAGE + SAFETY_BUFFER
    }

    /**
     * Parses the persisted JSON order string into a bounded component list.
     * Shared by the read flow, [getFavoriteComponentsOrderSnapshot] and the
     * atomic [removeComponentFromOrder] so all agree on parsing + the
     * MAX_ORDER_LIST_SIZE ceiling.
     */
    private fun parseOrderString(orderString: String?): List<String> {
        if (orderString.isNullOrBlank()) return emptyList()
        return try {
            val jsonArray = JSONArray(orderString)
            val size = jsonArray.length().coerceAtMostSafe(MAX_ORDER_LIST_SIZE)
            List(size) { i -> jsonArray.getString(i) }
        } catch (e: JSONException) {
            TimberWrapper.silentError(e, "Error parsing favorites order JSON")
            emptyList()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Unexpected error parsing order")
            emptyList()
        }
    }

    override suspend fun saveOrder(orderedComponentNames: List<String>): Boolean {
        return try {
            // Limitierung für Sicherheit
            val limitedList = orderedComponentNames.take(MAX_ORDER_LIST_SIZE)

            val jsonArray = JSONArray(limitedList)
            val orderString = jsonArray.toString()

            dataStore.edit { preferences ->
                preferences[PreferencesKeys.ORDER_LIST] = orderString
            }

            Timber.d("Favorites order saved: ${limitedList.size} components")
            true

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving favorites order")
            false
        }
    }

    override suspend fun sortFavoriteComponents(favoriteApps: List<AppInfo>, order: List<String>): List<AppInfo> {
        if (favoriteApps.isEmpty()) return emptyList()

        return try {
            sortAppsWithGivenOrder(favoriteApps, order)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error sorting favorite components, falling back to alphabetical")
            try {
                favoriteApps.sortedBy { it.displayNameLower }
            } catch (e2: Throwable) {
                TimberWrapper.silentError(e2, "Critical error in fallback sorting, returning unsorted list")
                favoriteApps
            }
        }
    }

    fun sortAppsWithGivenOrder(appsToSort: List<AppInfo>, order: List<String>): List<AppInfo> {
        try {
            if (order.isEmpty()) {
                return appsToSort.sortedBy { it.displayNameLower }
            }

            // Component-name lookup + a consumed-key set turns the former
            // O(n·m) find+remove loop (AUDIT-15 F1) into O(n+m). putIfAbsent
            // keeps the FIRST app per componentName, matching the old `find()`
            // (first match wins). componentName is meant to be unique across
            // favorites; on malformed input the set gives two behaviours:
            //   - a repeated componentName in `order` places the app once
            //     (add() returns false on the second hit) — same as the old
            //     `remove()` draining it on first match.
            //   - two apps sharing a componentName collapse to one: the second
            //     lands in `consumed`, so filterNot drops it from the remainder
            //     below. The old loop instead emitted that second copy in the
            //     alphabetical remainder (the app appeared twice). Deduping a
            //     malformed double-entry is the intended, safer result — pinned
            //     by FavoritesOrderRepositoryImplTest.
            val byComponent = HashMap<String, AppInfo>(appsToSort.size)
            for (app in appsToSort) {
                byComponent.putIfAbsent(app.componentName, app)
            }
            val consumed = HashSet<String>(order.size)
            val orderedApps = ArrayList<AppInfo>(appsToSort.size)

            for (componentName in order) {
                if (consumed.add(componentName)) {
                    byComponent[componentName]?.let(orderedApps::add)
                }
            }

            // Restliche Apps alphabetisch sortiert anhängen
            orderedApps.addAll(
                appsToSort.filterNot { it.componentName in consumed }.sortedBy { it.displayNameLower },
            )
            return orderedApps

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in sortAppsWithGivenOrder, returning original list")
            return appsToSort
        }
    }

    suspend fun removeComponentFromOrder(componentName: String): Boolean {
        return try {
            // Read-modify-write inside a single edit{} transaction so a
            // concurrent saveOrder can't wedge a stale snapshot between the
            // read and the write — the same race FavoritesRepositoryImpl and
            // HiddenAppsRepositoryImpl deliberately pull into edit{}. (The
            // previous version read favoriteComponentsOrderFlow.first()
            // outside the transaction.)
            dataStore.edit { preferences ->
                val currentOrder =
                    parseOrderString(preferences[PreferencesKeys.ORDER_LIST]).toMutableList()
                if (currentOrder.remove(componentName)) {
                    val limitedList = currentOrder.take(MAX_ORDER_LIST_SIZE)
                    preferences[PreferencesKeys.ORDER_LIST] = JSONArray(limitedList).toString()
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error removing component from order: $componentName")
            false
        }
    }

    override suspend fun getFavoriteComponentsOrderSnapshot(): List<String> =
        // Authoritative FRESH read (DSR-INV-1): same parseOrderString as the flow,
        // fail-open to empty on IOException. Used by point-reads from a context
        // without a warm Home subscriber (backup export, Settings sort) where the
        // old hot-share replay could have gone stale.
        dataStore.snapshotFailOpen("Error reading favorites order snapshot; treating as empty") { preferences ->
            parseOrderString(preferences[PreferencesKeys.ORDER_LIST])
        }

    override suspend fun purgeRepository() {
        dataStore.safePurge("FavoritesOrderRepositoryImpl") { preferences ->
            preferences.remove(PreferencesKeys.ORDER_LIST)
        }
    }
}
