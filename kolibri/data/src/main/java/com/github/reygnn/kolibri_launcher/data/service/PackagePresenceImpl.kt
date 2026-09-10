package com.github.reygnn.kolibri_launcher.data.service

import android.content.Intent
import android.content.pm.PackageManager
import com.github.reygnn.kolibri_launcher.core.IoDispatcher
import com.github.reygnn.kolibri_launcher.domain.service.PackagePresence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * [PackagePresence] over the Android [PackageManager].
 *
 * Presence is defined the same way the drawer list is built — a target is
 * "present" iff it would appear as a launcher entry (`ACTION_MAIN` /
 * `CATEGORY_LAUNCHER`). This keeps the deletion gate consistent with what the
 * user actually sees.
 *
 * Each query runs on [ioDispatcher] (the PackageManager call is blocking) and
 * is wrapped so any failure resolves to `true` (present): the reconcile must
 * never delete an assignment on a transient system-API error (fail-safe,
 * `RECONCILE_SPEC.md` R-INV / R-1). The broad `Throwable` catch is the
 * sanctioned System-API-boundary form — a real failure reported as the safe
 * value, not a swallowed programmer error; `CancellationException` still
 * propagates.
 */
class PackagePresenceImpl @Inject constructor(
    private val packageManager: PackageManager,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : PackagePresence {

    override suspend fun isComponentPresent(componentName: String): Boolean =
        withContext(ioDispatcher) {
            try {
                val packageName = componentName.substringBefore('/')
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(packageName)
                }
                packageManager
                    .queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                    .any { info ->
                        val activityInfo = info.activityInfo ?: return@any false
                        "${activityInfo.packageName}/${activityInfo.name}" == componentName
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                true
            }
        }

    override suspend fun isPackagePresent(packageName: String): Boolean =
        withContext(ioDispatcher) {
            try {
                packageManager.getLaunchIntentForPackage(packageName) != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                true
            }
        }
}
