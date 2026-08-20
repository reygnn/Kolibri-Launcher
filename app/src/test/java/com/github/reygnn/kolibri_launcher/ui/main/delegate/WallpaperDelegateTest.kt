package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.LayerTransform
import android.content.Context
import android.net.Uri
import com.github.reygnn.kolibri_launcher.data.WallpaperFileManager
import android.graphics.Bitmap
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperCompositeCache
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.DecodedWallpaperBitmap
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperFlattener
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.usecase.ClearWallpaperUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFabPositionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveFabPositionUseCase
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class WallpaperDelegateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val sentEvents = mutableListOf<UiEvent>()

    private lateinit var context: Context
    private lateinit var observeWallpaperStateUseCase: ObserveWallpaperStateUseCase
    private lateinit var saveWallpaperStateUseCase: SaveWallpaperStateUseCase
    private lateinit var setWallpaperImageUseCase: SetWallpaperImageUseCase
    private lateinit var clearWallpaperUseCase: ClearWallpaperUseCase
    private lateinit var getFabPositionUseCase: GetFabPositionUseCase
    private lateinit var saveFabPositionUseCase: SaveFabPositionUseCase
    private lateinit var wallpaperFileManager: WallpaperFileManager

    private val testUri: Uri = mockk()
    private val internalUriString = "file:///internal/wallpaper.jpg"
    private val internalUri: Uri = mockk(relaxed = true)

    @Before
    fun setUp() {
        sentEvents.clear()
        every { internalUri.toString() } returns internalUriString

        context = mockk(relaxed = true)

        observeWallpaperStateUseCase = mockk(relaxed = true)
        every { observeWallpaperStateUseCase.invoke() } returns emptyFlow()

        saveWallpaperStateUseCase = mockk(relaxed = true)
        setWallpaperImageUseCase = mockk(relaxed = true)
        clearWallpaperUseCase = mockk(relaxed = true)

        getFabPositionUseCase = mockk(relaxed = true)
        every { getFabPositionUseCase.invoke() } returns emptyFlow()
        saveFabPositionUseCase = mockk(relaxed = true)

        wallpaperFileManager = mockk(relaxed = true)
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns internalUri
    }

    private fun createDelegateScope() = DelegateScope(
        coroutineScope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()),
        mainDispatcher = mainDispatcherRule.testDispatcher,
        eventSender = { event -> sentEvents.add(event) }
    )

    /**
     * A delegate scope backed by a LAZY [StandardTestDispatcher] (vs. the
     * eager [io.mockk.mockk]-friendly Unconfined default). Launched
     * coroutines are queued until [advanceUntilIdle], which lets a test
     * interleave a synchronous session boundary (Cancel/Commit) between a
     * dispatched `onAddWallpaperLayer` and the moment its body actually
     * runs — the ordering AUDIT-6 #2 is about. Must be called from inside
     * `runTest` so it shares the test scheduler.
     */
    private fun kotlinx.coroutines.test.TestScope.lazyDelegateScope(): DelegateScope {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DelegateScope(
            coroutineScope = CoroutineScope(dispatcher + SupervisorJob()),
            mainDispatcher = dispatcher,
            eventSender = { event -> sentEvents.add(event) }
        )
    }

    private fun createDelegate(
        observeWallpaperStateUseCase: ObserveWallpaperStateUseCase = this.observeWallpaperStateUseCase,
        ioDispatcher: CoroutineDispatcher = mainDispatcherRule.testDispatcher,
        scope: DelegateScope = createDelegateScope(),
        wallpaperFlattener: WallpaperFlattener = mockk(relaxed = true),
        compositeCache: WallpaperCompositeCache = mockk(relaxed = true),
        bitmapLuminance: com.github.reygnn.kolibri_launcher.data.wallpaper.WallpaperBitmapLuminanceImpl = mockk(relaxed = true),
        compositeLuminanceSignal: com.github.reygnn.kolibri_launcher.core.CompositeLuminanceSignal = mockk(relaxed = true),
    ) = WallpaperDelegate(
        context = context,
        observeWallpaperStateUseCase = observeWallpaperStateUseCase,
        saveWallpaperStateUseCase = saveWallpaperStateUseCase,
        setWallpaperImageUseCase = setWallpaperImageUseCase,
        clearWallpaperUseCase = clearWallpaperUseCase,
        getFabPositionUseCase = getFabPositionUseCase,
        saveFabPositionUseCase = saveFabPositionUseCase,
        wallpaperFileManager = wallpaperFileManager,
        wallpaperFlattener = wallpaperFlattener,
        compositeCache = compositeCache,
        bitmapLuminance = bitmapLuminance,
        compositeLuminanceSignal = compositeLuminanceSignal,
        ioDispatcher = ioDispatcher,
        scope = scope
    )

    /**
     * A [CoroutineDispatcher] that forwards to [backing] (a test dispatcher on
     * the runTest scheduler) but counts how many blocks were dispatched through
     * it. Used to prove that blocking disk I/O hops off the main dispatcher onto
     * the injected io dispatcher (AUDIT-9 #4 / #N1): if a call is `withContext
     * (ioDispatcher) { ... }`-wrapped, [count] increases; if it runs inline on
     * main, it does not.
     */
    private class CountingDispatcher(private val backing: CoroutineDispatcher) : CoroutineDispatcher() {
        var count = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            count++
            backing.dispatch(context, block)
        }
    }

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
    // COMPOSITE WARM (in-memory, v4)
    // ===========================================
    //
    // The warm itself (flatten -> HARDWARE copy -> cache put) is not JVM-tested: the
    // software->HARDWARE Bitmap.copy needs a real bitmap (a mock can't), so a JVM test would be
    // mock theater (Rule 10). It is covered on-device; the flatten completeness and the content
    // key are unit-tested in WallpaperFlattener/WallpaperCompositeKey tests. What IS honestly
    // JVM-testable is the warm TRIGGER GATE — that a flatten is (not) kicked — pinned here.

    @Test
    fun `refills a single-layer wallpaper via decode, not flatten`() = runTest {
        // AUDIT-20 F15: single-layer now gets a PROACTIVE refill through the decode branch
        // (not the composite flatten), cached under its file:// key, with the debug toast.
        val single = WallpaperState(imageUri = "file:///single.jpg")
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(single)

        val flattener: WallpaperFlattener = mockk(relaxed = true)
        val bitmap = DecodedWallpaperBitmap(mockk<Bitmap>(relaxed = true), 1, 100, 100)
        coEvery { flattener.decodeSingle("file:///single.jpg") } returns bitmap
        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        every { cache.get(any()) } returns null

        val delegate = createDelegate(
            observeWallpaperStateUseCase = useCase,
            wallpaperFlattener = flattener,
            compositeCache = cache,
        )

        delegate.start()
        advanceUntilIdle()

        coVerify(exactly = 0) { flattener.flatten(any(), any(), any()) }
        coVerify { flattener.decodeSingle("file:///single.jpg") }
        verify { cache.put("file:///single.jpg", bitmap) }
        assertTrue(
            "a single-layer refill emits the debug toast",
            sentEvents.any { it is UiEvent.ShowToastFromString && it.message.startsWith("Single-layer cache filled") }
        )
    }

    @Test
    fun `refill does nothing when there is no wallpaper`() = runTest {
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(WallpaperState.NONE)

        val flattener: WallpaperFlattener = mockk(relaxed = true)
        val delegate = createDelegate(observeWallpaperStateUseCase = useCase, wallpaperFlattener = flattener)

        delegate.start()
        advanceUntilIdle()

        coVerify(exactly = 0) { flattener.decodeSingle(any()) }
        coVerify(exactly = 0) { flattener.flatten(any(), any(), any()) }
    }

    @Test
    fun `refill is a no-op for a single-layer cache hit`() = runTest {
        val single = WallpaperState(imageUri = "file:///single.jpg")
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(single)

        val flattener: WallpaperFlattener = mockk(relaxed = true)
        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        every { cache.get("file:///single.jpg") } returns mockk(relaxed = true) // already cached

        val delegate = createDelegate(
            observeWallpaperStateUseCase = useCase,
            wallpaperFlattener = flattener,
            compositeCache = cache,
        )

        delegate.start()
        advanceUntilIdle()

        coVerify(exactly = 0) { flattener.decodeSingle(any()) }
    }

    @Test
    fun `does not warm when the composite is already cached`() = runTest {
        // v4: the warm is gated on a compositeCache MISS. A cache hit means the composite is
        // already in memory, so no flatten runs (replaces the old "composite path exists" gate).
        val multi = WallpaperState(layers = listOf(WallpaperLayerState(imageUri = "file:///l1.jpg")))
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(multi)

        val flattener: WallpaperFlattener = mockk(relaxed = true)
        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        every { cache.get(any()) } returns mockk(relaxed = true) // hit for any key

        val delegate = createDelegate(
            observeWallpaperStateUseCase = useCase,
            wallpaperFlattener = flattener,
            compositeCache = cache,
        )

        delegate.start()
        advanceUntilIdle()

        coVerify(exactly = 0) { flattener.flatten(any(), any(), any()) }
    }

    @Test
    fun `a failed warm drops the composite luminance instead of stranding the previous value`() = runTest {
        // Review #1: a warm that cannot produce a composite (failed/partial flatten -> null) must
        // emit null so the AUTO classifier stops using a PREVIOUS wallpaper's luminance for this
        // state, rather than leaving the stale value in the signal.
        val multi = WallpaperState(layers = listOf(WallpaperLayerState(imageUri = "file:///l1.jpg")))
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(multi)

        val flattener: WallpaperFlattener = mockk()
        coEvery { flattener.flatten(any(), any(), any()) } returns null // failed / partial flatten

        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        every { cache.get(any()) } returns null // miss -> the warm fires
        val luminanceSignal: com.github.reygnn.kolibri_launcher.core.CompositeLuminanceSignal = mockk(relaxed = true)

        val delegate = createDelegate(
            observeWallpaperStateUseCase = useCase,
            wallpaperFlattener = flattener,
            compositeCache = cache,
            compositeLuminanceSignal = luminanceSignal,
        )

        delegate.start()
        advanceUntilIdle()

        verify { luminanceSignal.emit(null) }
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
        coVerify { setWallpaperImageUseCase.invoke(internalUriString) }
    }

    /**
     * Setting a wallpaper emits NO success toast — the wallpaper itself is the
     * confirmation. Pinned as a guard: the removed toast showed the picked file's
     * DISPLAY_NAME (an opaque temporary name on SAF/cloud providers) and cost a
     * blocking binder IPC into a foreign provider. Error toasts are unaffected —
     * see the two `shows error` cases below.
     */
    @Test
    fun `onSetWallpaperImage emits no success toast`() = runTest {
        val delegate = createDelegate()

        delegate.onSetWallpaperImage(testUri)
        advanceUntilIdle()

        coVerify { setWallpaperImageUseCase.invoke(internalUriString) }
        assertTrue(sentEvents.isEmpty())
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

    /**
     * AUDIT-9 #N1 regression guard: `clearAll` does blocking file deletion and
     * must run OFF the main dispatcher (the launchSafe block starts on it).
     */
    @Test
    fun `onClearWallpaper deletes files off the main dispatcher`() = runTest {
        val io = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val delegate = createDelegate(ioDispatcher = io)

        delegate.onClearWallpaper()
        advanceUntilIdle()

        coVerify { wallpaperFileManager.clearAll() }
        assertTrue(
            "clearAll must run on the injected io dispatcher, not the main thread",
            io.count > 0
        )
    }

    /**
     * AUDIT-20 F6: the clear path is serialized with composite regeneration. While a
     * backfill flatten holds the regen lock, `clearWallpaperUseCase` must not run until
     * the lock frees; afterwards the delegate resets in-memory state to NONE so any
     * regen still queued on the lock fails its latest-wins guard rather than
     * re-persisting the removed wallpaper.
     */
    @Test
    fun `onClearWallpaper waits for an in-flight backfill then clears and resets state to NONE`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val flattenCalls = AtomicInteger(0)
        val bitmap: Bitmap = mockk(relaxed = true)
        val flattener: WallpaperFlattener = mockk()
        coEvery { flattener.flatten(any(), any(), any()) } coAnswers {
            if (flattenCalls.getAndIncrement() == 0) gate.await()
            bitmap
        }
        val multiNoComposite = WallpaperState(layers = listOf(WallpaperLayerState(imageUri = "file:///l1.jpg")))
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(multiNoComposite)

        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        every { cache.get(any()) } returns null // cache miss -> the warm fires and holds the lock

        val delegate = createDelegate(
            observeWallpaperStateUseCase = useCase,
            wallpaperFlattener = flattener,
            compositeCache = cache,
        )

        delegate.start() // warm flatten parks on the gate, holding the regen lock
        advanceUntilIdle()
        assertEquals(1, flattenCalls.get())

        delegate.onClearWallpaper() // blocks on the held regen lock
        advanceUntilIdle()
        coVerify(exactly = 0) { clearWallpaperUseCase.invoke() } // must wait for the lock

        gate.complete(Unit) // backfill releases the lock
        advanceUntilIdle()

        coVerify { clearWallpaperUseCase.invoke() }
        assertEquals(WallpaperState.NONE, delegate.wallpaperState.value)
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
        val addedState: WallpaperState = mockk(relaxed = true) {        }
        val state: WallpaperState = mockk(relaxed = true) {
            every { isMultiLayer } returns true
            every { hasWallpaper } returns true
            every { withAddedLayer(any()) } returns addedState
        }

        val stateFlow = MutableStateFlow(state)
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

    @Test
    fun `onAddWallpaperLayer resuming after cancel discards the layer and deletes its file`() = runTest {
        // AUDIT-6 #2: copyToInternal hops to Dispatchers.IO and releases the
        // main dispatcher, so a synchronous Cancel can end the session before
        // the add finishes. Modeled here with a lazy (StandardTestDispatcher)
        // delegate scope: the add is scheduled while the session is active,
        // then Cancel runs and moves the session token, then the add runs. It
        // must NOT persist its layer onto the restored state.
        val delegate = createDelegate(scope = lazyDelegateScope())
        delegate.onEnterWallpaperEditMode()

        // Add is dispatched but not yet run (lazy dispatcher).
        delegate.onAddWallpaperLayer(testUri)
        assertTrue(delegate.isWallpaperEditMode.value)

        // User cancels before the add's work runs — synchronous session teardown.
        delegate.onCancelWallpaperEditMode()
        assertFalse(delegate.isWallpaperEditMode.value)

        // The add now runs across the closed session boundary.
        advanceUntilIdle()

        // The cancelled layer must not be persisted…
        assertFalse(
            "add from a closed session must not persist a layer",
            delegate.wallpaperState.value.hasWallpaper
        )
        coVerify(exactly = 0) { saveWallpaperStateUseCase.invoke(match { it.hasWallpaper }) }
        // …and its orphaned file is cleaned up.
        verify { wallpaperFileManager.deleteFile(internalUriString) }
    }

    @Test
    fun `onAddWallpaperLayer resuming after commit still persists the layer`() = runTest {
        // AUDIT-6 review #1: Commit KEEPS the current state (no restore), so an
        // add whose copy finishes just after Commit must still be applied — not
        // discarded like the Cancel case. Only a rollback (Cancel) may drop it.
        val delegate = createDelegate(scope = lazyDelegateScope())
        delegate.onEnterWallpaperEditMode()

        // Add is dispatched but not yet run (lazy dispatcher).
        delegate.onAddWallpaperLayer(testUri)
        // User commits before the add's work runs — keeps changes, no restore.
        delegate.onCommitWallpaperEditMode()
        assertFalse(delegate.isWallpaperEditMode.value)

        // The add now runs after the commit and must persist its layer.
        advanceUntilIdle()

        assertTrue(
            "add resuming after commit must persist its layer",
            delegate.wallpaperState.value.hasWallpaper
        )
        assertTrue(delegate.wallpaperState.value.isMultiLayer)
        coVerify { saveWallpaperStateUseCase.invoke(match { it.hasWallpaper }) }
        verify(exactly = 0) { wallpaperFileManager.deleteFile(internalUriString) }
    }

    @Test
    fun `onAddWallpaperLayer resuming within the same session still persists the layer`() = runTest {
        // Guard must not over-fire: an add that runs without any intervening
        // session boundary still persists its layer as before.
        val delegate = createDelegate(scope = lazyDelegateScope())
        delegate.onEnterWallpaperEditMode()

        delegate.onAddWallpaperLayer(testUri)
        // No session boundary crosses before the add runs.
        advanceUntilIdle()

        assertTrue(delegate.wallpaperState.value.hasWallpaper)
        assertTrue(delegate.wallpaperState.value.isMultiLayer)
        coVerify { saveWallpaperStateUseCase.invoke(match { it.hasWallpaper }) }
        verify(exactly = 0) { wallpaperFileManager.deleteFile(internalUriString) }
    }

    // ===========================================
    // COMMIT: SINGLE-LAYER COLLAPSE (AUDIT-20 F13)
    // ===========================================

    @Test
    fun `commit collapses a single plain layer back to single-layer and persists it`() = runTest {
        val multi = WallpaperState.multiLayer(
            listOf(WallpaperLayerState(imageUri = "file:///only.png", scale = 2f, label = "Layer 1"))
        )
        val stateFlow = MutableStateFlow<WallpaperState>(multi)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow
        // Hitting cache: keep the composite warm a guaranteed no-op so the test
        // isolates the collapse + persist, not the flatten path.
        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        every { cache.get(any()) } returns mockk(relaxed = true)

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase, compositeCache = cache)
        delegate.start()
        advanceUntilIdle()
        assertTrue(delegate.wallpaperState.value.isMultiLayer)

        delegate.onEnterWallpaperEditMode()
        delegate.onCommitWallpaperEditMode()
        advanceUntilIdle()

        val collapsed = delegate.wallpaperState.value
        assertFalse("commit must collapse a single plain layer", collapsed.isMultiLayer)
        assertEquals("file:///only.png", collapsed.imageUri)
        assertEquals(2f, collapsed.scale)
        coVerify { saveWallpaperStateUseCase.invoke(match { !it.isMultiLayer && it.imageUri == "file:///only.png" }) }
    }

    @Test
    fun `commit collapses even a single layer carrying dead per-layer props`() = runTest {
        // alpha/blend/isVisible are UI-less and slated for retirement; the
        // collapse is unconditional, so even a (theoretical) non-default layer
        // collapses and its dead props are dropped.
        val multi = WallpaperState.multiLayer(
            listOf(WallpaperLayerState(imageUri = "file:///only.png", alpha = 0.5f))
        )
        val stateFlow = MutableStateFlow<WallpaperState>(multi)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow
        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        every { cache.get(any()) } returns mockk(relaxed = true)

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase, compositeCache = cache)
        delegate.start()
        advanceUntilIdle()

        delegate.onEnterWallpaperEditMode()
        delegate.onCommitWallpaperEditMode()
        advanceUntilIdle()

        assertFalse(delegate.wallpaperState.value.isMultiLayer)
        coVerify { saveWallpaperStateUseCase.invoke(match { !it.isMultiLayer && it.imageUri == "file:///only.png" }) }
    }

    // ===========================================
    // MULTI-LAYER: REMOVE LAYER
    // ===========================================

    @Test
    fun `onRemoveWallpaperLayer deletes file and saves state`() = runTest {
        val layerUri = "file:///layer.jpg"
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns layerUri
        }
        val newState: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true        }
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
    fun `onRemoveWallpaperLayer applies the removal synchronously`() = runTest {
        // Structural guarantee (AUDIT-6 addendum): a mutation's read-modify-write
        // of _wallpaperState runs through the synchronous applyState critical
        // section, so the new state is visible IMMEDIATELY — with no suspension
        // point in between for a concurrent mutation to clobber. Uses real
        // WallpaperState objects (not mocks) so the transition is genuine, and
        // asserts BEFORE advanceUntilIdle. The reverted deleteFile -> IO change
        // would have deferred this write into a coroutine and failed here.
        val realState = WallpaperState(
            layers = listOf(
                WallpaperLayerState(imageUri = "file:///a.jpg", label = "Layer 1"),
                WallpaperLayerState(imageUri = "file:///b.jpg", label = "Layer 2"),
            )
        )
        val stateFlow = MutableStateFlow(realState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        // Not in edit mode → immediate delete + synchronous state write.
        delegate.onRemoveWallpaperLayer(0)

        // No advanceUntilIdle: the removal must already be reflected in state.
        assertEquals(1, delegate.wallpaperState.value.layerCount)
        assertEquals("file:///b.jpg", delegate.wallpaperState.value.layers[0].imageUri)
    }

    @Test
    fun `onRemoveWallpaperLayer when last layer removed persists empty state`() = runTest {
        // Under the unified remove path, the delegate never calls
        // clearWallpaperUseCase from onRemoveWallpaperLayer — it simply
        // persists the resulting (empty) state. WallpaperRepositoryImpl.saveWallpaperState
        // treats the empty state as "no wallpaper" and wipes all keys.
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns "file:///layer.jpg"
        }
        val emptyState: WallpaperState = mockk(relaxed = true) {
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

        coVerify(exactly = 0) { clearWallpaperUseCase.invoke() }
        coVerify { saveWallpaperStateUseCase.invoke(any()) }
    }

    // ===========================================
    // MULTI-LAYER: SWAP LAYERS
    // ===========================================

    @Test
    fun `onSwapWallpaperLayers swaps and saves`() = runTest {
        val swappedState: WallpaperState = mockk {        }
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
        val updatedState: WallpaperState = mockk {        }
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
            every { withUpdatedLayer(any(), any()) } returns this        }
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
            LayerTransform(1.0f, 0f, 0f, 1),
            LayerTransform(2.0f, 10f, 20f, 2)
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
    // Contract: onEnterWallpaperEditMode() snapshots the state. Layer removals
    // during the session defer their physical file deletion until commit;
    // added layers track their internal-file URI for orphan cleanup on cancel.
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
        val layerUri = "file:///layer.jpg"
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns layerUri
        }
        val newState: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true        }
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
        verify(exactly = 0) { wallpaperFileManager.deleteFile(any<String>()) }
        verify(exactly = 0) { wallpaperFileManager.deleteFile(any<Uri>()) }
        coVerify { saveWallpaperStateUseCase.invoke(any()) }
    }

    @Test
    fun `onRemoveWallpaperLayer outside edit mode deletes file immediately`() = runTest {
        // Regression guard: non-edit-mode behavior must be unchanged.
        val layerUri = "file:///layer.jpg"
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns layerUri
        }
        val newState: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true        }
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

    /**
     * AUDIT-9 #N1 regression guard: `deleteFile` does blocking disk I/O and must
     * run OFF the main dispatcher. We baseline the dispatch count after `start()`
     * (which also hops `gcOrphans` onto the io dispatcher) and assert the remove
     * pushes at least one more dispatch through it.
     */
    @Test
    fun `onRemoveWallpaperLayer outside edit mode deletes file off the main dispatcher`() = runTest {
        val layerUri = "file:///layer.jpg"
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns layerUri
        }
        val newState: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true
        }
        val currentState: WallpaperState = mockk {
            every { getLayer(0) } returns layer
            every { withRemovedLayer(0) } returns newState
        }

        val stateFlow = MutableStateFlow(currentState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val io = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val delegate = createDelegate(observeWallpaperStateUseCase = useCase, ioDispatcher = io)
        delegate.start()
        advanceUntilIdle()
        val countAfterStart = io.count

        delegate.onRemoveWallpaperLayer(0)
        advanceUntilIdle()

        verify { wallpaperFileManager.deleteFile(layerUri) }
        assertTrue(
            "deleteFile must run on the injected io dispatcher, not the main thread",
            io.count > countAfterStart
        )
    }

    @Test
    fun `onCommitWallpaperEditMode deletes deferred-remove files`() = runTest {
        val layerUri = "file:///layer.jpg"
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns layerUri
        }
        val newState: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true
            // Commit calls toSingleLayer() (F13); this mock state does not collapse.
            every { toSingleLayer() } returns this@mockk
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
        verify(exactly = 0) { wallpaperFileManager.deleteFile(any<String>()) }
        verify(exactly = 0) { wallpaperFileManager.deleteFile(any<Uri>()) }

        delegate.onCommitWallpaperEditMode()
        advanceUntilIdle()

        // Post-commit: deferred file deletion executed
        verify { wallpaperFileManager.deleteFile(layerUri) }
    }

    @Test
    fun `onCancelWallpaperEditMode does not delete deferred-remove files`() = runTest {
        val layerUri = "file:///layer.jpg"
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns layerUri
        }
        val newState: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true        }
        val currentState: WallpaperState = mockk(relaxed = true) {
            every { getLayer(0) } returns layer
            every { withRemovedLayer(0) } returns newState        }

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
            every { hasWallpaper } returns true        }
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns "file:///layer.jpg"
        }
        val snapshotState: WallpaperState = mockk(relaxed = true) {
            every { getLayer(0) } returns layer
            every { withRemovedLayer(0) } returns newState        }

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
        val newStateAfterRemove: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true        }
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns "file:///layer.jpg"
        }
        val snapshotState: WallpaperState = mockk(relaxed = true) {
            every { getLayer(0) } returns layer
            every { withRemovedLayer(0) } returns newStateAfterRemove
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

        // Snapshot must be written back so that any prior in-session
        // saves are overwritten on disk.
        coVerify { saveWallpaperStateUseCase.invoke(snapshotState) }
    }

    @Test
    fun `onCancelWallpaperEditMode deletes files added during edit mode`() = runTest {
        val addedLayerUriString = "file:///added-during-edit.jpg"
        val addedLayerUri: Uri = mockk(relaxed = true)
        every { addedLayerUri.toString() } returns addedLayerUriString
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns addedLayerUri

        val baseState: WallpaperState = mockk(relaxed = true) {
            every { isMultiLayer } returns true
            every { hasWallpaper } returns true
            every { withAddedLayer(any()) } returns this
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
        verify(exactly = 0) { wallpaperFileManager.deleteFile(any<String>()) }
        verify(exactly = 0) { wallpaperFileManager.deleteFile(any<Uri>()) }

        delegate.onCancelWallpaperEditMode()
        advanceUntilIdle()

        // On cancel the orphan file gets cleaned up
        verify { wallpaperFileManager.deleteFile(addedLayerUriString) }
    }

    @Test
    fun `onCommitWallpaperEditMode does not delete files added during edit mode`() = runTest {
        val addedLayerUri: Uri = mockk()
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns addedLayerUri

        val baseState: WallpaperState = mockk(relaxed = true) {
            every { isMultiLayer } returns true
            every { hasWallpaper } returns true
            every { withAddedLayer(any()) } returns this
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
        verify(exactly = 0) { wallpaperFileManager.deleteFile(any<String>()) }
        verify(exactly = 0) { wallpaperFileManager.deleteFile(any<Uri>()) }
    }

    @Test
    fun `onSetWallpaperEditMode(true) behaves like onEnterWallpaperEditMode`() = runTest {
        // Regression guard: legacy API must still snapshot the state
        // so that Cancel can roll back removes done afterwards.
        val layerUri = "file:///layer.jpg"
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns layerUri
        }
        val newState: WallpaperState = mockk {
            every { layers } returns listOf(mockk())
            every { hasWallpaper } returns true        }
        val snapshotState: WallpaperState = mockk(relaxed = true) {
            every { getLayer(0) } returns layer
            every { withRemovedLayer(0) } returns newState        }

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

    // ===========================================
    // LABEL AUTO-NUMBERING
    // ===========================================

    @Test
    fun `onAddWallpaperLayer picks non-colliding auto-label after delete`() = runTest {
        // This is a weak-sauce test at the unit level because mockk()'d
        // WallpaperState cannot easily capture the label of the added layer.
        // The strongest signal we can assert here is that add still
        // persists even when layers is non-empty with gaps in labels.
        val existingLayer: WallpaperLayerState = mockk {
            every { label } returns "Layer 1"
        }
        val addedState: WallpaperState = mockk(relaxed = true)
        val state: WallpaperState = mockk(relaxed = true) {
            every { isMultiLayer } returns true
            every { hasWallpaper } returns true
            every { layers } returns listOf(existingLayer)
            every { withAddedLayer(any()) } returns addedState
        }

        val stateFlow = MutableStateFlow(state)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onAddWallpaperLayer(testUri)
        advanceUntilIdle()

        coVerify { saveWallpaperStateUseCase.invoke(any()) }
    }

    // ===========================================
    // ORPHAN GC — FREQUENCY CONTRACT
    // ===========================================

    @Test
    fun `start runs gcOrphans exactly once across multiple state emissions`() = runTest {
        // Running the GC per-emission would be dangerous: a mid-flight
        // copyFromInputStream (e.g. backup restore) could see its
        // freshly-written file classified as orphan before the matching
        // saveWallpaperState lands. The contract is once per process.
        val stateFlow = MutableStateFlow(WallpaperState.NONE)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        // Emit two more states — these represent routine saves during
        // normal operation (add layer, transform change, etc.).
        stateFlow.value = WallpaperState(imageUri = "file:///x.jpg")
        advanceUntilIdle()
        stateFlow.value = WallpaperState.NONE
        advanceUntilIdle()

        verify(exactly = 1) { wallpaperFileManager.gcOrphans(any<Set<String>>()) }
    }

    /**
     * AUDIT-9 #N1 regression guard: `gcOrphans` does blocking disk I/O
     * (listFiles + delete) and must run OFF the main dispatcher — the observe
     * collect that triggers it runs on the main dispatcher.
     */
    @Test
    fun `start runs gcOrphans off the main dispatcher`() = runTest {
        val stateFlow = MutableStateFlow<WallpaperState>(WallpaperState.NONE)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val io = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val delegate = createDelegate(observeWallpaperStateUseCase = useCase, ioDispatcher = io)
        delegate.start()
        advanceUntilIdle()

        verify(exactly = 1) { wallpaperFileManager.gcOrphans(any<Set<String>>()) }
        assertTrue(
            "gcOrphans must run on the injected io dispatcher, not the main thread",
            io.count > 0
        )
    }

    @Test
    fun `start does not run gcOrphans while an edit session is active`() = runTest {
        // Context: if the user enters edit mode BEFORE the first state
        // emission has been observed (unusual but possible during
        // reconfiguration), we must not GC — pending-cancel files would
        // be destroyed before they can be restored.
        val stateFlow = MutableStateFlow<WallpaperState>(WallpaperState.NONE)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)

        // Enter edit mode BEFORE starting the observer.
        delegate.onEnterWallpaperEditMode()
        delegate.start()
        advanceUntilIdle()

        // Emit additional states too — still no GC while in session.
        stateFlow.value = WallpaperState(imageUri = "file:///x.jpg")
        advanceUntilIdle()

        verify(exactly = 0) { wallpaperFileManager.gcOrphans(any<Set<String>>()) }
        verify(exactly = 0) { wallpaperFileManager.gcOrphans(any<Set<Uri>>()) }
    }

    // ===========================================
    // PENDING FOCUS (auto-activate newly added layer)
    // ===========================================

    @Test
    fun `onAddWallpaperLayer sets pendingFocusLayerId for the new layer`() = runTest {
        val internalUri: Uri = mockk()
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns internalUri

        val state: WallpaperState = mockk(relaxed = true) {
            every { isMultiLayer } returns true
            every { hasWallpaper } returns true
            every { withAddedLayer(any()) } returns this
        }

        val stateFlow = MutableStateFlow(state)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        assertNull("initial pending-focus must be null", delegate.pendingFocusLayerId.value)

        delegate.onAddWallpaperLayer(testUri, "My Layer")
        advanceUntilIdle()

        assertNotNull(
            "after add, pending-focus must carry the new layer's id",
            delegate.pendingFocusLayerId.value
        )
    }

    @Test
    fun `consumePendingFocusLayerId clears the signal`() = runTest {
        val internalUri: Uri = mockk()
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns internalUri

        val state: WallpaperState = mockk(relaxed = true) {
            every { isMultiLayer } returns true
            every { hasWallpaper } returns true
            every { withAddedLayer(any()) } returns this
        }

        val stateFlow = MutableStateFlow(state)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onAddWallpaperLayer(testUri)
        advanceUntilIdle()

        assertNotNull(delegate.pendingFocusLayerId.value)

        delegate.consumePendingFocusLayerId()

        assertNull("consume must clear the signal", delegate.pendingFocusLayerId.value)
    }

    @Test
    fun `onCancelWallpaperEditMode clears pendingFocusLayerId`() = runTest {
        // If the user adds a layer mid-session and then cancels, the
        // pending-focus hint points to a layer that no longer exists
        // in the restored snapshot. It must be cleared to avoid a
        // stale focus attempt on the next unrelated rebuild.
        val internalUri: Uri = mockk()
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns internalUri

        val baseState: WallpaperState = mockk(relaxed = true) {
            every { isMultiLayer } returns true
            every { hasWallpaper } returns true
            every { withAddedLayer(any()) } returns this
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

        assertNotNull(delegate.pendingFocusLayerId.value)

        delegate.onCancelWallpaperEditMode()
        advanceUntilIdle()

        assertNull(
            "cancel must drop the pending-focus hint — the added layer no longer exists",
            delegate.pendingFocusLayerId.value
        )
    }

    // ===========================================
    // FAB POSITION
    // ===========================================

    @Test
    fun `fabPosition starts at DEFAULT when the use case has not emitted`() {
        val delegate = createDelegate()
        // initialValue of stateIn — the empty flow never emits.
        assertEquals(com.github.reygnn.kolibri_launcher.domain.model.FabPosition.DEFAULT, delegate.fabPosition.value)
    }

    @Test
    fun `fabPosition reflects use-case flow emissions`() = runTest {
        val flow = MutableStateFlow(com.github.reygnn.kolibri_launcher.domain.model.FabPosition(xFraction = 0.2f, yFraction = 0.3f))
        every { getFabPositionUseCase.invoke() } returns flow

        val delegate = createDelegate()
        // StateIn(WhileSubscribed) requires at least one collector. Trigger it
        // on the test's backgroundScope so the test body owns no cancel.
        backgroundScope.launch { delegate.fabPosition.collect { } }
        advanceUntilIdle()
        assertEquals(0.2f, delegate.fabPosition.value.xFraction)
        assertEquals(0.3f, delegate.fabPosition.value.yFraction)

        flow.value = com.github.reygnn.kolibri_launcher.domain.model.FabPosition(xFraction = 0.7f, yFraction = 0.8f)
        advanceUntilIdle()
        assertEquals(0.7f, delegate.fabPosition.value.xFraction)
        assertEquals(0.8f, delegate.fabPosition.value.yFraction)
    }

    @Test
    fun `onFabPositionChanged invokes saveFabPositionUseCase with the given fractions`() = runTest {
        val delegate = createDelegate()
        delegate.onFabPositionChanged(xFraction = 0.42f, yFraction = 0.58f)
        advanceUntilIdle()
        coVerify {
            saveFabPositionUseCase.invoke(
                com.github.reygnn.kolibri_launcher.domain.model.FabPosition(xFraction = 0.42f, yFraction = 0.58f)
            )
        }
    }
}