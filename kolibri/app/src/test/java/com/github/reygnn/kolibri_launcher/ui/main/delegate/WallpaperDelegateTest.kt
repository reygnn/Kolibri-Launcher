package com.github.reygnn.kolibri_launcher.ui.main.delegate
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.feature.wallpaper.WallpaperDisplaySettingsStore
import com.github.reygnn.kolibri_launcher.data.KolibriWallpaperDisplayKeys
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.launcher.feature.wallpaper.CachedWallpaperComposite
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStore
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperRepositoryImpl

import com.github.reygnn.kolibri_launcher.domain.model.SettingsDefaults

import com.github.reygnn.launcher.core.wallpaper.LayerTransform
import android.content.Context
import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import android.graphics.Bitmap
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperCompositeCache
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperFlattener
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.kolibri_launcher.domain.usecase.ClearWallpaperUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFabPositionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperBackdropUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveFabPositionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveWallpaperStateUseCase
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
    private lateinit var clearWallpaperUseCase: ClearWallpaperUseCase
    private lateinit var getFabPositionUseCase: GetFabPositionUseCase
    private lateinit var saveFabPositionUseCase: SaveFabPositionUseCase
    private lateinit var observeWallpaperBackdropUseCase: ObserveWallpaperBackdropUseCase
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
        clearWallpaperUseCase = mockk(relaxed = true)

        getFabPositionUseCase = mockk(relaxed = true)
        every { getFabPositionUseCase.invoke() } returns emptyFlow()
        saveFabPositionUseCase = mockk(relaxed = true)

        observeWallpaperBackdropUseCase = mockk(relaxed = true)
        every { observeWallpaperBackdropUseCase.invoke() } returns emptyFlow()

        wallpaperFileManager = mockk(relaxed = true)
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns internalUri
    }

    /** The in-memory DataStore behind the display-settings store (3a-9b backdrop tests). */
    private val backdropDataStore = FakeDataStore()

    private val backdropKey = stringPreferencesKey("wallpaper_backdrop")

    private suspend fun persistedBackdrop(): String? = backdropDataStore.data.first()[backdropKey]

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
        bitmapLuminance: com.github.reygnn.launcher.common.data.wallpaper.WallpaperBitmapLuminanceImpl = mockk(relaxed = true),
        compositeLuminanceSignal: com.github.reygnn.launcher.core.CompositeLuminanceSignal = mockk(relaxed = true),
        // The real display-settings store over an in-memory DataStore (3a-9b): the backdrop
        // toggle lives there now, and its tests check what lands in the store.
        displaySettings: WallpaperDisplaySettingsStore = WallpaperDisplaySettingsStore(backdropDataStore, KolibriWallpaperDisplayKeys),
    ) = WallpaperDelegate(
        context = context,
        observeWallpaperStateUseCase = observeWallpaperStateUseCase,
        saveWallpaperStateUseCase = saveWallpaperStateUseCase,
        clearWallpaperUseCase = clearWallpaperUseCase,
        getFabPositionUseCase = getFabPositionUseCase,
        saveFabPositionUseCase = saveFabPositionUseCase,
        observeWallpaperBackdropUseCase = observeWallpaperBackdropUseCase,
        imageStore = WallpaperImageStore(wallpaperFileManager, persistedNothing(), ioDispatcher),
        // The composite since 3a-8: the real implementation around the same mocks, so every
        // composite expectation below (cache, flattener, luminance, IO hop) stays as it was.
        composite = CachedWallpaperComposite(compositeCache, wallpaperFlattener, bitmapLuminance, compositeLuminanceSignal, ioDispatcher),
        displaySettings = displaySettings,
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
        assertThat(delegate.wallpaperState.value).isEqualTo(WallpaperState.NONE)
    }

    @Test
    fun `initial isWallpaperEditMode is false`() {
        val delegate = createDelegate()
        assertThat(delegate.isWallpaperEditMode.value).isFalse()
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

        assertThat(delegate.wallpaperState.value).isEqualTo(testState)
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
        assertThat(delegate.wallpaperState.value).isEqualTo(state1)

        stateFlow.value = state2
        advanceUntilIdle()
        assertThat(delegate.wallpaperState.value).isEqualTo(state2)
    }

    // ===========================================
    // COMPOSITE WARM (in-memory, v4)
    // ===========================================
    //
    // Only the warm's real software->HARDWARE Bitmap.copy SEMANTICS are on-device (a JVM mock can't
    // reproduce the copy); that part is covered on-device, and flatten completeness + the content key
    // are unit-tested in WallpaperFlattener/WallpaperCompositeKey tests. Everything around the copy IS
    // JVM-pinned here with a relaxed Bitmap mock standing in for it: the warm TRIGGER GATE (that a
    // flatten is (not) kicked) AND the luminance-emit path (success -> emit(value), fail/clear -> emit(null)).

    @Test
    fun `refill does nothing when there is no wallpaper`() = runTest {
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(WallpaperState.NONE)

        val flattener: WallpaperFlattener = mockk(relaxed = true)
        val delegate = createDelegate(observeWallpaperStateUseCase = useCase, wallpaperFlattener = flattener)

        delegate.start()
        advanceUntilIdle()

        coVerify(exactly = 0) { flattener.flatten(any(), any(), any()) }
    }

    @Test
    fun `does not warm when the composite is already cached`() = runTest {
        // v4: the warm is gated on a compositeCache MISS. A cache hit means the composite is
        // already in memory, so no flatten runs (replaces the old "composite path exists" gate).
        // Two layers so this is a genuine composite (layerCount >= 2) — the flatten path.
        val multi = WallpaperState(
            layers = listOf(
                WallpaperLayerState(imageUri = "file:///l1.jpg"),
                WallpaperLayerState(imageUri = "file:///l2.jpg"),
            )
        )
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
        val multi = WallpaperState(
            layers = listOf(
                WallpaperLayerState(imageUri = "file:///l1.jpg"),
                WallpaperLayerState(imageUri = "file:///l2.jpg"),
            )
        )
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(multi)

        val flattener: WallpaperFlattener = mockk()
        coEvery { flattener.flatten(any(), any(), any()) } returns null // failed / partial flatten

        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        every { cache.get(any()) } returns null // miss -> the warm fires
        val luminanceSignal: com.github.reygnn.launcher.core.CompositeLuminanceSignal = mockk(relaxed = true)

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

    @Test
    fun `onClearWallpaper drops the composite luminance for the AUTO classifier`() = runTest {
        // §25 P4 guard (finding 3): warmComposite is the SOLE producer of the composite-luminance
        // signal the AUTO surface classifier consumes, and no test guarded that feed. This pins the
        // CLEAR half — removing the wallpaper must drop the signal to null so the classifier stops
        // using the removed wallpaper's value. The positive warm -> emit(value) half is pinned by
        // the sibling test below; WAH-INV-6 (never delete warmComposite) is the standing tripwire.
        val luminanceSignal: com.github.reygnn.launcher.core.CompositeLuminanceSignal = mockk(relaxed = true)
        val delegate = createDelegate(compositeLuminanceSignal = luminanceSignal)

        delegate.onClearWallpaper()
        advanceUntilIdle()

        verify { luminanceSignal.emit(null) }
    }

    @Test
    fun `a successful multi-layer warm emits the composite luminance for the AUTO classifier`() = runTest {
        // §25 review T3: the POSITIVE half of the luminance feed (warmComposite success ->
        // emit(luminance)), the value the AUTO surface classifier reads for a multi-layer wallpaper.
        // Pure-JVM after all: the flatten result and its HARDWARE copy are relaxed Bitmap mocks and
        // computeFromBitmap is stubbed, so no real Bitmap.copy(HARDWARE) / Robolectric is needed. A
        // regression that dropped or mis-valued WallpaperDelegate.warmComposite's emit(luminance)
        // would leave the classifier on a stale LIGHT/DARK surface with no crash — now caught here.
        val multi = WallpaperState(
            layers = listOf(
                WallpaperLayerState(imageUri = "file:///l1.jpg"),
                WallpaperLayerState(imageUri = "file:///l2.jpg"),
            )
        )
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(multi)

        val flattener: WallpaperFlattener = mockk()
        coEvery { flattener.flatten(any(), any(), any()) } returns mockk<Bitmap>(relaxed = true)

        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        every { cache.get(any()) } returns null // miss -> the warm fires

        val bitmapLuminance: com.github.reygnn.launcher.common.data.wallpaper.WallpaperBitmapLuminanceImpl =
            mockk(relaxed = true)
        every { bitmapLuminance.computeFromBitmap(any()) } returns 0.73f

        val luminanceSignal: com.github.reygnn.launcher.core.CompositeLuminanceSignal = mockk(relaxed = true)

        val delegate = createDelegate(
            observeWallpaperStateUseCase = useCase,
            wallpaperFlattener = flattener,
            compositeCache = cache,
            bitmapLuminance = bitmapLuminance,
            compositeLuminanceSignal = luminanceSignal,
        )

        delegate.start()
        advanceUntilIdle()

        verify { luminanceSignal.emit(0.73f) } // the sampled composite luminance is published
    }

    /**
     * AUDIT-20 F12 (structural): refillCache drops a stale-key entry UP FRONT
     * ([WallpaperCompositeCache.invalidateIfNotKey]) before deciding to warm, so a warm that
     * then FAILS (flatten -> null here) cannot strand the prior-resolution ~10 MB bitmap
     * under a key nothing queries. The drop must not depend on a successful put.
     */
    @Test
    fun `a failed warm still drops any stale-key cache entry up front`() = runTest {
        val multi = WallpaperState(
            layers = listOf(
                WallpaperLayerState(imageUri = "file:///l1.jpg"),
                WallpaperLayerState(imageUri = "file:///l2.jpg"),
            )
        )
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(multi)

        val flattener: WallpaperFlattener = mockk()
        coEvery { flattener.flatten(any(), any(), any()) } returns null // warm fails -> no put

        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        every { cache.get(any()) } returns null // miss -> the warm fires

        val delegate = createDelegate(
            observeWallpaperStateUseCase = useCase,
            wallpaperFlattener = flattener,
            compositeCache = cache,
        )

        delegate.start()
        advanceUntilIdle()

        verify { cache.invalidateIfNotKey(any()) } // dropped up front, not gated on success
        verify(exactly = 0) { cache.put(any(), any()) } // warm failed -> nothing (re)cached
    }

    /**
     * §25 review M1/T2: a multi->single-layer transition (an edit deletes a layer down to one and
     * commits) must not strand the prior multi-layer composite. cacheKeyOrNull is null for a
     * single-layer state (§25 P4), so [WallpaperDelegate.refillCache] now drops the resident
     * ~10 MB composite AND its luminance on the null-key branch instead of early-returning — before
     * the fix the composite stayed resident until clear / next multi-warm / process death, and the
     * AUTO classifier kept the removed composite's luminance (M1 leak + T2 stale-signal). The branch
     * fires for any null-key state, so a single-layer state models it; the multi->single transition
     * is what produces a resident composite to drop in production.
     */
    @Test
    fun `a single-layer state drops any resident composite and its luminance`() = runTest {
        val single = WallpaperState(layers = listOf(WallpaperLayerState(imageUri = "file:///l1.jpg")))
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(single)

        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        val luminanceSignal: com.github.reygnn.launcher.core.CompositeLuminanceSignal = mockk(relaxed = true)

        val delegate = createDelegate(
            observeWallpaperStateUseCase = useCase,
            compositeCache = cache,
            compositeLuminanceSignal = luminanceSignal,
        )

        delegate.start()
        advanceUntilIdle()

        verify { cache.invalidate() } // the stale multi-layer composite is dropped, not stranded
        verify { luminanceSignal.emit(null) } // AUTO classifier stops reading the removed composite's value
    }

    /**
     * AUDIT-20 F11: leaving edit mode is a single funnel ([WallpaperDelegate.leaveEditMode])
     * that refills the display cache for BOTH commit and cancel. A no-op cancel restores an
     * unchanged state and produces no DataStore emission, so this explicit warm is the only
     * trigger that re-fills after a config change (rotate/fold) that was deferred while
     * editing — without it the composite stays cold for the new resolution until some later
     * emission. Modeled with a permanently-missing cache so every refill warms; the counter
     * proves the cancel path added a second warm on top of start()'s.
     */
    @Test
    fun `onCancelWallpaperEditMode warms the display cache via the exit funnel`() = runTest {
        val flattenCalls = AtomicInteger(0)
        val bitmap: Bitmap = mockk(relaxed = true)
        val flattener: WallpaperFlattener = mockk()
        coEvery { flattener.flatten(any(), any(), any()) } coAnswers {
            flattenCalls.incrementAndGet()
            bitmap
        }
        val multi = WallpaperState(
            layers = listOf(
                WallpaperLayerState(imageUri = "file:///l1.jpg"),
                WallpaperLayerState(imageUri = "file:///l2.jpg"),
            )
        )
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(multi)

        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        every { cache.get(any()) } returns null // permanent miss -> every refill warms

        val delegate = createDelegate(
            observeWallpaperStateUseCase = useCase,
            wallpaperFlattener = flattener,
            compositeCache = cache,
        )

        delegate.start()
        advanceUntilIdle()
        assertWithMessage("start warms once via the collect loop").that(flattenCalls.get()).isEqualTo(1)

        delegate.onEnterWallpaperEditMode()
        delegate.onCancelWallpaperEditMode() // unchanged snapshot -> no emission; the funnel must warm
        advanceUntilIdle()

        assertWithMessage("cancel must trigger a second warm through leaveEditMode").that(flattenCalls.get()).isEqualTo(2)
    }

    /**
     * Anti-loop guard (refillCache self-reschedule, spec S5): a warm that fails for the CURRENT
     * key must NOT re-fire the same key, or a persistently-failing fill would spin forever. With
     * flatten permanently failing and the cache a permanent miss, the collect-loop warm runs
     * exactly once — the self-reschedule sees currentKey == key and declines. If the guard broke,
     * this test would hang (infinite reschedule), so exactly-one IS the assertion.
     */
    @Test
    fun `a persistently failing warm does not self-reschedule the same key`() = runTest {
        val flattenCalls = AtomicInteger(0)
        val flattener: WallpaperFlattener = mockk()
        coEvery { flattener.flatten(any(), any(), any()) } coAnswers {
            flattenCalls.incrementAndGet()
            null // always fails
        }
        val multi = WallpaperState(
            layers = listOf(
                WallpaperLayerState(imageUri = "file:///l1.jpg"),
                WallpaperLayerState(imageUri = "file:///l2.jpg"),
            )
        )
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(multi)

        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        every { cache.get(any()) } returns null // permanent miss

        val delegate = createDelegate(
            observeWallpaperStateUseCase = useCase,
            wallpaperFlattener = flattener,
            compositeCache = cache,
        )

        delegate.start()
        advanceUntilIdle()

        assertWithMessage("same-key failure must not loop -> exactly one warm").that(flattenCalls.get()).isEqualTo(1)
    }

    /**
     * Single-flight (refillInProgress): while a warm is in flight, a second refill trigger must be
     * dropped, not start a concurrent warm. The first warm parks on a gate; onDisplayConfigChanged
     * fires a refill into that window; it must early-return, leaving the flatten count at one.
     */
    @Test
    fun `a refill trigger while a warm is in flight is dropped`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val flattenCalls = AtomicInteger(0)
        val bitmap: Bitmap = mockk(relaxed = true)
        val flattener: WallpaperFlattener = mockk()
        coEvery { flattener.flatten(any(), any(), any()) } coAnswers {
            flattenCalls.incrementAndGet()
            gate.await()
            bitmap
        }
        val multi = WallpaperState(
            layers = listOf(
                WallpaperLayerState(imageUri = "file:///l1.jpg"),
                WallpaperLayerState(imageUri = "file:///l2.jpg"),
            )
        )
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(multi)

        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        every { cache.get(any()) } returns null

        val delegate = createDelegate(
            observeWallpaperStateUseCase = useCase,
            wallpaperFlattener = flattener,
            compositeCache = cache,
        )

        delegate.start()
        advanceUntilIdle() // warm #1 parks on the gate, refillInProgress == true
        assertThat(flattenCalls.get()).isEqualTo(1)

        delegate.onDisplayConfigChanged() // refillCache -> single-flight guard -> early return
        advanceUntilIdle()
        assertWithMessage("second trigger while in-flight must be dropped").that(flattenCalls.get()).isEqualTo(1)

        gate.complete(Unit)
        advanceUntilIdle()
        assertWithMessage("same key -> no self-reschedule after completion").that(flattenCalls.get()).isEqualTo(1)
    }

    /**
     * F11 commit half: the leaveEditMode funnel warms on COMMIT too (the F11 test above pins the
     * cancel half). A two-layer state stays multi through the F13 collapse (a no-op for >1 layer),
     * so the committed warm takes the composite flatten path.
     */
    @Test
    fun `onCommitWallpaperEditMode warms the display cache via the exit funnel`() = runTest {
        val flattenCalls = AtomicInteger(0)
        val bitmap: Bitmap = mockk(relaxed = true)
        val flattener: WallpaperFlattener = mockk()
        coEvery { flattener.flatten(any(), any(), any()) } coAnswers {
            flattenCalls.incrementAndGet()
            bitmap
        }
        val multi = WallpaperState(
            layers = listOf(
                WallpaperLayerState(imageUri = "file:///l1.jpg"),
                WallpaperLayerState(imageUri = "file:///l2.jpg"),
            ),
        )
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(multi)

        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        every { cache.get(any()) } returns null // permanent miss -> every refill warms

        val delegate = createDelegate(
            observeWallpaperStateUseCase = useCase,
            wallpaperFlattener = flattener,
            compositeCache = cache,
        )

        delegate.start()
        advanceUntilIdle()
        assertWithMessage("start warms once via the collect loop").that(flattenCalls.get()).isEqualTo(1)

        delegate.onEnterWallpaperEditMode()
        delegate.onCommitWallpaperEditMode()
        advanceUntilIdle()

        assertWithMessage("commit must trigger a second warm through leaveEditMode").that(flattenCalls.get()).isEqualTo(2)
    }

    /**
     * Config-change re-warm (rotate/fold): onDisplayConfigChanged drives a refill so the composite
     * is re-warmed for the new resolution's key. Pins the delegate-level trigger (the key test
     * proves the resolution change is a MISS; this proves the miss actually re-warms).
     */
    @Test
    fun `onDisplayConfigChanged re-warms the composite`() = runTest {
        val flattenCalls = AtomicInteger(0)
        val bitmap: Bitmap = mockk(relaxed = true)
        val flattener: WallpaperFlattener = mockk()
        coEvery { flattener.flatten(any(), any(), any()) } coAnswers {
            flattenCalls.incrementAndGet()
            bitmap
        }
        val multi = WallpaperState(
            layers = listOf(
                WallpaperLayerState(imageUri = "file:///l1.jpg"),
                WallpaperLayerState(imageUri = "file:///l2.jpg"),
            )
        )
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(multi)

        val cache: WallpaperCompositeCache = mockk(relaxed = true)
        every { cache.get(any()) } returns null // permanent miss

        val delegate = createDelegate(
            observeWallpaperStateUseCase = useCase,
            wallpaperFlattener = flattener,
            compositeCache = cache,
        )

        delegate.start()
        advanceUntilIdle()
        assertThat(flattenCalls.get()).isEqualTo(1)

        delegate.onDisplayConfigChanged()
        advanceUntilIdle()

        assertWithMessage("config change must drive a re-warm").that(flattenCalls.get()).isEqualTo(2)
    }

    // ===========================================
    // SET WALLPAPER IMAGE
    // ===========================================

    @Test
    fun `onSetWallpaperImage copies file and saves the single-image state`() = runTest {
        // 3a-9 (K2): the replace saves through the persistence port — the single-image state,
        // exactly what the former SetWallpaperImageUseCase did (removed after 3b-7).
        val delegate = createDelegate()

        delegate.onSetWallpaperImage(testUri)
        advanceUntilIdle()

        coVerify { wallpaperFileManager.copyToInternal(testUri) }
        coVerify { saveWallpaperStateUseCase.invoke(match { it.layerCount == 1 && it.layers.single().imageUri == internalUriString }) }
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

        coVerify { saveWallpaperStateUseCase.invoke(match { it.layerCount == 1 && it.layers.single().imageUri == internalUriString }) }
        assertThat(sentEvents.isEmpty()).isTrue()
    }

    @Test
    fun `onSetWallpaperImage shows error when copy fails`() = runTest {
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns null

        val delegate = createDelegate()

        delegate.onSetWallpaperImage(testUri)
        advanceUntilIdle()

        coVerify(exactly = 0) { saveWallpaperStateUseCase.invoke(any()) }
        assertThat(sentEvents.any { it is UiEvent.ShowToast }).isTrue()
    }

    @Test
    fun `onSetWallpaperImage shows error toast on exception`() = runTest {
        coEvery { wallpaperFileManager.copyToInternal(any()) } throws RuntimeException("IO error")

        val delegate = createDelegate()

        delegate.onSetWallpaperImage(testUri)
        advanceUntilIdle()

        assertThat(sentEvents.any { it is UiEvent.ShowToast }).isTrue()
    }

    // ===========================================
    // SAVE WALLPAPER TRANSFORM
    // ===========================================

    @Test
    fun `onSaveWallpaperTransform updates state synchronously and persists`() = runTest {
        // The stale-frame fix: the new transform must land in _wallpaperState
        // SYNCHRONOUSLY (before the async persist), so the commit-triggered re-render
        // reads it on the first frame instead of the old transform.
        val initial = WallpaperState.single("file:///a.png", scale = 1f)
        val stateFlow = MutableStateFlow(initial)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onSaveWallpaperTransform(2.0f, 10f, 20f)

        // Synchronous — asserted BEFORE advanceUntilIdle (no persist round-trip yet). A
        // single-image wallpaper is the one-element layer list, so the transform lands
        // in layer 0.
        assertThat(delegate.wallpaperState.value.layers.first().scale).isEqualTo(2.0f)
        assertThat(delegate.wallpaperState.value.layers.first().translateX).isEqualTo(10f)
        assertThat(delegate.wallpaperState.value.layers.first().translateY).isEqualTo(20f)

        advanceUntilIdle()
        coVerify {
            saveWallpaperStateUseCase.invoke(
                match {
                    it.layers.first().scale == 2.0f &&
                        it.layers.first().translateX == 10f &&
                        it.layers.first().translateY == 20f
                }
            )
        }
    }

    @Test
    fun `onSaveWallpaperTransform does nothing when no wallpaper`() = runTest {
        val delegate = createDelegate()

        delegate.onSaveWallpaperTransform(2.0f, 10f, 20f)
        advanceUntilIdle()

        assertThat(delegate.wallpaperState.value).isEqualTo(WallpaperState.NONE)
        coVerify(exactly = 0) { saveWallpaperStateUseCase.invoke(any()) }
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

        assertThat(sentEvents.any { it is UiEvent.ShowToast }).isTrue()
    }

    @Test
    fun `onClearWallpaper shows error toast on exception`() = runTest {
        every { wallpaperFileManager.clearAll() } throws RuntimeException("IO error")

        val delegate = createDelegate()

        delegate.onClearWallpaper()
        advanceUntilIdle()

        assertThat(sentEvents.any { it is UiEvent.ShowToast }).isTrue()
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
        assertWithMessage("clearAll must run on the injected io dispatcher, not the main thread").that(io.count > 0).isTrue()
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
        val multiNoComposite = WallpaperState(
            layers = listOf(
                WallpaperLayerState(imageUri = "file:///l1.jpg"),
                WallpaperLayerState(imageUri = "file:///l2.jpg"),
            )
        )
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
        assertThat(flattenCalls.get()).isEqualTo(1)

        delegate.onClearWallpaper() // blocks on the held regen lock
        advanceUntilIdle()
        coVerify(exactly = 0) { clearWallpaperUseCase.invoke() } // must wait for the lock

        gate.complete(Unit) // backfill releases the lock
        advanceUntilIdle()

        coVerify { clearWallpaperUseCase.invoke() }
        assertThat(delegate.wallpaperState.value).isEqualTo(WallpaperState.NONE)
    }

    // ===========================================
    // EDIT MODE
    // ===========================================

    @Test
    fun `onSetWallpaperEditMode sets to true`() {
        val delegate = createDelegate()

        delegate.onSetWallpaperEditMode(true)

        assertThat(delegate.isWallpaperEditMode.value).isTrue()
    }

    @Test
    fun `onSetWallpaperEditMode sets to false`() {
        val delegate = createDelegate()

        delegate.onSetWallpaperEditMode(true)
        delegate.onSetWallpaperEditMode(false)

        assertThat(delegate.isWallpaperEditMode.value).isFalse()
    }

    @Test
    fun `onToggleWallpaperEditMode toggles from false to true`() {
        val delegate = createDelegate()

        assertThat(delegate.isWallpaperEditMode.value).isFalse()
        delegate.onToggleWallpaperEditMode()
        assertThat(delegate.isWallpaperEditMode.value).isTrue()
    }

    @Test
    fun `onToggleWallpaperEditMode toggles from true to false`() {
        val delegate = createDelegate()

        delegate.onSetWallpaperEditMode(true)
        delegate.onToggleWallpaperEditMode()

        assertThat(delegate.isWallpaperEditMode.value).isFalse()
    }

    @Test
    fun `onToggleWallpaperEditMode toggles multiple times`() {
        val delegate = createDelegate()

        delegate.onToggleWallpaperEditMode() // false -> true
        assertThat(delegate.isWallpaperEditMode.value).isTrue()

        delegate.onToggleWallpaperEditMode() // true -> false
        assertThat(delegate.isWallpaperEditMode.value).isFalse()

        delegate.onToggleWallpaperEditMode() // false -> true
        assertThat(delegate.isWallpaperEditMode.value).isTrue()
    }

    // ===========================================
    // MULTI-LAYER: ADD LAYER
    // ===========================================

    @Test
    fun `onAddWallpaperLayer copies file and saves state`() = runTest {
        val addedState: WallpaperState = mockk(relaxed = true) {        }
        val state: WallpaperState = mockk(relaxed = true) {
            every { hasWallpaper } returns true
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
        assertThat(delegate.isWallpaperEditMode.value).isTrue()

        // User cancels before the add's work runs — synchronous session teardown.
        delegate.onCancelWallpaperEditMode()
        assertThat(delegate.isWallpaperEditMode.value).isFalse()

        // The add now runs across the closed session boundary.
        advanceUntilIdle()

        // The cancelled layer must not be persisted…
        assertWithMessage("add from a closed session must not persist a layer").that(delegate.wallpaperState.value.hasWallpaper).isFalse()
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
        assertThat(delegate.isWallpaperEditMode.value).isFalse()

        // The add now runs after the commit and must persist its layer.
        advanceUntilIdle()

        assertWithMessage("add resuming after commit must persist its layer").that(delegate.wallpaperState.value.hasWallpaper).isTrue()
        // Adding a layer to NONE yields the one-element layer list (a lone image).
        assertThat(delegate.wallpaperState.value.layerCount).isEqualTo(1)
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

        assertThat(delegate.wallpaperState.value.hasWallpaper).isTrue()
        // Adding a layer to NONE yields the one-element layer list (a lone image).
        assertThat(delegate.wallpaperState.value.layerCount).isEqualTo(1)
        coVerify { saveWallpaperStateUseCase.invoke(match { it.hasWallpaper }) }
        verify(exactly = 0) { wallpaperFileManager.deleteFile(internalUriString) }
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
                WallpaperLayerState(imageUri = "file:///a.jpg"),
                WallpaperLayerState(imageUri = "file:///b.jpg"),
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
        assertThat(delegate.wallpaperState.value.layerCount).isEqualTo(1)
        assertThat(delegate.wallpaperState.value.layers[0].imageUri).isEqualTo("file:///b.jpg")
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

        assertThat(delegate.isWallpaperEditMode.value).isTrue()
    }

    @Test
    fun `onCommitWallpaperEditMode sets edit mode to false`() {
        val delegate = createDelegate()

        delegate.onEnterWallpaperEditMode()
        delegate.onCommitWallpaperEditMode()

        assertThat(delegate.isWallpaperEditMode.value).isFalse()
    }

    @Test
    fun `onCancelWallpaperEditMode sets edit mode to false`() {
        val delegate = createDelegate()

        delegate.onEnterWallpaperEditMode()
        delegate.onCancelWallpaperEditMode()

        assertThat(delegate.isWallpaperEditMode.value).isFalse()
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
        assertWithMessage("deleteFile must run on the injected io dispatcher, not the main thread").that(io.count > countAfterStart).isTrue()
    }

    @Test
    fun `onCommitWallpaperEditMode deletes deferred-remove files`() = runTest {
        val layerUri = "file:///layer.jpg"
        val layer: WallpaperLayerState = mockk {
            every { imageUri } returns layerUri
        }
        // Relaxed: commit refills the display cache synchronously through the F11 exit
        // funnel, which reads layerCount/layers on this committed state. The lone remaining
        // layer carries no image, so cacheKeyOrNull returns null and the refill early-returns
        // — this test is about the deferred file deletion, not the warm.
        val newState: WallpaperState = mockk(relaxed = true) {
            every { layers } returns listOf(WallpaperLayerState(imageUri = null))
            every { hasWallpaper } returns true
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
        assertThat(delegate.wallpaperState.value).isEqualTo(newState)

        // Key guarantee: in-memory state is reverted BEFORE any further
        // coroutine work — the caller (HomeFragment) relies on this so
        // it can immediately feed the restored value into updateWallpaper().
        delegate.onCancelWallpaperEditMode()
        assertThat(delegate.wallpaperState.value).isEqualTo(snapshotState)
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
    fun `add then remove of the same layer in one session - commit deletes the file exactly once`() = runTest {
        // Combination the single-op tests above never exercise: within ONE session the
        // user adds a layer (its copied file is tracked in pendingRemovalsOnCancel) and
        // then removes that SAME layer (its file is deferred into pendingRemovalsOnCommit),
        // so the one URI is in BOTH sets. On commit only pendingRemovalsOnCommit is
        // processed — the file must be deleted, and EXACTLY once (never double-deleted,
        // never orphaned).
        val addedLayer: WallpaperLayerState = mockk { every { imageUri } returns internalUriString }
        val afterRemove: WallpaperState = mockk(relaxed = true) {
            every { layers } returns listOf(WallpaperLayerState(imageUri = null))
            every { hasWallpaper } returns true
        }
        val afterAdd: WallpaperState = mockk(relaxed = true) {
            every { getLayer(0) } returns addedLayer          // the just-added layer
            every { withRemovedLayer(0) } returns afterRemove
        }
        val baseState: WallpaperState = mockk(relaxed = true) {
            every { hasWallpaper } returns true
            every { withAddedLayer(any()) } returns afterAdd
        }

        val stateFlow = MutableStateFlow(baseState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onEnterWallpaperEditMode()
        delegate.onAddWallpaperLayer(testUri)   // copyToInternal → internalUriString
        advanceUntilIdle()
        delegate.onRemoveWallpaperLayer(0)       // removes that same layer
        advanceUntilIdle()

        delegate.onCommitWallpaperEditMode()
        advanceUntilIdle()

        verify(exactly = 1) { wallpaperFileManager.deleteFile(internalUriString) }
        // ...and only that one file — the double set-membership must not double-delete.
        verify(exactly = 1) { wallpaperFileManager.deleteFile(any<String>()) }
    }

    @Test
    fun `add then remove of the same layer in one session - cancel deletes the file exactly once and restores the snapshot`() = runTest {
        // Same combination, opposite exit. On cancel only pendingRemovalsOnCancel is
        // processed: the in-session-added file is cleaned up (exactly once), and the
        // pre-session snapshot — which never contained the layer — is restored.
        val addedLayer: WallpaperLayerState = mockk { every { imageUri } returns internalUriString }
        val afterRemove: WallpaperState = mockk(relaxed = true) {
            every { layers } returns listOf(WallpaperLayerState(imageUri = null))
            every { hasWallpaper } returns true
        }
        val afterAdd: WallpaperState = mockk(relaxed = true) {
            every { getLayer(0) } returns addedLayer
            every { withRemovedLayer(0) } returns afterRemove
        }
        val baseState: WallpaperState = mockk(relaxed = true) {
            every { hasWallpaper } returns true
            every { withAddedLayer(any()) } returns afterAdd
        }

        val stateFlow = MutableStateFlow(baseState)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onEnterWallpaperEditMode()      // snapshot = baseState
        delegate.onAddWallpaperLayer(testUri)
        advanceUntilIdle()
        delegate.onRemoveWallpaperLayer(0)
        advanceUntilIdle()

        delegate.onCancelWallpaperEditMode()
        // Snapshot restored synchronously, before the async cleanup runs.
        assertThat(delegate.wallpaperState.value).isEqualTo(baseState)

        advanceUntilIdle()
        verify(exactly = 1) { wallpaperFileManager.deleteFile(internalUriString) }
        verify(exactly = 1) { wallpaperFileManager.deleteFile(any<String>()) }
    }

    // ===========================================
    // wallpaperImageChanged signal (scrim-reset offer)
    // ===========================================

    @Test
    fun `onClearWallpaper signals wallpaper image changed`() = runTest {
        val delegate = createDelegate()
        val events = mutableListOf<Unit>()
        val job = launch(mainDispatcherRule.testDispatcher) {
            delegate.wallpaperImageChanged.collect { events.add(it) }
        }

        delegate.onClearWallpaper()
        advanceUntilIdle()

        assertThat(events.size).isEqualTo(1)
        job.cancel()
    }

    @Test
    fun `onSetWallpaperImage outside edit mode signals immediately`() = runTest {
        val delegate = createDelegate()
        val events = mutableListOf<Unit>()
        val job = launch(mainDispatcherRule.testDispatcher) {
            delegate.wallpaperImageChanged.collect { events.add(it) }
        }

        delegate.onSetWallpaperImage(testUri)          // picker path, no session
        advanceUntilIdle()

        assertThat(events.size).isEqualTo(1)
        job.cancel()
    }

    @Test
    fun `an in-session image change signals on commit, not before`() = runTest {
        val delegate = createDelegate()
        val events = mutableListOf<Unit>()
        val job = launch(mainDispatcherRule.testDispatcher) {
            delegate.wallpaperImageChanged.collect { events.add(it) }
        }

        delegate.onEnterWallpaperEditMode()
        delegate.onAddWallpaperLayer(testUri)
        advanceUntilIdle()
        assertWithMessage("deferred while editing (scrim is hidden there)").that(events.size).isEqualTo(0)

        delegate.onCommitWallpaperEditMode()
        advanceUntilIdle()
        assertWithMessage("surfaces on commit").that(events.size).isEqualTo(1)
        job.cancel()
    }

    @Test
    fun `transform-only commit does not signal`() = runTest {
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(WallpaperState.single("file:///a.jpg"))
        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        val events = mutableListOf<Unit>()
        val job = launch(mainDispatcherRule.testDispatcher) {
            delegate.wallpaperImageChanged.collect { events.add(it) }
        }

        delegate.onEnterWallpaperEditMode()
        delegate.onSaveWallpaperTransform(scale = 1.5f, translateX = 10f, translateY = 20f)
        delegate.onCommitWallpaperEditMode()           // pan/zoom only -> no emit
        advanceUntilIdle()

        assertThat(events.size).isEqualTo(0)
        job.cancel()
    }

    @Test
    fun `removing a layer in session does not signal on commit`() = runTest {
        val multi = WallpaperState(
            layers = listOf(
                WallpaperLayerState(imageUri = "file:///a.jpg"),
                WallpaperLayerState(imageUri = "file:///b.jpg"),
            ),
        )
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns flowOf(multi)
        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        val events = mutableListOf<Unit>()
        val job = launch(mainDispatcherRule.testDispatcher) {
            delegate.wallpaperImageChanged.collect { events.add(it) }
        }

        delegate.onEnterWallpaperEditMode()
        delegate.onRemoveWallpaperLayer(0)             // a removal is not "a new wallpaper"
        delegate.onCommitWallpaperEditMode()
        advanceUntilIdle()

        assertThat(events.size).isEqualTo(0)
        job.cancel()
    }

    @Test
    fun `onSetWallpaperImage does not signal when the copy fails`() = runTest {
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns null
        val delegate = createDelegate()
        val events = mutableListOf<Unit>()
        val job = launch(mainDispatcherRule.testDispatcher) {
            delegate.wallpaperImageChanged.collect { events.add(it) }
        }

        delegate.onSetWallpaperImage(testUri)          // copy fails -> early return, no emit
        advanceUntilIdle()

        assertThat(events.size).isEqualTo(0)
        job.cancel()
    }

    @Test
    fun `cancel after an in-session image change does not signal`() = runTest {
        val delegate = createDelegate()
        val events = mutableListOf<Unit>()
        val job = launch(mainDispatcherRule.testDispatcher) {
            delegate.wallpaperImageChanged.collect { events.add(it) }
        }

        delegate.onEnterWallpaperEditMode()
        delegate.onAddWallpaperLayer(testUri)
        advanceUntilIdle()
        delegate.onCancelWallpaperEditMode()           // rollback -> no emit
        advanceUntilIdle()

        assertThat(events.size).isEqualTo(0)
        job.cancel()
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

        assertThat(delegate.wallpaperState.value).isEqualTo(snapshotState)
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
        stateFlow.value = WallpaperState.single("file:///x.jpg")
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
        assertWithMessage("gcOrphans must run on the injected io dispatcher, not the main thread").that(io.count > 0).isTrue()
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
        stateFlow.value = WallpaperState.single("file:///x.jpg")
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
            every { hasWallpaper } returns true
            every { withAddedLayer(any()) } returns this
        }

        val stateFlow = MutableStateFlow(state)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow

        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        assertWithMessage("initial pending-focus must be null").that(delegate.pendingFocusLayerId.value).isNull()

        delegate.onAddWallpaperLayer(testUri)
        advanceUntilIdle()

        assertWithMessage("after add, pending-focus must carry the new layer's id").that(delegate.pendingFocusLayerId.value).isNotNull()
    }

    @Test
    fun `consumePendingFocusLayerId clears the signal`() = runTest {
        val internalUri: Uri = mockk()
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns internalUri

        val state: WallpaperState = mockk(relaxed = true) {
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

        assertThat(delegate.pendingFocusLayerId.value).isNotNull()

        delegate.consumePendingFocusLayerId()

        assertWithMessage("consume must clear the signal").that(delegate.pendingFocusLayerId.value).isNull()
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

        assertThat(delegate.pendingFocusLayerId.value).isNotNull()

        delegate.onCancelWallpaperEditMode()
        advanceUntilIdle()

        assertWithMessage("cancel must drop the pending-focus hint — the added layer no longer exists").that(delegate.pendingFocusLayerId.value).isNull()
    }

    // ===========================================
    // FAB POSITION
    // ===========================================

    @Test
    fun `fabPosition starts at DEFAULT when the use case has not emitted`() {
        val delegate = createDelegate()
        // initialValue of stateIn — the empty flow never emits.
        assertThat(delegate.fabPosition.value).isEqualTo(com.github.reygnn.launcher.core.wallpaper.FabPosition.DEFAULT)
    }

    @Test
    fun `fabPosition reflects use-case flow emissions`() = runTest {
        val flow = MutableStateFlow(com.github.reygnn.launcher.core.wallpaper.FabPosition(xFraction = 0.2f, yFraction = 0.3f))
        every { getFabPositionUseCase.invoke() } returns flow

        val delegate = createDelegate()
        // StateIn(WhileSubscribed) requires at least one collector. Trigger it
        // on the test's backgroundScope so the test body owns no cancel.
        backgroundScope.launch { delegate.fabPosition.collect { } }
        advanceUntilIdle()
        assertThat(delegate.fabPosition.value.xFraction).isEqualTo(0.2f)
        assertThat(delegate.fabPosition.value.yFraction).isEqualTo(0.3f)

        flow.value = com.github.reygnn.launcher.core.wallpaper.FabPosition(xFraction = 0.7f, yFraction = 0.8f)
        advanceUntilIdle()
        assertThat(delegate.fabPosition.value.xFraction).isEqualTo(0.7f)
        assertThat(delegate.fabPosition.value.yFraction).isEqualTo(0.8f)
    }

    @Test
    fun `onFabPositionChanged invokes saveFabPositionUseCase with the given fractions`() = runTest {
        val delegate = createDelegate()
        delegate.onFabPositionChanged(xFraction = 0.42f, yFraction = 0.58f)
        advanceUntilIdle()
        coVerify {
            saveFabPositionUseCase.invoke(
                com.github.reygnn.launcher.core.wallpaper.FabPosition(xFraction = 0.42f, yFraction = 0.58f)
            )
        }
    }

    // ===========================================
    // WALLPAPER BACKDROP
    // ===========================================

    @Test
    fun `wallpaperBackdrop starts at DEFAULT when the use case has not emitted`() {
        // observe returns emptyFlow() (setUp) — stateIn holds its initialValue.
        val delegate = createDelegate()
        assertThat(delegate.wallpaperBackdrop.value).isEqualTo(SettingsDefaults.DEFAULT_WALLPAPER_BACKDROP)
    }

    @Test
    fun `wallpaperBackdrop reflects use-case flow emissions`() = runTest {
        val flow = MutableStateFlow(WallpaperBackdrop.BLACK)
        every { observeWallpaperBackdropUseCase.invoke() } returns flow

        val delegate = createDelegate()
        // StateIn(WhileSubscribed) needs a collector before it leaves initialValue.
        backgroundScope.launch { delegate.wallpaperBackdrop.collect { } }
        advanceUntilIdle()
        assertThat(delegate.wallpaperBackdrop.value).isEqualTo(WallpaperBackdrop.BLACK)

        flow.value = WallpaperBackdrop.SYSTEM_WALLPAPER
        advanceUntilIdle()
        assertThat(delegate.wallpaperBackdrop.value).isEqualTo(WallpaperBackdrop.SYSTEM_WALLPAPER)
    }

    // 3a-9b: the toggle runs in the display-settings store; these tests check what lands there.

    @Test
    fun `onToggleWallpaperBackdrop flips SYSTEM_WALLPAPER to BLACK`() = runTest {
        // Nothing persisted yet: the store reads its default, SYSTEM_WALLPAPER.
        val delegate = createDelegate()
        delegate.onToggleWallpaperBackdrop()
        advanceUntilIdle()
        assertThat(persistedBackdrop()).isEqualTo(WallpaperBackdrop.BLACK.name)
    }

    @Test
    fun `onToggleWallpaperBackdrop flips BLACK to SYSTEM_WALLPAPER`() = runTest {
        // The toggle reads the PERSISTED value — no collector on wallpaperBackdrop is needed
        // anymore (a stateIn value without subscribers would be the default, not BLACK).
        backdropDataStore.setInitialData(preferencesOf(backdropKey to WallpaperBackdrop.BLACK.name))
        val delegate = createDelegate()
        delegate.onToggleWallpaperBackdrop()
        advanceUntilIdle()
        assertThat(persistedBackdrop()).isEqualTo(WallpaperBackdrop.SYSTEM_WALLPAPER.name)
    }

    @Test
    fun `onToggleWallpaperBackdrop double-tap nets to a no-op`() = runTest {
        // The second tap flips from the first tap's last-written target (BLACK), not from a
        // write→read-lagged value — two writes, net back to SYSTEM_WALLPAPER.
        val delegate = createDelegate()
        delegate.onToggleWallpaperBackdrop() // SYSTEM_WALLPAPER -> BLACK
        delegate.onToggleWallpaperBackdrop() // BLACK -> SYSTEM_WALLPAPER
        advanceUntilIdle()
        assertThat(backdropDataStore.updateDataCallCount).isEqualTo(2)
        assertThat(persistedBackdrop()).isEqualTo(WallpaperBackdrop.SYSTEM_WALLPAPER.name)
    }

    @Test
    fun `onToggleWallpaperBackdrop retries the same target after a failed persist`() = runTest {
        // Against the real store over a DataStore that refuses the first write: the failed write
        // does not advance lastWritten, so the next tap aims at BLACK again — and no error reaches
        // the caller (no error toast; the store logs and does not throw).
        backdropDataStore.makeEditFail()
        val delegate = createDelegate()
        delegate.onToggleWallpaperBackdrop() // SYSTEM_WALLPAPER -> BLACK, the write fails
        advanceUntilIdle()
        assertThat(persistedBackdrop()).isNull()

        backdropDataStore.resetErrorFlags()
        delegate.onToggleWallpaperBackdrop() // must aim at BLACK again, not flip to SYSTEM
        advanceUntilIdle()

        assertThat(persistedBackdrop()).isEqualTo(WallpaperBackdrop.BLACK.name)
        assertThat(sentEvents.none { it is UiEvent.ShowToast }).isTrue()
    }

    // ===========================================
    // EDIT SESSION (3a-3): emissions during a session, re-sync after it, re-entering
    // ===========================================

    private fun twoLayers() = WallpaperState.multiLayer(
        listOf(
            WallpaperLayerState(id = "first", imageUri = "file:///data/wallpapers/a"),
            WallpaperLayerState(id = "second", imageUri = "file:///data/wallpapers/b"),
        ),
    )

    @Test
    fun `an emission during an edit session is ignored and the session end re-syncs with the persisted state`() = runTest {
        // E4: the session ignores emissions; after it, the latest PERSISTED state is applied —
        // not just a next emission that may never come.
        val start = WallpaperState.single("file:///data/wallpapers/a")
        val stateFlow = MutableStateFlow(start)
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow
        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()
        delegate.onEnterWallpaperEditMode()

        val elsewhere = WallpaperState.single("file:///data/wallpapers/z")
        stateFlow.value = elsewhere
        advanceUntilIdle()
        assertThat(delegate.wallpaperState.value).isEqualTo(start)

        delegate.onCommitWallpaperEditMode()
        advanceUntilIdle()
        assertThat(delegate.wallpaperState.value).isEqualTo(elsewhere)
    }

    @Test
    fun `a layer removed in a session and committed right away does not come back`() = runTest {
        // E4: a stale emission (an older save landing late) must not revive the removed layer,
        // neither during the session nor through the re-sync after it.
        val stateFlow = MutableStateFlow(twoLayers())
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow
        coEvery { saveWallpaperStateUseCase.invoke(any()) } coAnswers { stateFlow.value = firstArg() }
        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onEnterWallpaperEditMode()
        delegate.onRemoveWallpaperLayer(0)
        stateFlow.value = twoLayers() // the stale emission
        delegate.onCommitWallpaperEditMode()
        advanceUntilIdle()

        assertThat(delegate.wallpaperState.value.layers.map { it.id }).containsExactly("second")
    }

    @Test
    fun `removing the wallpaper waits for a session save that is still pending`() = runTest {
        // 3a-3b: removing is a write too. If the clear overtook a pending save, that save would
        // write layers whose files the clear had just deleted — a dangling reference on disk.
        val stateFlow = MutableStateFlow(twoLayers())
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow
        val saveGate = CompletableDeferred<Unit>()
        coEvery { saveWallpaperStateUseCase.invoke(any()) } coAnswers {
            saveGate.await()
            stateFlow.value = firstArg()
        }
        coEvery { clearWallpaperUseCase.invoke() } coAnswers { stateFlow.value = WallpaperState.NONE }
        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onEnterWallpaperEditMode()
        delegate.onSaveLayerTransform(0, scale = 2f, translateX = 0f, translateY = 0f)
        delegate.onCommitWallpaperEditMode()
        delegate.onClearWallpaper()
        advanceUntilIdle()
        coVerify(exactly = 0) { clearWallpaperUseCase.invoke() } // waits for the pending save

        saveGate.complete(Unit)
        advanceUntilIdle()

        coVerifyOrder {
            saveWallpaperStateUseCase.invoke(any())
            clearWallpaperUseCase.invoke()
        }
        assertThat(stateFlow.value).isEqualTo(WallpaperState.NONE) // nothing references a deleted file
        verify { wallpaperFileManager.clearAll() }
    }

    @Test
    fun `entering a running session again keeps its snapshot`() = runTest {
        // E3: re-entering used to overwrite the snapshot, so a cancel restored the edited state.
        val stateFlow = MutableStateFlow(twoLayers())
        val useCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { useCase.invoke() } returns stateFlow
        val delegate = createDelegate(observeWallpaperStateUseCase = useCase)
        delegate.start()
        advanceUntilIdle()

        delegate.onEnterWallpaperEditMode()
        delegate.onRemoveWallpaperLayer(0)
        delegate.onEnterWallpaperEditMode()
        delegate.onCancelWallpaperEditMode()

        assertThat(delegate.wallpaperState.value).isEqualTo(twoLayers())
    }
}

/**
 * A repository for [WallpaperImageStore] whose persisted state references nothing (3a-2c): every
 * delete candidate counts as unreferenced, as before the store read the persisted state itself.
 */
private fun persistedNothing(): WallpaperRepository = io.mockk.mockk(relaxed = true) {
    io.mockk.coEvery { readPersistedImageUris() } returns emptySet()
}
