package com.github.reygnn.kolibri_launcher.ui.util

import android.content.Context
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import com.github.reygnn.kolibri_launcher.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * UI-side helper for the ACRA crash-report consent dialog.
 *
 * Pure view concern: it builds and shows the AlertDialog and reports the
 * user's choice through [onResult]. It deliberately does NOT read or
 * persist consent and owns NO coroutine scope of its own. The
 * "already asked?" gate and the persistence both live in the caller via
 * [CrashReportConsentController], which owns an app-lifetime scope and the
 * consent use cases. (This removed the old detached
 * `CoroutineScope(Dispatchers.IO).launch { saveConsent(...) }` per
 * button click — AUDIT-9 #11.)
 *
 * Plain `Timber.e` here is grandfathered for now: the dialog's catches
 * are not on the pre-Hilt bootstrap path (unlike the store), so they
 * could migrate to `silentError`, but doing so is out of scope.
 */
object CrashReportConsent {

    /**
     * Shows the consent dialog. Reports the user's choice via [onResult]
     * (`true` = accept, `false` = decline). Persistence and the ACRA
     * enable/disable are the caller's responsibility.
     *
     * @param context Activity context, required to show a dialog.
     * @param onResult Callback delivering the consent result.
     * @return the shown [AlertDialog], or `null` if no dialog was shown
     *   (non-Activity context or a show error). The dialog is
     *   `setCancelable(false)`, so callers must track the returned instance
     *   and dismiss it on `onDestroyView`/`onDestroy` to avoid leaking its
     *   window across a config change.
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
                        onResult(true)
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.crash_report_button_decline) { dialog, _ ->
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
