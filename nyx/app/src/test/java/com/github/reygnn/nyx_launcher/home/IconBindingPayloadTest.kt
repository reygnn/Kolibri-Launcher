package com.github.reygnn.nyx_launcher.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure truth-table for the RecyclerView change-payload helpers in IconBinding. */
class IconBindingPayloadTest {

    @Test fun empty_payload_is_neither_dot_only_nor_icon_style() {
        assertThat(emptyList<Any>().isDotOnlyPayload()).isFalse()
        assertThat(emptyList<Any>().hasIconStylePayload()).isFalse()
    }

    @Test fun dot_only_payload() {
        val payload = listOf(NOTIFICATION_DOT_PAYLOAD)
        assertThat(payload.isDotOnlyPayload()).isTrue()
        assertThat(payload.hasIconStylePayload()).isFalse()
    }

    @Test fun icon_style_payload() {
        val payload = listOf(ICON_STYLE_PAYLOAD)
        assertThat(payload.hasIconStylePayload()).isTrue()
        assertThat(payload.isDotOnlyPayload()).isFalse()
    }

    @Test fun a_coalesced_dot_plus_icon_style_batch_counts_as_icon_style() {
        // The pager must treat this as a full icon re-decode (which also refreshes dots) so
        // the batch isn't dropped on the floor — the reason hasIconStylePayload uses `any`.
        val payload = listOf(NOTIFICATION_DOT_PAYLOAD, ICON_STYLE_PAYLOAD)
        assertThat(payload.hasIconStylePayload()).isTrue()
        assertThat(payload.isDotOnlyPayload()).isFalse() // not "dot only" any more
    }

    @Test fun multiple_dots_are_still_dot_only() {
        val payload = listOf(NOTIFICATION_DOT_PAYLOAD, NOTIFICATION_DOT_PAYLOAD)
        assertThat(payload.isDotOnlyPayload()).isTrue()
        assertThat(payload.hasIconStylePayload()).isFalse()
    }

    // --- iconRepaintPackages (targeted per-package repaint, F1) ---

    @Test fun icon_repaint_payload_names_its_package() {
        val payload = listOf(IconRepaintPayload("com.a"))
        assertThat(payload.iconRepaintPackages()).containsExactly("com.a")
        // It is neither a dot-only nor an icon-style batch, so those branches don't claim it.
        assertThat(payload.isDotOnlyPayload()).isFalse()
        assertThat(payload.hasIconStylePayload()).isFalse()
    }

    @Test fun multiple_icon_repaint_payloads_dedupe_to_their_packages() {
        val payload = listOf(
            IconRepaintPayload("com.a"),
            IconRepaintPayload("com.b"),
            IconRepaintPayload("com.a"),
        )
        assertThat(payload.iconRepaintPackages()).containsExactly("com.a", "com.b")
    }

    @Test fun a_coalesced_dot_plus_icon_repaint_keeps_both_signals() {
        // The pager repaints the matching tiles AND, because a dot payload rode along, refreshes
        // the whole-page dots — so the coalesced dot update isn't dropped. Pin both halves.
        val payload = listOf(NOTIFICATION_DOT_PAYLOAD, IconRepaintPayload("com.a"))
        assertThat(payload.iconRepaintPackages()).containsExactly("com.a")
        assertThat(payload.any { it === NOTIFICATION_DOT_PAYLOAD }).isTrue()
        assertThat(payload.isDotOnlyPayload()).isFalse() // not "dot only" any more
    }

    @Test fun no_icon_repaint_payload_yields_empty() {
        assertThat(listOf(NOTIFICATION_DOT_PAYLOAD).iconRepaintPackages()).isEmpty()
        assertThat(listOf(ICON_STYLE_PAYLOAD).iconRepaintPackages()).isEmpty()
        assertThat(emptyList<Any>().iconRepaintPackages()).isEmpty()
    }
}
