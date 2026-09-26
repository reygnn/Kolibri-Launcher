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
}
