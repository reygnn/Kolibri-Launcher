package com.github.reygnn.kolibri_launcher.ui.util

import android.content.Context
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.data.CrashReportConsentStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * UI-side helper for the ACRA crash-report consent dialog.
 *
 * Persistence (read/write of consent state) lives in
 * [CrashReportConsentStore] in the data layer. This object only owns
 * the AlertDialog and routes the user choice into the store.
 *
 * Plain `Timber.e` here is grandfathered for now: the dialog's catches
 * are not on the pre-Hilt bootstrap path (unlike the store), so they
 * could migrate to `silentError`, but doing so is out of scope for
 * the data→ui cycle elimination that introduced this split.
 */
object CrashReportConsent {

    /**
     * Shows the consent dialog if it has not been shown before.
     * Otherwise, returns the stored consent state immediately.
     *
     * @param context Activity context, required to show a dialog.
     * @param onResult Callback delivering the consent result.
     * @return the shown [AlertDialog], or `null` if no dialog was shown
     *   (consent already asked, non-Activity context, or a show error).
     *   Callers should track the returned dialog and dismiss it on
     *   `onDestroyView`/`onDestroy` so a config change can't leak its window.
     */
    suspend fun showConsentDialog(context: Context, onResult: (Boolean) -> Unit): AlertDialog? {
        if (CrashReportConsentStore.hasAsked(context)) {
            onResult(CrashReportConsentStore.hasConsent(context))
            return null
        }

        return forceShowConsentDialog(context, onResult)
    }

    /**
     * Forces the dialog regardless of previous interactions. Useful for
     * letting the user revise their choice from the settings screen.
     *
     * @param context Activity context.
     * @param onResult Callback delivering the new consent result.
     * @return the shown [AlertDialog], or `null` if it could not be shown
     *   (non-Activity context or a show error). The caller owns the
     *   returned instance and must dismiss it on teardown to avoid a
     *   leaked window on config change.
     */
    suspend fun forceShowConsentDialog(context: Context, onResult: (Boolean) -> Unit): AlertDialog? {
        return withContext(Dispatchers.Main) {
            if (context !is android.app.Activity) {
                Timber.e("Cannot show dialog: Context is not an Activity (is ${context::class.java.simpleName})")
                onResult(false)
                return@withContext null
            }

            try {
                val messageWithLink = HtmlCompat.fromHtml(
                    context.getString(R.string.crash_report_dialog_message),
                    HtmlCompat.FROM_HTML_MODE_LEGACY
                )

                val dialog = AlertDialog.Builder(context)
                    .setTitle(R.string.crash_report_dialog_title)
                    .setMessage(messageWithLink)
                    .setPositiveButton(R.string.crash_report_button_accept) { dialog, _ ->
                        val appContext = context.applicationContext
                        CoroutineScope(Dispatchers.IO).launch {
                            CrashReportConsentStore.saveConsent(appContext, true)
                        }

                        onResult(true)
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.crash_report_button_decline) { dialog, _ ->
                        val appContext = context.applicationContext
                        CoroutineScope(Dispatchers.IO).launch {
                            CrashReportConsentStore.saveConsent(appContext, false)
                        }

                        onResult(false)
                        dialog.dismiss()
                    }
                    .setCancelable(false)
                    .create()

                dialog.show()

                // Must come AFTER .show() so links become clickable.
                dialog.findViewById<TextView>(android.R.id.message)?.apply {
                    movementMethod = LinkMovementMethod.getInstance()
                }

                dialog
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error showing consent dialog")
                onResult(false)
                null
            }
        }
    }
}
