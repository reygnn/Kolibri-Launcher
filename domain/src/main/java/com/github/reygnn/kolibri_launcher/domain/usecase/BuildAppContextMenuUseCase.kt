package com.github.reygnn.kolibri_launcher.domain.usecase

import android.content.pm.ShortcutInfo
import com.github.reygnn.kolibri_launcher.domain.R
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.MenuContext
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ShortcutRepository
import com.github.reygnn.kolibri_launcher.domain.model.AppContextMenuAction
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Builds the action list rendered in `AppContextMenuDialogFragment`.
 *
 * The previous home of this logic was directly inside the Fragment, where
 * it could only be exercised through an Android-runtime test. The
 * orchestration here is a textbook use case: query four repositories,
 * branch on five state predicates (has-shortcuts, is-favorite, has-custom-name,
 * is-hidden, drawer-with-usage-data), and assemble an ordered list.
 *
 * Each repository call is wrapped in a per-call try/catch with a safe
 * fallback (empty list / `false`), preserving the Fragment's pre-extraction
 * crash-safety pattern: a single failing repository doesn't blank the
 * entire context menu — the user still sees the unaffected entries.
 *
 * Labels are emitted as `@StringRes` ids (see [AppContextMenuAction.LauncherAction]).
 * The Adapter resolves them to user-visible strings at bind time, which
 * keeps this whole code path JVM-testable.
 */
class BuildAppContextMenuUseCase @Inject constructor(
    private val shortcutRepository: ShortcutRepository,
    private val favoritesRepository: FavoritesRepository,
    private val customNamesRepository: CustomNamesRepository,
    private val hiddenAppsRepository: HiddenAppsRepository,
) {

    suspend operator fun invoke(
        appInfo: AppInfo,
        menuContext: MenuContext,
        hasUsageData: Boolean,
    ): List<AppContextMenuAction> {
        val actions = mutableListOf<AppContextMenuAction>()

        // 1. Shortcuts (if any) followed by a separator.
        val shortcuts = safelyGetShortcuts(appInfo.packageName)
        shortcuts.forEach { actions.add(AppContextMenuAction.Shortcut(it)) }
        if (shortcuts.isNotEmpty()) {
            actions.add(AppContextMenuAction.Separator)
        }

        // 2. Favorite toggle — label depends on current state.
        val isFavorite = safelyIsFavorite(appInfo.componentName)
        actions.add(
            AppContextMenuAction.LauncherAction(
                id = AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE,
                labelRes = if (isFavorite) {
                    R.string.remove_from_favorites
                } else {
                    R.string.add_to_favorites
                },
            ),
        )

        // 3. Restore-original-name (only if a custom name is set).
        val hasCustomName = safelyHasCustomName(appInfo.packageName)
        if (hasCustomName) {
            actions.add(
                AppContextMenuAction.LauncherAction(
                    id = AppContextMenuAction.ACTION_ID_RESTORE_NAME,
                    labelRes = R.string.restore_original_name,
                ),
            )
        }

        // 4. Rename — always present.
        actions.add(
            AppContextMenuAction.LauncherAction(
                id = AppContextMenuAction.ACTION_ID_RENAME_APP,
                labelRes = R.string.rename_app,
            ),
        )

        // 5. Hide / Unhide — id and label depend on current state.
        val isHidden = safelyIsHidden(appInfo.componentName)
        actions.add(
            AppContextMenuAction.LauncherAction(
                id = if (isHidden) {
                    AppContextMenuAction.ACTION_ID_UNHIDE_APP
                } else {
                    AppContextMenuAction.ACTION_ID_HIDE_APP
                },
                labelRes = if (isHidden) {
                    R.string.unhide_app_in_drawer
                } else {
                    R.string.hide_app_from_drawer
                },
            ),
        )

        // 6. Reset usage — drawer-only, only when there's usage data to reset.
        if (menuContext == MenuContext.APP_DRAWER && hasUsageData) {
            actions.add(
                AppContextMenuAction.LauncherAction(
                    id = AppContextMenuAction.ACTION_ID_RESET_USAGE,
                    labelRes = R.string.action_reset_sorting,
                ),
            )
        }

        // 7. App info — always last.
        actions.add(
            AppContextMenuAction.LauncherAction(
                id = AppContextMenuAction.ACTION_ID_APP_INFO,
                labelRes = R.string.app_info,
            ),
        )

        return actions
    }

    private fun safelyGetShortcuts(packageName: String): List<ShortcutInfo> = try {
        shortcutRepository.getShortcutsForPackage(packageName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "Error loading shortcuts for $packageName")
        emptyList()
    }

    private suspend fun safelyIsFavorite(componentName: String): Boolean = try {
        favoritesRepository.isFavoriteComponent(componentName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "Error checking favorite status for $componentName")
        false
    }

    private suspend fun safelyHasCustomName(packageName: String): Boolean = try {
        customNamesRepository.hasCustomNameForPackage(packageName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "Error checking custom name for $packageName")
        false
    }

    private suspend fun safelyIsHidden(componentName: String): Boolean = try {
        hiddenAppsRepository.isComponentHidden(componentName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "Error checking hidden status for $componentName")
        false
    }
}
