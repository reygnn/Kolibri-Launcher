package com.github.reygnn.nyx_launcher.home

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.DragEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
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
import com.github.reygnn.nyx_launcher.home.drawer.AppDrawerAdapter
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.launcher.common.ui.gesture.GestureFrameLayout
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DropTarget
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
 * The launcher home: a [ViewPager2] of grid pages, a persistent dock, and a
 * drawer panel that overlays the home (swipe up from the dock, or long-press the
 * home). Tapping launches/opens; long-pressing drags. A drag started in the grid/
 * dock carries the item's id (→ move); one started in the drawer carries a
 * [DragPayload.NewApp] (→ place at the drop cell). Dropping on the remove bar
 * removes; dropping a drawer app there is ignored.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: HomeViewModel by viewModels()

    @Inject lateinit var iconLoader: IconLoader
    @Inject lateinit var folderRenderer: FolderIconRenderer

    private lateinit var homeRoot: GestureFrameLayout
    private lateinit var pager: ViewPager2
    private lateinit var dock: RecyclerView
    private lateinit var drawerContainer: GestureFrameLayout
    private lateinit var drawerPanel: RecyclerView
    private lateinit var removeBar: TextView
    private var pagerAdapter: HomePagerAdapter? = null
    private lateinit var dockAdapter: DockAdapter
    private lateinit var drawerAdapter: AppDrawerAdapter

    private var gridIconPx = 0
    private var dockSize = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        homeRoot = findViewById(R.id.home_root)
        pager = findViewById(R.id.home_pager)
        dock = findViewById(R.id.dock)
        drawerContainer = findViewById(R.id.drawer_container)
        drawerPanel = findViewById(R.id.drawer_panel)
        removeBar = findViewById(R.id.remove_bar)
        gridIconPx = (48 * resources.displayMetrics.density).toInt()

        setupDock()
        setupDrawerPanel()
        setupRemoveBar()
        setupGestures()
        onBackPressedDispatcher.addCallback(this) { if (drawerContainer.isVisible) hideDrawer() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.layout.collect(::renderLayout) }
                launch { viewModel.drawerApps.collect(drawerAdapter::submit) }
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
        dock.setOnDragListener { _, event -> handleDockDrag(event) }
        // No setOnLongClickListener here: a long-click listener on a RecyclerView
        // never fires (its onTouchEvent handles scrolling and never triggers the
        // View long-press path), and it would mark the dock long-clickable, which
        // makes the shared core's hit-test suppress homeRoot.onLongPress over the
        // dock. Empty-dock long-press → Settings is handled by homeRoot.onLongPress;
        // dock icons keep their own long-press (drag) via DockAdapter.
    }

    private fun setupDrawerPanel() {
        drawerAdapter = AppDrawerAdapter(
            iconLoader = iconLoader,
            scope = lifecycleScope,
            iconSizePx = gridIconPx,
            onClick = { app -> launchApp(app.key); hideDrawer() },
            onAddToHome = { }, // panel uses drag, not add-to-first-free-cell
            onItemLongPress = { view, app ->
                startDrag(view, DragPayload.NewApp(app.key))
                hideDrawer()
            },
            itemLayout = R.layout.item_app_grid,
        )
        drawerPanel.layoutManager = GridLayoutManager(this, drawerColumns())
        drawerPanel.adapter = drawerAdapter
    }

    private fun setupRemoveBar() {
        findViewById<View>(R.id.home_root).setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> { removeBar.isVisible = true; true }
                DragEvent.ACTION_DRAG_ENDED -> { removeBar.isVisible = false; true }
                else -> true
            }
        }
        removeBar.setOnDragListener { _, event ->
            if (event.action == DragEvent.ACTION_DROP) {
                (event.localState as? DragPayload.Existing)?.let { viewModel.remove(it.id) }
                true
            } else {
                true
            }
        }
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

        // A decisive swipe-down anywhere on the drawer dismisses it, surviving
        // an in-progress list scroll (ACTION_CANCEL is dispatched to the
        // RecyclerView on trigger). No top exclusion band on the drawer — the
        // downward swipe is intentional at any y.
        drawerContainer.topExclusionPx = 0f
        drawerContainer.onSwipeDown = { hideDrawer() }
    }

    // ---- rendering ----

    private fun renderLayout(layout: HomeLayout?) {
        layout ?: return
        dockSize = layout.dock.size
        if (pagerAdapter == null) {
            pagerAdapter = HomePagerAdapter(
                iconLoader = iconLoader,
                folderRenderer = folderRenderer,
                scope = lifecycleScope,
                iconSizePx = gridIconPx,
                columns = layout.grid.columns,
                onLaunch = ::launchApp,
                onOpenFolder = ::openFolder,
                onStartDrag = { v, id -> startDrag(v, DragPayload.Existing(id)) },
                onDropOnPage = ::dropOnPage,
            ).also { pager.adapter = it }
        }
        val currentPage = pager.currentItem
        pagerAdapter?.submit((0 until layout.pages).map(layout::pageCells))
        if (currentPage < layout.pages) pager.setCurrentItem(currentPage, false)
        dockAdapter.submit(layout.dockCells())
    }

    // ---- drawer panel ----

    private fun showDrawer() {
        if (drawerContainer.isVisible) return
        drawerContainer.alpha = 0f
        drawerContainer.isVisible = true
        drawerContainer.animate().alpha(1f).setDuration(160).start()
    }

    private fun hideDrawer() {
        if (!drawerContainer.isVisible) return
        drawerContainer.animate().alpha(0f).setDuration(140).withEndAction {
            drawerContainer.isVisible = false
        }.start()
    }

    // ---- drag ----

    private fun startDrag(view: View, payload: DragPayload) {
        view.startDragAndDrop(null, View.DragShadowBuilder(view), payload, 0)
    }

    private fun dropOnPage(page: Int, cellIndex: Int, payload: DragPayload) {
        val cols = currentColumns()
        val target = DropTarget.Cell(CellPos(page, cellIndex % cols, cellIndex / cols))
        applyDrop(payload, target)
    }

    private fun handleDockDrag(event: DragEvent): Boolean = when (event.action) {
        DragEvent.ACTION_DROP -> {
            val payload = event.localState as? DragPayload
            val child = dock.findChildViewUnder(event.x, event.y)
            val slot = child?.let(dock::getChildAdapterPosition)?.takeIf { it != RecyclerView.NO_POSITION } ?: dockSize
            if (payload != null) applyDrop(payload, DropTarget.DockSlot(slot))
            true
        }
        else -> true
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

    private fun drawerColumns(): Int {
        val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (dp / 90f).toInt().coerceIn(3, 6)
    }

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
