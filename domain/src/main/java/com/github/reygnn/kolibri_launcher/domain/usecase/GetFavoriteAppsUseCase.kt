package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.DefaultDispatcher
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.sortedByDisplayName
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.service.ComponentLabelResolver
import com.github.reygnn.kolibri_launcher.domain.model.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import com.github.reygnn.kolibri_launcher.core.KolibriLog
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * Produces the list of favorite apps shown on the home screen, with a
 * fallback to top-N alphabetically-sorted apps when the user has set
 * no favorites.
 *
 * == ARCHITECTURE RULE: hidden apps and the home screen ==
 * (First written-down location of this rule. Kept in this KDoc as the
 * canonical reference; if the rule ever moves to a higher-level doc,
 * this block can be replaced with a link.)
 *
 * Hidden apps applies only to the AppDrawer. On the home screen, the
 * hidden filter is broken only by favorite status — a favorite that is
 * also hidden remains pinned to the home screen, because favorites
 * must always be visible.
 *
 * The two code paths in this use case follow from that one rule:
 *
 *   - Favorites path ([processApps] when favorites exist):
 *     filter by `isFavorite` only. Hidden flag does not apply, because
 *     the favorite-status break is in effect.
 *
 *   - Fallback path ([createFallbackApps], used when the
 *     user has set no favorites): filter by `!hidden`. There is no
 *     favorite status to break the hidden filter, so the filter
 *     applies as it does in the drawer.
 *
 * The unhide path remains reachable for a hidden favorite via long-press
 * on the home screen entry; HomeFragment routes the resulting UnhideApp
 * action to onShowApp. The same action is unreachable from the
 * AppDrawer for the symmetric reason — hidden apps do not appear in
 * the drawer listing (see [GetDrawerAppsUseCase]), so they
 * cannot be long-pressed there.
 *
 * == Why HiddenAppsRepository is injected ==
 * The flow is part of the combined state graph because both paths need
 * it: the favorites path needs to know the hidden set is in scope (for
 * the rule above), and the fallback path uses it as a filter directly.
 *
 * == First-paint provisional favorites (live label resolution) ==
 * On a cold start the authoritative favorites are gated by the full
 * PackageManager enumeration ([InstalledAppsStateRepository.rawAppsFlow]).
 * While that list is still empty, [buildProvisional] resolves the handful
 * of favorite labels DIRECTLY via [ComponentLabelResolver] — an order of
 * magnitude cheaper than the bulk enumeration — and emits a provisional
 * [UiState.Success]; the authoritative pass then replaces it in place.
 * Reusing [applyCustomNames] and
 * [FavoritesOrderRepository.sortFavoriteComponents] keeps the provisional
 * list name- and order-identical to the authoritative result, so the
 * replacement does not reshuffle — with one cosmetic exception: two favorites
 * with byte-identical labels, both outside the saved order, may tie-break in a
 * different order (the provisional sorts a `Set`, the authoritative sorts the
 * enumeration list, and `sortedByDisplayName` is stable), so their positions
 * can swap on replacement. Only their icons differ, the text is identical.
 * A favorite whose component no longer resolves returns `null` and is simply
 * omitted (no ghost), unlike a persisted cache which would paint it until
 * reconciliation.
 */
