package com.github.reygnn.nyx_launcher.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.TimberWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Resolves the device's actual default Phone / SMS / Browser / Camera apps to
 * their launcher components, for seeding a first-run home dock (so a fresh install
 * isn't an empty screen). Uses the system defaults/roles — never hardcoded package
 * names — so it matches whatever the user's OEM ships. An app that can't be
 * resolved (role unset, no launcher activity, hidden by package visibility) is
 * simply omitted; the result may be shorter than four and is de-duplicated
 * (some OEMs point two roles at one app).
 */
class DefaultAppsResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun resolveDockApps(): List<ComponentKey> {
        val packages = listOfNotNull(
            runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull(),
            runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull(),
            defaultPackageFor(Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)),
            defaultPackageFor(Intent(MediaStore.ACTION_IMAGE_CAPTURE)),
        )
        // Map each package to its launcher component; drop the unresolvable; dedup.
        return packages.mapNotNull { launcherKeyFor(it) }.distinct()
    }

    private fun defaultPackageFor(intent: Intent): String? = runCatching {
        context.packageManager.resolveActivity(intent, 0)
            ?.activityInfo?.packageName
            // The system resolver/chooser is not a concrete app — skip it.
            ?.takeIf { it != "android" }
    }.getOrNull()

    private fun launcherKeyFor(packageName: String?): ComponentKey? {
        packageName ?: return null
        return runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)?.component
                ?.let { ComponentKey(it.packageName, it.className) }
        }.getOrElse {
            TimberWrapper.silentError(it, "Failed to resolve launcher component for $packageName")
            null
        }
    }
}
