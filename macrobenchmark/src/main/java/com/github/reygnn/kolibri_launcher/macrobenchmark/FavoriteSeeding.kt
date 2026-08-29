package com.github.reygnn.kolibri_launcher.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/**
 * Completes first-run onboarding by SELECTING [count] favorites in the onboarding
 * app list, then dismissing the ACRA consent dialog that follows — so the measured
 * cold starts render the favorites path, not the empty-set fallback.
 *
 * WHY favorites must be seeded here: `connectedBenchmarkAndroidTest` reinstalls the
 * target fresh (it uninstalls on completion), wiping the favorites DataStore. Any
 * favorite configured by hand before the run is gone by the time it measures, so
 * every cold start would render the top-N fallback
 * ([com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase] →
 * `createFallbackApps`) — exactly the state where the provisional first-paint
 * (snapshot cache / live-label resolution) is BY DESIGN inactive, collapsing the
 * A/B difference this benchmark exists to show.
 *
 * WHY onboarding-native (not the drawer): onboarding is a single screen — a
 * searchable app list where tapping a row selects it as a favorite, then a Done
 * button, then the ACRA consent dialog directly after. Selecting here is the
 * natural user flow and avoids fighting the drawer's "context action returns to
 * Home" behaviour. Because the run always reinstalls fresh, onboarding is always
 * present at the first `setupBlock`, so this both onboards AND seeds in one pass —
 * it REPLACES [dismissFirstRunGatesIfPresent] for this benchmark (that helper taps
 * Done with an empty selection, which is what the other benchmarks want).
 *
 * Called once per test method from `setupBlock`, AFTER `startActivityAndWait`. The
 * selection persists to DataStore and survives the run's cold kills, so a
 * first-iteration seed is enough for all iterations.
 *
 * Resource ids are duplicated across the module boundary on purpose (same drift
 * note as [dismissFirstRunGatesIfPresent]); keep in sync with
 * `activity_onboarding.xml` (`all_apps_recycler_view`, `done_button`),
 * `item_app_selectable_text.xml` (`app_label`) and the AlertDialog negative button
 * if they ever change.
 */
fun MacrobenchmarkScope.completeOnboardingWithFavorites(targetPackage: String, count: Int) {
    if (!device.wait(Until.hasObject(By.res(targetPackage, ONBOARDING_LIST_ID)), SEED_TIMEOUT_MS)) {
        error(
            "completeOnboardingWithFavorites: onboarding app list ($ONBOARDING_LIST_ID) not shown — " +
                "a fresh install must land on onboarding",
        )
    }

    // Select `count` distinct apps: a row tap toggles its selection checkbox. Track
    // by label and skip already-selected so distinct apps are chosen; re-find each
    // round since selection can rebind the list.
    val selected = HashSet<String>()
    var guard = 0
    while (selected.size < count && guard++ < count * 8) {
        val row = device.findObjects(By.res(targetPackage, ONBOARDING_ITEM_LABEL_ID))
            .firstOrNull { it.text != null && it.text !in selected }
            ?: break
        selected.add(row.text)
        row.click()
        device.waitForIdle()
    }
    if (selected.size < count) {
        error("completeOnboardingWithFavorites: only selected ${selected.size}/$count favorites")
    }

    // Finish onboarding — persists the selection to the favorites DataStore.
    val done = device.wait(Until.findObject(By.res(targetPackage, ONBOARDING_DONE_ID)), SEED_TIMEOUT_MS)
        ?: error("completeOnboardingWithFavorites: done button ($ONBOARDING_DONE_ID) not found")
    done.click()

    // ACRA consent dialog (privacy-by-default) appears directly after Done — decline
    // via the standard AlertDialog negative button (`android:id/button2`), which is
    // locale-independent, so this holds on any device locale.
    device.wait(Until.findObject(By.res(ANDROID_PACKAGE, DIALOG_NEGATIVE_BUTTON_ID)), SEED_TIMEOUT_MS)
        ?.let { decline ->
            decline.click()
            device.wait(Until.gone(By.res(ANDROID_PACKAGE, DIALOG_NEGATIVE_BUTTON_ID)), SEED_GONE_TIMEOUT_MS)
        }
    device.waitForIdle()
}

/**
 * Tolerant variant of [completeOnboardingWithFavorites] for a benchmark whose class
 * has MORE THAN ONE `@Test` method (e.g. [StartupBenchmark]: None + Partial arms).
 * All methods of one `connectedBenchmarkAndroidTest` share a SINGLE install, so only
 * the method that runs FIRST meets onboarding — it seeds, and the seeded favorites
 * persist in DataStore for every later method (macrobenchmark does not reinstall or
 * clear data between methods).
 *
 * Returns true if it onboarded + seeded on this call, false if the onboarding list
 * was absent — which for a multi-method class is the normal "already seeded by an
 * earlier method" case, NOT a failure, so (unlike the strict variant) it does not
 * `error()`. Call it from each method's `setupBlock`; the first seeds, the rest
 * no-op and measure the persisted favorites.
 */
fun MacrobenchmarkScope.completeOnboardingWithFavoritesIfPresent(
    targetPackage: String,
    count: Int,
): Boolean {
    if (!device.wait(Until.hasObject(By.res(targetPackage, ONBOARDING_LIST_ID)), SEED_TIMEOUT_MS)) {
        return false // already past onboarding — favorites persisted from an earlier @Test method
    }
    completeOnboardingWithFavorites(targetPackage, count)
    return true
}

private const val ONBOARDING_LIST_ID = "all_apps_recycler_view" // activity_onboarding.xml
private const val ONBOARDING_ITEM_LABEL_ID = "app_label"        // item_app_selectable_text.xml
private const val ONBOARDING_DONE_ID = "done_button"            // activity_onboarding.xml
private const val ANDROID_PACKAGE = "android"
private const val DIALOG_NEGATIVE_BUTTON_ID = "button2"         // AlertDialog negative button
private const val SEED_TIMEOUT_MS = 5_000L
private const val SEED_GONE_TIMEOUT_MS = 5_000L
