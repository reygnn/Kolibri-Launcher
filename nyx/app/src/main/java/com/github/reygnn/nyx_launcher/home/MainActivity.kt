package com.github.reygnn.nyx_launcher.home

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.data.DefaultAppsResolver
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.data.home.NyxFabPositionStore
import com.github.reygnn.nyx_launcher.data.home.NyxWallpaperImageSetter
import com.github.reygnn.nyx_launcher.home.wallpaper.NyxWallpaperEditController
import com.github.reygnn.nyx_launcher.home.drag.DragLayer
import com.github.reygnn.nyx_launcher.home.drag.DropZone
import com.github.reygnn.nyx_launcher.home.drawer.AppDrawerFragment
import com.github.reygnn.launcher.common.ui.timeinfo.ClockDelegate
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperViewBinder
import com.github.reygnn.launcher.common.ui.wallpaper.ZoomableImageView
import com.github.reygnn.launcher.common.ui.wallpaper.decodeBoundedWallpaperBitmap
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.wallpaper.WallpaperRenderScheduler
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.nyx_launcher.home.wallpaper.NyxWallpaperEditCoordinator
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.settings.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
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
class MainActivity : AppCompatActivity(), AppDrawerFragment.Host {

    private val viewModel: HomeViewModel by viewModels()

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

    // First-run dock seed: resolve the device's default Phone/SMS/Email/Browser/Camera
    // and place them in the dock so a fresh install isn't a blank screen (one-shot; the
    // repo no-ops on a returning install).
    @Inject lateinit var defaultAppsResolver: DefaultAppsResolver
    @Inject lateinit var homeLayoutRepository: HomeLayoutRepository

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
    private lateinit var dock: RecyclerView
    private lateinit var drawerContainer: View
    private lateinit var removeBar: TextView

