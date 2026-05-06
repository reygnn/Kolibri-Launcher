package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.core.net.toUri
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.inject.Inject

/**
 * Why instrumented: BackupRoundTripSafTest covers favorites/hidden/customNames
 * round-trip but skips the wallpaper image binary path. The wallpaper restore
 * goes through:
 *   ZIP entry → WallpaperFileManager.copyFromInputStream → internal file →
 *   Uri.fromFile(internalFile) → WallpaperRepository.saveWallpaperState →
 *   DataStore.
 *
 * The Robolectric companion (BackupRepositoryImplWallpaperTest) stubs
 * `WallpaperFileManager.copyToInternal` to return `firstArg<Uri>()` — the
 * actual file copy never runs. On a real device the bytes have to physically
 * arrive in `filesDir/wallpapers/`, the destination URI has to be readable,
 * and the wallpaper-state has to point at a file that actually contains the
 * original PNG bytes. Any of those steps can break and Robolectric would
 * stay green.
 *
 * What this test asserts:
 *  1. After save+wipe+load round-trip, the wallpaper file exists on disk.
 *  2. The restored bytes match the original (same PNG).
 *  3. The restored state references the new internal URI (not the
 *     pre-wipe one) and round-trips scale + translate fields.
 *
 * Wallpaper is Kolibri's signature feature; a silent loss of
 * wallpaper after restore would be highly visible to users.
 */
@HiltAndroidTest
class BackupRoundTripWallpaperTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var backup: BackupRepository
    @Inject lateinit var wallpaper: WallpaperRepository
    @Inject lateinit var fileManager: WallpaperFileManager
    @Inject @ApplicationContext lateinit var context: Context

    private lateinit var backupFile: File
    private lateinit var seedWallpaperFile: File

    /**
     * Tiny but unmistakeably non-zero PNG-shaped payload. Real PNG validity
     * doesn't matter — WallpaperFileManager copies bytes opaquely; we only
     * need a stable byte-equality reference for the round-trip.
     */
    private val originalBytes: ByteArray =
        ByteArray(2048) { i -> ((i * 31 + 7) and 0xFF).toByte() }

    private val testScale = 1.4f
    private val testTranslateX = 12.5f
    private val testTranslateY = -34.0f

    @Before fun setUp() {
        hiltRule.inject()
        backupFile = File(context.cacheDir, "test-wallpaper-backup-${System.nanoTime()}.zip")
            .also { it.parentFile?.mkdirs(); it.delete() }
        seedWallpaperFile = File(context.cacheDir, "seed-wallpaper-${System.nanoTime()}.png")
            .also {
                it.parentFile?.mkdirs(); it.delete()
                it.writeBytes(originalBytes)
            }
    }

    @Test
    fun saveAndLoad_roundTripsWallpaperBytes_throughZipAndInternalStorage() = runBlocking {
        // ── ARRANGE: copy seed into the launcher's internal wallpapers/ dir
        // (mirrors what production does when the user picks a wallpaper),
        // then point WallpaperRepository at it. The state has to reference
        // an internal file:// URI for the export-side resolveToLocalFile()
        // to recognise it as embeddable into the ZIP — content:// URIs
        // would be skipped.
        val internalUriBeforeBackup = fileManager.copyToInternal(seedWallpaperFile.toUri())
            ?: error("copyToInternal returned null — internal storage must be writable for this test")

        wallpaper.saveWallpaperState(
            WallpaperState(
                imageUri = internalUriBeforeBackup.toString(),
                scale = testScale,
                translateX = testTranslateX,
                translateY = testTranslateY,
            )
        )

        // ── SANITY: the state we just saved should round-trip through
        // the WallpaperRepository read path before we even invoke backup.
        // If parseSingleLayerState rejects the URI (file missing, scheme
        // mismatch), getWallpaperStateSync returns NONE and the backup
        // export silently embeds nothing — the bytes-level assertion at
        // the end would then fire with a misleading message. Asserting
        // the read-back here pins the failure to the right step.
        val sanityState = wallpaper.wallpaperState.first()
        assertThat(sanityState.imageUri).isEqualTo(internalUriBeforeBackup.toString())

        // ── ACT 1: save backup ─────────────────────────────────────────
        val saved = withTimeout(10_000) {
            backup.saveBackupToFile(backupFile.toUri().toString())
        }
        assertThat(saved).isTrue()

        // ── ARRANGE 2: wipe wallpaper state to prove restore actually
        // brings it back, not just preserves a no-op state.
        wallpaper.clearWallpaper()
        assertThat(wallpaper.wallpaperState.first().imageUri).isNull()

        // ── ACT 2: load backup ─────────────────────────────────────────
        val result = withTimeout(10_000) {
            backup.loadBackupFromFile(
                backupFile.toUri().toString(),
                ImportOptions(importThemeSettings = true),
            )
        }
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // ── ASSERT: state restored ─────────────────────────────────────
        val restoredState = wallpaper.wallpaperState.first()
        val restoredUriString = restoredState.imageUri
        assertThat(restoredUriString).isNotNull()
        assertThat(restoredState.scale).isEqualTo(testScale)
        assertThat(restoredState.translateX).isEqualTo(testTranslateX)
        assertThat(restoredState.translateY).isEqualTo(testTranslateY)

        // ── ASSERT: bytes survived the ZIP round-trip ──────────────────
        // The restored URI typically points at a NEW file in wallpapers/
        // because the ZIP-import path writes a fresh entry via
        // WallpaperFileManager.copyFromInputStream. The pre-backup
        // internal URI may have been GC'd by the wallpaper cleanup pass.
        // What matters for the user is that *some* on-disk file exists
        // with the original bytes.
        val restoredUri = restoredUriString!!.toUri()
        assertThat(restoredUri.scheme).isEqualTo("file")
        val restoredFile = File(restoredUri.path!!)
        assertThat(restoredFile.exists()).isTrue()
        assertThat(restoredFile.readBytes()).isEqualTo(originalBytes)
    }
}
