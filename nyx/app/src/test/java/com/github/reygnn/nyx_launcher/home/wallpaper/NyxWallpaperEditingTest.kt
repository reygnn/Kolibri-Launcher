package com.github.reygnn.nyx_launcher.home.wallpaper

import com.github.reygnn.launcher.core.CompositeLuminanceSignal
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperBitmapLuminanceImpl
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperCompositeCache
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperFlattener
import com.github.reygnn.launcher.feature.wallpaper.CachedWallpaperComposite
import com.github.reygnn.launcher.feature.wallpaper.WallpaperComposite
import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.wallpaper.FakeWallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStore
import com.github.reygnn.nyx_launcher.data.home.NyxWallpaperEditing
import com.github.reygnn.nyx_launcher.data.home.NyxWallpaperImageSetter
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.ContinuationInterceptor

/**
 * [NyxWallpaperEditing] (3b-3, M1): one shared session for the editor and the Settings/sheet setter,
 * everything it launches on Main, the session bound to MainActivity's lifetime, and the end of the
 * 3b-1 interim (a choose from outside during an open session survives the commit, M5).
 */
@RunWith(RobolectricTestRunner::class)
class NyxWallpaperEditingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeWallpaperRepository()
    private val fileManager = mockk<WallpaperFileManager>(relaxed = true)
    private val appScope = CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher)

    @After
    fun stopAppScope() {
        appScope.cancel()
    }

    private fun editing(scope: CoroutineScope = appScope, main: CoroutineDispatcher = mainDispatcherRule.testDispatcher) =
        NyxWallpaperEditing(
            repository = repository,
            imageStore = WallpaperImageStore(fileManager, repository, mainDispatcherRule.testDispatcher),
            composite = WallpaperComposite.None(), // placeholder: no composite in these tests (3b-6)
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            appScope = scope,
            mainDispatcher = main,
        )

    private fun uri(s: String): Uri = Uri.parse(s)

    @Test
    fun everything_it_launches_runs_on_the_main_dispatcher() = runTest(mainDispatcherRule.testDispatcher) {
        // The app scope's own dispatcher is a different one (Default in production); the session
        // is main-confined, so every launch must land on the injected main dispatcher.
        val scopeDispatcher = StandardTestDispatcher(testScheduler) // the app scope's own dispatcher (Default in production)
        val main = mainDispatcherRule.testDispatcher
        val scope = CoroutineScope(SupervisorJob() + scopeDispatcher)
        val e = editing(scope = scope, main = main)
        var ranOn: Any? = null

        e.launchOnMain("test") { ranOn = currentCoroutineContext()[ContinuationInterceptor] }
        advanceUntilIdle()

        assertThat(ranOn).isSameInstanceAs(main)
        scope.cancel()
    }

    @Test
    fun the_editor_and_the_setter_drive_one_session() = runTest(mainDispatcherRule.testDispatcher) {
        repository.currentState = WallpaperState.single(uri = "file:///w/old")
        coEvery { fileManager.copyToInternal(any()) } returns uri("file:///w/new")
        val e = editing()
        val coordinator = NyxWallpaperEditCoordinator(e, mockk(relaxed = true), appScope).also { it.start() }
        val setter = NyxWallpaperImageSetter(e)
        advanceUntilIdle()

        coordinator.onEnterEditMode()
        setter.setFromUri(uri("content://picker/new"))
        advanceUntilIdle()

        // The setter's change shows in the editor's session, and the editor's cancel undoes it.
        assertThat(coordinator.wallpaperState.value.referencedUris).containsExactly("file:///w/new")
        coordinator.onCancelEditMode()
        advanceUntilIdle()
        assertThat(coordinator.wallpaperState.value.referencedUris).containsExactly("file:///w/old")
    }

    @Test
    fun a_choose_from_outside_during_an_open_session_survives_the_commit() = runTest(mainDispatcherRule.testDispatcher) {
        // M5 — the 3b-1 side effect is gone: the choose is a session change, so the commit keeps it.
        repository.currentState = WallpaperState.single(uri = "file:///w/old")
        coEvery { fileManager.copyToInternal(any()) } returns uri("file:///w/new")
        val e = editing()
        val coordinator = NyxWallpaperEditCoordinator(e, mockk(relaxed = true), appScope).also { it.start() }
        advanceUntilIdle()

        coordinator.onEnterEditMode()
        NyxWallpaperImageSetter(e).setFromUri(uri("content://picker/new"))
        coordinator.onCommitEditMode()
        advanceUntilIdle()

        assertThat(repository.currentState.referencedUris).containsExactly("file:///w/new")
        verify { fileManager.deleteFile("file:///w/old") } // the replaced file goes with the commit
    }

    @Test
    fun the_end_of_main_activity_cancels_an_open_session() = runTest(mainDispatcherRule.testDispatcher) {
        // Correction 2: the session stays bound to its host — like leaving the editor unsaved.
        repository.currentState = WallpaperState.single(uri = "file:///w/old")
        coEvery { fileManager.copyToInternal(any()) } returns uri("file:///w/added")
        val e = editing()
        val coordinator = NyxWallpaperEditCoordinator(e, mockk(relaxed = true), appScope).also { it.start() }
        advanceUntilIdle()
        coordinator.onEnterEditMode()
        coordinator.onAddLayer(uri("content://picker/added"))
        advanceUntilIdle()

        e.onHostDestroyed(changingConfigurations = false)
        advanceUntilIdle()

        assertThat(e.session.isEditMode.value).isFalse() // no orphaned session
        assertThat(repository.currentState.referencedUris).containsExactly("file:///w/old") // snapshot saved
        verify { fileManager.deleteFile("file:///w/added") } // the session-added file goes
        // Emissions are taken over again (the re-sync ended the session's ignore window).
        repository.currentState = WallpaperState.single(uri = "file:///w/later")
        advanceUntilIdle()
        assertThat(e.session.state.value.referencedUris).containsExactly("file:///w/later")
    }

    @Test
    fun a_recreation_for_a_configuration_change_keeps_the_open_session() = runTest(mainDispatcherRule.testDispatcher) {
        // Audit A3: locale or font scale recreate MainActivity; the new activity restores the
        // editor from the session, so the old one must not cancel it on its way out.
        repository.currentState = WallpaperState.single(uri = "file:///w/old")
        coEvery { fileManager.copyToInternal(any()) } returns uri("file:///w/added")
        val e = editing()
        val coordinator = NyxWallpaperEditCoordinator(e, mockk(relaxed = true), appScope).also { it.start() }
        advanceUntilIdle()
        coordinator.onEnterEditMode()
        coordinator.onAddLayer(uri("content://picker/added"))
        advanceUntilIdle()

        e.onHostDestroyed(changingConfigurations = true)
        advanceUntilIdle()

        assertThat(e.session.isEditMode.value).isTrue() // still editing
        assertThat(e.session.state.value.referencedUris).containsExactly("file:///w/old", "file:///w/added")
        verify(exactly = 0) { fileManager.deleteFile("file:///w/added") } // nothing rolled back
    }

    // ---- 3b-6: the injected composite (E3) ----

    private val software = mockk<android.graphics.Bitmap>(relaxed = true)
    private val hardware = mockk<android.graphics.Bitmap>(relaxed = true)
    private val flattener = mockk<WallpaperFlattener> {
        io.mockk.coEvery { flatten(any(), any(), any()) } returns software
    }

    private fun cachedComposite(): CachedWallpaperComposite {
        io.mockk.every { software.copy(android.graphics.Bitmap.Config.HARDWARE, false) } returns hardware
        return CachedWallpaperComposite(
            cache = WallpaperCompositeCache(),
            flattener = flattener,
            luminance = mockk<WallpaperBitmapLuminanceImpl>(relaxed = true),
            luminanceSignal = mockk<CompositeLuminanceSignal>(relaxed = true),
            ioDispatcher = mainDispatcherRule.testDispatcher,
        )
    }

    private fun editingWith(composite: WallpaperComposite) = NyxWallpaperEditing(
        repository = repository,
        imageStore = WallpaperImageStore(fileManager, repository, mainDispatcherRule.testDispatcher),
        composite = composite,
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        appScope = appScope,
        mainDispatcher = mainDispatcherRule.testDispatcher,
    )

    private val twoLayers = WallpaperState.multiLayer(
        listOf(
            com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState(id = "a", imageUri = "file:///w/a"),
            com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState(id = "b", imageUri = "file:///w/b"),
        ),
    )

    private fun displaySize(): Pair<Int, Int> =
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    @Test
    fun the_injected_composite_is_used_and_refilled_after_commit() = runTest(mainDispatcherRule.testDispatcher) {
        // The shared edit session warms the injected composite (start and the commit's leave):
        // one flatten in total, and the read side finds the composite afterwards.
        repository.currentState = twoLayers
        val composite = cachedComposite()
        val e = editingWith(composite)
        e.start()
        advanceUntilIdle()

        e.session.enter()
        e.operations.commit(onImageChanged = {})
        advanceUntilIdle()

        val (w, h) = displaySize()
        io.mockk.coVerify(exactly = 1) { flattener.flatten(any(), any(), any()) }
        assertThat(composite.cachedKeyFor(twoLayers, w, h)).isNotNull()
    }

    @Test
    fun on_host_started_rewarms_after_an_invalidate() = runTest(mainDispatcherRule.testDispatcher) {
        // R2: memory pressure dropped the composite; home becoming visible warms it anew.
        repository.currentState = twoLayers
        val composite = cachedComposite()
        val e = editingWith(composite)
        e.start()
        advanceUntilIdle()

        composite.invalidate(dropLuminance = false)
        e.onHostStarted()
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 2) { flattener.flatten(any(), any(), any()) }
    }

    @Test
    fun on_host_started_with_a_valid_cache_does_not_flatten() = runTest(mainDispatcherRule.testDispatcher) {
        repository.currentState = twoLayers
        val composite = cachedComposite()
        val e = editingWith(composite)
        e.start()
        advanceUntilIdle()

        e.onHostStarted()
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 1) { flattener.flatten(any(), any(), any()) }
    }
}
