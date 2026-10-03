package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * After a factory reset, Kolibri reseeds its curated default favorites (phone, SMS, email,
 * browser, camera) the way the first-run onboarding does (SPEC_NYX_REWRITE 2b/29). Without it a
 * reset kept `ONBOARDING_COMPLETED` (deliberately, a reset does not force onboarding again), the
 * favorites stayed empty, and the home screen fell back to the first installed apps.
 *
 * Runs as its own step AFTER [FactoryResetUseCase] — never inside it, so the reset itself still
 * leaves the store empty (the `ResetCompletenessContract` pins that). The caller runs it after a
 * success and after a partial failure.
 *
 * One source for the set: [GetDefaultFavoriteComponentsUseCase] computes it, and
 * [CompleteOnboardingUseCase] saves it exactly like onboarding's done path (favorites only — the
 * onboarding flag is left alone).
 */
class SeedDefaultFavoritesUseCase @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val onboardingAppsUseCase: GetOnboardingAppsUseCase,
    private val getDefaultFavoriteComponentsUseCase: GetDefaultFavoriteComponentsUseCase,
    private val completeOnboardingUseCase: CompleteOnboardingUseCase,
) {
    /**
     * Seeds the default set — only if the favorites are empty. After a partial failure the
     * favorites purge may have failed; the remaining favorites must not be overwritten, and
     * "favorites empty" plays the role Nyx's seed flags play. Returns whether it seeded.
     *
     * Kolibri deliberately has no seed flag: it seeds only right after a reset, where empty
     * favorites are unambiguous. Moving the seeding to any other trigger (e.g. a startup
     * self-heal) needs a flag like Nyx's `home_dock_seeded_v1` — an empty home screen is a
     * legitimate choice in a minimal launcher.
     */
    suspend operator fun invoke(): Boolean {
        if (favoritesRepository.getFavoriteComponentsSnapshot().isNotEmpty()) return false
        // Like onboarding: the installed-apps flow replays an empty list before the enumeration
        // the reset just triggered has finished, so wait for the first POPULATED emission, bounded
        // so a device without matching apps degrades to "nothing seeded" instead of hanging.
        val availableApps = withTimeoutOrNull(APPS_LOAD_TIMEOUT_MS) {
            onboardingAppsUseCase.onboardingAppsFlow.first { it.isNotEmpty() }
        }.orEmpty()
        val defaults = getDefaultFavoriteComponentsUseCase(availableApps)
        if (defaults.isEmpty()) return false
        completeOnboardingUseCase(componentNames = defaults, isInitialSetup = false)
        return true
    }

    companion object {
        /** Same bound as onboarding's preselection (`OnboardingViewModel.APPS_LOAD_TIMEOUT_MS`). */
        const val APPS_LOAD_TIMEOUT_MS = 5_000L
    }
}
