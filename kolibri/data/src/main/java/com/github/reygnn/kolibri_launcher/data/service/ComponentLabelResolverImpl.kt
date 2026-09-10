package com.github.reygnn.kolibri_launcher.data.service

import android.content.Intent
import android.content.pm.PackageManager
import com.github.reygnn.kolibri_launcher.core.IoDispatcher
import com.github.reygnn.kolibri_launcher.domain.service.ComponentLabelResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * [ComponentLabelResolver] over the Android [PackageManager].
 *
 * A component is resolved the same way the drawer list is built — a scoped
 * `ACTION_MAIN` / `CATEGORY_LAUNCHER` query restricted to the component's package
 * (so only that one package's launcher activities are enumerated, not all ~150
 * installed apps), then an exact-component match. The label of the match is loaded
 * with the same `loadLabel(...).ifBlank { packageName }` fallback as
 * `InstalledAppsRepositoryImpl.processResolveInfoList`, so a provisional favorite and
 * its authoritative replacement carry an identical label (no reshuffle on swap).
 *
 * Each query runs on [ioDispatcher] (the PackageManager call is blocking). The broad
 * `Throwable` catch is the sanctioned System-API-boundary form; unlike
 * `PackagePresenceImpl` it resolves failure to `null` (omit), not to a safe default —
 * see the fail-closed rationale in the [ComponentLabelResolver] KDoc.
 * `CancellationException` still propagates.
 */
class ComponentLabelResolverImpl @Inject constructor(
    private val packageManager: PackageManager,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ComponentLabelResolver {

    override suspend fun resolveLabel(componentName: String): String? =
        withContext(ioDispatcher) {
            try {
                val packageName = componentName.substringBefore('/')
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(packageName)
                }
                val match = packageManager
                    .queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                    .firstOrNull { info ->
                        val activityInfo = info.activityInfo ?: return@firstOrNull false
                        "${activityInfo.packageName}/${activityInfo.name}" == componentName
                    }
                if (match == null) {
                    null
                } else {
                    match.loadLabel(packageManager).toString().ifBlank { packageName }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
        }
}
