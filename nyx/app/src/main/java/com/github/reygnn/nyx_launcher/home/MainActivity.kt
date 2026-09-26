package com.github.reygnn.nyx_launcher.home

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import android.provider.Settings
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.annotation.SuppressLint
import android.os.Bundle
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import com.github.reygnn.launcher.common.ui.base.BaseActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentController
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentDialog
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.data.home.NyxFabPositionStore
import com.github.reygnn.nyx_launcher.data.home.NyxWallpaperImageSetter
import com.github.reygnn.nyx_launcher.home.wallpaper.NyxWallpaperEditController
import com.github.reygnn.nyx_launcher.home.drag.DragLayer
import com.github.reygnn.nyx_launcher.home.drag.DropZone
import com.github.reygnn.nyx_launcher.home.drawer.AppDrawerAdapter
import com.github.reygnn.nyx_launcher.home.drawer.AppDrawerFragment
import com.github.reygnn.launcher.common.ui.AppLaunchResult
import com.github.reygnn.launcher.common.ui.DrawerOverlayController
import com.github.reygnn.launcher.common.ui.EventRowsAdapter
import com.github.reygnn.launcher.common.ui.openBatterySettings
import com.github.reygnn.launcher.common.ui.openCalendarApp
import com.github.reygnn.launcher.common.ui.openClockApp
import com.github.reygnn.launcher.common.ui.runLaunchCatching
import com.github.reygnn.launcher.common.ui.showToastSafe
import com.github.reygnn.launcher.common.ui.timeinfo.ClockDelegate
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperViewBinder
import com.github.reygnn.launcher.common.ui.wallpaper.ZoomableImageView
import com.github.reygnn.launcher.common.ui.wallpaper.decodeBoundedWallpaperBitmap
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.wallpaper.WallpaperRenderScheduler
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.LazySlotMembership
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.nyx_launcher.home.wallpaper.NyxWallpaperEditCoordinator
import com.github.reygnn.nyx_launcher.home.wallpaper.WallpaperLayerBitmapCache
import com.github.reygnn.nyx_launcher.home.wallpaper.launchSafe
import com.github.reygnn.launcher.core.wallpaper.ScrimRender
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.timeinfo.ObserveTimeBasedEventsUseCase
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEvent
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEventType
import com.github.reygnn.launcher.core.timeinfo.TimeEventFormatter
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.reygnn.nyx_launcher.home.model.DrawerEntry
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.firstFreeCell
import com.github.reygnn.nyx_launcher.settings.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * The launcher home: a [ViewPager2] of grid pages, a persistent dock, and an
 * app-drawer overlay ([AppDrawerFragment]) revealed by swipe-up (long-press on
 * empty home space opens Settings). Tapping launches/opens; long-pressing an
 * icon drags. A drag started in the grid/dock carries the item's id (→ move);
 * one started in the drawer carries a [DragPayload.NewApp] (→ place at the drop
 * cell). Dropping on the remove bar removes; dropping a drawer app there is
 * ignored.
 *
 * Implements [AppDrawerFragment.Host]: the drawer fragment is self-contained but
 * routes launch, drag and show/hide back here, since those touch intents, drag
 * payloads and the overlay container that lives in this activity's layout.
 */
@AndroidEntryPoint
class MainActivity : BaseActivity<Nothing, HomeViewModel>(), AppDrawerFragment.Host {

    override val viewModel: HomeViewModel by viewModels()

    @Inject lateinit var iconLoader: IconLoader
    @Inject lateinit var folderRenderer: FolderIconRenderer
    @Inject lateinit var observeTimeBasedEventsUseCase: ObserveTimeBasedEventsUseCase

    // Wallpaper (WV5): state source + narrow display-settings port. The render
    // machinery (binder, view) is held here directly — Nyx has no home ViewModel,
    // mirroring the ClockDelegate pattern.
    @Inject lateinit var wallpaperRepository: WallpaperRepository
    @Inject lateinit var wallpaperDisplaySettings: WallpaperDisplaySettings
    @Inject lateinit var wallpaperImageSetter: NyxWallpaperImageSetter
    @Inject lateinit var wallpaperFileManager: WallpaperFileManager
    @Inject lateinit var fabPositionStore: NyxFabPositionStore

    // App-scoped per-layer decode cache (@Singleton): survives MainActivity
    // re-creation, so returning to a recreated home skips re-decoding the collage;
    // also turns the delete-a-layer FullRebuild into cache hits. Cleared when the
    // wallpaper is removed (see renderWallpaper).
    @Inject lateinit var wallpaperLayerCache: WallpaperLayerBitmapCache

    // First-run defaults (dock apps + Play Store on the grid, and the Google drawer
    // folder) so a fresh install isn't a blank screen. One-shot; each seed no-ops on a
    // returning install (gated in its repository). See onCreate.
    @Inject lateinit var firstRunSeeder: FirstRunSeeder

    // Drives the shared first-launch ACRA consent dialog (see onCreate); all builds.
    @Inject lateinit var consentController: ConsentController

    // The shared first-launch consent dialog (setCancelable(false)); tracked so onDestroy
    // can dismiss it and not leak its window.
    private var consentDialog: AlertDialog? = null

    /** The single tracked cancelable dialog (events + by-maker pickers); see [showTrackedDialog]. */
    private var currentDialog: AlertDialog? = null

    // The wallpaper edit-session coordinator (ClockDelegate pattern): owns the live
    // wallpaper state (mirrored from the repo), drives the transactional edit session.
    private lateinit var wallpaperEditCoordinator: NyxWallpaperEditCoordinator
    private lateinit var wallpaperEditController: NyxWallpaperEditController

