package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.AppLoad
import com.github.reygnn.kolibri_launcher.domain.model.sortedByDisplayName
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetOnboardingAppsUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
    private val customNamesRepository: CustomNamesRepository
) {

    val onboardingAppsFlow: Flow<List<AppInfo>> =
        combine(
            installedAppsRepository.getInstalledApps(),
            customNamesRepository.customNamesFlow
        ) { load, names ->
            when (load) {
                // Behavior-preserving compat boundary (INSTALLED_APPS_LOAD_SPEC
                // §3 Belang A b): a load failure shows an empty picker, exactly
                // as before AppLoad — NOT the live error path (that is Belang D,
                // decided separately). Deliberate, not an incidental collapse.
                is AppLoad.Failed -> emptyList()
                // Custom names folded in reactively (REACTIVE_APPLIST_SPEC Site 1);
                // since migration step 2b the enumeration emits the original label,
                // so this is the operative name-application point.
                is AppLoad.Loaded -> applyCustomNames(load.apps, names).toOnboardingPicker()
            }
        }
            .distinctUntilChanged()
            .catch { e ->
                if (e is CancellationException) throw e
                TimberWrapper.silentError(e, "Error in onboarding apps flow, emitting empty list")
                emit(emptyList())
            }

}

/**
 * Pure shaping for the onboarding app picker: drop entries with a blank package or
 * display name, then sort by display name.
 *
 * The sort is owned here at the display boundary (RAL-4 map-only: the shared
 * name-resolution no longer imposes an order). Extracted as a pure function so it
 * is unit-testable with an unsorted, already-name-applied list: a test feeds an
 * unsorted list straight in and asserts sorted output, so a dropped sort fails the
 * test independently of what the upstream helper does. `isNotBlank()` on non-null
 * data-class properties cannot throw.
 */
fun List<AppInfo>.toOnboardingPicker(): List<AppInfo> =
    filter { it.packageName.isNotBlank() && it.displayName.isNotBlank() }
        .sortedByDisplayName()