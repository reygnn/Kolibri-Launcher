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
 *
 * WHY THIS LIVES IN data/src/test/ (not domain/src/test/ like the other 11
 * fake-contract-tests):
 *   The Robolectric dependency above forces the location. `:domain` is a
 *   pure-Kotlin JVM module (`kotlin("jvm")`) with no Android SDK on its test
 *   classpath, so it cannot host a Robolectric runner. This test therefore
 *   sits alongside [WallpaperRepositoryImplContractTest] in the Android `:data`
 *   module. The off-pattern location is structurally required, not a drift.
 */
@RunWith(RobolectricTestRunner::class)
class FakeWallpaperRepositoryContractTest : WallpaperRepositoryContract() {

    override fun createRepository(): WallpaperRepository = FakeWallpaperRepository()
}