    // In-DragLayer folder overlay (finger-drag extraction). Populated on open.
    private lateinit var folderOverlay: View
    private lateinit var folderTitle: EditText
    private lateinit var folderMembers: RecyclerView
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
            // BitmapLoader contract: return null on failure, let only cancellation
            // escape. decodeBoundedWallpaperBitmap does NOT catch internally —
            // openInputStream can throw FileNotFoundException/SecurityException and
            // decode can OOM (Throwable). Without this guard the throw would escape
            // bind() → the unguarded collect/launch → crash the HOME activity
            // (mirrors Kolibri's loadBitmapFromUri).
            withContext(Dispatchers.IO) {
                try {
                    decodeBoundedWallpaperBitmap { contentResolver.openInputStream(uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error loading wallpaper bitmap from $uri")
                    null
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
        dock = findViewById(R.id.dock)
        drawerContainer = findViewById(R.id.drawer_container)
        removeBar = findViewById(R.id.remove_bar)
        folderOverlay = findViewById(R.id.folder_overlay)
        folderTitle = findViewById(R.id.folder_title)
        folderMembers = findViewById(R.id.folder_members)
        // Tap the scrim (outside the card) closes; the card swallows its own taps.
        folderOverlay.setOnClickListener { closeFolderOverlay() }
        findViewById<View>(R.id.folder_card).setOnClickListener { /* swallow */ }
        contextMenuOverlay = findViewById(R.id.context_menu_overlay)
        contextMenuCard = findViewById(R.id.context_menu_card)
        contextMenuOverlay.setOnClickListener { dismissContextMenu() }
        contextMenuCard.setOnClickListener { /* swallow */ }
        // Launcher3-style long-press: arm shows the menu; a move promotes to a drag.
        homeRoot.onArm = { payload, source -> showContextMenu(payload, source) }
        homeRoot.onArmedPromote = {
            dismissContextMenu()
            if (drawerContainer.isVisible) hideDrawer()
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
        // settings (mirrors Kolibri's intents).
        clockTime.setOnClickListener { openClockApp() }
        clockDate.setOnClickListener { openCalendarApp() }
        clockBattery.setOnClickListener { openBatterySettings() }
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
            val content = (REMOVE_BAR_CONTENT_DP * resources.displayMetrics.density).toInt()
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
            } else if (drawerContainer.isVisible) {
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

        // One-shot on startup: reclaim wallpaper files stranded by a crash between
        // copy and save (the shared repo/file-manager split doesn't self-clean).
        lifecycleScope.launch { wallpaperImageSetter.reclaimOrphans() }

        // One-shot on startup: seed the dock with the device's default apps on first
        // run. Resolving hits PackageManager, so it's done off the main thread; the
        // repo gates the write (SEEDED_KEY) so a returning install is a no-op.
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.Default) { defaultAppsResolver.resolveDockApps() }
            homeLayoutRepository.seedInitialDock(apps)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.layout.collect(::renderLayout) }
                launch { viewModel.monochromeIcons.collect { renderLayout(viewModel.layout.value) } }
                launch { clockDelegate.timeString.collect { clockTime.text = it } }
                launch { clockDelegate.dateString.collect { clockDate.text = it } }
                launch { clockDelegate.batteryString.collect { clockBattery.text = it } }
                launch { clockDelegate.timeBasedEvents.collect(::updateEventsIndicator) }
                // Wallpaper (WV5): render on every state change (latest-wins — the
                // collector awaits each bind before the next emission). Scrim +
                // backdrop react to their own settings flows.
                launch {
                    wallpaperEditCoordinator.wallpaperState.collect { renderWallpaper(it) }
                }
                launch { wallpaperDisplaySettings.wallpaperScrimAlphaStateFlow.collect { currentScrimAlpha = it; applyScrim() } }
                launch {
                    wallpaperDisplaySettings.wallpaperBackdropFlow.collect {
                        applyBackdrop(it)
                        wallpaperEditController.applyBackdrop(it)
                    }
                }
                launch {
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
                launch { fabPositionStore.fabPositionFlow.collect { wallpaperEditController.applyFabPosition(it) } }
            }
        }
    }

    /**
     * Single entry point to render a wallpaper [state] onto the view. Routed through
     * [wallpaperRenderScheduler] (latest-wins, cancels the previous in-flight bind) so
     * the state collector and rerenderWallpaper can never run two concurrent binds.
     * Consumes the one-shot focus hint and re-syncs the edit toolbar after a rebuild.
     */
    private fun renderWallpaper(state: WallpaperState) {
        val focusId = wallpaperEditCoordinator.consumePendingFocusLayerId()
        wallpaperRenderScheduler.render(lifecycleScope) {
            wallpaperBinder.bind(
                wallpaperView,
                state,
                preferredActiveLayerId = focusId,
                onRebuildComplete = { wallpaperEditController.onWallpaperRebuilt() },
            )
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

    /** All upcoming events, grouped today/tomorrow via the shared formatter. */
    private fun showEventsDialog() {
        val events = currentEvents
        if (events.isEmpty()) return
        val is24Hour = DateFormat.is24HourFormat(this)
        val allDay = getString(R.string.event_all_day)
        val zone = ZoneId.systemDefault()
        val text = timeEventFormatter.buildEventRows(events, LocalDate.now(zone), zone)
            .joinToString("\n") { row ->
                when (row) {
                    is TimeEventFormatter.EventRow.Item ->
                        timeEventFormatter.formatEventRow(row.event, is24Hour, allDay)
                    TimeEventFormatter.EventRow.TomorrowSeparator ->
                        getString(R.string.events_tomorrow_separator)
                }
            }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.events_dialog_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
        runCatching { unregisterReceiver(batteryReceiver) }
    }

    // ---- setup ----

    private fun setupDock() {
        dockAdapter = DockAdapter(iconLoader, folderRenderer, lifecycleScope, gridIconPx, ::launchApp, ::openFolder) { v, id ->
            homeRoot.armDrag(DragPayload.Existing(id), v)
        }
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
        controller.onDragStart = { removeBar.visibility = View.VISIBLE }
        controller.onDragEnd = {
            removeBar.visibility = View.INVISIBLE
            removeBar.setBackgroundColor(REMOVE_BAR_IDLE_COLOR)
            cancelEdgeAdvance()
        }
        // Hold a drag at the left/right pager edge to page across grids (incl. the
        // empty landing page), so an app can be carried to another page.
        controller.onDragMove = { x, _ -> onDragEdge(x) }
        // The drag view is kept at the drop point until the commit's re-render
        // clears it (renderLayout). This fallback covers no-op drops (same cell)
        // and errors, where no re-render arrives.
        controller.onDropSettle = {
            homeRoot.postDelayed({
                if (!homeRoot.dragController.isDragging) homeRoot.removeDragView()
            }, DRAG_SETTLE_FALLBACK_MS)
        }

        // 1) Remove zone — the top strip, reaching y=0. Existing items only.
        controller.addDropZone(object : DropZone {
            override fun hitRect(out: Rect) = out.set(0, 0, homeRoot.width, removeBar.bottom)
            override fun accepts(payload: DragPayload) = payload is DragPayload.Existing
            override fun onDragEnter() { removeBar.setBackgroundColor(REMOVE_BAR_ACTIVE_COLOR) }
            override fun onDragExit() { removeBar.setBackgroundColor(REMOVE_BAR_IDLE_COLOR) }
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
                var index = 0
                for (i in 0 until dock.childCount) {
                    val child = dock.getChildAt(i)
                    if (child.visibility != View.VISIBLE) continue // the icon being dragged
                    if (dock.getChildAdapterPosition(child) == RecyclerView.NO_POSITION) continue
                    if (child.left + child.width / 2f < localX) index++
                }
                applyDrop(payload, DropTarget.DockSlot(index))
            }
        })

        // 3) Grid zone — the rest of the surface; target resolved geometrically
        // (cell centre → place/folder, cell edge → reorder-insert).
        controller.addDropZone(object : DropZone {
            override fun hitRect(out: Rect) = rectInDragLayer(pager, out)
            override fun accepts(payload: DragPayload) = true
            override fun onDrop(payload: DragPayload, x: Int, y: Int) {
                resolveGridDrop(x.toFloat(), y.toFloat())?.let { applyDrop(payload, it) }
            }
        })
    }

    /** A descendant view's bounds in [homeRoot] (DragLayer) coordinates. */
    private fun rectInDragLayer(view: View, out: Rect) {
        out.set(0, 0, view.width, view.height)
        homeRoot.offsetDescendantRectToMyCoords(view, out)
    }

    private fun setupGestures() {
        // Swipe-up anywhere on the home surface opens the drawer. The shared
        // GestureDispatchCore drives detection through dispatchTouchEvent, so
        // it fires even over the ViewPager2 / dock RecyclerViews (which would
        // eat an OnTouchListener-based fling mid-scroll). Horizontal page
        // swipes fall through untouched via the analyzer's axis dominance.
        homeRoot.onSwipeUp = { if (!wallpaperEditCoordinator.isEditMode.value) showDrawer() }

        // Long-press on empty home space opens the live-preview customization
        // sheet (scrim/dim, monochrome, wallpaper, → full Settings). The shared
        // core's hit-test suppresses this over app icons and dock icons (they
        // keep their own long-press → drag), so it only fires on the wallpaper /
        // empty area.
        homeRoot.onLongPress = { if (!wallpaperEditCoordinator.isEditMode.value) showCustomizationDialog() }

        // Double-tap on empty home space shows the upcoming events (HIE Phase C3,
        // mirrors Kolibri); the two indicators next to the clock just signal that
        // events exist. Suppressed over icons by the shared core's hit-test.
        homeRoot.onDoubleTap = { if (!wallpaperEditCoordinator.isEditMode.value) showEventsDialog() }

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
            ).also { pager.adapter = it }
        }
        val currentPage = pager.currentItem
        // Render the occupied pages plus one empty landing page (see renderedPageCount).
        val renderedPages = layout.renderedPageCount()
        pagerAdapter?.submit((0 until renderedPages).map(layout::pageCells))
        if (currentPage < renderedPages) pager.setCurrentItem(currentPage, false)
        dockAdapter.submit(layout.dockCells())
        // A drop leaves its drag view in place to bridge the async commit; the
        // commit's re-render arrives here, so clear it now (idempotent otherwise).
        // Skip while an actual drag is in flight (an unrelated re-render mid-drag
        // must not yank the live drag view).
        if (!homeRoot.dragController.isDragging) homeRoot.removeDragView()
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

    private fun showDrawer() {
        if (drawerContainer.isVisible) return
        viewModel.refreshDrawer() // pick up apps installed/removed since last open (A1-04)
        // Slide up from below, over the home (which stays put) — matching
        // Kolibri's drawer transition (translateY 100%→0, 180ms, accel-decel).
        drawerContainer.translationY = drawerSlideDistance()
        drawerContainer.isVisible = true
        drawerContainer.animate()
            .translationY(0f)
            .setDuration(DRAWER_SLIDE_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    override fun hideDrawer() {
        if (!drawerContainer.isVisible) return
        drawerContainer.animate()
            .translationY(drawerSlideDistance())
            .setDuration(DRAWER_SLIDE_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                drawerContainer.isVisible = false
                drawerContainer.translationY = 0f
            }
            .start()
    }

    /** Full off-screen travel for the slide; the overlay is full-height. */
    private fun drawerSlideDistance(): Float = resources.displayMetrics.heightPixels.toFloat()

    override fun launchFromDrawer(key: ComponentKey) {
        launchApp(key)
        hideDrawer()
    }

    override fun startDrawerDrag(view: View, key: ComponentKey) {
        // Arm (menu + drag): the drawer is hidden only once a move promotes to a drag
        // (onArmedPromote); a plain long-press keeps the menu over the drawer.
        homeRoot.armDrag(DragPayload.NewApp(key), view)
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
        val density = resources.displayMetrics.density
        val colTargetPx = HOME_COL_TARGET_DP * density
        val rowTargetPx = HOME_CELL_TARGET_DP * density
        if (pager.width <= 0 || pager.height <= 0 || colTargetPx <= 0f || rowTargetPx <= 0f) return
        val columns = (pager.width / colTargetPx).toInt().coerceIn(HOME_MIN_COLUMNS, HOME_MAX_COLUMNS)
        val rows = (pager.height / rowTargetPx).toInt().coerceIn(HOME_MIN_ROWS, HOME_MAX_ROWS)
        viewModel.applyDeviceGrid(columns, rows)
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

    private fun applyDrop(payload: DragPayload, target: DropTarget) = when (payload) {
        is DragPayload.Existing -> viewModel.move(payload.id, target)
        is DragPayload.NewApp -> viewModel.place(payload.key, target)
        is DragPayload.FolderMember -> viewModel.extractFromFolder(payload.folderId, payload.key, target)
    }

    // ---- pager edge auto-advance during a drag ----

    private var lastDragX = 0
    private var edgeAdvanceScheduled = false

    // Fires after a dwell at the edge: flip one page toward the edge, then re-arm
    // while the finger is still held there (continuous paging).
    private val edgeAdvanceRunnable = object : Runnable {
        override fun run() {
            edgeAdvanceScheduled = false
            if (!homeRoot.dragController.isDragging) return
            val dir = edgeDirection(lastDragX)
            if (dir == 0) return
            val maxPage = (viewModel.layout.value?.renderedPageCount() ?: 1) - 1
            val target = (pager.currentItem + dir).coerceIn(0, maxPage)
            if (target != pager.currentItem) pager.setCurrentItem(target, true)
            scheduleEdgeAdvance() // keep paging while held at the edge
        }
    }

    /** -1 near the left pager edge, +1 near the right, 0 otherwise. */
    private fun edgeDirection(x: Int): Int {
        val rect = Rect().also { rectInDragLayer(pager, it) }
        val edge = (EDGE_ADVANCE_DP * resources.displayMetrics.density).toInt()
        return when {
            x <= rect.left + edge -> -1
            x >= rect.right - edge -> 1
            else -> 0
        }
    }

    private fun onDragEdge(x: Int) {
        lastDragX = x
        if (edgeDirection(x) != 0) scheduleEdgeAdvance() else cancelEdgeAdvance()
    }

    private fun scheduleEdgeAdvance() {
        if (edgeAdvanceScheduled) return
        edgeAdvanceScheduled = true
        homeRoot.postDelayed(edgeAdvanceRunnable, EDGE_ADVANCE_DWELL_MS)
    }

    private fun cancelEdgeAdvance() {
        if (!edgeAdvanceScheduled) return
        homeRoot.removeCallbacks(edgeAdvanceRunnable)
        edgeAdvanceScheduled = false
    }

    // ---- folder sheet ----

    private fun openFolder(folderId: ItemId) {
        val layout = viewModel.layout.value ?: return
        val folder = layout.allHomeItems().firstOrNull { it.id == folderId } as? HomeItem.Folder ?: return

        openFolderId = folderId
        openFolderTitle = folder.title
        folderTitle.setText(folder.title)
        folderMembers.layoutManager = GridLayoutManager(this, currentColumns())
        folderMembers.adapter = FolderMemberAdapter(
            iconLoader = iconLoader,
            scope = lifecycleScope,
            iconSizePx = gridIconPx,
            onLaunch = { key -> launchApp(key); closeFolderOverlay() },
            onStartDrag = { view, key -> startFolderMemberDrag(view, key) },
        ).also { it.submit(folder.members) }
        folderOverlay.isVisible = true
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
        folderOverlay.isVisible = false
    }

    /** Closes the folder overlay (tap-outside / launch), applying any title edit. */
    private fun closeFolderOverlay() {
        if (!folderOverlay.isVisible) return
        applyFolderTitleEdit()
        openFolderId = null
        folderOverlay.isVisible = false
    }

    private fun applyFolderTitleEdit() {
        val folderId = openFolderId ?: return
        val newTitle = folderTitle.text.toString()
        if (newTitle != openFolderTitle) viewModel.renameFolder(folderId, newTitle)
    }

    // ---- long-press context menu ----

    private class ContextMenuItem(val label: String, val action: () -> Unit)

    private fun showContextMenu(payload: DragPayload, source: View) {
        val items = buildContextMenuItems(payload)
        if (items.isEmpty()) return
        contextMenuCard.removeAllViews()
        for (item in items) {
            val row = layoutInflater.inflate(R.layout.item_context_menu, contextMenuCard, false) as TextView
            row.text = item.label
            row.setOnClickListener { item.action(); dismissContextMenu() }
            contextMenuCard.addView(row)
        }
        contextMenuOverlay.isVisible = true
        // Position the card near the pressed icon once it has measured.
        contextMenuCard.doOnLayout {
            val icon = IntArray(2).also(source::getLocationInWindow)
            val root = IntArray(2).also(homeRoot::getLocationInWindow)
            val ix = icon[0] - root[0]
            val iy = icon[1] - root[1]
            val margin = (12 * resources.displayMetrics.density).toInt()
            val cw = contextMenuCard.width
            val ch = contextMenuCard.height
            val x = (ix + source.width / 2 - cw / 2).coerceIn(margin, homeRoot.width - cw - margin)
            val y = if (iy - ch - margin >= margin) iy - ch - margin else iy + source.height + margin
            contextMenuCard.translationX = x.toFloat()
            contextMenuCard.translationY = y.toFloat()
        }
    }

    private fun buildContextMenuItems(payload: DragPayload): List<ContextMenuItem> = when (payload) {
        is DragPayload.Existing -> {
            val item = viewModel.layout.value?.allHomeItems()?.firstOrNull { it.id == payload.id }
            val pkg = (item as? HomeItem.App)?.key?.packageName
            buildList {
                if (pkg != null) add(ContextMenuItem(getString(R.string.menu_app_info)) { openAppInfo(pkg) })
                add(ContextMenuItem(getString(R.string.menu_remove_from_home)) { viewModel.remove(payload.id) })
                if (pkg != null && !isSystemApp(pkg)) {
                    add(ContextMenuItem(getString(R.string.menu_uninstall)) { uninstallApp(pkg) })
                }
            }
        }
        is DragPayload.NewApp -> buildList {
            val pkg = payload.key.packageName
            add(ContextMenuItem(getString(R.string.menu_app_info)) { openAppInfo(pkg) })
            if (!isSystemApp(pkg)) add(ContextMenuItem(getString(R.string.menu_uninstall)) { uninstallApp(pkg) })
        }
        is DragPayload.FolderMember -> emptyList() // folder members extract by drag only
    }

    private fun dismissContextMenu() {
        if (!contextMenuOverlay.isVisible) return
        contextMenuOverlay.isVisible = false
        contextMenuCard.removeAllViews()
    }

    // Intent construction mirrors Kolibri's app-info action (Uri.fromParts +
    // NEW_TASK) for consistency across the family.
    private fun openAppInfo(pkg: String) = startActivitySafe(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", pkg, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        },
    )

    private fun uninstallApp(pkg: String) =
        startActivitySafe(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)))

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
        runCatching { startActivity(intent) }
            .onFailure { if (it !is ActivityNotFoundException) throw it }
    }

    // ---- home-info tap targets (mirror Kolibri's intents) ----

    private fun openClockApp() = startActivitySafely(Intent(AlarmClock.ACTION_SHOW_ALARMS))

    private fun openCalendarApp() {
        val uri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .let { ContentUris.appendId(it, System.currentTimeMillis()); it.build() }
        startActivitySafely(Intent(Intent.ACTION_VIEW).setData(uri))
    }

    private fun openBatterySettings() =
        startActivitySafely(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))

    /**
     * Launch an optional system intent; a launcher must never crash on a tap, so a
     * missing handler (ActivityNotFoundException) or a denied one (SecurityException,
     * e.g. an OEM alarm activity guarding SHOW_ALARMS) just toasts. Anything else is
     * a programmer error and propagates.
     */
    private fun startActivitySafely(intent: Intent) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                if (it !is ActivityNotFoundException && it !is SecurityException) throw it
                Toast.makeText(this, R.string.home_info_no_app, Toast.LENGTH_SHORT).show()
            }
    }
}

/** Every top-level item across the grid and the dock. */
private fun HomeLayout.allHomeItems(): List<HomeItem> = items.map { it.item } + dock

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

/** Visible content height (below the status-bar inset) of the remove bar, in dp. */
private const val REMOVE_BAR_CONTENT_DP = 64f

/** Remove-bar background at rest — matches @id/remove_bar's XML background. */
private const val REMOVE_BAR_IDLE_COLOR = 0xCCB00020.toInt()

/** Remove-bar background while a drag hovers it (opaque, brighter red). */
private const val REMOVE_BAR_ACTIVE_COLOR = 0xFFD50000.toInt()
