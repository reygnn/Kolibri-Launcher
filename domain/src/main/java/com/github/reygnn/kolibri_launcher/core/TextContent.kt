package com.github.reygnn.kolibri_launcher.core

/**
 * True when the string carries no visible, standalone character: it is empty,
 * pure whitespace, or made up only of code points that cannot render on their
 * own — combining marks with no preceding base letter, zero-width joiners, and
 * other format / control code points. Such a value would show as a blank row,
 * so callers treat it like an empty name (clear the entry) instead of persisting
 * an invisible label.
 *
 * A single base character keeps the string non-blank, so accented text
 * (`"é"` = "e" + combining acute) and emoji-only names (`"🐛"`,
 * an OTHER_SYMBOL) are NOT effectively blank — they still render. Iterates by
 * Unicode code point so astral characters (emoji, surrogate pairs) are
 * classified correctly rather than per UTF-16 unit.
 *
 * This is the stronger sibling of [CharSequence.isBlank]: every blank string is
 * also effectively blank, but a combining-mark-only string is effectively blank
 * while `isBlank()` still reports `false` (a combining mark is not whitespace).
 * That gap is exactly the "visually empty custom name" a plain `isBlank()` guard
 * lets slip through.
 */
fun String.isEffectivelyBlank(): Boolean =
    codePoints().noneMatch { codePoint -> codePoint.isVisibleContent() }

/**
 * True when the code point can render as visible content on its own — i.e. it is
 * neither whitespace nor a combining mark / format / control / lone-surrogate
 * code point. Emoji and other symbols count as visible.
 */
private fun Int.isVisibleContent(): Boolean {
    if (Character.isWhitespace(this) || Character.isSpaceChar(this)) return false
    return when (Character.getType(this)) {
        Character.NON_SPACING_MARK.toInt(),        // Mn — combining accents / diacritics
        Character.ENCLOSING_MARK.toInt(),          // Me — enclosing combining marks
        Character.COMBINING_SPACING_MARK.toInt(),  // Mc — spacing combining marks
        Character.FORMAT.toInt(),                  // Cf — ZWJ / ZWNJ / ZWSP, bidi controls
        Character.CONTROL.toInt(),                 // Cc — C0 / C1 control characters
        Character.SURROGATE.toInt(),               // Cs — lone surrogate
        -> false

        else -> true
    }
}