    // Layer-add image picker (edit mode). GetContent grants a transient read;
    // the coordinator copies the image to internal storage immediately.
    private val layerPickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { wallpaperEditCoordinator.onAddLayer(it) }
        }

    private lateinit var homeRoot: DragLayer
    private lateinit var pager: ViewPager2
    private lateinit var pageIndicator: LinearLayout
    private lateinit var dock: RecyclerView
    private lateinit var drawerContainer: View
    private lateinit var drawerOverlay: DrawerOverlayController
    private lateinit var removeBar: TextView
    private lateinit var addToHomeBar: TextView

    private val drawerFragment: AppDrawerFragment?
        get() = supportFragmentManager.findFragmentById(R.id.drawer_container) as? AppDrawerFragment

    // In-DragLayer folder overlay (finger-drag extraction). Populated on open.
    private lateinit var folderOverlay: View
    private lateinit var folderTitle: EditText
    private lateinit var folderMembers: RecyclerView
    private lateinit var folderOverlayController: FolderOverlayController
    private var openFolderId: ItemId? = null
    private var openFolderTitle: String = ""

    // In-DragLayer long-press context menu (Launcher3-style).
    private lateinit var contextMenuOverlay: View
    private lateinit var contextMenuCard: LinearLayout
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private lateinit var clockBattery: TextView
    private lateinit var alarmIndicator: ImageView
    private lateinit var calendarIndicator: ImageView
    private lateinit var wallpaperContainer: View
    private lateinit var wallpaperView: ZoomableImageView
    private lateinit var wallpaperScrim: View

    // Serial, latest-wins wallpaper render. The binder decodes off the main
    // thread; a single job at a time avoids overlapping rebuilds of the view.
    private val wallpaperBinder by lazy {
        WallpaperViewBinder(bitmapLoader = { uri: Uri ->
            // Cache hit → return the already-decoded layer instantly (no IO hop),
            // so deleting one layer doesn't re-decode the rest and flash.
            val key = uri.toString()
            wallpaperLayerCache.get(key) ?: run {
                // Capture the cache generation BEFORE the decode: if a clear() lands
                // during the IO hop (wallpaper removed mid-flight), putIfCurrent drops
                // the result instead of stranding it in the app-scoped cache.
                val generation = wallpaperLayerCache.generation()
                withContext(Dispatchers.IO) {
                    // BitmapLoader contract: return null on failure, let only cancellation
                    // escape. decodeBoundedWallpaperBitmap does NOT catch internally —
                    // openInputStream can throw FileNotFoundException/SecurityException and
                    // decode can OOM (Throwable). Without this guard the throw would escape
                    // bind() → the unguarded collect/launch → crash the HOME activity
                    // (mirrors Kolibri's loadBitmapFromUri).
                    try {
                        decodeBoundedWallpaperBitmap { contentResolver.openInputStream(uri) }
                            ?.also { wallpaperLayerCache.putIfCurrent(key, it, generation) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error loading wallpaper bitmap from $uri")
                        null
                    }
                }
            }
        })
    }

    // Single-slot latest-wins render: BOTH the state collector and rerenderWallpaper
    // go through this, so a Cancel (which fires a state emission AND rerenderWallpaper)
    // can never run two concurrent binds that would duplicate layers (mirrors Kolibri).
    private val wallpaperRenderScheduler = WallpaperRenderScheduler()

    // Shared home-info delegate (HIE Phase C): clock/date/battery/events StateFlows.
    private lateinit var clockDelegate: ClockDelegate
    private val timeEventFormatter = TimeEventFormatter()
    private var currentEvents: List<TimeBasedEvent> = emptyList()

    // App-local battery receiver (HIE-INV-3): the running ACTION_BATTERY_CHANGED
    // stream is bound to onResume/onPause and fed to the shared delegate.
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            clockDelegate.updateBatteryLevelFromIntent(intent)
        }
    }
    private var pagerAdapter: HomePagerAdapter? = null
    private var currentGrid: GridSpec? = null
    private lateinit var dockAdapter: DockAdapter

    private var gridIconPx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        homeRoot = findViewById(R.id.home_root)
        pager = findViewById(R.id.home_pager)
        pageIndicator = findViewById(R.id.page_indicator)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                viewModel.layout.value?.let(::updatePageIndicator)
            }
        })
        dock = findViewById(R.id.dock)
        drawerContainer = findViewById(R.id.drawer_container)
        drawerOverlay = DrawerOverlayController(
            container = drawerContainer,
            slideDistancePx = { drawerSlideDistance() },
            slideDurationMs = DRAWER_SLIDE_MS,
            onShown = { animate ->
                if (animate) viewModel.refreshDrawer() // pick up installs/removals since last open (A1-04)
                drawerFragment?.onDrawerShown() // arm drag-to-dismiss
            },
            onHidden = { drawerFragment?.onDrawerHidden() }, // disarm before the hide slide
        )
        removeBar = findViewById(R.id.remove_bar)
        addToHomeBar = findViewById(R.id.add_to_home_bar)
        folderOverlay = findViewById(R.id.folder_overlay)
        folderTitle = findViewById(R.id.folder_title)
        folderMembers = findViewById(R.id.folder_members)
        folderOverlayController = FolderOverlayController(
            folderOverlay, folderTitle, folderMembers, findViewById(R.id.folder_add_apps),
        ) { currentColumns() }
        // Tap the scrim (outside the card) closes; the card swallows its own taps.
        folderOverlay.setOnClickListener { closeFolderOverlay() }
        findViewById<View>(R.id.folder_card).setOnClickListener { /* swallow */ }
        contextMenuOverlay = findViewById(R.id.context_menu_overlay)
        contextMenuCard = findViewById(R.id.context_menu_card)
        contextMenuOverlay.setOnClickListener { dismissContextMenu() }
        contextMenuCard.setOnClickListener { /* swallow */ }
        // Launcher3-style long-press: arm shows the menu; a move promotes to a drag.
        homeRoot.onArm = { payload, source -> showContextMenu(payload, source) }
        homeRoot.onArmedPromote = { payload ->
            dismissContextMenu()
            // A drawer-app drag (NewApp) FOLDS within the drawer (§8), so keep the drawer
            // open for the drop; any other drag targets home, so close it as before.
            if (payload !is DragPayload.NewApp && drawerOverlay.isOpen) hideDrawer()
        }
        clockTime = findViewById(R.id.clock_time)
        clockDate = findViewById(R.id.clock_date)
        clockBattery = findViewById(R.id.clock_battery)
        alarmIndicator = findViewById(R.id.event_alarm_indicator)
        calendarIndicator = findViewById(R.id.event_calendar_indicator)
        wallpaperContainer = findViewById(R.id.wallpaper_container)
        wallpaperView = findViewById(R.id.wallpaper_view)
        wallpaperScrim = findViewById(R.id.wallpaper_scrim)
        // Home-info tap targets: time → alarms, date → calendar, battery → battery
        // settings (mirrors Kolibri's intents). Double-tap, not single: a single tap on
        // the clock is easy to trigger by accident, and the gesture consumes the stream so
        // it never bubbles to the home double-tap (events dialog).
        clockTime.setOnDoubleTap { openClockApp(R.string.home_info_no_app) }
        clockDate.setOnDoubleTap { openCalendarApp(R.string.home_info_no_app) }
        clockBattery.setOnDoubleTap { openBatterySettings(R.string.home_info_no_app) }
        gridIconPx = (48 * resources.displayMetrics.density).toInt()

        clockDelegate = ClockDelegate(
            context = this,
            observeTimeBasedEventsUseCase = observeTimeBasedEventsUseCase,
            scope = lifecycleScope,
            mainDispatcher = Dispatchers.Main,
        )

        // Edge-to-edge: inset the home content past the status/nav bars. The
        // drawer overlay stays edge-to-edge and covers the bars with its own
        // dark scrim, so no wallpaper shows through top/bottom while it's open.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.home_content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        // The remove bar reaches the very top edge and its red fill covers the
        // status-bar region while a drag is active: it sits at y=0 (a home_root
        // overlay), grows to `status-bar inset + content`, and pads its text down
        // by the inset so the label stays clear of the status icons (which the
        // system still draws on top). Insetting via height+padding rather than a
        // top margin is what lets the fill run under the bar.
        ViewCompat.setOnApplyWindowInsetsListener(removeBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            val content = (DROP_BAR_CONTENT_DP * resources.displayMetrics.density).toInt()
            v.updateLayoutParams { height = top + content }
            v.updatePadding(top = top)
            insets
        }
        // Add-to-home bar: at the TOP, overlaying the drawer search field. Like removeBar,
        // its fill covers the status-bar region while the label stays below the status icons.
        ViewCompat.setOnApplyWindowInsetsListener(addToHomeBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            val content = (DROP_BAR_CONTENT_DP * resources.displayMetrics.density).toInt()
            v.updateLayoutParams { height = top + content }
            v.updatePadding(top = top)
            insets
        }

        setupDock()
        setupDropZones()
        setupGestures()
        // Derive the grid from the real home-grid area once the pager is laid out
        // (a pre-layout metrics estimate mis-counts rows and leaves a big top gap).
        pager.doOnLayout { applyDeviceGrid() }
        onBackPressedDispatcher.addCallback(this) {
            // Edit mode → commit (exit); else close the drawer; else stay on home.
            if (wallpaperEditCoordinator.isEditMode.value) {
                // Flush live transforms then commit (same as the Save FAB) — a bare
                // commit would drop the active layer's unsaved pan/zoom.
                wallpaperEditController.commitEdit()
            } else if (contextMenuOverlay.isVisible) {
                dismissContextMenu()
            } else if (folderOverlay.isVisible) {
                closeFolderOverlay()
            } else if (drawerOverlay.isOpen) {
                hideDrawer()
            }
        }

        clockDelegate.start()

        wallpaperEditCoordinator = NyxWallpaperEditCoordinator(
            repository = wallpaperRepository,
            fileManager = wallpaperFileManager,
            displaySettings = wallpaperDisplaySettings,
            scope = lifecycleScope,
            ioDispatcher = Dispatchers.IO,
        )
        wallpaperEditCoordinator.start()

        wallpaperEditController = NyxWallpaperEditController(
            stub = findViewById(R.id.wallpaperEditOverlayStub),
            wallpaperView = wallpaperView,
            dimTarget = findViewById(R.id.home_content),
            coordinator = wallpaperEditCoordinator,
            onFabPositionChanged = { pos -> lifecycleScope.launchSafe("Error saving FAB position") { fabPositionStore.saveFabPosition(pos) } },
            launchLayerPicker = { layerPickerLauncher.launch("image/*") },
            rerenderWallpaper = { renderWallpaper(wallpaperEditCoordinator.wallpaperState.value) },
        )

        // Seed + paint the scrim NOW, before the first frame and before the wallpaper is
        // rendered, from the retained (Eagerly) scrim-alpha value. On a recreated/returning
        // home the hot wallpaper StateFlow renders (near-)immediately while the scrim's cold
        // DataStore read lands a few frames later, so without this the user briefly saw the
        // un-scrimmed wallpaper. The collector below keeps it in sync from here on.
        currentScrimAlpha = viewModel.wallpaperScrimAlpha.value
        applyScrim()

        // First-launch ACRA consent (shared dialog, mirrors Kolibri) for all builds: resolve
        // the stored decision → show the dialog once / re-affirm ACRA / skip on unreadable.
        lifecycleScope.launch { showCrashReportConsentIfNeeded() }

        // One-shot on startup: reclaim wallpaper files stranded by a crash between
        // copy and save (the shared repo/file-manager split doesn't self-clean).
        lifecycleScope.launch { wallpaperImageSetter.reclaimOrphans() }

        // One-shot on startup: seed the first-run defaults. Each seed gates on its own
        // flag and resolves apps (PackageManager IPCs) only on a real first run, so a
        // returning install is a no-op.
        lifecycleScope.launch {
            val seeded = firstRunSeeder.seedHomeLayout()
            // The one-shot device-grid fit (pager.doOnLayout) runs against the pre-seed
            // empty layout, so a dock seeded above the device's column count wouldn't be
            // reconciled until the next cold start. Re-fit once seeding actually wrote, so
            // the regridder re-homes any over-capacity dock overflow in THIS session.
            if (seeded) pager.doOnLayout { applyDeviceGrid() }
        }
        // Independent one-shot: seed the "Google" drawer folder from installed Google apps.
        lifecycleScope.launch { firstRunSeeder.seedDrawerFolders() }

        // Run the UI collectors under the BaseActivity crash-net handler AND guard each
        // one individually via launchGuarded: a throwable in a single collector is
        // reported without cancelling its siblings or tearing down repeatOnLifecycle.
        // A bare `launch {}` here would let one throw cancel the whole block — the CEH
        // then reports it (no crash) but repeatOnLifecycle never re-runs, silently
        // freezing every collector until the Activity is recreated. Mirrors
        // BaseActivity's own per-collector arms.
        lifecycleScope.launch(coroutineExceptionHandler) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launchGuarded { viewModel.layout.collect(::renderLayout) }
                launchGuarded {
                    viewModel.iconStyle.collect {
                        // renderLayout refreshes the dock (DockAdapter full rebind) and keeps
                        // the layout consistent, but the grid's positional DiffUtil sees no cell
                        // change on a style switch — so force the pages to re-decode their icons.
                        renderLayout(viewModel.layout.value)
                        pagerAdapter?.refreshIcons()
                    }
                }
                // Re-render when the installed-apps set changes so a freshly uninstalled
                // app's tile greys out (missing state) and a reinstall un-greys it.
                launchGuarded { viewModel.installedKeys.collect { renderLayout(viewModel.layout.value) } }
                // Notification dots (gated by the toggle): push the package set into the
                // grid pages + dock so their icons show/hide the dot reactively.
                launchGuarded {
                    viewModel.notificationDots.collect { dots ->
                        pagerAdapter?.submitNotificationDots(dots)
                        dockAdapter.submitNotificationDots(dots)
                    }
                }
                launchGuarded { clockDelegate.timeString.collect { clockTime.text = it } }
                launchGuarded { clockDelegate.dateString.collect { clockDate.text = it } }
                launchGuarded { clockDelegate.batteryString.collect { clockBattery.text = it } }
                launchGuarded { clockDelegate.timeBasedEvents.collect(::updateEventsIndicator) }
                // Wallpaper (WV5): render on every state change (latest-wins — the
                // collector awaits each bind before the next emission). Scrim +
                // backdrop react to their own settings flows.
                launchGuarded {
                    wallpaperEditCoordinator.wallpaperState.collect { renderWallpaper(it) }
                }
                launchGuarded { viewModel.wallpaperScrimAlpha.collect { currentScrimAlpha = it; applyScrim() } }
                launchGuarded {
                    wallpaperDisplaySettings.wallpaperBackdropFlow.collect {
                        applyBackdrop(it)
                        wallpaperEditController.applyBackdrop(it)
                    }
                }
                launchGuarded {
                    wallpaperEditCoordinator.isEditMode.collect {
                        wallpaperEditController.applyEditMode(it)
                        // Bypass home gesture detection while editing so pinch/pan reach
                        // the wallpaper view instead of being stolen by the gesture core.
                        homeRoot.gesturesEnabled = !it
                        // Suppress the scrim during edit so the user adjusts against the
                        // wallpaper's true appearance (ScrimRender honours isEditMode).
                        applyScrim()
                    }
                }
                launchGuarded { fabPositionStore.fabPositionFlow.collect { wallpaperEditController.applyFabPosition(it) } }
            }
        }
    }

    /**
     * Launches a home-screen flow collector that CANNOT tear down its siblings: a
     * non-cancellation throwable is reported (not rethrown), so one failing collector
     * neither crashes the launcher nor kills the sibling collectors + the enclosing
     * repeatOnLifecycle (which would silently freeze the whole home). Cancellation
     * still propagates so STARTED-scope teardown works normally.
     */
    private fun CoroutineScope.launchGuarded(block: suspend CoroutineScope.() -> Unit) =
        launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Home collector failed")
            }
        }

    /**
     * Single entry point to render a wallpaper [state] onto the view. Routed through
     * [wallpaperRenderScheduler] (latest-wins, cancels the previous in-flight bind) so
     * the state collector and rerenderWallpaper can never run two concurrent binds.
     * Consumes the one-shot focus hint and re-syncs the edit toolbar after a rebuild.
     */
    private fun renderWallpaper(state: WallpaperState) {
        // Wallpaper removed / reset → drop the cached layer bitmaps; nothing renders
        // them again, so they would otherwise sit in memory until LRU eviction.
        if (!state.hasWallpaper) wallpaperLayerCache.clear()
        val focusId = wallpaperEditCoordinator.consumePendingFocusLayerId()
        wallpaperRenderScheduler.render(lifecycleScope) {
            // The scheduler launches this on the bare lifecycleScope, NOT under the
            // collector's coroutineExceptionHandler, so guard the bind here: a throwable
            // (e.g. a FullRebuild view/allocation error) would otherwise reach the global
            // handler and crash the launcher. Cancellation (latest-wins supersede) still
            // propagates, preserving the scheduler's single-slot semantics.
            try {
                wallpaperBinder.bind(
                    wallpaperView,
                    state,
                    preferredActiveLayerId = focusId,
                    onRebuildComplete = { wallpaperEditController.onWallpaperRebuilt() },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error rendering wallpaper")
            }
        }
    }

    /** Last scrim alpha from settings; applyScrim() combines it with the edit flag. */
    private var currentScrimAlpha = 0f

    /**
     * Applies the user-controlled dim overlay above the wallpaper. The color
     * (alpha baked in) comes from the shared [ScrimRender]; null → no scrim. During
     * edit mode ScrimRender returns null so the wallpaper shows its true appearance.
     */
    private fun applyScrim() {
        val color = ScrimRender.colorOrNull(
            alpha = currentScrimAlpha,
            isEditMode = wallpaperEditCoordinator.isEditMode.value,
        )
        if (color == null) {
            wallpaperScrim.visibility = View.GONE
        } else {
            wallpaperScrim.setBackgroundColor(color)
            wallpaperScrim.visibility = View.VISIBLE
        }
    }

    /**
     * Applies the backdrop (WSS-INV-6, a user choice) as the wallpaper container's
     * own background — behind the custom layers. SYSTEM_WALLPAPER → transparent, so
     * the live system wallpaper shows through the transparent window (collages can
     * build on it); BLACK → opaque black.
     */
    private fun applyBackdrop(backdrop: WallpaperBackdrop) {
        val color = when (backdrop) {
            WallpaperBackdrop.SYSTEM_WALLPAPER -> Color.TRANSPARENT
            WallpaperBackdrop.BLACK -> Color.BLACK
        }
        wallpaperContainer.background = ColorDrawable(color)
    }

    /**
     * Toggle the two subtle indicators next to the clock (mirrors Kolibri): the
     * alarm icon when a next alarm exists, the calendar icon when a next event
     * exists. INVISIBLE (not GONE) so each keeps its slot. Signals only — a
     * double-tap on the home opens the full list.
     */
    private fun updateEventsIndicator(events: List<TimeBasedEvent>) {
        currentEvents = events
        val hasAlarm = events.any { it.type == TimeBasedEventType.ALARM }
        val hasCalendar = events.any { it.type == TimeBasedEventType.CALENDAR }
        alarmIndicator.visibility = if (hasAlarm) View.VISIBLE else View.INVISIBLE
        calendarIndicator.visibility = if (hasCalendar) View.VISIBLE else View.INVISIBLE
    }

    /**
     * Show [dialog] as the single tracked cancelable dialog: dismiss any
     * previously-tracked one, clear the reference on dismiss, and let [onDestroy]
     * dismiss it so its window does not leak across a config change. Guards a
     * finishing / destroyed Activity. Covers the events + by-maker pickers; the
     * non-cancelable ConsentDialog stays tracked separately in [consentDialog].
     */
    private fun showTrackedDialog(dialog: AlertDialog) {
        if (isFinishing || isDestroyed) return
        currentDialog?.dismiss()
        currentDialog = dialog
        dialog.setOnDismissListener { if (currentDialog === dialog) currentDialog = null }
        dialog.show()
    }

    /**
     * Confirmation for removing a "missing" home tile — a placement whose app is no
     * longer installed (Windows-shortcut model, root TODO.md). The tile is never
     * auto-pruned; the user removes it here via the existing remove path. Only the
     * package name is available for a gone app, so the message uses it.
     */
    private fun confirmRemoveMissingApp(id: ItemId, key: ComponentKey) {
        if (isFinishing || isDestroyed) return
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.home_missing_dialog_title)
            .setMessage(getString(R.string.home_missing_dialog_message, key.packageName))
            .setPositiveButton(R.string.home_missing_dialog_remove) { _, _ -> viewModel.remove(id) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showTrackedDialog(dialog)
    }

    /**
     * Confirmation for removing a "missing" MEMBER from the open folder — a member whose app
     * is no longer installed (Windows-shortcut model). Reuses the top-level missing dialog
     * strings; removal goes through the folder-delete path (auto-dissolves below two members)
     * and closes the overlay, since its snapshot member list may have shrunk or dissolved.
     */
    private fun confirmRemoveMissingFolderMember(folder: ItemId, key: ComponentKey) {
        if (isFinishing || isDestroyed) return
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.home_missing_dialog_title)
            .setMessage(getString(R.string.home_missing_dialog_message, key.packageName))
            .setPositiveButton(R.string.home_missing_dialog_remove) { _, _ ->
                viewModel.removeMissingFolderMember(folder, key)
                folderOverlayController.close()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showTrackedDialog(dialog)
    }

    /** All upcoming events, grouped today/tomorrow via the shared formatter. */
    private fun showEventsDialog() {
        if (isFinishing || isDestroyed) return
        val events = currentEvents
        if (events.isEmpty()) return
        val zone = ZoneId.systemDefault()
        // rows[] is the single source of truth for both rendering and click routing, so
        // positions never drift. Labels resolved once; a blank title gets a localized fallback.
        val rows = timeEventFormatter.buildEventRows(events, LocalDate.now(zone), zone)
        val rowLabels = timeEventFormatter.buildRowLabels(
            rows,
            is24Hour = DateFormat.is24HourFormat(this),
            allDayLabel = getString(R.string.event_all_day),
            alarmFallbackLabel = getString(R.string.events_fallback_alarm),
            calendarFallbackLabel = getString(R.string.events_fallback_calendar),
        )
        val adapter = EventRowsAdapter(
            rows = rows,
            rowLabels = rowLabels,
            itemRowLayout = R.layout.item_event_row,
            itemLabelId = View.NO_ID,
            alarmIcon = R.drawable.ic_alarm,
            calendarIcon = R.drawable.ic_event,
            iconSizePx = resources.getDimensionPixelSize(R.dimen.events_dialog_icon_size),
            iconPaddingPx = resources.getDimensionPixelSize(R.dimen.events_dialog_icon_padding),
            dividerLayout = R.layout.item_events_divider,
            dividerLineId = R.id.events_divider_line,
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.events_dialog_title)
            .setAdapter(adapter) { _, which ->
                val row = rows[which] as? TimeEventFormatter.EventRow.Item ?: return@setAdapter
                when (row.event.type) {
                    TimeBasedEventType.ALARM -> openClockApp(R.string.home_info_no_app)
                    TimeBasedEventType.CALENDAR -> openCalendarApp(R.string.home_info_no_app)
                }
            }
            .setPositiveButton(android.R.string.ok, null)
            .create()
        showTrackedDialog(dialog)
    }

    override fun onResume() {
        super.onResume()
        // HIE-INV-3: the app owns the live battery receiver; the delegate derives.
        registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onPause() {
        super.onPause()
        // no suspension point — unregisterReceiver is a synchronous framework call.
        runCatching { unregisterReceiver(batteryReceiver) }
    }

    override fun onDestroy() {
        // ConsentDialog is setCancelable(false); dismiss the tracked instance so its window
        // doesn't leak across a config change / teardown.
        consentDialog?.dismiss()
        consentDialog = null
        currentDialog?.dismiss()
        currentDialog = null
        super.onDestroy()
    }

    /**
     * First-launch ACRA consent (all builds), mirroring Kolibri: show the shared dialog
     * once when the decision was never made, re-affirm ACRA from a stored decision, or skip
     * on an unreadable store. The dialog persists the choice via [ConsentController.applyConsent].
     */
    private suspend fun showCrashReportConsentIfNeeded() {
        when (val action = consentController.resolveStartupAction()) {
            ConsentController.StartupAction.ShowDialog ->
                consentDialog = ConsentDialog.show(this) { consentController.applyConsent(it) }
            is ConsentController.StartupAction.Reaffirm -> consentController.reaffirmConsent(action.granted)
            ConsentController.StartupAction.Skip -> Unit
        }
    }

    // ---- setup ----

    private fun setupDock() {
        dockAdapter = DockAdapter(
            iconLoader, folderRenderer, lifecycleScope, gridIconPx, ::launchApp, ::openFolder,
            onIconLongPress = { v, id -> homeRoot.armDrag(DragPayload.Existing(id), v) },
            onMissingApp = ::confirmRemoveMissingApp,
        )
        dock.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        dock.adapter = dockAdapter
        // Re-center the dock whenever its item set changes. Tied to the adapter's
        // data-change, NOT a render-time one-shot: after a drag-out the final render
        // could miss the new count and leave the dock off-center ("doesn't always
        // re-center"). onChanged fires from submit()'s notifyDataSetChanged, by which
        // point itemCount already reflects the new set.
        dockAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = recenterDock()
        })
        // No setOnLongClickListener here: a long-click listener on a RecyclerView
        // never fires (its onTouchEvent handles scrolling and never triggers the
        // View long-press path), and it would mark the dock long-clickable, which
        // makes the shared core's hit-test suppress homeRoot.onLongPress over the
        // dock. Empty-dock long-press → Settings is handled by homeRoot.onLongPress;
        // dock icons keep their own long-press (drag) via DockAdapter.
    }

    /**
     * Register the drop zones with the [DragLayer]'s controller, in priority order
     * (HOME_DRAG_ENGINE_SPEC §4): remove > dock > grid. The engine keeps the drag
     * gesture in-app, so the remove zone's hit rect can reach y=0 — a drop at the
     * very top edge deletes, unlike with OS drag-and-drop.
     */
    private fun setupDropZones() {
        val controller = homeRoot.dragController
        controller.clearDropZones()

        // The remove bar shows for the whole drag; the remove zone tints it on hover.
        // NOT for a drawer-internal fold drag (drawer open): the remove zone rejects
        // NewApp, so the bar would be a misleading, non-functional affordance over the
        // open drawer.
        controller.onDragStart = { payload ->
            if (drawerOverlay.isOpen) {
                // Drawer-app drag over the open drawer → offer the add-to-home target
                // (the fold zone still owns the rest of the drawer surface).
                if (payload is DragPayload.NewApp) {
                    tintAddToHomeBar(active = false)
                    addToHomeBar.visibility = View.VISIBLE
                }
            } else {
                tintRemoveBar(active = false)
                removeBar.visibility = View.VISIBLE
            }
        }
        controller.onDragEnd = {
            removeBar.visibility = View.INVISIBLE
            addToHomeBar.visibility = View.INVISIBLE
            edgeAdvance.onDragEnd()
        }
        // Hold a drag at the left/right pager edge to page across grids (incl. the
        // empty landing page), so an app can be carried to another page. Only for
        // home-targeted drags: during a drawer-internal fold drag the drawer is open,
        // and paging the hidden home grid behind it would silently leave home on the
        // wrong page after the drawer is dismissed.
        controller.onDragMove = { x, _ -> if (!drawerOverlay.isOpen) edgeAdvance.onDragMove(x) }
        // The drag view is kept at the drop point until the commit's re-render
        // clears it (renderLayout). This fallback covers no-op drops (same cell)
        // and errors, where no re-render arrives.
        controller.onDropSettle = {
            homeRoot.postDelayed({
                if (!homeRoot.dragController.isDragging) homeRoot.removeDragView()
            }, DRAG_SETTLE_FALLBACK_MS)
        }

        // Add-to-home zone — registered FIRST so it wins over the fold zone in its
        // top strip. Active only while dragging a drawer app (NewApp) over the open
        // drawer; a drop places the app at the first free cell and closes the drawer.
        controller.addDropZone(object : DropZone {
            override fun hitRect(out: Rect) {
                if (drawerOverlay.isOpen) out.set(0, 0, homeRoot.width, addToHomeBar.bottom)
                else out.setEmpty()
            }
            override fun accepts(payload: DragPayload) =
                drawerOverlay.isOpen && payload is DragPayload.NewApp
            override fun onDragEnter() { tintAddToHomeBar(active = true) }
            override fun onDragExit() { tintAddToHomeBar(active = false) }
            override fun onDrop(payload: DragPayload, x: Int, y: Int) {
                (payload as? DragPayload.NewApp)?.let { addToHome(it.key) }
            }
        })

        // 0) Drawer-fold zone (active ONLY while the drawer is open; sits below the
        //    add-to-home zone above, which claims the top strip):
        // a drawer-app drag (NewApp) dropped onto another drawer app makes a folder, onto a
        // folder adds a member (§8). Empty drawer space → no-op (the app stays put). When the
        // drawer is closed the hit rect is empty, so home drags fall through to the zones below.
        controller.addDropZone(object : DropZone {
            override fun hitRect(out: Rect) {
                if (drawerOverlay.isOpen) rectInDragLayer(drawerContainer, out) else out.setEmpty()
            }
            override fun accepts(payload: DragPayload) =
                drawerOverlay.isOpen && payload is DragPayload.NewApp
            override fun onDrop(payload: DragPayload, x: Int, y: Int) {
                val source = (payload as? DragPayload.NewApp)?.key ?: return
                when (val entry = drawerEntryAt(x, y)) {
                    is DrawerEntry.App ->
                        if (entry.app.key != source) viewModel.createDrawerFolder(source, entry.app.key)
                    is DrawerEntry.Folder -> viewModel.addToDrawerFolder(source, entry.id)
                    null -> Unit // dropped on empty drawer space — leave the app where it is
                }
            }
        })

        // 1) Remove zone — the top strip, reaching y=0. Existing items only.
        controller.addDropZone(object : DropZone {
            override fun hitRect(out: Rect) = out.set(0, 0, homeRoot.width, removeBar.bottom)
            override fun accepts(payload: DragPayload) = payload is DragPayload.Existing
            override fun onDragEnter() { tintRemoveBar(active = true) }
            override fun onDragExit() { tintRemoveBar(active = false) }
            override fun onDrop(payload: DragPayload, x: Int, y: Int) {
                (payload as? DragPayload.Existing)?.let { viewModel.remove(it.id) }
            }
        })

        // 2) Dock zone — insert index from the finger x: the number of icons whose
        // center is left of the finger. So a drop on an icon's left half inserts
        // before it (index 0 at the far left), the right half after it, and past
        // the last icon appends. The dragged icon's own (hidden) view is skipped so
        // its old slot doesn't shift the count during a reorder.
        controller.addDropZone(object : DropZone {
            override fun hitRect(out: Rect) = rectInDragLayer(dock, out)
            override fun accepts(payload: DragPayload) = true
            override fun onDrop(payload: DragPayload, x: Int, y: Int) {
                val bounds = Rect().also { rectInDragLayer(dock, it) }
                val localX = (x - bounds.left).toFloat()
                // Centre-vs-edge, identical to the grid (gridDropAt): a drop over the
                // central ~60% of a dock icon lands ON it (DockItem → folder create /
                // add-to-folder / move-between-folders), the outer ~20% on each side
                // inserts BETWEEN icons (DockSlot). Same GRID_INSERT_EDGE_FRACTION so
                // dock and grid folders behave the same.
                var index = 0
                for (i in 0 until dock.childCount) {
                    val child = dock.getChildAt(i)
                    if (child.visibility != View.VISIBLE) continue // the icon being dragged
                    val adapterPos = dock.getChildAdapterPosition(child)
                    if (adapterPos == RecyclerView.NO_POSITION) continue
                    val fx = (localX - child.left) / child.width.toFloat()
                    if (fx in GRID_INSERT_EDGE_FRACTION..(1f - GRID_INSERT_EDGE_FRACTION)) {
                        viewModel.onDrop(payload, DropTarget.DockItem(adapterPos)) // land ON this icon
                        return
                    }
                    if (child.left + child.width / 2f < localX) index++
                }
                viewModel.onDrop(payload, DropTarget.DockSlot(index)) // insert between icons
            }
        })

        // 3) Grid zone — the rest of the surface; target resolved geometrically
        // (cell centre → place/folder, cell edge → reorder-insert).
        controller.addDropZone(object : DropZone {
            override fun hitRect(out: Rect) = rectInDragLayer(pager, out)
            override fun accepts(payload: DragPayload) = true
            override fun onDrop(payload: DragPayload, x: Int, y: Int) {
                resolveGridDrop(x.toFloat(), y.toFloat())?.let { viewModel.onDrop(payload, it) }
            }
        })
    }

    // Material 3 semantic tint for the two drop bars: the destructive remove target is
    // the error role (idle errorContainer → error on hover), the constructive add target
    // the primary role (idle primaryContainer → primary on hover). Text follows the
    // matching on-role so it stays legible under Dynamic Color.
    private fun tintRemoveBar(active: Boolean) {
        removeBar.setBackgroundColor(
            MaterialColors.getColor(removeBar, if (active) android.R.attr.colorError else MaterialR.attr.colorErrorContainer),
        )
        removeBar.setTextColor(
            MaterialColors.getColor(removeBar, if (active) MaterialR.attr.colorOnError else MaterialR.attr.colorOnErrorContainer),
        )
    }

    private fun tintAddToHomeBar(active: Boolean) {
        addToHomeBar.setBackgroundColor(
            MaterialColors.getColor(addToHomeBar, if (active) android.R.attr.colorPrimary else MaterialR.attr.colorPrimaryContainer),
        )
        addToHomeBar.setTextColor(
            MaterialColors.getColor(addToHomeBar, if (active) MaterialR.attr.colorOnPrimary else MaterialR.attr.colorOnPrimaryContainer),
        )
    }

    /** A descendant view's bounds in [homeRoot] (DragLayer) coordinates. */
    private fun rectInDragLayer(view: View, out: Rect) {
        out.set(0, 0, view.width, view.height)
        homeRoot.offsetDescendantRectToMyCoords(view, out)
    }

    /**
     * The [DrawerEntry] under a drop point (in homeRoot coords) during a drawer-fold drag,
     * or null if the finger is over empty drawer space. Resolves the drawer grid tile via
     * the same homeRoot→child coordinate shift as [resolveGridDrop].
     *
     * Reads the entry from the ADAPTER (the list actually on screen), not from
     * `drawerContent`: while a search is active the drawer shows the flat filtered list,
     * which differs in length and order from the folder-view projection — indexing the
     * wrong list would fold onto an unrelated app (silent membership corruption).
     */
    private fun drawerEntryAt(rootX: Int, rootY: Int): DrawerEntry? {
        val list = findViewById<RecyclerView>(R.id.drawer_panel) ?: return null
        val rootLoc = IntArray(2).also(homeRoot::getLocationOnScreen)
        val listLoc = IntArray(2).also(list::getLocationOnScreen)
        val localX = (rootX - (listLoc[0] - rootLoc[0])).toFloat()
        val localY = (rootY - (listLoc[1] - rootLoc[1])).toFloat()
        val child = list.findChildViewUnder(localX, localY) ?: return null
        val pos = list.getChildAdapterPosition(child)
        if (pos == RecyclerView.NO_POSITION) return null
        return (list.adapter as? AppDrawerAdapter)?.entryAt(pos)
    }

    private fun setupGestures() {
        // Swipe-up anywhere on the home surface opens the drawer. The shared
        // GestureDispatchCore drives detection through dispatchTouchEvent, so
        // it fires even over the ViewPager2 / dock RecyclerViews (which would
        // eat an OnTouchListener-based fling mid-scroll). Horizontal page
        // swipes fall through untouched via the analyzer's axis dominance.
        homeRoot.onSwipeUp = { if (homeGesturesAllowed()) showDrawer() }

        // Long-press on empty home space opens the live-preview customization
        // sheet (scrim/dim, monochrome, wallpaper, → full Settings). The shared
        // core's hit-test suppresses this over app icons and dock icons (they
        // keep their own long-press → drag), so it only fires on the wallpaper /
        // empty area.
        homeRoot.onLongPress = { if (homeGesturesAllowed()) showCustomizationDialog() }

        // Double-tap on empty home space shows the upcoming events (HIE Phase C3,
        // mirrors Kolibri); the two indicators next to the clock just signal that
        // events exist. Suppressed over icons by the shared core's hit-test.
        homeRoot.onDoubleTap = { if (homeGesturesAllowed()) showEventsDialog() }

        // The drawer's own swipe-down dismiss lives in AppDrawerFragment (its
        // root is a GestureFrameLayout), so it isn't wired here.
    }

    // ---- rendering ----

    private fun renderLayout(layout: HomeLayout?) {
        layout ?: return
        // Rebuild the pager adapter when the grid dimensions change (columns feed
        // the span count, rows the cell height + bottom padding). applyDeviceGrid
        // can change them at runtime, and a stale rows would mis-size the padding
        // and make the page scroll by a row. Cheap: the grid changes at most once
        // per launch.
        if (pagerAdapter == null || currentGrid != layout.grid) {
            currentGrid = layout.grid
            pagerAdapter = HomePagerAdapter(
                iconLoader = iconLoader,
                folderRenderer = folderRenderer,
                scope = lifecycleScope,
                iconSizePx = gridIconPx,
                columns = layout.grid.columns,
                rows = layout.grid.rows,
                onLaunch = ::launchApp,
                onOpenFolder = ::openFolder,
                onStartDrag = { v, id -> homeRoot.armDrag(DragPayload.Existing(id), v) },
                onMissingApp = ::confirmRemoveMissingApp,
            ).also { pager.adapter = it }
            // Seed the fresh adapter with the current dots: notificationDots is a
            // StateFlow that won't re-emit its unchanged value for a new adapter, so
            // without this a runtime grid change would drop the home-grid dots until
            // the next notification event.
            pagerAdapter?.submitNotificationDots(viewModel.notificationDots.value)
        }
        val currentPage = pager.currentItem
        // Render the occupied pages plus one empty landing page (see renderedPageCount).
        // The installed-keys set flags "missing" tiles (apps no longer installed) so they
        // render greyed; an empty set (pre-enumeration) flags nothing (see HomeCell.toCell).
        val installed = viewModel.installedKeys.value
        val renderedPages = layout.renderedPageCount()
        pagerAdapter?.submit((0 until renderedPages).map { layout.pageCells(it, installed) })
        if (currentPage < renderedPages) pager.setCurrentItem(currentPage, false)
        updatePageIndicator(layout)
        dockAdapter.submit(layout.dockCells(installed))
        // A drop leaves its drag view in place to bridge the async commit; the
        // commit's re-render arrives here, so clear it now (idempotent otherwise).
        // Skip while an actual drag is in flight (an unrelated re-render mid-drag
        // must not yank the live drag view).
        if (!homeRoot.dragController.isDragging) homeRoot.removeDragView()
    }

    /**
     * One indicator dot per OCCUPIED page (the render-computed empty landing page — see
     * [renderedPageCount] — deliberately gets none, so a fresh home never shows a phantom
     * trailing dot). Hidden while the home is a single page. The current page's dot is
     * brightened + slightly enlarged; on the landing page the last content dot stays lit.
     */
    private fun updatePageIndicator(layout: HomeLayout) {
        val occupied = layout.items.maxOfOrNull { it.pos.page + 1 } ?: 0
        if (occupied < 2) {
            pageIndicator.isVisible = false
            pageIndicator.removeAllViews()
            return
        }
        if (pageIndicator.childCount != occupied) {
            pageIndicator.removeAllViews()
            val size = (6 * resources.displayMetrics.density).toInt()
            val margin = (4 * resources.displayMetrics.density).toInt()
            repeat(occupied) {
                val dot = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginStart = margin; marginEnd = margin
                    }
                    background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.page_dot)
                }
                pageIndicator.addView(dot)
            }
        }
        val active = pager.currentItem.coerceIn(0, occupied - 1)
        for (i in 0 until pageIndicator.childCount) {
            val dot = pageIndicator.getChildAt(i)
            dot.alpha = if (i == active) 1f else 0.35f
            val scale = if (i == active) 1.2f else 1f
            dot.scaleX = scale
            dot.scaleY = scale
        }
        pageIndicator.isVisible = true
    }

    /**
     * Centers the dock icons as a group via symmetric padding, instead of the
     * LinearLayoutManager's left-packing: one icon lands dead-center, an odd count
     * keeps one exactly in the middle, an even count straddles it — the balanced,
     * "premium" look. Computed from the LIVE adapter count so it is always current.
     * The dock View stays match_parent, so its whole width remains a drop target (a
     * drop over the padding hits no child → append); only the content is centered.
     * Clamped to the base inset so a full dock keeps its edge padding; the
     * paddingStart guard makes the setPadding → relayout settle instead of looping.
     */
    private fun recenterDock() {
        if (dock.width == 0) { dock.doOnLayout { recenterDock() }; return }
        val density = resources.displayMetrics.density
        val itemPx = (DOCK_ITEM_DP * density).toInt()
        val basePx = (DOCK_MIN_PADDING_DP * density).toInt()
        val content = dockAdapter.itemCount * itemPx
        val pad = ((dock.width - content) / 2).coerceAtLeast(basePx)
        if (dock.paddingStart != pad) {
            dock.setPaddingRelative(pad, dock.paddingTop, pad, dock.paddingBottom)
        }
    }

    // ---- drawer overlay (AppDrawerFragment.Host) ----

    // Show/hide + slide + intended-open state now live in the shared
    // DrawerOverlayController (common-ui), identical to Kolibri; app-specific
    // open/close work (refresh, drag arm/disarm) runs in its hooks (see onCreate).
    private fun showDrawer() = drawerOverlay.show()

    override fun hideDrawer() = drawerOverlay.hide()

    /** Full off-screen travel for the slide; the overlay is full-height. */
    private fun drawerSlideDistance(): Float = resources.displayMetrics.heightPixels.toFloat()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Persist the intended-open state so a config change re-opens the drawer
        // instead of dropping it (parity with Kolibri; overlay visibility is not
        // part of saved view state).
        drawerOverlay.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // restore() normalises the container to hidden (so a restored view
        // visibility cannot desync from isOpen) and reports whether it was open.
        if (drawerOverlay.restore(savedInstanceState)) showDrawerRestored()
    }

    /** Re-show at rest (no slide) after a config change. */
    private fun showDrawerRestored() = drawerOverlay.show(animate = false)

    override fun launchFromDrawer(key: ComponentKey) {
        launchApp(key)
        hideDrawer()
    }

    override fun startDrawerDrag(view: View, key: ComponentKey) {
        // Arm (menu + drag): the drawer is hidden only once a move promotes to a drag
        // (onArmedPromote); a plain long-press keeps the menu over the drawer.
        homeRoot.armDrag(DragPayload.NewApp(key), view)
    }

    override fun openDrawerFolder(folder: DrawerEntry.Folder) {
        // A drawer folder, not a home folder — keep the home onClose path inactive.
        openFolderId = null
        // Latest known membership: the bulk-add path updates it optimistically so the overlay
        // can stay open to add several makers in a row without a reopen. The drawer tile behind
        // refreshes reactively from drawerContent regardless.
        var members = folder.members
        val adapter = FolderMemberAdapter(
            iconLoader = iconLoader,
            scope = lifecycleScope,
            iconSizePx = gridIconPx,
            onLaunch = { key -> launchApp(key); folderOverlayController.close() },
            // Extract: a drawer folder has no placement ("loose in the drawer" is implicit),
            // so a member long-press just removes it — it reappears as a loose app via the
            // projection, and the folder auto-dissolves below two members (DFOLD-INV-1).
            onStartDrag = { _, key ->
                viewModel.extractFromDrawerFolder(folder.id, key)
                folderOverlayController.close()
            },
        ).also { it.submit(members); it.submitNotificationDots(viewModel.notificationDots.value) }
        folderOverlayController.open(
            initialTitle = folder.title,
            titleEditable = true,
            memberAdapter = adapter,
            onAddApps = {
                showAddByMakerDialog(folder.id, members) { added ->
                    members = members + added
                    adapter.submit(members)
                }
            },
            onClose = {
                val newTitle = normalizeFolderTitle(folderOverlayController.title)
                if (newTitle != folder.title) viewModel.renameDrawerFolder(folder.id, newTitle)
            },
        )
    }

    /**
     * Drawer overflow menu (three-dot in the top bar): drawer-wide actions not tied to a
     * single app. For now just "create folder by maker"; a PopupMenu so it can grow.
     */
    override fun showDrawerOverflowMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(Menu.NONE, MENU_CREATE_FOLDER_BY_MAKER, Menu.NONE, R.string.drawer_create_folder_by_maker)
            // Sort toggle: label shows the mode you'd switch TO.
            menu.add(
                Menu.NONE, MENU_TOGGLE_USAGE_SORT, Menu.NONE,
                if (viewModel.usageSortEnabled.value) R.string.drawer_sort_alphabetical else R.string.drawer_sort_usage,
            )
            val revealing = viewModel.showHidden.value
            // Only offer the reveal toggle when there is something to reveal — or while already
            // revealing, so the user can always turn it back off.
            if (viewModel.hiddenApps.value.isNotEmpty() || revealing) {
                menu.add(
                    Menu.NONE, MENU_TOGGLE_HIDDEN, Menu.NONE,
                    if (revealing) R.string.drawer_hide_hidden else R.string.drawer_show_hidden,
                )
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CREATE_FOLDER_BY_MAKER -> { showCreateFolderByMakerDialog(); true }
                    MENU_TOGGLE_USAGE_SORT -> { viewModel.toggleUsageSort(); true }
                    MENU_TOGGLE_HIDDEN -> { viewModel.setShowHidden(!revealing); true }
                    else -> false
                }
            }
            show()
        }
    }

    /**
     * Maker picker that CREATES a new drawer folder: list every maker (Google, Samsung, …)
     * with ≥ 2 drawer apps and the count; a tap makes a new folder titled after the maker
     * holding all its apps — no need to hand-fold two apps first to then bulk-add the rest.
     */
    private fun showCreateFolderByMakerDialog() {
        val groups = viewModel.drawerVendorGroups()
        if (groups.isEmpty()) {
            showToastSafe(R.string.drawer_create_folder_by_maker_none)
            return
        }
        val labels = groups.map { "${it.label} (${it.keys.size})" }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.drawer_create_folder_by_maker)
            .setItems(labels) { _, index ->
                val group = groups[index]
                viewModel.createDrawerFolderFromMaker(group.label, group.keys)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showTrackedDialog(dialog)
    }

    /**
     * Vendor picker for the open drawer folder: list the makers (Google, Samsung, …) that
     * still have apps NOT already in this folder, with the count that would be added; a tap
     * bulk-adds them. Fully tap-operable (an accessible alternative to fold-dragging).
     */
    private fun showAddByMakerDialog(
        folderId: DrawerFolderId,
        currentMembers: List<ComponentKey>,
        onAdded: (List<ComponentKey>) -> Unit,
    ) {
        val addable = viewModel.addableVendorGroups(currentMembers.toSet())
        if (addable.isEmpty()) {
            showToastSafe(R.string.folder_add_by_maker_none)
            return
        }
        val labels = addable.map { "${it.label} (${it.keys.size})" }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.folder_add_by_maker)
            .setItems(labels) { _, index ->
                val group = addable[index]
                viewModel.addAllToDrawerFolder(folderId, group.keys)
                // Auto-name a still-unnamed folder after the maker just added; never clobber
                // a title the user already set. Persisted via the overlay's rename-on-close.
                if (folderOverlayController.title.isBlank()) {
                    folderOverlayController.setTitle(group.label)
                }
                onAdded(group.keys)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showTrackedDialog(dialog)
    }

    // ---- drag ----

    private fun startDrag(view: View, payload: DragPayload) {
        homeRoot.startDrag(payload, view)
    }

    /**
     * Derive columns/rows from the actual pager (home-grid) area and hand them to
     * the ViewModel to re-fit the layout. Using the measured area — not a
     * pre-layout metrics estimate — makes the row count exact, so the
     * bottom-anchored grid leaves at most a sub-cell margin at the top. Idempotent
     * downstream, so running it on every layout (incl. rotation) is cheap.
     */
    private fun applyDeviceGrid() {
        val grid = computeDeviceGrid(pager.width, pager.height, resources.displayMetrics.density) ?: return
        viewModel.applyDeviceGrid(grid.columns, grid.rows)
    }

    /**
     * View glue for the grid drop: translates a drop point (in [homeRoot]
     * coordinates) into the current page's local coordinates, then hands off to
     * the pure [gridCellAt] geometry. Returns null only if the pager isn't laid
     * out yet.
     */
    private fun resolveGridDrop(rootX: Float, rootY: Float): DropTarget? {
        val grid = viewModel.layout.value?.grid ?: return null
        val internal = pager.getChildAt(0) as? RecyclerView ?: return null
        val page = pager.currentItem
        val pageView = internal.layoutManager?.findViewByPosition(page) as? RecyclerView ?: return null

        val rootLoc = IntArray(2).also(homeRoot::getLocationOnScreen)
        val pageLoc = IntArray(2).also(pageView::getLocationOnScreen)
        val localX = rootX - (pageLoc[0] - rootLoc[0])
        val localY = rootY - (pageLoc[1] - rootLoc[1])

        return gridDropAt(page, localX, localY, pageView.width, pageView.height, grid, resources.displayMetrics.density)
    }

    // ---- pager edge auto-advance during a drag ----

    // The dwell/re-arm state machine lives in EdgeAdvanceController (JVM-tested);
    // this only supplies the view/runtime glue.
    private val edgeAdvance = EdgeAdvanceController(
        dwellMs = EDGE_ADVANCE_DWELL_MS,
        scheduler = object : EdgeAdvanceController.Scheduler {
            override fun postDelayed(delayMs: Long, action: Runnable) {
                homeRoot.postDelayed(action, delayMs)
            }
            override fun cancel(action: Runnable) {
                homeRoot.removeCallbacks(action)
            }
        },
        isDragging = { homeRoot.dragController.isDragging },
        direction = ::edgeDirection,
        currentPage = { pager.currentItem },
        maxPage = { (viewModel.layout.value?.renderedPageCount() ?: 1) - 1 },
        goToPage = { pager.setCurrentItem(it, true) },
    )

    /** View glue for [pageEdgeDirection]: reads the pager rect + density. */
    private fun edgeDirection(x: Int): Int {
        val rect = Rect().also { rectInDragLayer(pager, it) }
        val edge = (EDGE_ADVANCE_DP * resources.displayMetrics.density).toInt()
        return pageEdgeDirection(x, rect.left, rect.right, edge)
    }

    // ---- folder sheet ----

    private fun openFolder(folderId: ItemId) {
        val layout = viewModel.layout.value ?: return
        val folder = layout.allHomeItems().firstOrNull { it.id == folderId } as? HomeItem.Folder ?: return

        openFolderId = folderId
        openFolderTitle = folder.title
        val adapter = FolderMemberAdapter(
            iconLoader = iconLoader,
            scope = lifecycleScope,
            iconSizePx = gridIconPx,
            onLaunch = { key -> launchApp(key); folderOverlayController.close() },
            onStartDrag = { view, key -> startFolderMemberDrag(view, key) },
            installed = viewModel.installedKeys.value,
            onMissingApp = { key -> confirmRemoveMissingFolderMember(folderId, key) },
        ).also { it.submit(folder.members); it.submitNotificationDots(viewModel.notificationDots.value) }
        folderOverlayController.open(
            initialTitle = folder.title,
            titleEditable = true,
            memberAdapter = adapter,
            onClose = { applyFolderTitleEdit() },
        )
    }

    /**
     * Long-press on a folder member → start a normal home drag of it (finger-drag),
     * then hide the overlay so it lands on the grid/dock where the user drops it.
     * The drag continues in the same window (the overlay lives in the DragLayer),
     * so the touch stream hands straight off to the DragController.
     */
    private fun startFolderMemberDrag(view: View, key: ComponentKey) {
        val folderId = openFolderId ?: return
        applyFolderTitleEdit() // persist any rename before the folder may dissolve
        startDrag(view, DragPayload.FolderMember(folderId, key))
        openFolderId = null
        folderOverlayController.dismiss() // title already committed; don't re-run onClose
    }

    /** Closes the folder overlay (tap-outside / launch); its onClose commits the title. */
    private fun closeFolderOverlay() {
        if (!folderOverlayController.isVisible) return
        folderOverlayController.close() // onClose: home applyFolderTitleEdit / drawer rename
        openFolderId = null
    }

    private fun applyFolderTitleEdit() {
        val folderId = openFolderId ?: return
        // Trim before comparing/persisting so a whitespace-only entry collapses to "" (the
        // default-hint state) instead of being stored verbatim (see normalizeFolderTitle).
        val newTitle = normalizeFolderTitle(folderTitle.text.toString())
        if (newTitle != openFolderTitle) viewModel.renameFolder(folderId, newTitle)
    }

    /**
     * Empty-space home gestures (swipe-up/long-press/double-tap) fire only when no
     * modal surface is up — edit mode, the folder overlay, or the context menu.
     * (These overlays keep gesturesEnabled=true so drags still capture, so the
     * gesture core would otherwise still run the callbacks over them.)
     */
    private fun homeGesturesAllowed(): Boolean = homeGesturesAllowed(
        editMode = wallpaperEditCoordinator.isEditMode.value,
        folderVisible = folderOverlay.isVisible,
        contextMenuVisible = contextMenuOverlay.isVisible,
    )

    // ---- long-press context menu ----

    private class ContextMenuItem(val label: String, val icon: Drawable? = null, val action: () -> Unit)

    /**
     * Bumped on every show and dismiss. The shortcut-load coroutine captures the value
     * at launch and re-checks it before mutating the shared card, so a stale load (its
     * cross-process getShortcuts still in flight after the menu was dismissed and
     * re-shown for a DIFFERENT item) no longer prepends the wrong app's shortcuts onto —
     * and re-anchors — the current menu.
     */
    private var contextMenuGeneration = 0

    private fun showContextMenu(payload: DragPayload, source: View) {
        val pkg = payloadPackage(payload)
        val newAppKey = (payload as? DragPayload.NewApp)?.key
        // A missing tile (kept reference to an uninstalled app) drops App info + Uninstall.
        // LazySlotMembership treats an EMPTY installed set as "not loaded yet" → nothing is
        // missing during the cold-start window, so actions aren't hidden spuriously.
        val payloadKey = payloadKey(payload)
        val isInstalled = payloadKey == null ||
            !LazySlotMembership.isMissing(payloadKey, viewModel.installedKeys.value)
        val standard = buildHomeContextMenuActions(
            payload = payload,
            packageName = pkg,
            isHidden = newAppKey != null && newAppKey in viewModel.hiddenApps.value,
            isSystemApp = pkg != null && isSystemApp(pkg),
            isInstalled = isInstalled,
        ).map(::contextMenuItemFor)
        if (standard.isEmpty() && pkg == null) return
        val generation = ++contextMenuGeneration
        contextMenuCard.removeAllViews()
        standard.forEach(::addMenuRow)
        contextMenuOverlay.isVisible = true
        positionContextMenu(source)
        // Load the app's shortcuts OFF the main thread (getShortcuts + icon decode are
        // cross-process), then prepend them Pixel-style; skip if the menu was dismissed
        // or already replaced by another item's menu (stale generation).
        pkg ?: return
        lifecycleScope.launch {
            val shortcuts = withContext(Dispatchers.Default) { appShortcuts(pkg) }
            if (generation != contextMenuGeneration || !contextMenuOverlay.isVisible || shortcuts.isEmpty()) return@launch
            val header = shortcuts.map(::makeMenuRow) + makeMenuDivider()
            header.forEachIndexed { i, view -> contextMenuCard.addView(view, i) }
            positionContextMenu(source) // height grew — re-anchor
        }
    }

    /** Anchors the menu card next to [source] once it has measured. */
    private fun positionContextMenu(source: View) {
        contextMenuCard.doOnLayout {
            val icon = IntArray(2).also(source::getLocationInWindow)
            val root = IntArray(2).also(homeRoot::getLocationInWindow)
            val anchor = contextMenuAnchor(
                iconX = icon[0] - root[0],
                iconY = icon[1] - root[1],
                sourceWidth = source.width,
                sourceHeight = source.height,
                cardWidth = contextMenuCard.width,
                cardHeight = contextMenuCard.height,
                rootWidth = homeRoot.width,
                rootHeight = homeRoot.height,
                margin = (12 * resources.displayMetrics.density).toInt(),
            )
            contextMenuCard.translationX = anchor.x.toFloat()
            contextMenuCard.translationY = anchor.y.toFloat()
        }
    }

    private fun makeMenuRow(item: ContextMenuItem): View {
        val row = layoutInflater.inflate(R.layout.item_context_menu, contextMenuCard, false) as TextView
        row.text = item.label
        item.icon?.let {
            row.setCompoundDrawablesRelative(it, null, null, null)
            row.compoundDrawablePadding = (14 * resources.displayMetrics.density).toInt()
        }
        row.setOnClickListener { item.action(); dismissContextMenu() }
        return row
    }

    private fun addMenuRow(item: ContextMenuItem) {
        contextMenuCard.addView(makeMenuRow(item))
    }

    private fun makeMenuDivider(): View {
        val margin = (6 * resources.displayMetrics.density).toInt()
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * resources.displayMetrics.density).toInt(),
            ).apply { topMargin = margin; bottomMargin = margin }
            setBackgroundColor(0x22FFFFFF)
        }
    }

    /** The ComponentKey a payload points at, or null (a folder / stale id). */
    private fun payloadKey(payload: DragPayload): ComponentKey? = when (payload) {
        is DragPayload.Existing ->
            (viewModel.layout.value?.allHomeItems()?.firstOrNull { it.id == payload.id } as? HomeItem.App)?.key
        is DragPayload.NewApp -> payload.key
        is DragPayload.FolderMember -> payload.key
    }

    private fun payloadPackage(payload: DragPayload): String? = payloadKey(payload)?.packageName

    /**
     * The app's launcher shortcuts (dynamic + manifest + pinned), tap-to-launch —
     * only available while nyx is the default launcher (else getShortcuts throws
     * SecurityException and we simply show none). Pinning-to-home is not offered
     * yet (would need a shortcut item type in the layout model).
     */
    private fun appShortcuts(pkg: String): List<ContextMenuItem> {
        val launcherApps = getSystemService(LauncherApps::class.java) ?: return emptyList()
        return try {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(pkg)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
                )
            val sizePx = (24 * resources.displayMetrics.density).toInt()
            (launcherApps.getShortcuts(query, Process.myUserHandle()) ?: emptyList())
                .filter { it.isEnabled }
                .sortedBy { it.rank }
                .take(4)
                .mapNotNull { sc ->
                    val label = (sc.shortLabel ?: sc.longLabel)?.toString() ?: return@mapNotNull null
                    // no suspension point — getShortcutIconDrawable is a synchronous IPC.
                    val icon = runCatching {
                        launcherApps.getShortcutIconDrawable(sc, resources.displayMetrics.densityDpi)
                    }.getOrNull()?.apply { setBounds(0, 0, sizePx, sizePx) }
                    ContextMenuItem(label, icon) {
                        // no suspension point — the action runs on tap; startShortcut is synchronous.
                        runCatching { launcherApps.startShortcut(sc, null, null) }
                    }
                }
        } catch (e: SecurityException) {
            emptyList() // not the default launcher
        } catch (e: IllegalStateException) {
            emptyList() // user locked / shortcuts unavailable
        }
    }

    /** Renders one pure [HomeContextMenuAction] into its tappable menu row. */
    private fun contextMenuItemFor(action: HomeContextMenuAction): ContextMenuItem = when (action) {
        is HomeContextMenuAction.AppInfo ->
            ContextMenuItem(getString(R.string.menu_app_info)) { openAppInfo(action.packageName) }
        is HomeContextMenuAction.RemoveFromHome ->
            ContextMenuItem(getString(R.string.menu_remove_from_home)) { viewModel.remove(action.id) }
        is HomeContextMenuAction.Uninstall ->
            ContextMenuItem(getString(R.string.menu_uninstall)) { uninstallApp(action.packageName) }
        is HomeContextMenuAction.AddToHome ->
            ContextMenuItem(getString(R.string.menu_add_to_home)) { addToHome(action.key) }
        is HomeContextMenuAction.HideApp ->
            ContextMenuItem(getString(R.string.menu_hide_app)) { viewModel.hideApp(action.key) }
        is HomeContextMenuAction.UnhideApp ->
            ContextMenuItem(getString(R.string.menu_unhide_app)) { viewModel.unhideApp(action.key) }
    }

    private fun dismissContextMenu() {
        if (!contextMenuOverlay.isVisible) return
        contextMenuGeneration++ // invalidate any in-flight shortcut-load coroutine
        contextMenuOverlay.isVisible = false
        contextMenuCard.removeAllViews()
    }

    /** "Add to home" (drawer menu): place the app at the first free cell, close the drawer. */
    private fun addToHome(key: ComponentKey) {
        val cell = viewModel.layout.value?.firstFreeCell() ?: return
        viewModel.place(key, DropTarget.Cell(cell))
        if (drawerOverlay.isOpen) hideDrawer()
    }

    // Intent construction mirrors Kolibri's app-info action (Uri.fromParts +
    // NEW_TASK) for consistency across the family.
    private fun openAppInfo(pkg: String) = startActivitySafe(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", pkg, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        },
    )

    // FLAG_ACTIVITY_NEW_TASK is required (as in openAppInfo): the system uninstaller has its
    // own taskAffinity, and launching it from the launcher's home task without NEW_TASK can be
    // silently dropped (observed on One UI) — the menu entry then "does nothing".
    private fun uninstallApp(pkg: String) = startActivitySafe(
        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        },
    )

    private fun isSystemApp(pkg: String): Boolean = try {
        (packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun startActivitySafe(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            TimberWrapper.silentError(e, "No activity for $intent")
        }
    }

    // ---- helpers ----

    private fun currentColumns(): Int = viewModel.layout.value?.grid?.columns ?: 1

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun showCustomizationDialog() {
        NyxCustomizationDialog().show(supportFragmentManager, NyxCustomizationDialog.TAG)
    }

    /** True when a wallpaper is set (the customization sheet gates its Edit entry on this). */
    fun hasWallpaper(): Boolean = wallpaperEditCoordinator.wallpaperState.value.hasWallpaper

    /** Enters wallpaper edit mode (called from the customization sheet's Edit entry). */
    fun enterWallpaperEditMode() {
        if (hasWallpaper()) wallpaperEditCoordinator.onEnterEditMode()
    }

    private fun launchApp(key: ComponentKey) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(key.packageName, key.className))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // no suspension point — launchApp is synchronous (startActivity).
        // Shared launch taxonomy (:common-ui): a failed tap now toasts instead of
        // silently doing nothing (ActivityNotFoundException) or crashing
        // (SecurityException) — Pixel / Kolibri parity. Usage is recorded only on a
        // real launch, so an uninstalled-since-last-refresh package never bumps it.
        // Component-gone cleanup is handled reactively by PackageEventCoordinator.
        when (val result = runLaunchCatching { startActivity(intent) }) {
            AppLaunchResult.Launched -> viewModel.recordLaunch(key)
            AppLaunchResult.ComponentGone,
            AppLaunchResult.PermissionDenied -> showToastSafe(R.string.app_launch_failed)
            is AppLaunchResult.Failed -> {
                TimberWrapper.silentError(result.cause, "app launch failed: ${key.packageName}")
                showToastSafe(R.string.app_launch_failed)
            }
        }
    }

}

