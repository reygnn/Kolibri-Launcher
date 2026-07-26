/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher.ui.main

import androidx.core.util.PatternsCompat

/**
 * The action a home-screen double-tap should take for a given clipboard text.
 * [MainActivity] maps this to an Intent and launches it via startActivitySafely.
 */
sealed interface ClipboardAction {
    /**
     * The trimmed, length-capped text this action was derived from — what the
     * confirmation dialog previews and what "Share" forwards. Always use this
     * rather than the raw clipboard string: the raw one can be megabytes and
     * would blow the Binder limit as an Intent extra.
     */
    val displayText: String

    /**
     * Open a full URL (scheme guaranteed present) in a browser.
     *
     * [url] is what gets launched; [source] is what the user actually copied.
     * The two differ for a bare domain, where `https://` is synthesized — and
     * the dialog must preview (and "Share" must forward) the copied text, not
     * our guess, so the user can recognise a misclassification like
     * `install.sh`. Defaults to [url] for input that already carried a scheme.
     */
    data class OpenUrl(val url: String, val source: String = url) : ClipboardAction {
        override val displayText: String get() = source
    }

    /** Compose an email to this address. */
    data class Email(val address: String) : ClipboardAction {
        override val displayText: String get() = address
    }

    /**
     * Open the dialer PRE-FILLED with this number (ACTION_DIAL, never
     * ACTION_CALL — nothing is ever dialled automatically, and no CALL_PHONE
     * permission is needed).
     */
    data class Dial(val number: String) : ClipboardAction {
        override val displayText: String get() = number
    }

    /** Fall back to a web search for the raw text. */
    data class WebSearch(val query: String) : ClipboardAction {
        override val displayText: String get() = query
    }
}

/**
 * PURE LOGIC — classifies clipboard text into a [ClipboardAction]. No Android
 * runtime dependency: the one library type used, [PatternsCompat], is plain
 * `java.util.regex`, so this stays fully JVM-testable without Robolectric.
 *
 * Design choices, each pinned by a test:
 *  - **Phone is strict, and errs towards search.** A leading `+` is the only
 *    unambiguous phone marker. Without it, dotted strings never dial (dates
 *    `25.07.2026`, IPs `192.168.1.1` and European-style amounts `12.345.678`
 *    all look phone-shaped otherwise), separator-formatted numbers need ≥ 9
 *    digits, and a bare digit run needs ≥ 10 — so 2FA codes, OTPs, PINs and
 *    short amounts fall through to web search instead of the dialer.
 *  - **URL is validated end to end.** A scheme-prefixed string must be a whole
 *    URL with a non-empty authority and no whitespace, so `https://example.com
 *    is a great site` (share text) and a bare `https://` are NOT opened.
 *    Scheme-less input must be a real host per
 *    [PatternsCompat.AUTOLINK_WEB_URL] (androidx's maintained IANA TLD list —
 *    `DOMAIN_NAME` is the *relaxed* variant and accepts `report.txt`);
 *    `https://` is then prepended,
 *    otherwise ACTION_VIEW finds no browser. See [asUrl] for why that beats
 *    both a hand-kept TLD allowlist and a file-extension denylist.
 *  - **Email before URL**, so `user@host.tld` isn't mis-read as a domain.
 *  - **Everything else → web search.** Note this branch forwards the clipboard
 *    text to the user's search provider verbatim; it is safe with respect to
 *    the *dialer*, not with respect to what leaves the device.
 *  - **Input is length-capped** before classification so an oversized clip
 *    can't blow the Binder transaction limit as an Intent extra.
 */
object ClipboardActionResolver {

    /**
     * Upper bound on the text we classify and forward. Comfortably above any
     * real URL (browsers themselves cap far below this) while keeping a
     * worst-case search query ~16 KB — orders of magnitude under the ~1 MB
     * Binder transaction budget.
     */
    private const val MAX_TEXT_LENGTH = 8192

    /**
     * RFC 1035 caps a fully qualified domain name at 255 octets, i.e. 253
     * characters of presentation form. Anything longer cannot be a host, so
     * [asUrl] rejects it before it reaches the TLD matcher.
     */
    private const val MAX_HOST_LENGTH = 253

    fun resolve(rawText: String?): ClipboardAction? {
        val raw = rawText ?: return null

        // Locate the trim boundaries by index rather than cap-then-trim: capping
        // first let leading whitespace eat the one char of headroom the
        // over-length check depends on, so a 9000-char URL behind a newline
        // measured as "exactly at the limit" and got opened truncated. Scanning
        // indices touches only the whitespace runs and allocates nothing, so a
        // multi-megabyte clip still never gets copied whole.
        var start = 0
        var end = raw.length
        while (start < end && raw[start].isWhitespace()) start++
        while (end > start && raw[end - 1].isWhitespace()) end--
        if (start == end) return null

        // Over-length input is ONLY ever a search. Truncating first and then
        // classifying would happily open a URL cut off mid-query — a silently
        // mangled link is worse than a search for it.
        if (end - start > MAX_TEXT_LENGTH) {
            return ClipboardAction.WebSearch(raw.substring(start, start + MAX_TEXT_LENGTH))
        }
        val text = raw.substring(start, end)

        if (EMAIL_REGEX.matches(text)) return ClipboardAction.Email(text)
        asUrl(text)?.let { return ClipboardAction.OpenUrl(url = it, source = text) }
        if (isDialablePhone(text)) return ClipboardAction.Dial(text)
        return ClipboardAction.WebSearch(text)
    }

    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    // A COMPLETE http(s) URL: scheme, non-empty authority, no whitespace
    // anywhere. Anchored end-to-end so trailing prose disqualifies the string.
    // NOTE the authority is `[^\s/?#]` + `\S*` and NOT `[^\s/?#]+` + `\S*`:
    // the character class is a subset of `\S`, so the `+` form makes every
    // split point of the two quantifiers a distinct backtrack state — O(n²)
    // on failure (measured ~116 ms at the 8192-char cap). Both forms accept
    // exactly the same language; this one is linear.
    private val SCHEME_URL_REGEX = Regex("^https?://[^\\s/?#]\\S*$", RegexOption.IGNORE_CASE)

