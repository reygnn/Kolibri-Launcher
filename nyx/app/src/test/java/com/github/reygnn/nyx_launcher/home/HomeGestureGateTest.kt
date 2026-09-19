package com.github.reygnn.nyx_launcher.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure JVM tests for [homeGesturesAllowed]. */
class HomeGestureGateTest {

    @Test fun allowed_only_when_no_modal_surface_is_up() {
        assertThat(homeGesturesAllowed(editMode = false, folderVisible = false, contextMenuVisible = false)).isTrue()
    }

    @Test fun suppressed_in_wallpaper_edit_mode() {
        assertThat(homeGesturesAllowed(editMode = true, folderVisible = false, contextMenuVisible = false)).isFalse()
    }

    @Test fun suppressed_while_folder_overlay_is_visible() {
        assertThat(homeGesturesAllowed(editMode = false, folderVisible = true, contextMenuVisible = false)).isFalse()
    }

    @Test fun suppressed_while_context_menu_is_visible() {
        assertThat(homeGesturesAllowed(editMode = false, folderVisible = false, contextMenuVisible = true)).isFalse()
    }
}
