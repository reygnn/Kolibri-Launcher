package com.github.reygnn.kolibri_launcher.ui.util

import android.content.Context
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * Error logging uses `TimberWrapper.silentError`: the dialog's catches are
 * NOT on the pre-Hilt bootstrap path (unlike the store), so the Rule 9
 * grandfathering no longer applies. The throw-in-DEBUG semantic is
 * desirable here — a dialog that fails to show is exactly the path that
 * must not silently masquerade as a user decision (AUDIT-10 #6/#8).
 */
object CrashReportConsent {

    /**
     * Shows the consent dialog. Reports the user's choice via [onResult]
     * (`true` = accept, `false` = decline). Persistence and the ACRA
     * enable/disable are the caller's responsibility.
     *
     * [onResult] fires ONLY on a genuine user choice (an Accept/Decline
     * button tap). A failure to show the dialog (non-Activity context or a
     * `show()` error) does NOT invoke [onResult] — it is reported solely
     * through a `null` return. This separation is deliberate: routing a
     * show-failure through `onResult(false)` would let a display failure
     * persist a decline the user never made (AUDIT-10 #6).
     *
     * @param context Activity context, required to show a dialog.
     * @param onResult Callback delivering a real user consent decision.
     * @return the shown [AlertDialog], or `null` if no dialog was shown
     *   (non-Activity context or a show error). A `null` return means "no
     *   decision was made" — callers must not persist anything for it. The
     *   dialog is `setCancelable(false)`, so callers must track the
     *   returned instance and dismiss it on `onDestroyView`/`onDestroy` to
     *   avoid leaking its window across a config change.
     */
    suspend fun forceShowConsentDialog(context: Context, onResult: (Boolean) -> Unit): AlertDialog? {
        return withContext(Dispatchers.Main) {
            if (context !is android.app.Activity) {
                TimberWrapper.silentError(
                    IllegalStateException("Context is not an Activity (is ${context::class.java.simpleName})"),
                    "Cannot show consent dialog: context is not an Activity"
                )
                return@withContext null
            }

            // The try wraps only the genuinely throwing part (HTML parse,
            // builder, show()). If show() throws — e.g. BadTokenException on a
            // finishing Activity — nothing is persisted and null propagates.
            val dialog = try {
                val messageWithLink = HtmlCompat.fromHtml(
                    context.getString(R.string.crash_report_dialog_message),
                    HtmlCompat.FROM_HTML_MODE_LEGACY
                )

                AlertDialog.Builder(context)
                    .setTitle(R.string.crash_report_dialog_title)
                    .setMessage(messageWithLink)
                    .setPositiveButton(R.string.crash_report_button_accept) { d, _ ->
                        onResult(true)
                        d.dismiss()
                    }
                    .setNegativeButton(R.string.crash_report_button_decline) { d, _ ->
                        onResult(false)
                        d.dismiss()
                    }
                    .setCancelable(false)
                    .create()
                    .also { it.show() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error showing consent dialog")
                null
            } ?: return@withContext null

            // Links become clickable only after show(). Pure view property
            // write (no catch, Rule 11); kept OUTSIDE the show() try so a
            // dialog that is already on screen is always returned and thus
            // tracked/dismissable — never discarded to null after it became
            // visible, which would leak its setCancelable(false) window
            // (AUDIT-10 #9).
            dialog.findViewById<TextView>(android.R.id.message)?.movementMethod =
                LinkMovementMethod.getInstance()

            dialog
        }
    }
}
