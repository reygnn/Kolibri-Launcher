package com.github.reygnn.kolibri_launcher.ui.main

import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.LuminanceClassification
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.ResolveWallpaperSurfaceUseCase
import com.github.reygnn.kolibri_launcher.ui.base.BaseActivity
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.colorcustomization.ColorCustomizationDialogFragment
import com.github.reygnn.kolibri_launcher.ui.layoutcustomization.LayoutCustomizationDialogFragment
import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingActivity
import com.github.reygnn.kolibri_launcher.ui.settings.SettingsActivity
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentController
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentDialog
import com.github.reygnn.kolibri_launcher.crashreporting.health.CrashReportingHealthMonitor
import com.github.reygnn.kolibri_launcher.crashreporting.health.CrashReportingHealthNotifier
import com.github.reygnn.kolibri_launcher.crashreporting.health.CrashReportingHealthState
import com.github.reygnn.kolibri_launcher.ui.util.LaunchTrace
import com.github.reygnn.kolibri_launcher.ui.util.WallpaperImagePicker
import com.github.reygnn.kolibri_launcher.ui.util.showToastSafe
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.roundToInt

/*
 * =============================================================================
 *                    MainActivity — Catch-Sweep & Frame Notes
 * =============================================================================
 *
 * Status: Post catch-sweep (2026-05-04). 24 try/catch blocks (20
 * Throwable), 972 lines. Down from 48 / 40 / 797 on origin/main —
 * catch reduction 50% / 50%. The line count grew because the audit-
 * style header KDoc and the inline rationale comments on every kept
 * catch are part of the deal: each remaining catch carries a one-
 * paragraph reason that names its four-category frame slot, so a
 * future reader knows why it is there before they ask.
 *
 *
 * Pure-logic extraction in this sweep
 * -----------------------------------
 * `InitialSetupAction.decide` — sealed resolver for the first-run setup
 * path (LaunchOnboarding vs InitializeImmediately, with the wipe+reinstall
 * edge case). Pattern parallel to `AppLaunchAction.decide`; JVM-tested
 * exhaustively in `InitialSetupActionTest`.
 *
 *
 * The four-category frame for try/catch
 * -------------------------------------
 * Every remaining catch in this file falls into exactly one of these four
 * named categories. Reviewers: please apply when adding a new catch, and
 * please challenge any unannotated catch.
 *
 *   Expected errors (system API / external boundary) — caught with a
 *     specific exception type, or with `Throwable` where several specific
 *     types share the same recovery. User-facing recovery via Toast where
 *     it matters. Examples: `launchApp`'s
 *     ActivityNotFoundException + SecurityException + Throwable triple,
 *     `startActivitySafely`'s Intent-resolution catch with fallback,
 *     `OpenCalendar`'s Toast-recovery branch.
 *
 *   Teardown races (Activity finishing / Window detached) — prevented
 *     structurally with `if (isFinishing || isDestroyed) return` guards
 *     before every Dialog.show() / DialogFragment.show() call site,
 *     NOT masked with a post-hoc catch. The race
 *     (WindowManager.BadTokenException / IllegalStateException
 *     "Can not perform this action after onSaveInstanceState") becomes
 *     impossible to hit, so no catch is needed.
 *
 *   Programmer errors (NPE, IllegalState, IndexOutOfBounds in pure
 *     paths) — bugs, not conditions. Crash loudly in DEBUG via
 *     silentError, RELEASE logs and ends the coroutine. Do not catch.
 *     Removed wholesale in this sweep from `setupWindow`, `onStart`,
 *     `onPause` (the `isReceiverRegistered` guard prevents the
 *     IllegalArgumentException case), `onDestroy`, the inner branches
 *     of `handleSpecificEvent` where the outer Catchall delivers
 *     equivalent recovery, and the inner try/catch blocks in coroutine
 *     bodies running under `mainActivityExceptionHandler` (the handler
 *     is the safety net).
 *
 *   Unrecoverable / HOME-Activity-resilience boundaries — system
 *     callbacks where letting the exception propagate would crash the
 *     launcher (BroadcastReceiver.onReceive, the outer Catchall in
 *     `handleSpecificEvent`), or paths where the Activity must not
 *     continue with a half-initialised state (`setupMainContent`'s two
 *     `silentDeath` paths — `finish()` would loop because Kolibri is
 *     registered as HOME). The post-onboarding init catch in
 *     `onboardingLauncher` is in the same family: on failure,
 *     `finish()` hands off to the next default launcher rather than
 *     leaving a half-initialised launcher in the foreground.
 *
 *
 * What changed in this sweep
 * --------------------------
 *   1. Bug-fix in `mainActivityExceptionHandler` — the explicit DEBUG
 *      re-throw was inside the outer try/catch(Throwable) and silently
 *      swallowed silentError's Rule-9 RuntimeException, defeating
 *      Rule 9 across every coroutine running under this handler. The
 *      `try` is now tight around the logging call only; the DEBUG
 *      re-throw lives outside. (Committed separately as the first
 *      commit of the catch-sweep branch for bisect-friendliness.)
 *
 *   2. Doubled-defence catches in coroutine bodies — every
 *      `lifecycleScope.launch(mainActivityExceptionHandler) { try ...
 *      catch (Throwable) ... }` had the inner catch removed where it
 *      only logged. The handler is the safety net. Affected:
 *      `updateSecureFlag`, `updateRotationLock`, `initializeMainApp`,
 *      `onStop`'s backup branch, `checkAndShowCrashReportConsent`'s
 *      inner ACRA-toggle lambda. Inner CancellationException rethrows
 *      went away with the catches they lived in. Where the Throwable
 *      catch was kept (`onboardingLauncher`, `handleInitialSetup`), the
 *      CancellationException rethrow stays to preserve cooperative
 *      cancellation per canonical Kotlin coroutine guidance.
 *
 *   3. Programmer-error swallows in lifecycle overrides — outer catches
 *      removed from `onCreate` (around installSplashScreen +
 *      setupWindow), `onStart` (around the fire-and-forget
 *      `viewModel.refreshAllData()`), `setupWindow` (three property
 *      setters), `onPause` (guarded `unregisterReceiver`), and
 *      `onDestroy` (plus its nested receiver-catch — same guard as
 *      `onPause`). Per Rule 11: catches are for real failure modes,
 *      not "just in case" wrappers around can't-throw operations.
 *
 *      Rule 7 (multi-layer paranoia for critical init) applies to
 *      `KolibriLauncherApp.onCreate`, not to per-Activity init. A
 *      programmer error during MainActivity init now propagates to the
 *      application-level handler set up there, where it belongs.
 *
 *   4. `handleSpecificEvent` inner-catch surgery — the outer Catchall
 *      stays (HOME-Activity-resilience boundary). Inner catches that
 *      only logged the same way the outer Catchall would were removed
 *      from `ShowAppDrawer`, `ShowSettings`, and `ShowColorPickerDialog`.
 *      Inner catches that delivered Toast recovery (`OpenCalendar`,
 *      `OpenWallpaperPicker`) or scoped diagnostic messages
 *      (`LaunchApp`'s `popBackStack` branch) stay, with inline
 *      rationale.
 *
 *   5. Standalone helper outer catches —
 *      `showColorCustomizationDialog`, `showLayoutCustomizationDialog`,
 *      `openSettingsActivity` and `showDialog`
 *      are all called from `handleSpecificEvent` whose Catchall already
 *      covers them. Outer catches removed.
 *
 *   6. Dialog-show teardown-race guards — `showDialog`,
 *      `showCustomizationOptionsDialog`, and the two
 *      `DialogFragment.show()` call sites
 *      (`ColorCustomizationDialogFragment`,
 *      `LayoutCustomizationDialogFragment`) now guard with
 *      `if (isFinishing || isDestroyed) return` before `.show()`. See
 *      "Teardown races" above.
 *
 *   7. `onResume` narrowed try-block (deliberate behaviour change) —
 *      the previous catch wrapped both `registerReceiver` AND the
 *      `updateRotationLock` call. A registerReceiver failure therefore
 *      skipped the update as a side effect. The catch is now tight around
 *      `registerReceiver` only; `updateRotationLock` runs unconditionally.
 *      Justification: the two concerns (wallpaper colours, rotation-lock)
 *      are independent of battery-receiver registration — losing the
 *      receiver should not also leave the screen in stale tint or a stale
 *      rotation state. `updateRotationLock` has its own safety net
 *      downstream (internal catch / coroutine handler).
 *
 *
 * DataStore-read fallback by inaction — explicit trade-off
 * --------------------------------------------------------
 * When DataStore reads in coroutine bodies (`updateSecureFlag`,
 * `updateRotationLock`, `initializeMainApp`) fail, the exception handler
 * logs and the coroutine ends. Window flags / init state remain in their
 * previous values — graceful fallback by inaction. This is a deliberate
 * trade-off; a per-call try/catch with a default would not produce
 * different user-visible behaviour, so the extra surface is not worth it.
 *
 *
 * Known pressure point: `handleSpecificEvent` size
 * ------------------------------------------------
 * The method is ~120 lines with ~15 `when`-branches. Each branch is
 * Activity-bound (View setter, navigation, dialog show), so splitting
 * into helper methods would relocate lines without reducing complexity
 * — same argument as HomeFragment's Fragment-delegate-split deferral.
 * If a single branch later accumulates non-trivial logic, lift that
 * branch's logic into a sealed-resolver or pure helper (the
 * `AppLaunchAction.decide` extract already inside this Activity is the
 * template). Until then, accept the size.
 *
 * =============================================================================
 */
