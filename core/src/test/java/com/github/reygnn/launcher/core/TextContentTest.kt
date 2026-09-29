package com.github.reygnn.launcher.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM pins for [isEffectivelyBlank] — the "visually empty" guard the
 * custom-name write paths (and [com.github.reygnn.kolibri_launcher.ui.customnames.RenameDecision])
 * use to treat combining-only / format-only names like an empty name instead of
 * persisting an invisible label. The point of the helper over `isBlank()` is the
 * combining-mark case, so that is pinned on both sides (blank AND not-blank).
 *
 * Strings are built from explicit code points ([cp]) so the (otherwise invisible)
 * characters stay legible and unambiguous in source — a decomposed vs. precomposed
 * accented "e" would look identical as literals.
 */
class TextContentTest {

    /** Builds a String from Unicode code points, keeping the source ASCII. */
    private fun cp(vararg codePoints: Int): String =
        buildString { codePoints.forEach { appendCodePoint(it) } }

    @Test
    fun `empty string is effectively blank`() {
        assertThat("".isEffectivelyBlank()).isTrue()
    }

    @Test
    fun `whitespace-only strings are effectively blank`() {
        assertThat("   ".isEffectivelyBlank()).isTrue()
        assertThat("\t\n ".isEffectivelyBlank()).isTrue()
    }

    @Test
    fun `a lone combining mark is effectively blank`() {
        // U+0301 COMBINING ACUTE / U+0300 GRAVE with no base letter: an empty row.
        assertThat(cp(0x0301).isEffectivelyBlank()).isTrue()
        assertThat(cp(0x0301, 0x0300).isEffectivelyBlank()).isTrue()
    }

    @Test
    fun `zero-width and format characters are effectively blank`() {
        assertThat(cp(0x200B).isEffectivelyBlank()).isTrue() // ZERO WIDTH SPACE
        assertThat(cp(0x200D).isEffectivelyBlank()).isTrue() // ZERO WIDTH JOINER
        assertThat(cp(0x202E).isEffectivelyBlank()).isTrue() // RIGHT-TO-LEFT OVERRIDE
        assertThat(cp(0xFEFF).isEffectivelyBlank()).isTrue() // ZERO WIDTH NO-BREAK SPACE (BOM)
    }

    @Test
    fun `plain text is not effectively blank`() {
        assertThat("Camera".isEffectivelyBlank()).isFalse()
        assertThat("a".isEffectivelyBlank()).isFalse()
        assertThat("  x  ".isEffectivelyBlank()).isFalse() // a base char among whitespace
    }

    @Test
    fun `a base letter with a combining mark is not effectively blank`() {
        assertThat(cp(0x0065, 0x0301).isEffectivelyBlank()).isFalse() // "e" + combining acute
        assertThat(cp(0x00E9).isEffectivelyBlank()).isFalse()         // precomposed "é"
    }

    @Test
    fun `emoji-only names are not effectively blank`() {
        // The README rename example U+1F41B (bug) — an OTHER_SYMBOL that renders
        // fine, so it stays a valid custom name (it just sorts to the end).
        assertThat(cp(0x1F41B).isEffectivelyBlank()).isFalse()
        assertThat(cp(0x1F41B, 0x1F41B).isEffectivelyBlank()).isFalse()
    }
}
