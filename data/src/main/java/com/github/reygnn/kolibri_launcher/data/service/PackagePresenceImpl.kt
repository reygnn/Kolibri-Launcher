package com.github.reygnn.kolibri_launcher.data.service

import android.content.Intent
import android.content.pm.PackageManager
import com.github.reygnn.kolibri_launcher.domain.service.PackagePresence
import javax.inject.Inject

/**
 * [PackagePresence] over the Android [PackageManager].
 *
 * Presence is defined the same way the drawer list is built — a target is
 * "present" iff it would appear as a launcher entry (`ACTION_MAIN` /
 * `CATEGORY_LAUNCHER`). This keeps the deletion gate consistent with what the
 * user actually sees.
 *
 * Every query is wrapped so any PackageManager failure resolves to `true`
 * (present): the reconcile must never delete an assignment on a transient
 * system-API error (fail-safe, `RECONCILE_SPEC.md` R-INV / R-1). The broad
 * `Throwable` catch is the sanctioned System-API-boundary form — a real
 * failure reported as the safe value, not a swallowed programmer error.
 */
class PackagePresenceImpl @Inject constructor(
    private val packageManager: PackageManager
) : PackagePresence {

    override fun isComponentPresent(componentName: String): Boolean = try {
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
    } catch (e: Throwable) {
        true
    }

    override fun isPackagePresent(packageName: String): Boolean = try {
        packageManager.getLaunchIntentForPackage(packageName) != null
    } catch (e: Throwable) {
        true
    }
}