/** Every top-level item across the grid and the dock. */
private fun HomeLayout.allHomeItems(): List<HomeItem> = items.map { it.item } + dock

/**
 * Fire [action] on a double-tap of this view. Consumes the whole touch stream (returns
 * true) so a single tap does nothing and the gesture never bubbles up to the home
 * double-tap (events dialog).
 */
@SuppressLint("ClickableViewAccessibility")
private fun View.setOnDoubleTap(action: () -> Unit) {
    // An OnClickListener is also set (not just the touch double-tap) for TWO reasons:
    //  - Accessibility: TalkBack's activation gesture is a synthetic ACTION_CLICK that bypasses
    //    onTouch, so without an OnClickListener the target would be unreachable via TalkBack.
    //    A sighted single tap still does nothing — the onTouch listener below consumes the
    //    stream (returns true) so onTouchEvent/performClick never runs for a physical tap.
    //  - It marks this view as its own touch pipeline (hasOnClickListeners), so the home
    //    GestureDispatchCore suppresses its own double-tap over it (no events-dialog double-fire).
    setOnClickListener { action() }
    val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                action()
                return true
            }
        },
    )
    setOnTouchListener { _, event -> detector.onTouchEvent(event); true }
}

/** Drawer overflow menu item ids. */
private const val MENU_CREATE_FOLDER_BY_MAKER = 1
private const val MENU_TOGGLE_HIDDEN = 2
private const val MENU_TOGGLE_USAGE_SORT = 3

/** Width of the left/right pager edge zone (dp) that triggers drag page-advance. */
private const val EDGE_ADVANCE_DP = 36f

/** Dwell at the pager edge before advancing one page (and between repeats), in ms. */
private const val EDGE_ADVANCE_DWELL_MS = 500L

/** Dock icon slot width in dp (matches item_dock_icon.xml), for centering math. */
private const val DOCK_ITEM_DP = 72f

/** Minimum horizontal dock padding in dp (matches activity_main.xml). */
private const val DOCK_MIN_PADDING_DP = 12f

/** Drawer slide-up/down duration, mirroring Kolibri's anim_duration_drawer_slide. */
private const val DRAWER_SLIDE_MS = 180L

/** Fallback delay to clear a dropped drag view when no commit re-render arrives. */
private const val DRAG_SETTLE_FALLBACK_MS = 300L

/** Visible content height (below the system-bar inset) of the drop bars, in dp. */
private const val DROP_BAR_CONTENT_DP = 64f
