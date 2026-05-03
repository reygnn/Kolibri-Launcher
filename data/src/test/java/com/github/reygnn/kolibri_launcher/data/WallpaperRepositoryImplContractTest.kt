package com.github.reygnn.kolibri_launcher.data

import android.net.Uri
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import io.mockk.every
import io.mockk.mockk
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [WallpaperRepositoryImpl].
 *
 * Setup-Details:
 *   - [FakeDataStore] als DataStore-Double.
 *   - [WallpaperFileManager] als relaxed-Mock mit `fileExists(any()) returns true`.
 *     Begründung: Der Manager prüft beim Lesen `wallpaperFileManager.fileExists(uri)`
 *     und returnt bei `false` `WallpaperState.NONE`. Ohne den Stub würde jeder
 *     Save → Read-Roundtrip nach dem `relaxed`-Default `Boolean.DEFAULT`-
 *     Verhalten von MockK gehen, das in dem Fall `false` zurückgibt — und alle
 *     Save-Tests würden trivially auf NONE fallen, statt das Roundtrip-Verhalten
 *     zu prüfen.
 *
 * WARUM ROBOLECTRIC:
 *   Sowohl der Contract als auch der Manager benutzen `android.net.Uri` —
 *   auf reiner JVM wirft das eine RuntimeException. Der existierende
 *   `WallpaperRepositoryImplTest` nutzt aus demselben Grund Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class WallpaperRepositoryImplContractTest : WallpaperRepositoryContract() {

    override fun createRepository(): WallpaperRepository {
        val fakeDataStore = FakeDataStore()
        val fileManager: WallpaperFileManager = mockk(relaxed = true)
        every { fileManager.fileExists(any<Uri>()) } returns true
        return WallpaperRepositoryImpl(fakeDataStore, fileManager)
    }
}