    // Only phone-shaped characters (no letters); structure/length gate the rest.
    private val PHONE_CHARS_REGEX = Regex("^\\+?[0-9 ().\\-]+$")

    /**
     * Returns the ready-to-launch URL, or null if [text] isn't one.
     *
     * Host validity is delegated to [PatternsCompat.AUTOLINK_WEB_URL], the
     * strict variant that only matches a real IANA TLD (verified: it rejects
     * `report.txt`, `index.html`, `report.odt`, `song.flac`, `data.db` and
     * accepts `bit.ly/3xYzQ`, `shop.swiss`, `docs.zip`, `nic.md` — note
     * `DOMAIN_NAME` is the *relaxed* variant and matches `report.txt`).
     * That beats both a hand-kept TLD allowlist (ages against ~1500 live TLDs,
     * silently turning real links into searches) and a hand-kept file-extension
     * denylist (which had `.zip`/`.mov`/`.md` blocked although all three are
     * live TLDs, while letting `report.odt` through as a hostname).
     * PatternsCompat is plain `java.util.regex`, so this stays JVM-testable.
     *
     * The field carries `@RestrictTo(LIBRARY_GROUP_PREFIX)`, hence the
     * `@Suppress` below. It is knowingly accepted: the strict IANA list is
     * reachable through no other member — `DOMAIN_NAME` and `WEB_URL` are
     * public but both build on the relaxed `TLD`. Should a future androidx
     * bump drop the field, the result is a compile error rather than a silent
     * behaviour change, and the version is pinned in `libs.versions.toml`.
     *
     * A numeric final label is rejected on purpose, so a bare IP address
     * (`192.168.1.1`) stays a search rather than becoming a URL — note the
     * pattern's own `STRICT_DOMAIN_NAME` would otherwise accept one, so that
     * guard is load-bearing and not merely an early-out.
     *
     * Only the host is handed to the matcher, capped at [MAX_HOST_LENGTH] and
     * lowercased. Both matter:
     *  - **Capped**, because the pattern's ~1500-TLD alternation recurses per
     *    dot-separated label. Matching the full 8192-char text let
     *    `"a.".repeat(4090) + "zzzz"` cost ~42 ms and throw StackOverflowError
     *    on a 1 MB stack — which is what coroutine workers get. A host can
     *    never legally be longer than 253 chars, so nothing valid is lost.
     *  - **Lowercased**, because `IANA_TOP_LEVEL_DOMAINS` is a lowercase-only
     *    alternation and the pattern compiles with no flags, so `GOOGLE.COM`
     *    would fail and fall through to a web search. The scheme branch has
     *    handled autocapitalised input via IGNORE_CASE all along; this makes
     *    the two symmetric.
     * The path is no longer part of the match, which is fine: whitespace is
     * already excluded above, and the string is launched as-is either way.
     *
     * Residual ambiguity is unavoidable: `install.sh` and `main.py` are valid
     * domains AND common filenames, so they classify as URLs. The
     * confirmation dialog is what makes that harmless — the user sees the
     * proposed action before anything is launched.
     */
    @Suppress("RestrictedApi") // see the KDoc above for why AUTOLINK_WEB_URL is worth it
    private fun asUrl(text: String): String? {
        if (SCHEME_URL_REGEX.matches(text)) return text
        if (text.any { it.isWhitespace() }) return null

        val host = text.substringBefore('/').substringBefore('?')
            .substringBefore('#').substringBefore(':')
        if (host.length > MAX_HOST_LENGTH) return null
        val finalLabel = host.substringAfterLast('.', "")
        if (finalLabel.length < 2 || !finalLabel.all { it.isLetter() }) return null

        val matches = PatternsCompat.AUTOLINK_WEB_URL.matcher(host.lowercase()).matches()
        return if (matches) "https://$text" else null
    }

    private fun isDialablePhone(text: String): Boolean {
        if (!PHONE_CHARS_REGEX.matches(text)) return false
        val digits = text.count { it.isDigit() }
        if (digits !in 7..15) return false

        // International format is unambiguous.
        if (text.startsWith("+")) return true

        // Dots without a leading '+' mean date / IP / amount far more often
        // than phone number, so they disqualify rather than qualify.
        if ('.' in text) return false

        // Otherwise a formatted number needs national length (>= 9 digits) and
        // a bare digit run needs >= 10, keeping codes and short amounts out.
        val hasSeparator = text.any { it == ' ' || it == '-' || it == '(' || it == ')' }
        return if (hasSeparator) digits >= 9 else digits >= 10
    }
}
