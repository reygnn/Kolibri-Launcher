package com.github.reygnn.kolibri_launcher.ui.home

/**
 * REVOLUTIONARY REACTIVE APPROACH - NO CAPACITY MEASUREMENTS NEEDED!
 *
 * The Problem We Solved:
 * - Old approach: Try to calculate "how many buttons fit" (unreliable, timing issues)
 * - Race conditions between rendering and measurement
 * - Complex dummy button logic prone to crashes
 *
 * Our Solution: Let Android Decide!
 *
 * Flow Architecture:
 * 1. User adds favorites / rotates device
 *    → renderFavorites() / onConfigurationChanged()
 *
 * 2. After rendering completes (OnGlobalLayoutListener ensures timing)
 *    → checkScrollStateAfterNextLayout() is called
 *
 * 3. System determines scroll capability
 *    → checkAndEmitScrollState() asks: canScrollVertically ?
 *    → TRUE = content overflows, scrolling needed
 *    → FALSE = content fits, no scrolling needed
 *
 * 4. Emit new state to Flow
 *    → _needsSplit.value = canScroll
 *    → Flow only emits if value actually changed (prevents redundant updates)
 *
 * 5. Observer reacts automatically
 *    → needsSplit.collect { split -> ... }
 *    → adjustScrollViewWidth(split) gets called
 *
 * 6. UI adapts based on split mode:
 *    - split=false: ScrollView takes 100% width, fully transparent to touches
 *                   → All gestures work across entire screen
 *    - split=true:  ScrollView takes 55% (portrait) or 30% (landscape)
 *                   → Border appears, scrolling enabled
 *                   → Gesture zone takes remaining space for swipe gestures
 *
 * Why This Works:
 * - System (canScrollVertically) is the single source of truth
 * - Pure reactive: State change → Flow emission → UI reaction
 * - No manual calculations = no race conditions
 * - Self-correcting: Every content change re-evaluates automatically
 *
 * IT SIMPLY WORKS!
 */

data class LayoutConfig(
    val scale: Float,
    val paddingFactor: Float,
    val isBold: Boolean,
    val marginScale: Float
)