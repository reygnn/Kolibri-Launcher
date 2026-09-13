package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.repository.DefaultAppsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the user's default phone / SMS / email / browser / camera apps to package
 * names via PUBLIC platform APIs.
 *
 * Deliberately NOT `RoleManager.getRoleHolders(ROLE_DIALER/SMS/BROWSER)`: reading a
 * role holder is a system-privileged operation (`MANAGE_ROLE_HOLDERS`) a launcher
 * cannot perform. The public resolvers below return the same "who is the default"
 * answer at our permission level: `TelecomManager` / `Telephony` for dialer + SMS,
 * `PackageManager.resolveActivity` for email (a `mailto:` intent), browser and camera.
 */
@Singleton
class DefaultAppsRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DefaultAppsRepository {

    override suspend fun getDefaultAppPackages(): List<String> = withContext(ioDispatcher) {
        // Order: phone, SMS, email, browser, camera. resolveActivity / getSystemService
        // touch PackageManager, so run off the main thread (StrictMode-safe on Samsung).
        val candidates = listOf(
            defaultDialerPackage(),
            defaultSmsPackage(),
            resolvedPackage(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))),
            resolvedPackage(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))
                    .apply { addCategory(Intent.CATEGORY_BROWSABLE) }
            ),
            resolvedPackage(Intent(MediaStore.ACTION_IMAGE_CAPTURE)),
        )
        // Drop unresolved roles, the system resolver ("android", shown when no default
        // is set), and our own launcher — no dummy or self entries.
        candidates
            .filterNotNull()
            .filter { it.isNotBlank() && it != ANDROID_RESOLVER_PACKAGE && it != context.packageName }
            .distinct()
    }

    private fun defaultDialerPackage(): String? = try {
        (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "Failed to resolve default dialer")
        null
    }

    private fun defaultSmsPackage(): String? = try {
        Telephony.Sms.getDefaultSmsPackage(context)
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "Failed to resolve default SMS app")
        null
    }

    private fun resolvedPackage(intent: Intent): String? = try {
        context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "Failed to resolve default app for ${intent.action}")
        null
    }

    private companion object {
        // PackageManager returns this synthetic package for the disambiguation
        // ("resolver") activity when the user has set no default for an intent.
        const val ANDROID_RESOLVER_PACKAGE = "android"
    }
}
