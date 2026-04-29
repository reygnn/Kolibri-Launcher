package com.github.reygnn.kolibri_launcher.data

import androidx.core.net.toUri
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * WALLPAPER REPOSITORY — CONTRACT TEST
 * ============================================================================
 *
 * Siehe [FavoritesRepositoryContract] für Hintergrund und Konventionen.
 *
 * Das [WallpaperRepository]-Interface ist klein, aber hat eine wichtige
 * Konsistenz-Property zwischen den beiden Read-Pfaden:
 *
 *   `getWallpaperStateSync()` und `wallpaperState.first()` müssen denselben
 *   Wert liefern. Der Manager implementiert das, indem `getWallpaperStateSync`
 *   intern `wallpaperState.first()` aufruft. Der Fake implementiert das,
 *   indem beide auf dieselbe `MutableStateFlow.value` zeigen. Wenn jemand
 *   beim Refactoring eine Cache-Schicht einbaut, die nur einer der beiden
 *   Pfade benutzt, fängt der entsprechende Contract-Test das ab.
 *
 * BEKANNTE DIVERGENZEN — werden vermutlich auf dem Fake rot:
 *
 *   1. **`saveWallpaperState(WallpaperState(scale = 2.0f, imageUri = null))`**
 *      - Manager: interpretiert "kein imageUri und nicht multi-layer" als
 *        "kein Wallpaper" und persistiert nichts. Folge-Read liefert
 *        `WallpaperState.NONE` — der `scale` ist verloren.
 *      - Fake: speichert den exakten State, den man übergibt.
 *
 *   2. **Non-file URI scheme** (z.B. `content://...`)
 *      - Manager: verwirft beim Read mit `scheme != "file"` → returnt
 *        `WallpaperState.NONE`. Schutz vor SD-Card-Remap und Backup-Restore.
 *      - Fake: speichert jede Uri unverändert.
 *
 *   3. **Datei nicht (mehr) auf Disk** (file://-URI ohne existierende Datei)
 *      - Manager: ruft `WallpaperFileManager.fileExists(uri)` und returnt
 *        bei `false` → `WallpaperState.NONE`.
 *      - Fake: kennt das Konzept "Datei existiert" nicht.
 *
 *   Realer Schaden bei (1): Code, der scale/translateX/translateY ohne
 *   imageUri persistieren möchte (gibt's in der Codebase aktuell vermutlich
 *   nicht — aber wer weiß), wird in Produktion silently brechen.
 *
 *   Realer Schaden bei (2)+(3): Tests die mit `content://`-URIs oder
 *   nicht-existierenden Dateien arbeiten, sehen im Fake sinnvoll-aussehende
 *   Daten — die in Produktion sofort zu `NONE` werden.
 *
 *   Damit der Contract überhaupt sinnvolle Save/Load-Roundtrips testen kann,
 *   benutzen die Tests `file://`-URIs und der Manager-Test stubt
 *   `fileExists(any()) returns true`. Die drei Drifts oben werden bewusst
 *   im KDoc dokumentiert, aber NICHT als Tests im Contract aufgenommen —
 *   die Fakes "kaputt" zu deklarieren und auf Robolectric zwingen wäre
 *   übertrieben für Verhalten, das in der Praxis kaum jemand benutzt.
 *
 * NICHT IM CONTRACT (Manager-spezifisch):
 *   - Multi-Layer JSON-Serialisierung (Implementation-Detail des Managers).
 *   - Backward-Compat-Migration zwischen Single- und Multi-Layer-Keys.
 *   - DataStore-IOException-Recovery.
 *   - URI-scheme- und file-existence-Validierung (s.o.).
 *
 * WARUM ROBOLECTRIC:
 *   Der Contract konstruiert echte `android.net.Uri`-Instanzen via
 *   `String.toUri()`. Auf reiner JVM wirft das eine RuntimeException — also
 *   müssen alle Subklassen mit `@RunWith(RobolectricTestRunner::class)`
 *   annotiert werden. Das ist der einzige Repository-Contract im Projekt
 *   mit diesem Setup; Begründung im jeweiligen Subklassen-KDoc.
 *
 * @see FakeWallpaperRepositoryContractTest
 * @see WallpaperRepositoryImplContractTest
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class WallpaperRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    protected abstract fun createRepository(): WallpaperRepository

    // file://-URIs gewählt, weil der Manager content://-URIs auf NONE setzt
    // (siehe Klassen-KDoc, Drift Nr. 2). Damit testen wir die gemeinsame Basis.
    private val testUri = "file:///data/wallpaper.jpg".toUri()
    private val testUri2 = "file:///data/other.jpg".toUri()

    // ---------- Fresh state ----------

    @Test
    fun `fresh repository emits WallpaperState NONE on flow`() = runTest {
        val repo = createRepository()
        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }

    @Test
    fun `fresh repository returns WallpaperState NONE on sync getter`() = runTest {
        val repo = createRepository()
        assertEquals(WallpaperState.NONE, repo.getWallpaperStateSync())
    }

    // ---------- saveWallpaperState ----------

    @Test
    fun `saveWallpaperState with imageUri persists imageUri`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState(imageUri = testUri))
        assertEquals(testUri, repo.wallpaperState.first().imageUri)
    }

    @Test
    fun `saveWallpaperState with imageUri and scale persists both`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState(imageUri = testUri, scale = 2.5f))
        val state = repo.wallpaperState.first()
        assertEquals(testUri, state.imageUri)
        assertEquals(2.5f, state.scale)
    }

    @Test
    fun `saveWallpaperState with imageUri and translation persists both`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(
            WallpaperState(imageUri = testUri, translateX = 100f, translateY = -50f)
        )
        val state = repo.wallpaperState.first()
        assertEquals(100f, state.translateX)
        assertEquals(-50f, state.translateY)
    }

    @Test
    fun `saveWallpaperState overwrites previous wallpaper`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState(imageUri = testUri, scale = 2.0f))
        repo.saveWallpaperState(WallpaperState(imageUri = testUri2, scale = 1.5f))
        val state = repo.wallpaperState.first()
        assertEquals(testUri2, state.imageUri)
        assertEquals(1.5f, state.scale)
    }

    // ---------- Konsistenz: sync == flow.first() ----------

    /**
     * Die wichtigste Vertrags-Property dieses Repositories: beide Read-Pfade
     * liefern denselben Wert. Falls jemand eine Cache-Schicht einbaut, die nur
     * einer der beiden Pfade benutzt, fängt dieser Test das sofort ab.
     */
    @Test
    fun `getWallpaperStateSync returns same value as wallpaperState first`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState(imageUri = testUri, scale = 1.75f))

        val viaFlow = repo.wallpaperState.first()
        val viaSync = repo.getWallpaperStateSync()

        assertEquals(viaFlow, viaSync)
    }

    @Test
    fun `getWallpaperStateSync stays in sync after multiple saves`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState(imageUri = testUri, scale = 1.0f))
        repo.saveWallpaperState(WallpaperState(imageUri = testUri2, scale = 2.0f))

        assertEquals(repo.wallpaperState.first(), repo.getWallpaperStateSync())
    }

    @Test
    fun `getWallpaperStateSync stays in sync after clearWallpaper`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState(imageUri = testUri))
        repo.clearWallpaper()

        assertEquals(repo.wallpaperState.first(), repo.getWallpaperStateSync())
    }

    // ---------- clearWallpaper ----------

    @Test
    fun `clearWallpaper resets state to NONE`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState(imageUri = testUri, scale = 2.0f))
        repo.clearWallpaper()
        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }

    @Test
    fun `clearWallpaper on fresh repository is safe`() = runTest {
        val repo = createRepository()
        repo.clearWallpaper()
        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }

    @Test
    fun `clearWallpaper can be called multiple times`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState(imageUri = testUri))
        repo.clearWallpaper()
        repo.clearWallpaper()
        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }

    // ---------- purgeRepository ----------

    @Test
    fun `purgeRepository resets state to NONE`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState(imageUri = testUri, scale = 2.0f))
        repo.purgeRepository()
        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }

    @Test
    fun `purgeRepository on fresh repository is safe`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }
}