@AndroidEntryPoint
class MainActivity : BaseActivity<UiEvent, LauncherViewModel>() {

    override val viewModel: LauncherViewModel by viewModels()

    private var navController: NavController? = null
    private var isReceiverRegistered = false
    private var currentDialog: androidx.appcompat.app.AlertDialog? = null

    // Idempotency flags
    private var isInitialized = false
    private var onboardingCheckCompleted = false

    // Coroutine exception handler for MainActivity scopes. Two responsibilities:
    //
    //   1. Log uncaught exceptions via silentError (RELEASE: logged; DEBUG:
    //      silentError throws RuntimeException per Rule 9).
    //   2. Re-throw the original throwable in DEBUG so programmer errors
    //      surface instead of being silently absorbed by the handler.
    //
    // The previous shape wrapped both responsibilities in one
    // try/catch(Throwable), which swallowed silentError's Rule-9
    // RuntimeException AND the explicit `throw throwable` — defeating
    // Rule 9 across every coroutine running under this handler. Layout
    // now: `try` is tight around the logging call only; the DEBUG re-throw
    // lives outside the catch. The catch's last-resort fallback is
    // System.err so we don't lose the original error if Timber itself
    // crashes (same shape as KolibriLog.silentDeath's stderr fallback).
    private val mainActivityExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        try {
            TimberWrapper.silentError(throwable, "Uncaught exception in MainActivity")
        } catch (loggingError: Throwable) {
            // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
            // Catch kept (Expected error, four-category frame): the
            // recursion-into-error-pipeline guard documented in the field
            // KDoc above. If Timber itself crashes, fall back to stderr
            // so the original error is not lost.
            System.err.println(
                "MainActivity logging failed: ${loggingError.message}; " +
                    "original error: ${throwable.message}"
            )
        }

