package com.github.reygnn.kolibri_launcher.ui.util

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.text.HtmlCompat
import com.github.reygnn.kolibri_launcher.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * A robust, thread-safe, and cancellation-aware utility for managing ACRA crash report consent.
 */
object CrashReportConsent {
    private const val PREFS_NAME = "acra_consent"
    private const val KEY_CONSENT = "has_consent"
    private const val KEY_ASKED = "has_asked"

    suspend fun hasConsent(context: Context): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.getBoolean(KEY_CONSENT, false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.Forest.e(e, "Failed to read consent status from SharedPreferences.")
            false
        }
    }

    private suspend fun hasAsked(context: Context): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.getBoolean(KEY_ASKED, false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.Forest.e(e, "Failed to read 'has_asked' status from SharedPreferences.")
            false
        }
    }

    /**
     * Shows the consent dialog if it has not been shown before.
     * Otherwise, it immediately returns the stored consent state.
     * Includes a clickable link to the privacy policy.
     *
     * @param context The Activity context, required to show a dialog.
     * @param onResult A callback that provides the consent result (`true` for accepted, `false` for declined).
     */
    suspend fun showConsentDialog(context: Context, onResult: (Boolean) -> Unit) {
        if (hasAsked(context)) {
            onResult(hasConsent(context))
            return
        }

        // Ab hier ist die Logik identisch zu forceShowConsentDialog,
        // daher rufen wir diese einfach auf, um Code-Duplizierung zu vermeiden.
        forceShowConsentDialog(context, onResult)
    }

    /**
     * Forces the display of the consent dialog, regardless of previous interactions.
     * Useful for letting the user change their choice from a settings screen.
     * Includes a clickable link to the privacy policy.
     *
     * @param context The Activity context, required to show a dialog.
     * @param onResult A callback that provides the new consent result.
     */
    suspend fun forceShowConsentDialog(context: Context, onResult: (Boolean) -> Unit) {
        withContext(Dispatchers.Main) {
            // 1. Safety Check: Ohne Activity kein Dialog -> Absturz verhindern
            if (context !is android.app.Activity) {
                Timber.Forest.e("Cannot show dialog: Context is not an Activity (is ${context::class.java.simpleName})")
                onResult(false) // Fallback: Ablehnen
                return@withContext
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
                        // Leak-Safe: Application Context für Hintergrund-Operation
                        val appContext = context.applicationContext
                        CoroutineScope(Dispatchers.IO).launch { saveConsent(appContext, true) }

                        onResult(true)
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.crash_report_button_decline) { dialog, _ ->
                        val appContext = context.applicationContext
                        CoroutineScope(Dispatchers.IO).launch { saveConsent(appContext, false) }

                        onResult(false)
                        dialog.dismiss()
                    }
                    .setCancelable(false)
                    .create()

                dialog.show()

                // WICHTIG: Das muss NACH .show() kommen, damit Links klickbar sind
                dialog.findViewById<TextView>(android.R.id.message)?.apply {
                    movementMethod = LinkMovementMethod.getInstance()
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fangnetz für alle UI-Fehler
                Timber.Forest.e(e, "Error showing consent dialog")
                onResult(false)
            }
        }
    }

    suspend fun setConsent(context: Context, consent: Boolean) {
        saveConsent(context, consent)
    }

    /**
     * Resets the consent, forcing the dialog to be shown on the next app start.
     * Uses the modern KTX SharedPreferences extension function.
     */
    suspend fun resetConsent(context: Context) {
        try {
            withContext(Dispatchers.IO) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    clear()
                }
                Timber.Forest.i("Crash report consent has been reset.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.Forest.e(e, "Failed to reset consent.")
        }
    }

    /**
     * Saves the user's consent choice to SharedPreferences on a background thread.
     * Uses the modern KTX SharedPreferences extension function.
     */
    private suspend fun saveConsent(context: Context, consent: Boolean) {
        try {
            withContext(Dispatchers.IO) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY_CONSENT, consent)
                    putBoolean(KEY_ASKED, true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.Forest.e(e, "Failed to save consent choice to SharedPreferences.")
        }
    }
}