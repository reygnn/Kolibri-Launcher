package com.github.reygnn.nyx_launcher.home

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.TextView
import androidx.activity.addCallback
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
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.home.drag.DragLayer
import com.github.reygnn.nyx_launcher.home.drag.DropZone
import com.github.reygnn.nyx_launcher.home.drawer.AppDrawerFragment
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.firstFreeCell
import com.github.reygnn.nyx_launcher.settings.SettingsActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
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

    private lateinit var homeRoot: DragLayer
    private lateinit var pager: ViewPager2
    private lateinit var dock: RecyclerView
    private lateinit var drawerContainer: View
    private lateinit var removeBar: TextView
    private var pagerAdapter: HomePagerAdapter? = null
    private var currentGrid: GridSpec? = null
    private lateinit var dockAdapter: DockAdapter

    private var gridIconPx = 0
    private var dockSize = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        homeRoot = findViewById(R.id.home_root)
        pager = findViewById(R.id.home_pager)
        dock = findViewById(R.id.dock)
        drawerContainer = findViewById(R.id.drawer_container)
        removeBar = findViewById(R.id.remove_bar)
        gridIconPx = (48 * resources.displayMetrics.density).toInt()

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
        onBackPressedDispatcher.addCallback(this) { if (drawerContainer.isVisible) hideDrawer() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.layout.collect(::renderLayout) }
                launch { viewModel.monochromeIcons.collect { renderLayout(viewModel.layout.value) } }
            }
        }
    }

    // ---- setup ----

    private fun setupDock() {
        dockAdapter = DockAdapter(iconLoader, folderRenderer, lifecycleScope, gridIconPx, ::launchApp, ::openFolder) { v, id ->
            startDrag(v, DragPayload.Existing(id))
        }
        dock.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        dock.adapter = dockAdapter
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

        // 2) Dock zone — slot from the x under the finger (or append at the end).
        controller.addDropZone(object : DropZone {
            override fun hitRect(out: Rect) = rectInDragLayer(dock, out)
            override fun accepts(payload: DragPayload) = true
            override fun onDrop(payload: DragPayload, x: Int, y: Int) {
                val bounds = Rect().also { rectInDragLayer(dock, it) }
                val child = dock.findChildViewUnder((x - bounds.left).toFloat(), (y - bounds.top).toFloat())
                val slot = child?.let(dock::getChildAdapterPosition)
                    ?.takeIf { it != RecyclerView.NO_POSITION } ?: dockSize
                applyDrop(payload, DropTarget.DockSlot(slot))
            }
        })

        // 3) Grid zone — the rest of the surface; cell resolved geometrically.
        controller.addDropZone(object : DropZone {
            override fun hitRect(out: Rect) = rectInDragLayer(pager, out)
            override fun accepts(payload: DragPayload) = true
            override fun onDrop(payload: DragPayload, x: Int, y: Int) {
                resolveGridCell(x.toFloat(), y.toFloat())?.let { applyDrop(payload, it) }
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
        homeRoot.onSwipeUp = { showDrawer() }

        // Long-press on empty home space opens Settings. The shared core's
        // hit-test suppresses this over app icons and dock icons (they keep
        // their own long-press → drag), so it only fires on the wallpaper /
        // empty area.
        homeRoot.onLongPress = { openSettings() }

        // The drawer's own swipe-down dismiss lives in AppDrawerFragment (its
        // root is a GestureFrameLayout), so it isn't wired here.
    }

    // ---- rendering ----

    private fun renderLayout(layout: HomeLayout?) {
        layout ?: return
        dockSize = layout.dock.size
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
                onStartDrag = { v, id -> startDrag(v, DragPayload.Existing(id)) },
            ).also { pager.adapter = it }
        }
        val currentPage = pager.currentItem
        pagerAdapter?.submit((0 until layout.pages).map(layout::pageCells))
        if (currentPage < layout.pages) pager.setCurrentItem(currentPage, false)
        dockAdapter.submit(layout.dockCells())
    }

    // ---- drawer overlay (AppDrawerFragment.Host) ----

    private fun showDrawer() {
        if (drawerContainer.isVisible) return
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
        startDrag(view, DragPayload.NewApp(key))
        hideDrawer()
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
     * Maps a drop point (in [homeRoot] coordinates) to the grid cell under it on
     * the current page. Uses the same geometry the grid renders with — fixed cell
     * height ([homeCellHeightPx]) and the top dead space that bottom-anchors the
     * grid — rather than a hit-tested child view, so any spot on the home surface
     * resolves to a real [CellPos]; the pure move/place transition then decides
     * empty-vs-occupied. Returns null only if the pager isn't laid out yet.
     */
    private fun resolveGridCell(rootX: Float, rootY: Float): DropTarget.Cell? {
        val grid = viewModel.layout.value?.grid ?: return null
        val internal = pager.getChildAt(0) as? RecyclerView ?: return null
        val page = pager.currentItem
        val pageView = internal.layoutManager?.findViewByPosition(page) as? RecyclerView ?: return null

        val cellHpx = homeCellHeightPx(pageView.height, grid.rows, resources.displayMetrics.density)
        val cellW = pageView.width.toFloat() / grid.columns
        if (cellW <= 0f || cellHpx <= 0) return null
        // Dead space that bottom-anchors the grid (mirrors HomeGridAdapter's padding).
        val topPad = pageView.height - grid.rows * cellHpx

        val rootLoc = IntArray(2).also(homeRoot::getLocationOnScreen)
        val pageLoc = IntArray(2).also(pageView::getLocationOnScreen)
        val localX = rootX - (pageLoc[0] - rootLoc[0])
        val localY = rootY - (pageLoc[1] - rootLoc[1]) - topPad

        val col = (localX / cellW).toInt().coerceIn(0, grid.columns - 1)
        val row = (localY / cellHpx).toInt().coerceIn(0, grid.rows - 1)
        return DropTarget.Cell(CellPos(page, col, row))
    }

    private fun applyDrop(payload: DragPayload, target: DropTarget) = when (payload) {
        is DragPayload.Existing -> viewModel.move(payload.id, target)
        is DragPayload.NewApp -> viewModel.place(payload.key, target)
    }

    // ---- folder sheet ----

    private fun openFolder(folderId: ItemId) {
        val layout = viewModel.layout.value ?: return
        val folder = layout.allHomeItems().firstOrNull { it.id == folderId } as? HomeItem.Folder ?: return

        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.folder_sheet, null)
        val titleField = view.findViewById<EditText>(R.id.folder_title)
        titleField.setText(folder.title)
        dialog.setOnDismissListener {
            val newTitle = titleField.text.toString()
            if (newTitle != folder.title) viewModel.renameFolder(folderId, newTitle)
        }
        val members = view.findViewById<RecyclerView>(R.id.folder_members)
        members.layoutManager = GridLayoutManager(this, currentColumns())
        members.adapter = FolderMemberAdapter(
            iconLoader = iconLoader,
            scope = lifecycleScope,
            iconSizePx = gridIconPx,
            onLaunch = { key -> launchApp(key); dialog.dismiss() },
            onExtract = { key ->
                viewModel.extractFromFolder(folderId, key, DropTarget.Cell(layout.firstFreeCell()))
                dialog.dismiss()
            },
        ).also { it.submit(folder.members) }
        dialog.setContentView(view)
        dialog.show()
    }

    // ---- helpers ----

    private fun currentColumns(): Int = viewModel.layout.value?.grid?.columns ?: 1

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun launchApp(key: ComponentKey) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(key.packageName, key.className))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { if (it !is ActivityNotFoundException) throw it }
    }
}

/** Every top-level item across the grid and the dock. */
private fun HomeLayout.allHomeItems(): List<HomeItem> = items.map { it.item } + dock

/** Drawer slide-up/down duration, mirroring Kolibri's anim_duration_drawer_slide. */
private const val DRAWER_SLIDE_MS = 180L

/** Visible content height (below the status-bar inset) of the remove bar, in dp. */
private const val REMOVE_BAR_CONTENT_DP = 64f

/** Remove-bar background at rest — matches @id/remove_bar's XML background. */
private const val REMOVE_BAR_IDLE_COLOR = 0xCCB00020.toInt()

/** Remove-bar background while a drag hovers it (opaque, brighter red). */
private const val REMOVE_BAR_ACTIVE_COLOR = 0xFFD50000.toInt()