        if (BuildConfig.DEBUG) {
            throw throwable
        }
    }

    @Inject
    lateinit var appPackageManager: PackageManager
    @Inject
    lateinit var settingsRepository: SettingsRepository
    @Inject
    lateinit var resolveWallpaperSurfaceUseCase: ResolveWallpaperSurfaceUseCase
    @Inject
    lateinit var appLauncher: AppLauncher
    @Inject
    lateinit var crashReportConsentController: ConsentController
    @Inject
    lateinit var crashReportingHealthMonitor: CrashReportingHealthMonitor
    @Inject
    lateinit var crashReportingHealthNotifier: CrashReportingHealthNotifier

    /**
     * Latest wallpaper-surface classification emitted by
     * [resolveWallpaperSurfaceUseCase]. Cached so home-anchored dialogs
     * — built synchronously from click listeners, not in coroutines —
     * can pick the matching alert-dialog theme overlay
     * (CustomAlertDialog.Light vs .Dark) without re-subscribing to the
     * flow. Default is [LuminanceClassification.DARK] so the very
     * first dialog (before the first emission lands) matches the
     * historical look-and-feel.
     */
    private var currentSurfaceClassification: LuminanceClassification =
        LuminanceClassification.DARK

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_CHANGED -> viewModel.updateBatteryLevelFromIntent(intent)
                }
            } catch (e: Throwable) {
                // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
                // Catch kept (HOME-Activity-resilience boundary, four-
                // category frame): BroadcastReceiver.onReceive runs as a
                // system callback. An unhandled throw here crashes the
                // launcher process, so we keep the safety net even
                // though updateBatteryLevelFromIntent has its own
                // internal catch.
                TimberWrapper.silentError(e, "Error in systemEventReceiver")
            }
        }
    }

    companion object {
        private const val STATE_CURRENT_DESTINATION = "current_destination"
    }

    private val onboardingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        lifecycleScope.launch(mainActivityExceptionHandler) {
            try {
                when (result.resultCode) {
                    RESULT_OK -> {
                        // Onboarding successful - initialize app directly
                        initializeMainApp()
                    }
                    else -> {
                        // Onboarding aborted - close app
                        Timber.w("Onboarding aborted, closing app")
                        finish()
                    }
                }
            } catch (e: CancellationException) {
                // Rethrow per canonical Kotlin coroutines guidance:
                // catching Throwable in a coroutine body absorbs
                // CancellationException too, which silently breaks
                // cooperative cancellation. Letting it through here
                // keeps the structured-concurrency contract intact.
                throw e
            } catch (e: Throwable) {
                // Catch kept (HOME-Activity-resilience boundary, four-
                // category frame): this is the post-onboarding init
                // path. If it fails, without finish() the user is left
                // with a half-initialised launcher in the foreground —
                // exactly the failure mode the frame calls out.
                // finish() hands off to the next default launcher.
                TimberWrapper.silentError(e, "Error handling onboarding result")
                finish()
            }
        }
    }

    private val wallpaperPickerLauncher = registerForActivityResult(
        WallpaperImagePicker.contract()
    ) { uri: android.net.Uri? ->
        try {
            if (uri != null) {
                viewModel.onSetWallpaperImage(uri)
            }
        } catch (e: Throwable) {
            // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
            // Catch kept (Expected error, four-category frame): the
            // ActivityResult callback runs outside any outer Catchall,
            // and onSetWallpaperImage delegates into a coroutine that
            // touches ContentResolver. Toast gives the user a recovery
            // signal; without the catch they would get nothing.
            TimberWrapper.silentError(e, "Error handling wallpaper picker result")
            showToastSafe(R.string.error_generic)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        setupWindow()

        super.onCreate(savedInstanceState)

        if (!setupMainContent()) {
            return
        }

        // Keep the cached wallpaper-surface classification fresh across
        // wallpaper / surface-mode changes for the lifetime of the
        // Activity. Read at click-time by [wallpaperAwareDialogStyle]
        // when the user opens one of the home-anchored MaterialAlertDialogs
        // (long-press menu, remove-wallpaper confirm, accessibility prompt).
        lifecycleScope.launch(mainActivityExceptionHandler) {
            resolveWallpaperSurfaceUseCase().collect { classification ->
                currentSurfaceClassification = classification
            }
        }

        // Offer to reset a leftover wallpaper scrim after the wallpaper image was
        // cleared or replaced (only fires when the scrim is non-zero — see
        // LauncherViewModel.offerScrimResetEvents), so a dim tuned for an extreme
        // wallpaper doesn't silently darken a fresh, normal one.
        lifecycleScope.launch(mainActivityExceptionHandler) {
            viewModel.offerScrimResetEvents.collect { currentAlpha ->
                offerScrimReset(currentAlpha)
            }
        }

        // Only run initial setup on first creation (not on config changes)
        if (savedInstanceState == null) {
            lifecycleScope.launch(mainActivityExceptionHandler) {
                // Launch setup immediately to fetch DataStore in parallel with UI startup.
                // The lifecycle check happens internally only if we need to launch an Activity.
                handleInitialSetup()
            }
        } else {
            // After config change: restore flags
            isInitialized = true
            onboardingCheckCompleted = true
            Timber.d("Activity recreated, skipping initialization")
        }
    }

    private fun setupMainContent(): Boolean {
        return try {
            setContentView(R.layout.activity_main)

            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment

            if (navHostFragment != null) {
                navController = navHostFragment.navController
                WindowCompat.setDecorFitsSystemWindows(window, false)
                true
            } else {
                // finish() würde nichts bringen: Kolibri ist als HOME registriert,
                // Android startet die Activity sofort wieder → Endlos-Loop mit
                // ACRA-Spam. silentDeath beendet den Process kontrolliert; das
                // System fällt dann auf den nächsten Default-Launcher zurück.
                TimberWrapper.silentDeath("NavHostFragment not found — main UI cannot start")
            }
        } catch (e: Throwable) {
            // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
            // Catch kept (Unrecoverable, four-category frame): see the
            // silentDeath rationale in the if/else branch above.
            // Gleicher Grund wie oben: setContentView/findFragment kann fatal
            // failen (View-Inflation, OOM, kaputte Resource), und ein finish()
            // wäre Zombie-Maker.
            TimberWrapper.silentDeath(e, "Fatal error setting up main content")
        }
    }

    /**
     * Runs ONLY ONCE on first app start.
     * Determines if onboarding is needed or proceeds directly to initialization.
     */
    private suspend fun handleInitialSetup() {
        if (onboardingCheckCompleted) {
            Timber.d("Initial setup already completed, skipping")
            return
        }

        try {
            // 1. Fetch data immediately (Async IO) - Don't wait for UI
            val onboardingCompleted = settingsRepository.onboardingCompletedFlow.first()

            onboardingCheckCompleted = true

            when (InitialSetupAction.decide(onboardingCompleted)) {
                InitialSetupAction.LaunchOnboarding -> {
                    // 2. Wait for STARTED state, then launch ONCE
                    // This prevents BackgroundActivityLaunchViolation on Pixel/Android 14+
                    // Using withStarted is cleaner than repeatOnLifecycle + cancel
                    lifecycle.withStarted {
                        launchOnboardingActivity()
                    }
                }
                InitialSetupAction.InitializeImmediately -> {
                    // 3. Normal start (or wipe+reinstall with backup) —
                    // initialize immediately, no wait needed.
                    initializeMainApp()
                }
            }
        } catch (e: CancellationException) {
            // Rethrow per canonical Kotlin coroutines guidance — see
            // the matching comment in onboardingLauncher.
            throw e
        } catch (e: Throwable) {
            // Catch kept (Expected error → Unrecoverable, four-category
            // frame): DataStore IO failure on the first-run path. We
            // attempt initializeMainApp anyway as a recovery; if THAT
            // also fails we hand off to silentDeath rather than leaving
            // the user with a half-initialised launcher.
            TimberWrapper.silentError(e, "Error in handleInitialSetup")
            try {
                initializeMainApp()
            } catch (cancellation: CancellationException) {
                // Rethrow per canonical Kotlin coroutines guidance: the
                // recovery call suspends (consent-store read), so a torn-down
                // Activity (rotation, home switch, process pressure) cancels it
                // here. A cancelled Activity is normal teardown, NOT a lying
                // state — routing it into silentDeath would exitProcess(1) on
                // ordinary control flow and defeat the isInitialized retry on
                // the next onCreate. silentDeath stays reserved for a genuine
                // recovery failure below.
                throw cancellation
            } catch (fallbackError: Throwable) {
                // Inner catch kept (Unrecoverable, four-category frame):
                // last recovery path also failed. Letting the Activity
                // continue would leave a launcher with no ViewModel data
                // — worse than a restart that may work the second time.
                TimberWrapper.silentDeath(
                    fallbackError,
                    "Fallback initialization failed — no recovery path left"
                )
            }
        }
    }

    private fun launchOnboardingActivity() {
        try {
            val intent = Intent(this, OnboardingActivity::class.java)
            onboardingLauncher.launch(intent)
        } catch (e: Throwable) {
            // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
            // Catch kept (Expected error, four-category frame):
            // ActivityResultLauncher.launch can throw on lifecycle-state
            // mismatches or activity-not-found. Fallback initialises
            // without onboarding rather than blocking the user.
            TimberWrapper.silentError(e, "Error launching OnboardingActivity")
            lifecycleScope.launch(mainActivityExceptionHandler) {
                initializeMainApp()
            }
        }
    }

    /**
     * Final app initialization. Runs ONLY ONCE thanks to isInitialized flag.
     */
    private suspend fun initializeMainApp() {
        if (isInitialized) {
            Timber.d("App already initialized, skipping")
            return
        }

        // No try/catch: this runs under mainActivityExceptionHandler.
        // A throw from checkAndShowCrashReportConsent propagates to the
        // handler (DEBUG: crashes loud per Rule 9; RELEASE: logs and
        // ends the coroutine). isInitialized stays false in that case,
        // so the next onCreate after a config change retries.
        checkAndShowCrashReportConsent()
        isInitialized = true
        Timber.d("Main app initialization completed")
    }

    private fun setupWindow() {
        window.setWindowAnimations(0)
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
    }

    private suspend fun checkAndShowCrashReportConsent() {
        when (val action = crashReportConsentController.resolveStartupAction()) {
            is ConsentController.StartupAction.Reaffirm -> {
                // Already answered on a previous launch (this branch is only
                // reached when the read SUCCEEDED with a stored Granted/Denied
                // decision). ACRA was set from the stored consent at bootstrap
                // (attachBaseContext); re-affirm it here — cheap, and covers a
                // bootstrap read that failed — without showing the dialog
                // again. Synchronous: this runs under
                // mainActivityExceptionHandler already, so no extra
                // lifecycleScope.launch hop is needed (AUDIT-10 #5).
                crashReportConsentController.reaffirmConsent(action.granted)
            }

            ConsentController.StartupAction.Skip -> {
                // Consent state unreadable — it is unknown whether the user was
                // ever asked. Doing nothing is the only safe option: showing the
                // dialog would write back whatever gets tapped and could
                // overwrite a decision the user already made, while flipping
                // ACRA would act on a state we do not know. ACRA keeps what the
                // bootstrap read established; the next launch reads again. The
                // failure itself is already logged in the controller
                // (AUDIT-10 #2).
            }

            ConsentController.StartupAction.ShowDialog -> {
                val consentDialog = ConsentDialog.show(this) { userGaveConsent ->
                    // Persist + apply the decision through the controller
                    // (app-lifetime scope for the write; survives an immediate
                    // Activity teardown after the tap). AUDIT-10 #12.
                    crashReportConsentController.applyConsent(userGaveConsent)
                }
                // Track so onDestroy dismisses it. The consent dialog is
                // setCancelable(false), so a config change with it open would
                // otherwise leak its window. null = couldn't show, so don't clobber.
                if (consentDialog != null) {
                    currentDialog?.dismiss()
                    currentDialog = consentDialog
                }
            }
        }

        // Out-of-band ACRA-health check (after consent is settled). If consent is
        // Granted but the bootstrap consent gate did not complete, ACRA's
        // bootstrap-time reporting is broken (the reaffirm above masks it at
        // runtime) — surface it via a notification + the Settings hint. Never via
        // ACRA/silentError (circular). Clear the notification when healthy again.
        when (crashReportingHealthMonitor.evaluate()) {
            CrashReportingHealthState.BROKEN -> crashReportingHealthNotifier.showBroken()
            else -> crashReportingHealthNotifier.clear()
        }
    }

    override fun onStart() {
        super.onStart()
        // Clock/battery only (AUDIT-19 F5). The installed-app list is kept fresh
        // REACTIVELY now — PackageUpdateReceiver (add/remove/update, plus
        // enable/disable via ACTION_PACKAGE_CHANGED) and the locale hook in
        // KolibriLauncherApp.onConfigurationChanged. Re-enumerating the whole
        // launcher list (queryIntentActivities + one loadLabel IPC per app) on
        // every foreground was redundant with that reactive layer and the
        // cold-start prime, so it was dropped (it also removes the cold-start
        // double-enumeration). refreshDynamicUiData is the synchronous clock
        // refresh only — no realistic sync-throw path, so no try/catch.
        viewModel.refreshDynamicUiData()
    }

    override fun onResume() {
        super.onResume()
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        try {
            registerReceiver(systemEventReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
            isReceiverRegistered = true
        } catch (e: Throwable) {
            // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
            // Catch kept (Expected error, four-category frame):
            // registerReceiver is a system-API call that can fail on
            // permission issues or system races. Resetting the flag to
            // false keeps onPause from later trying to unregister a
            // receiver that was never actually registered.
            TimberWrapper.silentError(e, "Error registering system event receiver")
            isReceiverRegistered = false
        }

        // updateRotationLock launches under the handler.
        // System-wallpaper colour hints flow continuously through
        // SystemWallpaperColorsSignal (wired in KolibriLauncherApp.onCreate)
        // — no onResume poll needed anymore.
        updateRotationLock()
    }

    private fun updateRotationLock() {
        lifecycleScope.launch(mainActivityExceptionHandler) {
            val locked = settingsRepository.rotationLockedFlow.first()
            requestedOrientation = if (locked) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // No try/catch: the isReceiverRegistered guard structurally
        // prevents the only realistic throw (IllegalArgumentException
        // from unregistering a receiver that was never registered).
        if (isReceiverRegistered) {
            unregisterReceiver(systemEventReceiver)
            isReceiverRegistered = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Bundle.putInt wirft nicht; der ?.-Chain ist null-safe.
        navController?.currentDestination?.id?.let { destinationId ->
            outState.putInt(STATE_CURRENT_DESTINATION, destinationId)
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // Bundle.getInt + Null-Checks werfen nicht. Der einzige echte
        // Wurfpfad ist NavController.navigate (IllegalStateException
        // wenn Destination nicht im Graph) — der hat seinen eigenen
        // Catch direkt unten. Frühere Outer-Catch war DEAD_REDUNDANT.
        val destinationId = savedInstanceState.getInt(STATE_CURRENT_DESTINATION, R.id.homeFragment)
        val currentNav = navController

        if (currentNav != null &&
            destinationId != R.id.homeFragment &&
            currentNav.currentDestination?.id != destinationId
        ) {
            try {
                currentNav.navigate(destinationId)
            } catch (e: Throwable) {
                // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
                // Catch kept (Expected error, four-category frame):
                // NavController.navigate throws IllegalStateException when
                // the saved destination ID is no longer in the graph
                // (e.g. after an upgrade that renamed a fragment).
                // Fallback to home keeps the launcher usable.
                Timber.w(e, "Failed to restore navigation state")
                try {
                    currentNav.popBackStack(R.id.homeFragment, false)
                } catch (fallbackError: Throwable) {
                    // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
                    // Inner catch kept (Expected error, four-category
                    // frame): even popBackStack can fail in degenerate
                    // nav-graph states. silentError logs and continues;
                    // the launcher recovers on the next user interaction.
                    TimberWrapper.silentError(fallbackError, "Failed to navigate to home fragment")
                }
            }
        }
    }

    override fun onDestroy() {
        // No try/catch: dialog dismiss is null-safe and self-protected
        // against already-dismissed; unregisterReceiver is guarded by
        // isReceiverRegistered (same structural prevention as onPause);
        // property nullings cannot throw. super.onDestroy() runs
        // unconditionally because there is no earlier return path.
        currentDialog?.dismiss()
        currentDialog = null

        if (isReceiverRegistered) {
            unregisterReceiver(systemEventReceiver)
            isReceiverRegistered = false
        }
        navController = null

        super.onDestroy()
    }

    override fun handleSpecificEvent(event: UiEvent) {
        if (BuildConfig.DEBUG) {
            Timber.d("[MAIN] handleSpecificEvent called with: ${event.javaClass.simpleName}")
        }

        try {
            when (event) {
                is UiEvent.ShowAppDrawer -> {
                    if (navController?.currentDestination?.id == R.id.homeFragment) {
                        // Traced: the drawer-open navigation. The 300ms
                        // slide_in_up transition runs after this returns — the
                        // fragment transaction cost itself is what this pins.
                        LaunchTrace.section(LaunchTrace.Names.DRAWER_OPEN) {
                            navController?.navigate(R.id.appDrawerFragment)
                        }
                        if (BuildConfig.DEBUG) {
                            Timber.d("[MAIN] Navigated to app drawer")
                        }
                    } else if (BuildConfig.DEBUG) {
                        Timber.d("[MAIN] Not navigating - wrong destination: ${navController?.currentDestination?.id}")
                    }
                }

                is UiEvent.ShowSettings -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                }

                is UiEvent.ShowCustomizationOptions -> {
                    showCustomizationOptionsDialog()
                }

                is UiEvent.ShowColorPickerDialog -> {
                    if (isFinishing || isDestroyed) return
                    ColorCustomizationDialogFragment().show(supportFragmentManager, "ColorCustomizationDialog")
                }

                is UiEvent.OpenClock -> {
                    startActivitySafely(Intent(AlarmClock.ACTION_SHOW_ALARMS))
                }

                is UiEvent.OpenCalendar -> {
                    try {
                        val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                        ContentUris.appendId(builder, System.currentTimeMillis())
                        startActivitySafely(Intent(Intent.ACTION_VIEW).setData(builder.build()))
                    } catch (e: Throwable) {
                        // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
                        // Inner catch kept (Expected error, four-category
                        // frame): outer Catchall would log but not Toast,
                        // so the user-visible "no calendar app" recovery
                        // would be lost.
                        TimberWrapper.silentError(e, "[MAIN] Error opening calendar")
                        showToastSafe(R.string.error_no_calendar_app)
                    }
                }

                is UiEvent.OpenBatterySettings -> {
                    startActivitySafely(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
                }

                is UiEvent.ShowRecentApps -> {
                    showRecentAppsDialog(event.apps)
                }

                is UiEvent.PerformClipboardAction -> {
                    performClipboardAction()
                }

                is UiEvent.LaunchApp -> {
                    // Traced: the synchronous Main-thread dispatch of a launch
                    // (decide + optional drawer popBackStack + launchApp). The
                    // GAP before this section on the Perfetto timeline (from the
                    // app_launch_tap slice) is the Channel hop latency.
                    LaunchTrace.section(LaunchTrace.Names.DISPATCH) {
                        val action = AppLaunchAction.decide(
                            currentDestinationId = navController?.currentDestination?.id,
                            drawerDestinationId = R.id.appDrawerFragment,
                            app = event.app,
                        )

                        if (BuildConfig.DEBUG) {
                            Timber.d(
                                "[MAIN] Processing LaunchApp for: ${event.app.displayName}, " +
                                    "action: ${action::class.simpleName}",
                            )
                        }

                        if (action is AppLaunchAction.PopThenLaunch) {
                            try {
                                navController?.popBackStack()
                                if (BuildConfig.DEBUG) {
                                    Timber.d("[MAIN] Drawer closed")
                                }
                            } catch (e: Throwable) {
                                // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
                                // Inner catch kept (Expected error, four-
                                // category frame): scoped log "Error popping
                                // back stack" preserves diagnostic context
                                // that the outer Catchall would flatten to
                                // "Error in handleSpecificEvent". Even if
                                // popBackStack fails, launchApp below still
                                // runs (correct user-visible behaviour).
                                TimberWrapper.silentError(e, "[MAIN] Error popping back stack")
                            }
                        }

                        launchApp(action.app)
                    }
                }

                is UiEvent.OpenWallpaperPicker -> {
                    try {
                        WallpaperImagePicker.launch(wallpaperPickerLauncher)
                    } catch (e: Throwable) {
                        // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
                        // Inner catch kept (Expected error, four-category
                        // frame): same shape as OpenCalendar — outer
                        // Catchall would lose the user-visible Toast
                        // recovery.
                        TimberWrapper.silentError(e, "Error launching wallpaper picker")
                        showToastSafe(R.string.error_generic)
                    }
                }

                is UiEvent.EnterWallpaperEditMode -> {
                    viewModel.onSetWallpaperEditMode(true)
                }

                is UiEvent.ExitWallpaperEditMode -> {
                    viewModel.onSetWallpaperEditMode(false)
                }

                is UiEvent.ShowToast,
                is UiEvent.ShowToastFromString,
                is UiEvent.NavigateUp,
                is UiEvent.RefreshAppDrawer -> {
                    // Intentionally empty - handled in BaseActivity
                }

                is UiEvent.FavoritesOrderChanged -> {
                    // Emitted only by FavoritesSortViewModel (a separate
                    // screen). MainActivity never receives it; declared here
                    // only so the sealed `when` stays exhaustive.
                }
            }
        } catch (e: Throwable) {
            // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
            // Outer Catchall kept (HOME-Activity-resilience boundary,
            // four-category frame): event dispatch must not crash the
            // launcher. Anything reaching this catch is a programmer
            // error in one of the branches — silentError makes it loud
            // in DEBUG (Rule 9), RELEASE logs and continues. The outer
            // catch is also why several inner branches can drop their
            // own catches: the recovery is identical here.
            TimberWrapper.silentError(e, "[MAIN] Error in handleSpecificEvent")
        }
    }

    private fun launchApp(appInfo: AppInfo) {
        if (BuildConfig.DEBUG) {
            Timber.d("[LAUNCH] Starting launch for: ${appInfo.displayName}")
        }

        // The LauncherApps / ActivityOptions runtime glue now lives behind
        // [AppLauncher]; this method only reacts to the typed result. The
        // former inline triple-catch moved into AppLauncherImpl.
        val result = appLauncher.launch(this, appInfo)
        when (result) {
            is AppLaunchResult.Launched -> {
                if (BuildConfig.DEBUG) {
                    Timber.d("[LAUNCH] Success: ${appInfo.displayName}")
                }
            }
            is AppLaunchResult.ComponentGone -> {
                TimberWrapper.silentError("[LAUNCH] Component gone: ${appInfo.displayName}")
                showToastSafe(R.string.error_app_not_available)
            }
            is AppLaunchResult.PermissionDenied -> {
                TimberWrapper.silentError("[LAUNCH] Permission denied: ${appInfo.displayName}")
                showToastSafe(R.string.error_generic)
            }
            is AppLaunchResult.Failed -> {
                TimberWrapper.silentError(result.cause, "[LAUNCH] Exception: ${appInfo.displayName}")
                showToastSafe(R.string.error_generic)
            }
        }

        // A failed launch of a resolved component is the definitive "this app
        // is gone" signal — more reliable than any cache inspection. Kick an
        // app-list refresh so the load-time orphan sweep (ObserveInstalled-
        // AppsUseCase) reconciles any stale assignment pointing at it (swipe /
        // favorite / hidden / custom name), TODO §25. Gated on the typed
        // result: only ComponentGone reconciles — PermissionDenied and other
        // failures don't imply an uninstall (see AppLaunchResult.shouldReconcile,
        // pinned by AppLaunchResultTest).
        if (result.shouldReconcile) {
            viewModel.refreshInstalledApps()
        }
    }

    /**
     * Double-tap on the home screen: read the clipboard and do the obvious
     * thing with it — open a URL, dial a number (DIAL only, never CALL),
     * compose an email, or fall back to a web search. Classification is the
     * pure [ClipboardActionResolver]; here we only map the result to an Intent
     * and launch it via startActivitySafely. Empty clipboard → a short toast.
     *
     * The read runs on [Dispatchers.IO]: for a URI-backed clip (a file copied
     * from a gallery / cloud provider) `coerceToText` opens the item through
     * ContentResolver and reads the stream, i.e. a blocking binder call into a
     * possibly cold foreign provider — not something to do on the UI thread of
     * the HOME activity.
     */
    private fun performClipboardAction() {
        lifecycleScope.launch(mainActivityExceptionHandler) {
            // Both the read AND the classification run off the main thread:
            // coerceToText can do a blocking ContentResolver round-trip, and
            // the resolver runs several regexes over up to 8 KB. This is the
            // HOME activity — a stall here is a frozen home screen.
            val read = withContext(Dispatchers.IO) { readClipboard() }

            // Never act on a clip the source app marked sensitive (password
            // managers set this): the WebSearch fallback would otherwise hand
            // a password to the user's search provider.
            if (read.isSensitive) {
                showToastSafe(R.string.clipboard_sensitive)
                return@launch
            }

            val action = withContext(Dispatchers.Default) {
                ClipboardActionResolver.resolve(read.text)
            }
            if (action == null) {
                showToastSafe(R.string.clipboard_empty)
                return@launch
            }

            showClipboardActionDialog(action)
        }
    }

    /**
     * Builds the Intent for a [ClipboardLaunchSpec]. Pure glue: every decision
     * — action, URI shape, fallback, label — was already made in [launchSpec],
     * where a JVM test can reach it.
     */
    private fun intentFor(spec: ClipboardLaunchSpec): Intent {
        val intent = Intent(spec.intentAction)
        // normalizeScheme(): IntentFilter matches schemes CASE-SENSITIVELY, so
        // an autocapitalised `Https://…` resolves to no browser without this.
        spec.dataUri?.let { intent.data = it.toUri().normalizeScheme() }
        spec.queryExtra?.let { intent.putExtra(SearchManager.QUERY, it) }
        return intent
    }

    private fun fallbackIntentFor(spec: ClipboardLaunchSpec): Intent? =
        spec.fallbackUri?.let { Intent(Intent.ACTION_VIEW, it.toUri()) }

    /**
     * Shows one line of the copied text plus the proposed action, so nothing
     * is launched — and nothing leaves the device — before the user has seen
     * what it is. That is also what makes the classifier's unavoidable
     * ambiguity harmless (`install.sh` is both a filename and a valid domain).
     *
     * "Share" is offered alongside because it is the one action that can never
     * be wrong for arbitrary text, and unlike ACTION_WEB_SEARCH it always
     * resolves. There is deliberately no Cancel button: tapping outside
     * dismisses, which is the lighter gesture for the common "not now" case.
     */
    private fun showClipboardActionDialog(action: ClipboardAction) {
        val spec = action.launchSpec()

        // Inflate against the DIALOG theme, not the Activity's. The layout's
        // ?attr/colorOnSurface would otherwise resolve against AppTheme, which
        // follows system night mode, while the dialog surface follows wallpaper
        // luminance — light wallpaper plus system dark mode then paints the
        // preview near-white on near-white. Same fix as showRecentAppsDialog.
        val dialogStyle = wallpaperAwareDialogStyle()
        val themedInflater = LayoutInflater.from(ContextThemeWrapper(this, dialogStyle))
        val previewView = themedInflater.inflate(R.layout.dialog_clipboard_action, null)
        previewView.findViewById<TextView>(R.id.clipboardPreview).text = action.displayText

        showDialog(
            MaterialAlertDialogBuilder(this, dialogStyle)
                .setTitle(R.string.clipboard_action_title)
                .setView(previewView)
                .setNeutralButton(R.string.clipboard_action_share) { _, _ ->
                    runDialogAction("Error sharing clipboard text") {
                        shareClipboardText(action.displayText)
                    }
                }
                .setPositiveButton(spec.confirmLabel) { _, _ ->
                    runDialogAction("Error launching clipboard action") {
                        startActivitySafely(intentFor(spec), fallbackIntentFor(spec))
                    }
                },
        )
    }

    /**
     * Runs a dialog button's body behind a catch.
     *
     * A `DialogInterface.OnClickListener` is a system callback: it runs outside
     * every coroutine safety net this Activity has, so an escaping throw takes
     * the launcher down. `showCustomizationOptionsDialog` has guarded its item
     * listener this way from the start; this is the same boundary, extracted so
     * the two cannot drift.
     */
    private inline fun runDialogAction(message: String, body: () -> Unit) {
        try {
            body()
        } catch (e: Throwable) {
            // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
            // Catch kept (HOME-Activity-resilience boundary, four-category
            // frame): see the KDoc above — a system click callback has no
            // outer Catchall, so an unhandled throw here crashes the launcher.
            TimberWrapper.silentError(e, message)
        }
    }

    private fun shareClipboardText(text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivitySafely(
            Intent.createChooser(send, getString(R.string.clipboard_action_share)),
        )
    }

    private fun readClipboard(): ClipboardRead =
        ClipboardReader.read(
            clip = getSystemService(ClipboardManager::class.java)?.primaryClip,
            isSensitive = {
                it.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            },
            // coerceToText hands back item.text outright for a plain-text clip,
            // but reads a URI-backed one to EOF even though resolve() caps at
            // 8 KB. Knowingly accepted — ACCEPTED_LIMITATIONS.md §2 explains why
            // the obvious MIME pre-check would not help.
            coerceFirstItem = {
                it.takeIf { clip -> clip.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
            },
        )

    /**
     * Recent-apps dialog for the swipe-down gesture. Anchored to the top of
     * the screen with a slide-from-top animation so it reads as "pulled down".
     * Tapping an entry routes through [LauncherViewModel.onAppClicked], which
     * launches AND records the launch — so the recency list updates itself.
     * An empty list (fresh install / after a usage reset) shows a short toast
     * instead of an empty dialog.
     */
    private fun showRecentAppsDialog(apps: List<AppInfo>) {
        if (isFinishing || isDestroyed) return
        if (apps.isEmpty()) {
            showToastSafe(R.string.recent_apps_empty)
            return
        }
        val names = apps.map { it.displayName }
        // Custom row layout (item_recent_app) so font size + spacing match the
        // app drawer. The adapter MUST inflate rows with the wallpaper-aware
        // dialog theme (same overlay the dialog window uses) — otherwise
        // item_recent_app's ?attr/colorOnSurface resolves against the Activity
        // theme (system day/night) and the text can go invisible when wallpaper
        // luminance and system mode diverge.
        val rowContext = ContextThemeWrapper(this, wallpaperAwareDialogStyle())
        val adapter = ArrayAdapter(rowContext, R.layout.item_recent_app, R.id.recent_app_name, names)
        val dialog = MaterialAlertDialogBuilder(this, wallpaperAwareDialogStyle())
            .setTitle(getString(R.string.recent_apps_title))
            .setAdapter(adapter) { _, which ->
                runDialogAction("Error launching recent app") { viewModel.onAppClicked(apps[which]) }
            }
            // Same reference-clearing reason as showDialog; this one assigns
            // currentDialog directly because it customizes the window.
            .setOnDismissListener { if (currentDialog == it) currentDialog = null }
            .create()
        dialog.window?.let { w ->
            w.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            w.setWindowAnimations(R.style.DialogAnimationFromTop)
            // Dim the home screen behind the dialog.
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.55f)
            // Blur behind (Android 12+; always compiled in on this min=36 app).
            // isCrossWindowBlurEnabled is false when the system disabled blur
            // (power saver / "reduce transparency") — then it's a no-op and the
            // dim alone carries the effect.
            if (windowManager.isCrossWindowBlurEnabled) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.attributes = w.attributes.apply {
                    blurBehindRadius = (32 * resources.displayMetrics.density).toInt()
                }
            }
        }
        currentDialog?.dismiss()
        currentDialog = dialog
        dialog.show()
    }

    private fun showDialog(builder: MaterialAlertDialogBuilder) {
        // Structural teardown-race guard: prevents BadTokenException
        // from showing a dialog on a finishing/destroyed Activity. With
        // the guard, no post-hoc catch is needed.
        if (isFinishing || isDestroyed) return
        currentDialog?.dismiss()
        // Clear the reference on dismiss, so a dismissed dialog — and whatever
        // its button lambdas captured, up to 8 KB of clipboard text in the
        // clipboard case — is not retained until the next dialog happens to
        // open. This Activity is HOME, so onDestroy effectively never runs.
        // Callers that need their own dismiss logic assign currentDialog
        // directly instead of going through here.
        builder.setOnDismissListener { if (currentDialog == it) currentDialog = null }
        currentDialog = builder.show()
    }

    /**
     * Picks the wallpaper-following alert-dialog theme overlay
     * (CustomAlertDialog.Light vs .Dark) based on the cached
     * [currentSurfaceClassification]. Home-anchored dialogs need this
     * because they hover directly over the wallpaper — a dark dialog
     * on a white wallpaper (or vice versa) reads as a theming bug.
     * Settings-screen / Onboarding dialogs do NOT use this helper;
     * they keep the activity-theme default via `alertDialogTheme`.
     */
    private fun wallpaperAwareDialogStyle(): Int = when (currentSurfaceClassification) {
        LuminanceClassification.LIGHT -> R.style.CustomAlertDialog_Light
        LuminanceClassification.DARK -> R.style.CustomAlertDialog_Dark
    }

    private fun showCustomizationOptionsDialog() {
        currentDialog?.dismiss()
        currentDialog = null

        val model = CustomizationDialogModel.build(
            hasWallpaper = viewModel.wallpaperState.value.hasWallpaper,
            isWallpaperEditMode = viewModel.isWallpaperEditMode.value,
        )

        // The dialog is suppressed while in edit mode — the inline
        // buttons are visible there anyway.
        val visible = model as? CustomizationDialogModel.Visible ?: return

        val labels = visible.options.map { resolveCustomizationLabel(it) }
        val actions = visible.options.map { resolveCustomizationAction(it) }

        // Structural teardown-race guard before .show().
        if (isFinishing || isDestroyed) return

        currentDialog = MaterialAlertDialogBuilder(this, wallpaperAwareDialogStyle())
            .setTitle(getString(R.string.customize_title))
            .setItems(labels.toTypedArray()) { _, which ->
                try {
                    actions.getOrNull(which)?.invoke()
                } catch (e: Throwable) {
                    // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
                    // Inner catch kept (HOME-Activity-resilience boundary,
                    // four-category frame): system click-listener
                    // callback runs outside any outer Catchall, so an
                    // unhandled throw here would crash the launcher.
                    TimberWrapper.silentError(e, "Error handling dialog selection")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                if (currentDialog == it) {
                    currentDialog = null
                }
            }
            .show()
    }

    private fun resolveCustomizationLabel(option: CustomizationOption): String =
        getString(
            when (option) {
                CustomizationOption.ChooseWallpaper -> R.string.wallpaper_choose
                CustomizationOption.EditWallpaper -> R.string.wallpaper_edit_mode
                CustomizationOption.RemoveWallpaper -> R.string.wallpaper_remove
                CustomizationOption.CustomizeColors -> R.string.customize_colors_and_shadow
                CustomizationOption.CustomizeLayout -> R.string.customize_layout_title
                CustomizationOption.MoreSettings -> R.string.more_settings
            },
        )

    private fun resolveCustomizationAction(option: CustomizationOption): () -> Unit =
        when (option) {
            CustomizationOption.ChooseWallpaper -> ::launchWallpaperPicker
            CustomizationOption.EditWallpaper -> ::enterWallpaperEditMode
            CustomizationOption.RemoveWallpaper -> ::confirmRemoveWallpaper
            CustomizationOption.CustomizeColors -> ::showColorCustomizationDialog
            CustomizationOption.CustomizeLayout -> ::showLayoutCustomizationDialog
            CustomizationOption.MoreSettings -> ::openSettingsActivity
        }

    private fun launchWallpaperPicker() {
        WallpaperImagePicker.launch(wallpaperPickerLauncher)
    }

    private fun enterWallpaperEditMode() {
        viewModel.onSetWallpaperEditMode(true)
    }

    private fun confirmRemoveWallpaper() {
        // Route through showDialog so the dialog is tracked in currentDialog
        // and dismissed in onDestroy — otherwise a config change / finish while
        // it is open leaks its window. showDialog also carries the
        // finishing/destroyed teardown-race guard.
        val builder = MaterialAlertDialogBuilder(this, wallpaperAwareDialogStyle())
            .setTitle(getString(R.string.wallpaper_remove))
            .setMessage(getString(R.string.wallpaper_remove_confirm))
            .setPositiveButton(getString(R.string.wallpaper_remove_yes)) { _, _ ->
                viewModel.onClearWallpaper()
            }
            .setNegativeButton(getString(R.string.cancel), null)
        showDialog(builder)
    }

    /**
     * Offers to reset the wallpaper scrim after the wallpaper image changed while a
     * non-zero dim is set (gated in [LauncherViewModel.offerScrimResetEvents]). An
     * offer, never a silent override: the user may deliberately keep the dim.
     * Routed through [showDialog] for the same window-teardown tracking as the
     * remove-wallpaper confirm.
     */
    private fun offerScrimReset(currentAlpha: Float) {
        val percent = (currentAlpha * 100f).roundToInt()
        val builder = MaterialAlertDialogBuilder(this, wallpaperAwareDialogStyle())
            .setTitle(getString(R.string.scrim_reset_offer_title))
            .setMessage(getString(R.string.scrim_reset_offer_message, percent))
            .setPositiveButton(getString(R.string.scrim_reset_offer_confirm)) { _, _ ->
                viewModel.onSetWallpaperScrimAlpha(0f)
            }
            .setNegativeButton(getString(R.string.scrim_reset_offer_keep), null)
        showDialog(builder)
    }

    private fun showColorCustomizationDialog() {
        // Structural teardown-race guard. FragmentManager.show() throws
        // IllegalStateException ("Can not perform this action after
        // onSaveInstanceState") on a finishing/destroyed Activity.
        if (isFinishing || isDestroyed) return
        ColorCustomizationDialogFragment().show(supportFragmentManager, "ColorCustomizationDialog")
    }

    private fun showLayoutCustomizationDialog() {
        if (isFinishing || isDestroyed) return
        LayoutCustomizationDialogFragment().show(supportFragmentManager, "LayoutCustomizationDialog")
    }

    private fun openSettingsActivity() {
        // No try/catch: this is only called from the customization-
        // dialog click listener (system callback), which already has
        // its own catch around the .invoke() call. Adding another here
        // would be doubled defence.
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun startActivitySafely(intent: Intent, fallbackIntent: Intent? = null) {
        try {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Throwable) {
            // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
            // Catch kept (Expected error, four-category frame):
            // startActivity throws ActivityNotFoundException for
            // optional system intents (clock app, calendar, battery
            // settings) that may not exist on every ROM. The fallback-
            // intent branch + final Toast give the user a recovery
            // signal instead of a silent no-op.
            //
            // ORDER MATTERS: recovery runs before any silentError. silentError
            // rethrows in DEBUG (Rule 9), so logging first made the whole
            // fallback branch below dead code in debug builds and let the throw
            // escape into whatever system callback invoked us.
            if (fallbackIntent != null) {
                try {
                    fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(fallbackIntent)
                    // Recovered — a missing optional handler on some ROM is not
                    // a bug to be loud about, so no silentError on this path.
                    Timber.w(e, "Primary intent failed, fallback succeeded: %s", intent)
                    return
                } catch (fallbackError: Throwable) {
                    // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
                    // Inner catch kept (Expected error, four-category
                    // frame): even the fallback intent can fail. The
                    // Toast below then handles the user-visible recovery.
                    Timber.w(fallbackError, getString(R.string.error_fallback_intent_failed))
                }
            }

            showToastSafe(R.string.error_activity_not_found)
            TimberWrapper.silentError(e, getString(R.string.error_starting_intent, intent.toString()))
        }
    }

}
