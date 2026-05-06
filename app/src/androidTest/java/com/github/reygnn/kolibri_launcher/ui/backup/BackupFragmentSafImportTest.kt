package com.github.reygnn.kolibri_launcher.ui.backup

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.github.reygnn.kolibri_launcher.HiltTestActivity
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.data.CrashReportConsentStore
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.support.awaitUntil
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.inject.Inject

/**
 * Why instrumented: BackupFragment's import path orchestrates four moving
 * pieces — `ActivityResultContracts.OpenDocument()` SAF launch, async
 * `previewBackup` flow, `MaterialAlertDialogBuilder` checkboxes, and the
 * `viewModel.importBackup` round-trip. None of these survive Robolectric
 * cleanly: SAF goes through real ATM, the preview is a `WhileSubscribed`
 * StateFlow, and AlertDialog windows need a real WindowManager to attach.
 *
 * [BackupRoundTripSafTest] covers the data layer (load+save through real
 * ContentResolver) but skips the UI orchestration. This test covers the
 * UI side: button click → SAF intent → preview → dialog → confirm →
 * persistence.
 *
 * Setup pattern:
 *  - Pre-stage a real ZIP backup file in cacheDir using
 *    `BackupRepository.saveBackupToFile` against seeded repository state.
 *    This keeps the test self-contained — no committed test fixtures.
 *  - `Intents.intending` intercepts the OpenDocument SAF intent and returns
 *    the staged URI. The actual SAF picker UI is system code we don't own
 *    and shouldn't drive from instrumentation.
 *  - Host BackupFragment in HiltTestActivity (the `src/debug/`-only
 *    @AndroidEntryPoint shim that exists for exactly this kind of
 *    fragment-hosting test). HiltTestActivity sits in `src/debug/`, which
 *    androidTest also exercises, so the import resolves at compile time.
 *
 * What this test asserts:
 *  1. Clicking buttonImportBackup fires the SAF intent (verified by
 *     `Intents.intended`).
 *  2. The intercepted SAF result drives previewBackup → checkbox dialog
 *     becomes visible.
 *  3. Clicking the Import positive button persists the backup data,
 *     observable via FavoritesRepository state delta.
 */
