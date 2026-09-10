package com.github.reygnn.nyx_launcher.data.icon

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure JVM truth-table for the byte-bounded LRU policy (ICL-INV-2/-6/-7). */
class LruBudgetTest {

    private fun key(s: String) = CacheKey(s)

    @Test fun under_budget_evicts_nothing() {
        val lru = LruBudget(maxBytes = 100)
        assertThat(lru.touch(key("a"), 40)).isEmpty()
        assertThat(lru.touch(key("b"), 40)).isEmpty()
        assertThat(lru.totalBytes).isEqualTo(80)
        assertThat(lru.keys()).containsExactly(key("a"), key("b"))
    }

    @Test fun over_budget_evicts_lru_first() {
        val lru = LruBudget(maxBytes = 100)
        lru.touch(key("a"), 40)
        lru.touch(key("b"), 40)
        val evicted = lru.touch(key("c"), 40) // 120 > 100 → drop eldest (a)
        assertThat(evicted).containsExactly(key("a"))
        assertThat(lru.keys()).containsExactly(key("b"), key("c"))
        assertThat(lru.totalBytes).isEqualTo(80)
    }

    @Test fun re_access_moves_to_mru_and_protects_from_eviction() {
        val lru = LruBudget(maxBytes = 100)
        lru.touch(key("a"), 40)
        lru.touch(key("b"), 40)
        lru.touch(key("a"), 40) // re-access a ⇒ a is now MRU, b is eldest
        val evicted = lru.touch(key("c"), 40)
        assertThat(evicted).containsExactly(key("b")) // a survived
    }

    @Test fun single_oversized_item_is_retained() {
        val lru = LruBudget(maxBytes = 100)
        assertThat(lru.touch(key("big"), 200)).isEmpty() // size==1 guard
        assertThat(lru.keys()).containsExactly(key("big"))
    }

    @Test fun trim_ui_hidden_clears_all() {
        val lru = LruBudget(maxBytes = 100)
        lru.touch(key("a"), 40)
        lru.touch(key("b"), 40)
        val evicted = lru.trim(level = 20) // TRIM_UI_HIDDEN
        assertThat(evicted).containsExactly(key("a"), key("b"))
        assertThat(lru.totalBytes).isEqualTo(0)
    }

    @Test fun trim_running_low_halves() {
        val lru = LruBudget(maxBytes = 100)
        lru.touch(key("a"), 30)
        lru.touch(key("b"), 30)
        lru.touch(key("c"), 30) // 90 bytes
        val evicted = lru.trim(level = 10) // TRIM_RUNNING_LOW → down to <= 50
        assertThat(lru.totalBytes).isAtMost(50)
        assertThat(evicted).isNotEmpty()
    }

    @Test fun trim_low_level_does_nothing() {
        val lru = LruBudget(maxBytes = 100)
        lru.touch(key("a"), 40)
        assertThat(lru.trim(level = 5)).isEmpty() // below RUNNING_LOW
        assertThat(lru.totalBytes).isEqualTo(40)
    }

    @Test fun forget_removes_keys_and_adjusts_bytes() {
        val lru = LruBudget(maxBytes = 100)
        lru.touch(key("a"), 40)
        lru.touch(key("b"), 30)
        lru.forget(listOf(key("a")))
        assertThat(lru.keys()).containsExactly(key("b"))
        assertThat(lru.totalBytes).isEqualTo(30)
    }
}
