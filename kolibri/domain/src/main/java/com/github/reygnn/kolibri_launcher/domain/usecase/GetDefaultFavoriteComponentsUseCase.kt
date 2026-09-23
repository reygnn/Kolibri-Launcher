package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.DefaultAppsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the user's default apps (phone / SMS / email / browser / camera) to
 * launcher component identities that are actually present in [availableApps] — the
 * set to pre-select as favorites on first-run onboarding.
 *
 * Pure mapping over the repository result: a default package that isn't installed
 * here (or exposes no launcher entry in the list) is skipped, role order is
 * preserved, and duplicates are removed. An empty list is a legitimate result (no
 * default resolved, or none installed as a launchable entry) — the caller must not
 * treat it as an error.
 */
@Singleton
class GetDefaultFavoriteComponentsUseCase @Inject constructor(
    private val defaultAppsRepository: DefaultAppsRepository,
) {
    suspend operator fun invoke(availableApps: List<AppInfo>): List<String> {
        // First launcher entry per package wins — a package can expose several
        // activities, but a favorite pins one component. LinkedHashMap keeps the
        // list order stable for that first-wins pick.
        val firstComponentByPackage = LinkedHashMap<String, String>()
        for (app in availableApps) {
            if (!firstComponentByPackage.containsKey(app.packageName)) {
                firstComponentByPackage[app.packageName] = app.componentName
            }
        }
        return defaultAppsRepository.getDefaultAppPackages()
            .mapNotNull { firstComponentByPackage[it] }
            .distinct()
    }
}