@HiltAndroidTest
class BackupFragmentSafImportTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var backup: BackupRepository
    @Inject lateinit var favorites: FavoritesRepository
    @Inject lateinit var hidden: HiddenAppsRepository
    @Inject lateinit var customNames: CustomNamesRepository
    @Inject @ApplicationContext lateinit var context: Context

    private lateinit var stagedBackupFile: File
    private lateinit var stagedComponentA: String
    private lateinit var stagedComponentB: String

    @Before fun setUp() {
        hiltRule.inject()
        runBlocking {
            CrashReportConsentStore.saveConsent(context, consent = false)
        }

        // ── Resolve two real launcher components for the seed payload ─
        // (same portability rationale as BackupRoundTripSafTest §70-86:
        // synthetic strings get filtered out at import time, so the seed
        // has to use components the device actually reports as installed).
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = context.packageManager.queryIntentActivities(launcherIntent, 0)
        assumeTrue(
            "Need at least 2 launcher apps on the test device; got ${resolved.size}",
            resolved.size >= 2,
        )
        stagedComponentA = with(resolved[0].activityInfo) { "$packageName/$name" }
        stagedComponentB = with(resolved[1].activityInfo) { "$packageName/$name" }

        // ── Build the staged backup file by seeding the repos and
        // calling BackupRepository.saveBackupToFile. This is the same
        // path BackupRoundTripSafTest uses for save coverage; here we
        // only need the artifact — the load side is what we're testing.
        stagedBackupFile = File(context.cacheDir, "saf-import-seed-${System.nanoTime()}.zip")
            .also { it.parentFile?.mkdirs(); it.delete() }

        runBlocking {
            favorites.addFavoriteComponent(stagedComponentA)
            favorites.addFavoriteComponent(stagedComponentB)
            hidden.hideComponent(stagedComponentB)
            val saved = withTimeout(10_000) {
                backup.saveBackupToFile(stagedBackupFile.toUri().toString())
            }
            assertThat(saved).isTrue()
            // Wipe so the import-side delta is visible.
            favorites.purgeRepository()
            hidden.purgeRepository()
        }

        Intents.init()
    }

    @After fun tearDown() {
        Intents.release()
        try { stagedBackupFile.delete() } catch (_: Throwable) {}
    }

    @Test
    fun importButton_firesSafIntent_runsPreview_andPersistsAfterConfirm() {
        // ── Stub the SAF launcher: every ACTION_OPEN_DOCUMENT in this
        // test process is intercepted and resolved with our staged file.
        // The real system picker never opens — Espresso's intent stub is
        // resolved before the intent leaves the test process.
        val resultData = Intent().setData(stagedBackupFile.toUri())
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, resultData))

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val launchIntent = Intent(ctx, HiltTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<HiltTestActivity>(launchIntent).use {
            // ── Attach BackupFragment to the host. Same pattern as
            // AppDrawerFragmentRobolectricTest: HiltTestActivity is
            // @AndroidEntryPoint, so its fragment manager can resolve
            // the @AndroidEntryPoint BackupFragment cleanly.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<HiltTestActivity>()
                    .single()
                activity.supportFragmentManager.beginTransaction()
                    .replace(android.R.id.content, BackupFragment(), "backup")
                    .commitNow()
            }

            // ── Wait for the fragment's import button to render. Fragment
            // commit + view inflation is async w.r.t. Espresso's idle
            // model when triggered from runOnMainSync.
            awaitUntil(
                timeoutMs = 10_000,
                describe = { "buttonImportBackup never displayed" },
            ) {
                try {
                    onView(withId(R.id.buttonImportBackup)).check(matches(isDisplayed()))
                    true
                } catch (_: Throwable) {
                    false
                }
            }

            // ── ACT: click Import. SAF intent fires (intercepted),
            // result delivers our staged URI, BackupFragment runs
            // previewBackup, the dialog appears.
            onView(withId(R.id.buttonImportBackup)).perform(click())

            // ── ASSERT: the SAF intent actually went out. This is the
            // structural assertion for the SAF wiring; without it the
            // result-delivery would silently never happen and the
            // dialog poll below would just time out without explaining why.
            Intents.intended(hasAction(Intent.ACTION_OPEN_DOCUMENT))

            // ── WAIT: the favorites checkbox is unique to the import
            // dialog (no other UI in BackupFragment uses checkboxes), so
            // its visibility is a clean signal that the dialog inflated
            // and previewBackup completed.
            awaitUntil(
                timeoutMs = 10_000,
                describe = { "import options dialog never appeared" },
            ) {
                try {
                    onView(withId(R.id.checkboxImportFavorites))
                        .check(matches(isDisplayed()))
                    true
                } catch (_: Throwable) {
                    false
                }
            }

            // ── ACT: confirm. Default ImportOptions check the favorites
            // box, so a vanilla positive-button click imports favorites.
            // Match by text rather than withId because MaterialAlertDialog
            // resolves the positive button via android.R.id.button1 which
            // routes through ActivityInvoker — same desugar gap we hit in
            // CustomNamesActivityRenameTest.
            onView(allOf(withText(R.string.import_button), isDisplayed())).perform(click())

            // ── ASSERT: favorites repository now contains the staged
            // components. The delta over the wipe-empty baseline is what
            // proves the SAF→preview→dialog→importBackup chain ran end-to-end.
            awaitUntil(
                timeoutMs = 10_000,
                describe = { "favorites never restored from intercepted SAF backup" },
            ) {
                runBlocking {
                    val current = favorites.favoriteComponentsFlow.first()
                    current.contains(stagedComponentA) && current.contains(stagedComponentB)
                }
            }
        }

        // ── Final assertion outside the .use block: the snapshot is
        // stable and matches the seed exactly (no extras smuggled in).
        runBlocking {
            val finalFavorites = favorites.favoriteComponentsFlow.first()
            assertThat(finalFavorites).containsExactly(stagedComponentA, stagedComponentB)
        }
    }
}
