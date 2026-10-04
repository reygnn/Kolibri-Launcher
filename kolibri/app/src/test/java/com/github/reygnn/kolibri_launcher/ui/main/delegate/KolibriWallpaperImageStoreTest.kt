package com.github.reygnn.kolibri_launcher.ui.main.delegate

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.kolibri_launcher.domain.usecase.ClearWallpaperUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetWallpaperImageUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeWallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.feature.wallpaper.CachedWallpaperComposite
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStore
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStoreContract
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Kolibri's run of the shared [WallpaperImageStoreContract] (3a-2), written against the code as
 * it was (3a-2a), and green through the move: the file lifecycle behind [WallpaperDelegate] with the real [WallpaperFileManager] on
 * Robolectric's files dir, the state behind the real use cases on a [FakeWallpaperRepository].
 * The composite path is not under test here; its collaborators are relaxed mocks.
 *
 * The orphan GC runs in [WallpaperDelegate.start] on the first state emission, skipped while an
 * edit session is open — so "trigger the GC" is another `start()`. Every file decision goes
 * through [WallpaperImageStore] since 3a-2, which deletes a replaced file right away (W1), hence
 * [deletesReplacedImageImmediately] is true; before 3a-2 Kolibri left it for the next cold-start
 * GC and the contract skipped those cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KolibriWallpaperImageStoreTest : WallpaperImageStoreContract() {

    @get:Rule
    val timberRule = TimberRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = FakeWallpaperRepository()
    private val fileManager = WallpaperFileManager(context, mainDispatcherRule.testDispatcher)
    private val delegateScope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
    private lateinit var delegate: WallpaperDelegate

    @After
    fun stopDelegate() {
        delegateScope.cancel()
    }

    override val deletesReplacedImageImmediately = true // since 3a-2: the lifecycle class in :feature-wallpaper

    override val wallpaperDir: File get() = File(context.filesDir, "wallpapers")

    override suspend fun startStore() {
        wallpaperDir.deleteRecursively()
        delegate = WallpaperDelegate(
            context = context,
            observeWallpaperStateUseCase = ObserveWallpaperStateUseCase(repository),
            saveWallpaperStateUseCase = SaveWallpaperStateUseCase(repository),
            setWallpaperImageUseCase = SetWallpaperImageUseCase(repository),
            clearWallpaperUseCase = ClearWallpaperUseCase(repository),
            getFabPositionUseCase = mockk(relaxed = true) { every { this@mockk.invoke() } returns emptyFlow() },
            saveFabPositionUseCase = mockk(relaxed = true),
            observeWallpaperBackdropUseCase = mockk(relaxed = true) { every { this@mockk.invoke() } returns emptyFlow() },
            setWallpaperBackdropUseCase = mockk(relaxed = true),
            imageStore = WallpaperImageStore(fileManager, repository, mainDispatcherRule.testDispatcher),
            composite = CachedWallpaperComposite(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mainDispatcherRule.testDispatcher),
            displaySettings = mockk(relaxed = true),
            scope = DelegateScope(
                coroutineScope = delegateScope,
                mainDispatcher = mainDispatcherRule.testDispatcher,
                eventSender = { },
            ),
        )
        delegate.start()
    }

    override suspend fun setWallpaper(image: File) {
        delegate.onSetWallpaperImage(Uri.fromFile(image))
    }

    override suspend fun enterEditSession() = delegate.onEnterWallpaperEditMode()

    override suspend fun commitEditSession() = delegate.onCommitWallpaperEditMode()

    override suspend fun cancelEditSession() = delegate.onCancelWallpaperEditMode()

    override suspend fun addLayer(image: File) {
        delegate.onAddWallpaperLayer(Uri.fromFile(image))
    }

    override suspend fun removeLayer(index: Int) {
        delegate.onRemoveWallpaperLayer(index)
    }

    override suspend fun removeWallpaper() {
        delegate.onClearWallpaper()
    }

    /** Kolibri's GC runs on the first emission of a `start()`, unless an edit session is open. */
    override suspend fun runOrphanGc() = delegate.start()

    override suspend fun copyWithoutSave(image: File) {
        fileManager.copyToInternal(Uri.fromFile(image))
    }

    override suspend fun saveTwoLayersOnOneFile(image: File) {
        val copy = checkNotNull(fileManager.copyToInternal(Uri.fromFile(image))).toString()
        repository.saveWallpaperState(
            WallpaperState.multiLayer(
                listOf(WallpaperLayerState(id = "first", imageUri = copy), WallpaperLayerState(id = "second", imageUri = copy)),
            ),
        )
    }

    override fun failSavesSilently() {
        repository.failSavesSilently = true
    }

    override fun makePersistedStateUnreadable() {
        repository.persistedStateUnreadable = true
    }

    override suspend fun savedLayerFiles(): List<String> =
        repository.currentState.layers.mapNotNull { layer -> layer.imageUri?.let { File(Uri.parse(it).path!!).name } }
}