class GetFavoriteAppsUseCase @Inject constructor(
    private val installedAppsStateRepository: InstalledAppsStateRepository,
    private val favoritesRepository: FavoritesRepository,
    private val favoritesOrderRepository: FavoritesOrderRepository,
    private val hiddenAppsRepository: HiddenAppsRepository,
    private val customNamesRepository: CustomNamesRepository,
    private val componentLabelResolver: ComponentLabelResolver,
    @param:DefaultDispatcher private val dispatcher: CoroutineDispatcher
) {

    /**
     * The authoritative combine over the five app-state sources. Emits a
     * [RawStep.Empty] marker (carrying the favorite set / saved order / custom names
     * needed to build a provisional first paint) while the installed-app list is
     * still empty (cold start, before the PackageManager enumeration finishes),
     * otherwise a [RawStep.Resolved] carrying the finished favorites result.
     */
    private val rawStepFlow: Flow<RawStep> = combine(
        // Stays on the post-veto keep-last-good rawAppsFlow (REACTIVE_APPLIST_SPEC
        // Site 2, NOT getInstalledApps) so the transient-empty flicker cannot return.
        installedAppsStateRepository.rawAppsFlow,
        favoritesRepository.favoriteComponentsFlow.catch { e ->
            if (e is CancellationException) throw e
            KolibriLog.w(e, "favoriteComponentsFlow error - using empty set fallback")
            emit(emptySet())
        },
        hiddenAppsRepository.hiddenAppsFlow.catch { e ->
            if (e is CancellationException) throw e
            KolibriLog.w(e, "hiddenAppsFlow error - showing all apps")
            emit(emptySet())
        },
        favoritesOrderRepository.favoriteComponentsOrderFlow.catch { e ->
            if (e is CancellationException) throw e
            KolibriLog.w(e, "favoriteComponentsOrderFlow error - using empty order")
            emit(emptyList())
        },
        // Custom names folded in reactively (REACTIVE_APPLIST_SPEC Site 2); since
        // migration step 2b the enumeration emits the original label, so this is
        // the operative name-application point.
        customNamesRepository.customNamesFlow.catch { e ->
            if (e is CancellationException) throw e
            KolibriLog.w(e, "customNamesFlow error - using original names")
            emit(emptyMap())
        }
    ) { rawApps, favorites, hiddenApps, savedOrder, customNames ->
        KolibriLog.d("[DATAFLOW-FAV] Combine triggered - rawApps: ${rawApps.size}, favorites: ${favorites.size}")

        // applyCustomNames returns input order (map-only, RAL-4); processApps
        // orders by savedOrder (+ alpha remainder) below.
        val namedApps = applyCustomNames(rawApps, customNames)

        // Empty app list → the provisional/Loading decision happens downstream in
        // [buildProvisional], which resolves the favorite labels live. The Empty
        // marker carries the inputs that provisional build needs.
        if (namedApps.isEmpty()) {
            RawStep.Empty(favorites, savedOrder, customNames)
        } else {
            RawStep.Resolved(processApps(namedApps, favorites, hiddenApps, savedOrder))
        }
    }

    /**
     * Home-screen favorites. While the installed-app list is empty ([RawStep.Empty])
     * a PROVISIONAL [UiState.Success] is emitted from live-resolved favorite labels
     * ([buildProvisional]); the authoritative [RawStep.Resolved] pass replaces it in
     * place once the enumeration finishes. No favorites (or none resolvable) → the
     * flow stays [UiState.Loading], the pre-existing behaviour for a first-ever run.
     *
     * [mapLatest] is what makes the provisional cheap and race-free: an Empty step
     * launches the live label resolution, and if the authoritative [RawStep.Resolved]
     * arrives while that is still in flight, the now-pointless provisional resolution
     * is cancelled and the authoritative result is emitted instead. The upstream
     * [distinctUntilChanged] collapses identical Empty re-emissions so the targeted
     * PackageManager lookups fire once per distinct Empty step, not on every
     * unrelated source re-emission. Note the Empty step's identity is the whole
     * (favorite set, saved order, custom names) triple, so a change to the saved
     * order or custom names during the empty window also re-triggers the lookup even
     * though only the favorite SET affects the resolved labels — harmless (the
     * result is unchanged) and practically unreachable (it needs a settings write
     * inside the sub-150 ms pre-enumeration window).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val favoriteApps: Flow<UiState<FavoriteAppsResult>> = rawStepFlow
        .distinctUntilChanged()
        .mapLatest { step ->
            when (step) {
                is RawStep.Resolved -> UiState.Success(step.result)
                is RawStep.Empty -> buildProvisional(step)
            }
        }
        .catch { e ->
            if (e is CancellationException) throw e
            TimberWrapper.silentError(e, "Critical error in favoriteApps flow")
            emit(UiState.Error("Failed to load apps"))
        }
        // Collapse redundant re-emissions: a settings write that leaves the favorites
        // result identical still re-emits upstream (e.g. renaming a NON-favorite app,
        // or an unrelated setting change). A provisional that equals the authoritative
        // result is likewise collapsed here, so no reshuffle flickers on replacement.
        .distinctUntilChanged()
        .flowOn(dispatcher)

    /**
     * Builds the provisional favorites list from LIVE per-component label lookups
     * (no persistence). A favorite whose component no longer resolves returns `null`
     * and is omitted (ghost-free). Empty favorites, or none resolvable, → [UiState.Loading].
     *
     * Reuses [applyCustomNames] and [FavoritesOrderRepository.sortFavoriteComponents]
     * so the provisional output matches the authoritative [processApps] result in
     * name and order — the [RawStep.Resolved] replacement then produces an identical
     * list and is collapsed by [distinctUntilChanged] instead of visibly reshuffling
     * (except the byte-identical-label tie noted in the class KDoc, which sorts a
     * `Set` here vs. the enumeration list there under a stable sort).
     */
    private suspend fun buildProvisional(step: RawStep.Empty): UiState<FavoriteAppsResult> {
        if (step.favorites.isEmpty()) return UiState.Loading

        // Resolve the favorite labels CONCURRENTLY. Each resolveLabel is its own
        // withContext(IO) + scoped PackageManager IPC, so a sequential mapNotNull
        // pays N serial IO round-trips on the first-paint critical path — and since
        // a user may pin up to MAX_FAVORITES_ON_HOME favorites, the "a handful"
        // assumption the provisional path rests on can break, eroding its whole
        // speed advantage over the bulk enumeration. awaitAll overlaps the lookups
        // on the IO pool (which caps real parallelism itself). coroutineScope keeps
        // it structured: when the authoritative RawStep.Resolved arrives, mapLatest
        // cancels this scope and the children cancel with it (CancellationException
        // propagates out of resolveLabel). Iteration order is preserved (map +
        // awaitAll are index-ordered), so the provisional list is identical to the
        // sequential one before sortFavoriteComponents reorders it — the byte-
        // identical-label tie in the class KDoc is unchanged.
        val resolved = coroutineScope {
            step.favorites
                .map { component ->
                    async {
                        componentLabelResolver.resolveLabel(component)
                            ?.let { label -> component.toProvisionalAppInfo(label) }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
        if (resolved.isEmpty()) return UiState.Loading

        val named = applyCustomNames(resolved, step.customNames)

        // Same order source as the authoritative path (only throw candidate:
        // sortFavoriteComponents, a suspend repo call).
        val ordered = try {
            favoritesOrderRepository.sortFavoriteComponents(named, step.savedOrder)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            KolibriLog.w(e, "Provisional sorting failed - using alphabetical fallback")
            named.sortedByDisplayName()
        }

        return UiState.Success(
            FavoriteAppsResult(
                apps = ordered.take(AppConstants.MAX_FAVORITES_ON_HOME),
                isFallback = false,
            ),
        )
    }

    private suspend fun processApps(
        rawApps: List<AppInfo>,
        favorites: Set<String>,
        // Two uses, both consistent with the architecture rule (see
        // class KDoc): not used as a filter on favorites in this body
        // (the favorite-status break is in effect), but forwarded to
        // createFallbackApps where it does filter (no favorites set
        // means no break, so the hidden filter applies normally).
        // Do NOT add a hidden filter to the favorites filter below
        // without revisiting the architecture rule.
        hiddenApps: Set<String>,
        savedOrder: List<String>
    ): FavoriteAppsResult {
        // Filter to favorites FIRST, then copy only the survivors with
        // isFavorite = true — avoids a full-list AppInfo.copy of every installed
        // app (each copy recomputes displayNameLower + componentName) just to keep
        // a handful. Set.contains(String) / filter / map on non-null data classes
        // cannot throw.
        val favoriteApps = rawApps
            .filter { favorites.contains(it.componentName) }
            .map { it.copy(isFavorite = true) }

        // Einziger Wurfkandidat: sortFavoriteComponents (suspend, Repo-Call).
        val orderedFavorites = try {
            favoritesOrderRepository.sortFavoriteComponents(favoriteApps, savedOrder)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            KolibriLog.w(e, "Sorting failed - using alphabetical fallback")
            favoriteApps.sortedByDisplayName()
        }

        val limitedOrderedFavorites = orderedFavorites.take(AppConstants.MAX_FAVORITES_ON_HOME)

        return if (limitedOrderedFavorites.isNotEmpty()) {
            KolibriLog.d("[DATAFLOW-FAV] Emitting ${limitedOrderedFavorites.size} favorites")
            FavoriteAppsResult(
                apps = limitedOrderedFavorites,
                isFallback = false
            )
        } else {
            // Fallback: Top N sichtbare Apps
            val fallbackApps = createFallbackApps(rawApps, hiddenApps)
            KolibriLog.d("[DATAFLOW-FAV] No favorites - emitting ${fallbackApps.size} fallback apps")
            FavoriteAppsResult(
                apps = fallbackApps,
                isFallback = true
            )
        }
        // Programmierfehler-Pfad: nicht mehr inline; propagiert zum
        // Flow-catch oben, der UiState.Error("Failed to load apps") emittiert.
    }

    private fun createFallbackApps(
        rawApps: List<AppInfo>,
        hiddenApps: Set<String>
    ): List<AppInfo> {
        // Filter / sortedBy / take auf String-Properties — kann nicht werfen.
        return rawApps
            .filter { !hiddenApps.contains(it.componentName) }
            .sortedByDisplayName()
            .take(AppConstants.MAX_FAVORITES_ON_HOME)
    }

    /**
     * Rebuilds a display-only [AppInfo] from a favorite componentName plus its
     * live-resolved [label] for the provisional first paint. componentName is split
     * back into packageName/className; since the favorite componentName is always the
     * normalized long form, the reconstructed [AppInfo.componentName] round-trips it
     * exactly (so DiffUtil identity matches the authoritative entry that replaces it).
     * [label] is the TRUE launcher label, set as BOTH [AppInfo.originalName] and
     * [AppInfo.displayName]; [applyCustomNames] then overrides only the displayName
     * for a renamed app, leaving originalName correct by construction (no separately
     * persisted original-name field needed).
     */
    private fun String.toProvisionalAppInfo(label: String): AppInfo {
        val separator = indexOf('/')
        val packageName = if (separator > 0) substring(0, separator) else this
        val className =
            if (separator in 0 until length - 1) substring(separator + 1) else ""
        return AppInfo(
            originalName = label,
            displayName = label,
            packageName = packageName,
            className = className,
            isFavorite = true,
        )
    }

    /**
     * Intermediate result of [rawStepFlow]: either the installed-app list is still
     * empty ([Empty], cold start — carrying the inputs [buildProvisional] needs) or
     * the authoritative favorites are [Resolved].
     */
    private sealed interface RawStep {
        data class Empty(
            val favorites: Set<String>,
            val savedOrder: List<String>,
            val customNames: Map<String, String>,
        ) : RawStep

        data class Resolved(val result: FavoriteAppsResult) : RawStep
    }
}
