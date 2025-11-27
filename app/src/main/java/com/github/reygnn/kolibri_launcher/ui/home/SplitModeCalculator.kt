package com.github.reygnn.kolibri_launcher.ui.home

/**
 * SAFETY-CRITICAL COMPONENT - Split-Mode Decision Logic
 *
 * This class determines whether the Home Screen should operate in Split-Mode (scrollable)
 * or Full-Mode (static/locked).
 *
 * FAIL-SAFE PRINCIPLE:
 * - When in doubt: Enable Split-Mode (User must always be able to reach their apps).
 * - Invalid/Negative inputs: Handled defensively to prevent crashes or trapped states.
 * - Zero Side Effects: This is a pure logic class (no Android dependencies).
 *
 * DESIGN GUARANTEE:
 * This logic must NEVER trap the user or make apps inaccessible.
 * Tested against pathological edge cases (Integer overflows, negative dimensions).
 *
 * @see HomeFragment.checkAndEmitScrollState
 */
class SplitModeCalculator {

    /**
     * Determines if the Split-Mode should be activated based on the provided parameters.
     *
     * This method acts as the "Brain" of the scrolling behavior. It prioritizes
     * user accessibility over strict mathematical thresholds.
     *
     * @param threshold
     * Defines the logic mode:
     * - 0 (or negative): **Auto-Mode**. Relies purely on Android's `canScroll` flags.
     * - > 0: **Power-User Mode**. Uses exact pixel calculations to delay splitting.
     * @param canScrollDown
     * Flag from View system indicating if scrolling down is technically possible.
     * @param canScrollUp
     * Flag from View system indicating if scrolling up is technically possible.
     * @param contentHeight
     * Total height of the content (RecyclerView) in pixels. Used only in Power-User Mode.
     * @param containerHeight
     * Visible height of the parent container in pixels. Used only in Power-User Mode.
     *
     * @return `true` if Split-Mode (scrolling) should be enabled, `false` for Full-Mode (static).
     */
    fun shouldSplit(
        threshold: Int,
        canScrollDown: Boolean,
        canScrollUp: Boolean,
        contentHeight: Int = 0,
        containerHeight: Int = 0
    ): Boolean {
        // DEFENSIVE CHECK: Auto-Mode Fallback
        // If the user hasn't set a threshold (0), or if the config is invalid (negative),
        // we strictly trust the Android View system.
        if (threshold <= 0) {
            return canScrollDown || canScrollUp
        }

        // POWER-USER LOGIC: Pixel-Perfect Calculation
        // Calculate the exact amount of overflow pixels.
        // We do NOT trust 'canScroll' flags here because they might trigger too early
        // for users who want a strict threshold (e.g., "don't scroll unless 50px hidden").
        val scrollablePixels = calculateScrollablePixels(contentHeight, containerHeight)

        // DECISION:
        // Only split if the overflow strictly exceeds the user's defined threshold.
        return scrollablePixels > threshold
    }

    /**
     * Helper to safely calculate the scrollable overflow in pixels.
     *
     * Uses defensive math to handle invalid (negative) view dimensions
     * without throwing exceptions or returning garbage data.
     *
     * @param contentHeight Total height of content.
     * @param containerHeight Visible height of container.
     * @return The positive difference (overflow) or 0 if content fits.
     */
    fun calculateScrollablePixels(contentHeight: Int, containerHeight: Int): Int {
        // INPUT SANITIZATION:
        // View dimensions can be -1 or negative during layout passes or initialization.
        // We coerce them to at least 0 to prevent "double negative" additions (e.g., -50 - -100).
        val safeContent = contentHeight.coerceAtLeast(0)
        val safeContainer = containerHeight.coerceAtLeast(0)

        // CALCULATION:
        // 1. Subtract container from content to get raw overflow.
        // 2. Coerce to at least 0 (we don't care about "underflow" / empty space).
        return (safeContent - safeContainer).coerceAtLeast(0)
    }
}