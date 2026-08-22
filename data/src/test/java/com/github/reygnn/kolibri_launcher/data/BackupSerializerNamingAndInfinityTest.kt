package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.coerceInSafe
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SSOT pin for the recurring **camelCase / snake_case + Infinity "false
 * alarm"** in the backup pipeline.
 *
 * ## Why this file exists
 *
 * Three separate audits have reported the same non-bug:
 *
 *  - **AUDIT-3 #3** — "strict fallback looks up snake_case only" → fixed
 *    (commit `b67c5b8`), then confirmed fixed.
 *  - **AUDIT-8 §#1** — re-reported, confirmed already fixed.
 *  - **AUDIT-9 #1** — re-reported again: "`validateJsonTypes` only checks
 *    snake_case keys for numeric fields, but the app writes camelCase → the
 *    Infinity/type validation never fires on the app's own output." →
 *    **REFUTED** (see AUDIT-9.md #1).
 *
 * The *observation* is accurate — [BackupSerializer.validateJsonTypes] lists
 * snake_case scalar keys only, and the app writes camelCase, so that specific
 * up-front check does not reject a camelCase `Infinity`. But it is not a
 * defect: the value is stopped by two *other* layers. The assertions below
 * lock down exactly which, so a future auditor lands here instead of
 * re-filing the same finding.
 *
 * ## The real guarantee chain (each `@Test` pins one link)
 *
 *  1. **The app can never write a non-finite float.** kotlinx is configured
 *     with `allowSpecialFloatingPointValues = false`, so `encodeToJsonString`
 *     *throws* on `Infinity`/`NaN`. There is no app-produced backup carrying
 *     a non-finite value for `validateJsonTypes` to "miss" in the first place.
 *  2. **The app writes camelCase.** That is why the snake_case scalar list in
 *     `validateJsonTypes` is legacy-only defense-in-depth, not the live path.
 *  3. **A non-finite literal makes the serializer reject the WHOLE backup.**
 *     Even hand-crafted, a camelCase (or snake_case) overflow literal such as
 *     `1e309` makes [BackupSerializer.parseBackupData] return `null`: kotlinx
 *     decode throws on the non-finite value and the org.json strict fallback
 *     also fails, so the whole import is rejected (→ `ImportResult.Error`,
 *     existing settings untouched). `validateJsonTypes` passing it is
 *     irrelevant — the decode stage is the gate. Infinity never reaches
 *     [BackupData].
 *  4. **Finite-but-out-of-range values are clamped at the import boundary.**
 *     A finite value the serializer *does* pass through (e.g. `9999.0`) is
 *     clamped by [coerceInSafe] at `BackupDataAssembler.kt:295`
 *     (`+Inf→max`, `-Inf→min`, `NaN→min`, else `coerceIn`). The serializer
 *     deliberately does not clamp range — that is this boundary's job.
 *  5. **Legacy snake_case still reads.** `@JsonNames` provides read-only
 *     aliases so backups written before the camelCase switch still import.
 *     This is the *entire* reason snake_case appears in the strict path.
 *
 * End-to-end persist-is-safe behaviour (import a `1e309` / `NaN` backup,
 * assert no corruption) is pinned at the repository layer by
 * `BackupRepositoryImplDoomsdayTest` (`alien - Infinity float value`,
 * `alien - NaN float value`) and `...SecurityTest`. This file is the
 * canonical *why*; those are the end-to-end proof.
 *
 * Robolectric: [BackupSerializer.parseBackupData] runs its strict pass over
 * `org.json`, an Android-provided class.
 */
@RunWith(RobolectricTestRunner::class)
class BackupSerializerNamingAndInfinityTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun checkEnvironment() {
            assumeTrue("Skipping Robolectric tests in GitHub CI", System.getenv("CI") == null)
        }

        /** Minimal current-version, camelCase backup with one injected scalar. */
        private fun backupJson(settingsBody: String): String = """
            {
              "version": "1.0.0",
              "timestamp": 0,
              "appVersion": "",
              "settings": { $settingsBody }
            }
        """.trimIndent()
    }

    private val serializer = BackupSerializer()

    // ---- Link 1: the app can never emit a non-finite float --------------

    @Test(expected = SerializationException::class)
    fun `encode rejects positive Infinity - app can never write it`() {
        // kotlinx allowSpecialFloatingPointValues=false → encode throws.
        // The linchpin: no app-written backup can carry Infinity, so
        // validateJsonTypes not catching it on the app's own output is by
        // construction, not an oversight.
        serializer.encodeToJsonString(
            BackupData(settings = LauncherSettings(layoutScale = Float.POSITIVE_INFINITY)),
        )
    }

    @Test(expected = SerializationException::class)
    fun `encode rejects NaN - app can never write it`() {
        serializer.encodeToJsonString(
            BackupData(settings = LauncherSettings(wallpaperScale = Float.NaN)),
        )
    }

    // ---- Link 2: the app writes camelCase -------------------------------

    @Test
    fun `encode writes camelCase scalar keys, never snake_case`() {
        val json = serializer.encodeToJsonString(
            BackupData(settings = LauncherSettings(textColor = -222, layoutScale = 1.25f)),
        )

        assertThat(json).contains("\"textColor\":")
        assertThat(json).contains("\"layoutScale\":")
        assertThat(json).doesNotContain("\"text_color\":")
        assertThat(json).doesNotContain("\"layout_scale\":")
    }

    // ---- Link 3: a non-finite literal rejects the whole backup ----------

    @Test
    fun `parse rejects a camelCase non-finite literal by returning null`() {
        // camelCase "layoutScale" is exactly the key validateJsonTypes does
        // NOT check. Irrelevant: the non-finite value (1e309 > Double.MAX_VALUE
        // → Infinity) makes decode throw and the strict fallback fail, so the
        // whole backup is rejected. Infinity never reaches the model.
        assertThat(serializer.parseBackupData(backupJson("\"layoutScale\": 1e309"))).isNull()
    }

    @Test
    fun `parse rejects a camelCase negative-Infinity literal by returning null`() {
        assertThat(serializer.parseBackupData(backupJson("\"wallpaperScale\": -1e309"))).isNull()
    }

    @Test
    fun `parse rejects a snake_case non-finite literal too`() {
        // The rejection is on the value, not the key style — snake_case is no
        // escape hatch for Infinity either.
        assertThat(serializer.parseBackupData(backupJson("\"layout_scale\": 1e309"))).isNull()
    }

    // ---- Link 4: finite range is clamped downstream, not by the parser --

    @Test
    fun `parse passes a finite out-of-range value through unchanged - clamping is the import boundary's job`() {
        // The serializer does NOT clamp range; it faithfully returns 9999.0.
        val parsed = serializer.parseBackupData(backupJson("\"layoutScale\": 9999.0"))

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.settings.layoutScale).isEqualTo(9999.0f)
    }

    @Test
    fun `coerceInSafe is the actual range clamp applied at import`() {
        // BackupDataAssembler.kt:295 wraps every restored scalar in this.
        val min = AppConstants.LAYOUT_SCALE_MIN
        val max = AppConstants.LAYOUT_SCALE_MAX

        assertThat(9999.0f.coerceInSafe(min, max)).isEqualTo(max)
        assertThat(Float.POSITIVE_INFINITY.coerceInSafe(min, max)).isEqualTo(max)
        assertThat(Float.NEGATIVE_INFINITY.coerceInSafe(min, max)).isEqualTo(min)
        assertThat(Float.NaN.coerceInSafe(min, max)).isEqualTo(min)
    }

    // ---- Link 5: legacy snake_case still reads --------------------------

    @Test
    fun `parse still accepts legacy snake_case scalar keys via JsonNames`() {
        // The whole reason snake_case appears in the strict path: pre-camelCase
        // backups must still import. A finite snake_case value round-trips.
        val parsed = serializer.parseBackupData(backupJson("\"layout_scale\": 1.5"))

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.settings.layoutScale).isEqualTo(1.5f)
    }
}
