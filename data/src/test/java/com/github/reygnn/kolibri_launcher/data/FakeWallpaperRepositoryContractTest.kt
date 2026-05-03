package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeWallpaperRepository
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Contract-Test-Ausführung gegen das Unit-Test-Fake [FakeWallpaperRepository].
 *
 * Siehe [WallpaperRepositoryContract] für die tatsächlichen Tests.
 *
 * WARUM ROBOLECTRIC:
 *   Der Contract konstruiert echte `android.net.Uri`-Instanzen via `String.toUri()`
 *   (siehe Test-Felder `testUri`/`testUri2`). Auf reiner JVM wirft das eine
 *   RuntimeException. Robolectric stellt eine funktionierende `Uri`-Implementierung
 *   bereit. Der Fake selbst berührt `Uri` nicht — die Annotation ist nur wegen der
 *   Test-Daten im Contract nötig, nicht wegen des Fakes.
 */
@RunWith(RobolectricTestRunner::class)
class FakeWallpaperRepositoryContractTest : WallpaperRepositoryContract() {

    override fun createRepository(): WallpaperRepository = FakeWallpaperRepository()
}
