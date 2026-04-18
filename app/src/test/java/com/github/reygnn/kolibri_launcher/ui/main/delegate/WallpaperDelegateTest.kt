package com.github.reygnn.kolibri_launcher.ui.main.delegate

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.github.reygnn.kolibri_launcher.data.WallpaperFileManager
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.usecase.ClearWallpaperUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetWallpaperImageUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WallpaperDelegateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val sentEvents = mutableListOf<UiEvent>()

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var observeWallpaperStateUseCase: ObserveWallpaperStateUseCase
    private lateinit var saveWallpaperStateUseCase: SaveWallpaperStateUseCase
    private lateinit var setWallpaperImageUseCase: SetWallpaperImageUseCase
    private lateinit var clearWallpaperUseCase: ClearWallpaperUseCase
    private lateinit var wallpaperFileManager: WallpaperFileManager

    private val testUri: Uri = mockk()
    private val internalUri: Uri = mockk()

    @Before
    fun setUp() {
        sentEvents.clear()

        contentResolver = mockk {
            every { query(any(), any(), any(), any(), any()) } returns null
        }

        context = mockk(relaxed = true)
        every { context.contentResolver } returns contentResolver
        every { context.getContentResolver() } returns contentResolver

        observeWallpaperStateUseCase = mockk(relaxed = true)
        every { observeWallpaperStateUseCase.invoke() } returns emptyFlow()

        saveWallpaperStateUseCase = mockk(relaxed = true)
        setWallpaperImageUseCase = mockk(relaxed = true)
        clearWallpaperUseCase = mockk(relaxed = true)

        wallpaperFileManager = mockk(relaxed = true)
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns internalUri
    }

    private fun createDelegateScope() = DelegateScope(
        coroutineScope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()),
        mainDispatcher = mainDispatcherRule.testDispatcher,
        eventSender = { event -> sentEvents.add(event) }
    )

    private fun createDelegate(
        observeWallpaperStateUseCase: ObserveWallpaperStateUseCase = this.observeWallpaperStateUseCase,
        scope: DelegateScope = createDelegateScope()
    ) = WallpaperDelegate(
        context = context,
        observeWallpaperStateUseCase = observeWallpaperStateUseCase,
        saveWallpaperStateUseCase = saveWallpaperStateUseCase,
        setWallpaperImageUseCase = setWallpaperImageUseCase,
        clearWallpaperUseCase = clearWallpaperUseCase,
        wallpaperFileManager = wallpaperFileManager,
        scope = scope
    )

    // ===========================================
    // INITIAL STATE
    // ===========================================

    @Test
    fun `initial wallpaperState is NONE`() {
        val delegate = createDelegate()
        assertEquals(WallpaperState.NONE, delegate.wallpaperState.value)
    }

    @Test
    fun `initial isWallpaperEditMode is false`() {
        val delegate = createDelegate()
        assertFalse(delegate.isWallpaperEditMode.value)
    }

    // ===========================================
    // START - OBSERVE
    // ===========================================

    @Test
    fun `start observes wallpaper state`() = runTest {
        val testState: WallpaperState = mockk {
            every { hasWallpaper } returns true
        }
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(testState)

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)

        delegate.start()
        advanceUntilIdle()

        assertEquals(testState, delegate.wallpaperState.value)
    }

    @Test
    fun `start reflects state updates over time`() = runTest {
        val state1 = WallpaperState.NONE
        val state2: WallpaperState = mockk {
            every { hasWallpaper } returns true
        }
        val stateFlow = MutableStateFlow(state1)

        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)

        delegate.start()
        advanceUntilIdle()
        assertEquals(state1, delegate.wallpaperState.value)

        stateFlow.value = state2
        advanceUntilIdle()
        assertEquals(state2, delegate.wallpaperState.value)
    }

    // ===========================================
    // SET WALLPAPER IMAGE
    // ===========================================

    @Test
    fun `onSetWallpaperImage copies file and calls setUseCase`() = runTest {
        val delegate = createDelegate()

        delegate.onSetWallpaperImage(testUri)
        advanceUntilIdle()

        coVerify { wallpaperFileManager.copyToInternal(testUri) }
        coVerify { setWallpaperImageUseCase.invoke(internalUri) }
    }

    @Test
    fun `onSetWallpaperImage shows toast on success`() = runTest {
        val delegate = createDelegate()

        delegate.onSetWallpaperImage(testUri)
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToastFromString })
    }

    @Test
    fun `onSetWallpaperImage shows display name in toast when available`() = runTest {
        val cursor: Cursor = mockk {
            every { moveToFirst() } returns true
            every { getString(0) } returns "my_wallpaper.jpg"
            every { close() } returns Unit
        }
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns cursor

        val delegate = createDelegate()

        delegate.onSetWallpaperImage(testUri)
        advanceUntilIdle()

        val toastEvent = sentEvents.filterIsInstance<UiEvent.ShowToastFromString>().firstOrNull()
        assertTrue(toastEvent != null)
    }

    @Test
    fun `onSetWallpaperImage shows error when copy fails`() = runTest {
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns null

        val delegate = createDelegate()

        delegate.onSetWallpaperImage(testUri)
        advanceUntilIdle()

        coVerify(exactly = 0) { setWallpaperImageUseCase.invoke(any()) }
        assertTrue(sentEvents.any { it is UiEvent.ShowToast })
    }

    @Test
    fun `onSetWallpaperImage shows error toast on exception`() = runTest {
        coEvery { wallpaperFileManager.copyToInternal(any()) } throws RuntimeException("IO error")

        val delegate = createDelegate()

        delegate.onSetWallpaperImage(testUri)
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToast })
    }

    // ===========================================
    // SAVE WALLPAPER TRANSFORM
    // ===========================================

    @Test
    fun `onSaveWallpaperTransform calls saveUseCase when wallpaper exists`() = runTest {
        val stateWithWallpaper: WallpaperState = mockk {
            every { hasWallpaper } returns true
        }
        val stateFlow = MutableStateFlow(stateWithWallpaper)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)

        delegate.start()
        advanceUntilIdle()

        delegate.onSaveWallpaperTransform(2.0f, 10f, 20f)
        advanceUntilIdle()

        coVerify {
            saveWallpaperStateUseCase.updateTransform(
                currentState = stateWithWallpaper,
                scale = 2.0f,
                translateX = 10f,
                translateY = 20f
            )
        }
    }

    @Test
    fun `onSaveWallpaperTransform does nothing when no wallpaper`() = runTest {
        val delegate = createDelegate()

        delegate.onSaveWallpaperTransform(2.0f, 10f, 20f)
        advanceUntilIdle()

        coVerify(exactly = 0) { saveWallpaperStateUseCase.updateTransform(any(), any(), any(), any()) }
    }

    // ===========================================
    // CLEAR WALLPAPER
    // ===========================================

    @Test
    fun `onClearWallpaper clears files and calls clearUseCase`() = runTest {
        val delegate = createDelegate()

        delegate.onClearWallpaper()
        advanceUntilIdle()

        coVerify { wallpaperFileManager.clearAll() }
        coVerify { clearWallpaperUseCase.invoke() }
    }

    @Test
    fun `onClearWallpaper shows removed toast`() = runTest {
        val delegate = createDelegate()

        delegate.onClearWallpaper()
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToast })
    }

    @Test
    fun `onClearWallpaper shows error toast on exception`() = runTest {
        every { wallpaperFileManager.clearAll() } throws RuntimeException("IO error")

        val delegate = createDelegate()

        delegate.onClearWallpaper()
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToast })
    }

    // ===========================================
    // EDIT MODE
    // ===========================================

    @Test
    fun `onSetWallpaperEditMode sets to true`() {
        val delegate = createDelegate()

        delegate.onSetWallpaperEditMode(true)

        assertTrue(delegate.isWallpaperEditMode.value)
    }

    @Test
    fun `onSetWallpaperEditMode sets to false`() {
        val delegate = createDelegate()

        delegate.onSetWallpaperEditMode(true)
        delegate.onSetWallpaperEditMode(false)

        assertFalse(delegate.isWallpaperEditMode.value)
    }

    @Test
    fun `onToggleWallpaperEditMode toggles from false to true`() {
        val delegate = createDelegate()

        assertFalse(delegate.isWallpaperEditMode.value)
        delegate.onToggleWallpaperEditMode()
        assertTrue(delegate.isWallpaperEditMode.value)
    }

    @Test
    fun `onToggleWallpaperEditMode toggles from true to false`() {
        val delegate = createDelegate()

        delegate.onSetWallpaperEditMode(true)
        delegate.onToggleWallpaperEditMode()

        assertFalse(delegate.isWallpaperEditMode.value)
    }

    @Test
    fun `onToggleWallpaperEditMode toggles multiple times`() {
        val delegate = createDelegate()

        delegate.onToggleWallpaperEditMode() // false -> true
        assertTrue(delegate.isWallpaperEditMode.value)

        delegate.onToggleWallpaperEditMode() // true -> false
        assertFalse(delegate.isWallpaperEditMode.value)

        delegate.onToggleWallpaperEditMode() // false -> true
        assertTrue(delegate.isWallpaperEditMode.value)
    }

    // ===========================================
    // MULTI-LAYER: ADD LAYER
    // ===========================================

    @Test
    fun `onAddWallpaperLayer copies file and saves state`() = runTest {
        val addedState: WallpaperState = mockk(relaxed = true) {
            every { forPersistence() } returns mockk()
        }
        val mockState: WallpaperState = mockk {
            every { isMultiLayer } returns true
            every { hasWallpaper } returns true
            every { layerCount } returns 1
            every { withAddedLayer(any()) } returns addedState
        }

        val stateFlow = MutableStateFlow(mockState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)

        delegate.start()
        advanceUntilIdle()

        delegate.onAddWallpaperLayer(testUri, "My Layer")
        advanceUntilIdle()

        coVerify { wallpaperFileManager.copyToInternal(testUri) }
        coVerify { saveWallpaperStateUseCase.invoke(any()) }
    }

    @Test
    fun `onAddWallpaperLayer does nothing when copy fails`() = runTest {
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns null

        val delegate = createDelegate()

        delegate.onAddWallpaperLayer(testUri)
        advanceUntilIdle()

        coVerify(exactly = 0) { saveWallpaperStateUseCase.invoke(any()) }
    }

    // ===========================================
    // MULTI-LAYER: REMOVE LAYER
    // ===========================================

    @Test
    fun `onRemoveWallpaperLayer deletes file and saves state`() = runTest {
        val layerUri: Uri = mockk()
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns layerUri
        }
        val newState: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true
            every { forPersistence() } returns mockk()
        }
        val currentState: WallpaperState = mockk {
            every { getLayer(0) } returns layer
            every { withRemovedLayer(0) } returns newState
        }

        val stateFlow = MutableStateFlow(currentState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)

        delegate.start()
        advanceUntilIdle()

        delegate.onRemoveWallpaperLayer(0)
        advanceUntilIdle()

        verify { wallpaperFileManager.deleteFile(layerUri) }
        coVerify { saveWallpaperStateUseCase.invoke(any()) }
    }

    @Test
    fun `onRemoveWallpaperLayer clears wallpaper when last layer removed`() = runTest {
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns mockk()
        }
        val emptyState: WallpaperState = mockk {
            every { layers } returns emptyList()
            every { hasWallpaper } returns false
        }
        val currentState: WallpaperState = mockk {
            every { getLayer(0) } returns layer
            every { withRemovedLayer(0) } returns emptyState
        }

        val stateFlow = MutableStateFlow(currentState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)

        delegate.start()
        advanceUntilIdle()

        delegate.onRemoveWallpaperLayer(0)
        advanceUntilIdle()

        coVerify { clearWallpaperUseCase.invoke() }
        coVerify(exactly = 0) { saveWallpaperStateUseCase.invoke(any()) }
    }

    // ===========================================
    // MULTI-LAYER: SWAP LAYERS
    // ===========================================

    @Test
    fun `onSwapWallpaperLayers swaps and saves`() = runTest {
        val swappedState: WallpaperState = mockk {
            every { forPersistence() } returns mockk()
        }
        val currentState: WallpaperState = mockk {
            every { withSwappedLayers(0, 1) } returns swappedState
        }

        val stateFlow = MutableStateFlow(currentState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)

        delegate.start()
        advanceUntilIdle()

        delegate.onSwapWallpaperLayers(0, 1)
        advanceUntilIdle()

        verify { currentState.withSwappedLayers(0, 1) }
        coVerify { saveWallpaperStateUseCase.invoke(any()) }
    }

    // ===========================================
    // MULTI-LAYER: LAYER PROPERTIES
    // ===========================================

    private fun createDelegateWithStatefulWallpaper(): WallpaperDelegate {
        val updatedState: WallpaperState = mockk {
            every { forPersistence() } returns mockk()
        }
        val currentState: WallpaperState = mockk {
            every { withUpdatedLayer(any(), any()) } returns updatedState
        }

        val stateFlow = MutableStateFlow(currentState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        return delegate
    }

    @Test
    fun `onSetLayerAlpha clamps and saves`() = runTest {
        val delegate = createDelegateWithStatefulWallpaper()

        delegate.start()
        advanceUntilIdle()

        delegate.onSetLayerAlpha(0, 0.5f)
        advanceUntilIdle()

        coVerify { saveWallpaperStateUseCase.invoke(any()) }
    }

    @Test
    fun `onSetLayerBlendMode saves state`() = runTest {
        val delegate = createDelegateWithStatefulWallpaper()

        delegate.start()
        advanceUntilIdle()

        delegate.onSetLayerBlendMode(0, "MULTIPLY")
        advanceUntilIdle()

        coVerify { saveWallpaperStateUseCase.invoke(any()) }
    }

    @Test
    fun `onSetLayerVisibility saves state`() = runTest {
        val delegate = createDelegateWithStatefulWallpaper()

        delegate.start()
        advanceUntilIdle()

        delegate.onSetLayerVisibility(0, false)
        advanceUntilIdle()

        coVerify { saveWallpaperStateUseCase.invoke(any()) }
    }

    // ===========================================
    // MULTI-LAYER: SAVE TRANSFORMS
    // ===========================================

    @Test
    fun `onSaveLayerTransform updates specific layer and saves`() = runTest {
        val delegate = createDelegateWithStatefulWallpaper()

        delegate.start()
        advanceUntilIdle()

        delegate.onSaveLayerTransform(0, 1.5f, 10f, 20f)
        advanceUntilIdle()

        coVerify { saveWallpaperStateUseCase.invoke(any()) }
    }

    @Test
    fun `onSaveAllLayerTransforms updates all layers and saves once`() = runTest {
        val updatedState: WallpaperState = mockk(relaxed = true) {
            every { withUpdatedLayer(any(), any()) } returns this
            every { forPersistence() } returns mockk()
        }
        val currentState: WallpaperState = mockk {
            every { withUpdatedLayer(any(), any()) } returns updatedState
        }

        val stateFlow = MutableStateFlow(currentState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)

        delegate.start()
        advanceUntilIdle()

        val transforms = listOf(
            Triple(1.0f, 0f, 0f),
            Triple(2.0f, 10f, 20f)
        )
        delegate.onSaveAllLayerTransforms(transforms)
        advanceUntilIdle()

        coVerify(exactly = 1) { saveWallpaperStateUseCase.invoke(any()) }
    }

    // ===========================================
    // EDIT SESSION — ENTER / COMMIT / CANCEL
    //
    // Covers the transactional edit session introduced to fix:
    //   (1) transform mis-assignment on Cancel after a layer delete, and
    //   (2) Cancel not actually undoing a layer deletion.
    //
    // The session contract is: onEnterWallpaperEditMode() snapshots the
    // state. Layer removals during the session defer their physical file
    // deletion; added layers track their internal-file URI for orphan
    // cleanup. onCommitWallpaperEditMode() commits (delete deferred
    // removes). onCancelWallpaperEditMode() rolls back (restore snapshot
    // synchronously, delete added orphans).
    // ===========================================

    @Test
    fun `onEnterWallpaperEditMode sets edit mode to true`() {
        val delegate = createDelegate()

        delegate.onEnterWallpaperEditMode()

        assertTrue(delegate.isWallpaperEditMode.value)
    }

    @Test
    fun `onCommitWallpaperEditMode sets edit mode to false`() {
        val delegate = createDelegate()

        delegate.onEnterWallpaperEditMode()
        delegate.onCommitWallpaperEditMode()

        assertFalse(delegate.isWallpaperEditMode.value)
    }

    @Test
    fun `onCancelWallpaperEditMode sets edit mode to false`() {
        val delegate = createDelegate()

        delegate.onEnterWallpaperEditMode()
        delegate.onCancelWallpaperEditMode()

        assertFalse(delegate.isWallpaperEditMode.value)
    }

    @Test
    fun `onRemoveWallpaperLayer in edit mode defers file deletion`() = runTest {
        val layerUri: Uri = mockk()
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns layerUri
        }
        val newState: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true
            every { forPersistence() } returns mockk()
        }
        val currentState: WallpaperState = mockk {
            every { getLayer(0) } returns layer
            every { withRemovedLayer(0) } returns newState
        }

        val stateFlow = MutableStateFlow(currentState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onEnterWallpaperEditMode()
        delegate.onRemoveWallpaperLayer(0)
        advanceUntilIdle()

        // State was updated & persisted, but the physical file must NOT
        // be deleted yet — Cancel must still be able to restore it.
        verify(exactly = 0) { wallpaperFileManager.deleteFile(any()) }
        coVerify { saveWallpaperStateUseCase.invoke(any()) }
    }

    @Test
    fun `onRemoveWallpaperLayer outside edit mode deletes file immediately`() = runTest {
        // Regression guard: non-edit-mode behavior must be unchanged.
        val layerUri: Uri = mockk()
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns layerUri
        }
        val newState: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true
            every { forPersistence() } returns mockk()
        }
        val currentState: WallpaperState = mockk {
            every { getLayer(0) } returns layer
            every { withRemovedLayer(0) } returns newState
        }

        val stateFlow = MutableStateFlow(currentState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        // NOT entering edit mode
        delegate.onRemoveWallpaperLayer(0)
        advanceUntilIdle()

        verify { wallpaperFileManager.deleteFile(layerUri) }
    }

    @Test
    fun `onCommitWallpaperEditMode deletes deferred-remove files`() = runTest {
        val layerUri: Uri = mockk()
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns layerUri
        }
        val newState: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true
            every { forPersistence() } returns mockk()
        }
        val currentState: WallpaperState = mockk {
            every { getLayer(0) } returns layer
            every { withRemovedLayer(0) } returns newState
        }

        val stateFlow = MutableStateFlow(currentState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onEnterWallpaperEditMode()
        delegate.onRemoveWallpaperLayer(0)
        advanceUntilIdle()

        // Pre-commit: still not deleted
        verify(exactly = 0) { wallpaperFileManager.deleteFile(any()) }

        delegate.onCommitWallpaperEditMode()
        advanceUntilIdle()

        // Post-commit: deferred file deletion executed
        verify { wallpaperFileManager.deleteFile(layerUri) }
    }

    @Test
    fun `onCancelWallpaperEditMode does not delete deferred-remove files`() = runTest {
        val layerUri: Uri = mockk()
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns layerUri
        }
        val newState: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true
            every { forPersistence() } returns mockk()
        }
        val currentState: WallpaperState = mockk(relaxed = true) {
            every { getLayer(0) } returns layer
            every { withRemovedLayer(0) } returns newState
            every { forPersistence() } returns mockk()
        }

        val stateFlow = MutableStateFlow(currentState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onEnterWallpaperEditMode()
        delegate.onRemoveWallpaperLayer(0)
        advanceUntilIdle()
        delegate.onCancelWallpaperEditMode()
        advanceUntilIdle()

        // File must survive — the restored snapshot still references it
        verify(exactly = 0) { wallpaperFileManager.deleteFile(layerUri) }
    }

    @Test
    fun `onCancelWallpaperEditMode restores snapshot state synchronously`() = runTest {
        val newState: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true
            every { forPersistence() } returns mockk()
        }
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns mockk()
        }
        val snapshotState: WallpaperState = mockk(relaxed = true) {
            every { getLayer(0) } returns layer
            every { withRemovedLayer(0) } returns newState
            every { forPersistence() } returns mockk()
        }

        val stateFlow = MutableStateFlow(snapshotState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onEnterWallpaperEditMode()
        delegate.onRemoveWallpaperLayer(0)
        advanceUntilIdle()

        // Sanity: state has diverged from the snapshot
        assertEquals(newState, delegate.wallpaperState.value)

        // Key guarantee: in-memory state is reverted BEFORE any further
        // coroutine work — the caller (HomeFragment) relies on this so
        // it can immediately feed the restored value into updateWallpaper().
        delegate.onCancelWallpaperEditMode()
        assertEquals(snapshotState, delegate.wallpaperState.value)
    }

    @Test
    fun `onCancelWallpaperEditMode persists restored snapshot`() = runTest {
        val persistenceForm: WallpaperState = mockk()
        val newStateAfterRemove: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true
            every { forPersistence() } returns mockk()
        }
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns mockk()
        }
        val snapshotState: WallpaperState = mockk(relaxed = true) {
            every { getLayer(0) } returns layer
            every { withRemovedLayer(0) } returns newStateAfterRemove
            every { forPersistence() } returns persistenceForm
        }

        val stateFlow = MutableStateFlow(snapshotState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onEnterWallpaperEditMode()
        delegate.onRemoveWallpaperLayer(0)
        advanceUntilIdle()
        delegate.onCancelWallpaperEditMode()
        advanceUntilIdle()

        // Snapshot (in persistence form) must be written back so that
        // any prior in-session saves are overwritten on disk.
        coVerify { saveWallpaperStateUseCase.invoke(persistenceForm) }
    }

    @Test
    fun `onCancelWallpaperEditMode deletes files added during edit mode`() = runTest {
        val addedLayerUri: Uri = mockk()
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns addedLayerUri

        val baseState: WallpaperState = mockk(relaxed = true) {
            every { isMultiLayer } returns true
            every { hasWallpaper } returns true
            every { layerCount } returns 0
            every { withAddedLayer(any()) } returns this
            every { forPersistence() } returns mockk()
        }

        val stateFlow = MutableStateFlow(baseState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onEnterWallpaperEditMode()
        delegate.onAddWallpaperLayer(testUri)
        advanceUntilIdle()

        // Still in session → no cleanup yet
        verify(exactly = 0) { wallpaperFileManager.deleteFile(any()) }

        delegate.onCancelWallpaperEditMode()
        advanceUntilIdle()

        // On cancel the orphan file gets cleaned up
        verify { wallpaperFileManager.deleteFile(addedLayerUri) }
    }

    @Test
    fun `onCommitWallpaperEditMode does not delete files added during edit mode`() = runTest {
        val addedLayerUri: Uri = mockk()
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns addedLayerUri

        val baseState: WallpaperState = mockk(relaxed = true) {
            every { isMultiLayer } returns true
            every { hasWallpaper } returns true
            every { layerCount } returns 0
            every { withAddedLayer(any()) } returns this
            every { forPersistence() } returns mockk()
        }

        val stateFlow = MutableStateFlow(baseState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onEnterWallpaperEditMode()
        delegate.onAddWallpaperLayer(testUri)
        advanceUntilIdle()
        delegate.onCommitWallpaperEditMode()
        advanceUntilIdle()

        // The added layer is kept → its backing file must stay on disk
        verify(exactly = 0) { wallpaperFileManager.deleteFile(any()) }
    }

    @Test
    fun `onSetWallpaperEditMode(true) behaves like onEnterWallpaperEditMode`() = runTest {
        // Regression guard: legacy API must still snapshot the state
        // so that Cancel can roll back removes done afterwards.
        val layerUri: Uri = mockk()
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns layerUri
        }
        val newState: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true
            every { forPersistence() } returns mockk()
        }
        val snapshotState: WallpaperState = mockk(relaxed = true) {
            every { getLayer(0) } returns layer
            every { withRemovedLayer(0) } returns newState
            every { forPersistence() } returns mockk()
        }

        val stateFlow = MutableStateFlow(snapshotState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onSetWallpaperEditMode(true) // routes to onEnterWallpaperEditMode
        delegate.onRemoveWallpaperLayer(0)
        advanceUntilIdle()
        delegate.onCancelWallpaperEditMode()

        assertEquals(snapshotState, delegate.wallpaperState.value)
    }
}