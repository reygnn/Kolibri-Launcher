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
 *  - **The URL we validate is the URL we launch.** Only `http`/`https` are
 *    ever opened, the authority is extracted the way a URI parser does it, and
 *    userinfo is refused — so `paypal.com:x@evil.tld/login` is a search, not a
 *    visit to evil.tld. Whitespace anywhere disqualifies the string, so
 *    `https://example.com is a great site` (share text) stays a search, as does
 *    a bare `https://`. Scheme-less input additionally needs a real IANA TLD
 *    per [PatternsCompat.AUTOLINK_WEB_URL] before `https://` is prepended.
 *    [asUrl] holds the full rationale; its invariant is the one thing to
 *    preserve when touching that function.
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

    // The one scheme pair we are willing to launch. Anchored, so `javascript:`,
    // `data:`, `file:`, `intent:` and `content:` never reach ACTION_VIEW.
    private val SCHEME_PREFIX_REGEX = Regex("^https?://", RegexOption.IGNORE_CASE)

    // Only phone-shaped characters (no letters); structure/length gate the rest.
    private val PHONE_CHARS_REGEX = Regex("^\\+?[0-9 ().\\-]+$")

    /**
     * Returns the ready-to-launch URL, or null if [text] isn't one.
     *
     * **The governing invariant: the authority that gets validated here must be
     * the authority a URI parser will resolve when the string is launched.**
     * An earlier revision validated `text.substringBefore(':')` and then
     * launched the whole `text`, so `paypal.com:x@evil.tld/login` was checked
     * as `paypal.com` and opened as `evil.tld`. Every guard below exists to
     * keep the checked string and the launched string the same string; treat
     * that as the property to preserve, not the individual guards.
     *
     * Both branches therefore share one authority extraction — everything up to
     * the first `/`, `?` or `#` after the optional scheme — and both reject
     * userinfo outright:
     *  - **Userinfo is refused, always.** `user@host` in a URL is a spoofing
     *    vector far more often than an intent (browsers strip or warn on it),
     *    and a copied `https://user:pass@host/` is a credential the launcher
     *    should not act on. Such input falls through to a web search, which the
     *    dialog still shows before anything leaves the device.
     *  - **A colon in the authority must introduce a numeric port.** Otherwise
     *    the string is malformed and we do not guess: `example.com:` and
     *    `javascript:alert(1)` are searches, `ratio.is:3` is a URL.
     *
     * Host *form* rules then differ by branch, mirroring how androidx itself
     * splits `WEB_URL_WITH_PROTOCOL` from `WEB_URL_WITHOUT_PROTOCOL`:
     *  - **With scheme**, the host is the user's business — `http://192.168.1.1`
     *    (a router admin page) and `http://nas/share` must keep working, so no
     *    TLD check applies once the authority is known to be clean.
     *  - **Without scheme**, the final label must be a real IANA TLD per
     *    [PatternsCompat.AUTOLINK_WEB_URL], or `report.txt` would become a
     *    hostname. That beats both a hand-kept TLD allowlist (ages against
     *    ~1500 live TLDs) and a file-extension denylist (which blocked
     *    `.zip`/`.mov`/`.md` although all three are live TLDs). A numeric final
     *    label is rejected so a bare `192.168.1.1` stays a search — the
     *    pattern's own `STRICT_DOMAIN_NAME` would otherwise accept it, so that
     *    guard is load-bearing.
     *
     * Only the host reaches the matcher, capped at [MAX_HOST_LENGTH] and
     * lowercased. Capped, because the ~1500-TLD alternation recurses per
     * dot-separated label and the full 8192-char cap threw StackOverflowError
     * on the 1 MB stacks coroutine workers get. Lowercased, because
     * `IANA_TOP_LEVEL_DOMAINS` is a lowercase-only alternation compiled with no
     * flags, so `GOOGLE.COM` would otherwise fall through to a search.
     *
     * [PatternsCompat.AUTOLINK_WEB_URL] carries `@RestrictTo(LIBRARY_GROUP_PREFIX)`,
     * hence the `@Suppress`. Knowingly accepted: the strict IANA list is
     * reachable through no other member — `DOMAIN_NAME` and `WEB_URL` are public
     * but build on the relaxed `TLD`. A future androidx bump that drops the
     * field yields a compile error, not a silent behaviour change, and the
     * version is pinned in `libs.versions.toml`.
     *
     * Residual ambiguity is unavoidable: `install.sh` and `main.py` are valid
     * domains AND common filenames, so they classify as URLs. The confirmation
     * dialog is what makes that harmless — but note the dialog is NOT a
     * backstop for authority spoofing, since it previews one ellipsised line.
     * That is why the rules above are structural rather than advisory.
     */
    @Suppress("RestrictedApi") // see the KDoc above for why AUTOLINK_WEB_URL is worth it
    private fun asUrl(text: String): String? {
        if (text.any { it.isWhitespace() }) return null

        val schemeLength = SCHEME_PREFIX_REGEX.find(text)?.value?.length
        val afterScheme = if (schemeLength != null) text.substring(schemeLength) else text

        val authority = afterScheme.substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val host = hostOf(authority) ?: return null

        // Scheme present: the authority is clean, the host form is the user's call.
        if (schemeLength != null) return text

        if (host.length > MAX_HOST_LENGTH) return null
        val finalLabel = host.substringAfterLast('.', "")
        if (finalLabel.length < 2 || !finalLabel.all { it.isLetter() }) return null

        val matches = PatternsCompat.AUTOLINK_WEB_URL.matcher(host.lowercase()).matches()
        return if (matches) "https://$text" else null
    }

    /**
     * The host of an RFC 3986 authority, or null if we refuse to launch it.
     *
     * Refused: an empty authority, any userinfo (`@`), and a colon that does
     * not introduce a numeric port. The last rule also disposes of IPv6
     * literals (`[::1]`) and of non-http schemes that survived
     * [SCHEME_PREFIX_REGEX], since neither leaves a digits-only tail.
     */
    private fun hostOf(authority: String): String? {
        if (authority.isEmpty()) return null
        if ('@' in authority) return null

        val portSeparator = authority.lastIndexOf(':')
        if (portSeparator < 0) return authority

        val port = authority.substring(portSeparator + 1)
        if (port.isEmpty() || !port.all { it.isDigit() }) return null
        return authority.substring(0, portSeparator)
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
