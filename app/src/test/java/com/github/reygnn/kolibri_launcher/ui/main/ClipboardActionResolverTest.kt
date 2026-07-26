package com.github.reygnn.kolibri_launcher.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ClipboardActionResolver] — no Android, no Robolectric.
 * The 2FA/OTP/PIN cases are the whole reason the phone branch is strict.
 */
class ClipboardActionResolverTest {

    private fun resolve(text: String?) = ClipboardActionResolver.resolve(text)

    // ---------- the dangerous ones: numeric codes must NOT dial ----------

    @Test
    fun `six-digit 2FA code goes to web search, not the dialer`() {
        assertEquals(ClipboardAction.WebSearch("483920"), resolve("483920"))
    }

    @Test
    fun `four-digit PIN goes to web search`() {
        assertEquals(ClipboardAction.WebSearch("1234"), resolve("1234"))
    }

    @Test
    fun `eight-digit OTP goes to web search`() {
        assertEquals(ClipboardAction.WebSearch("48392017"), resolve("48392017"))
    }

    @Test
    fun `bare seven-digit number is treated as a code, not a phone`() {
        // Conservative: without structure and under 10 digits we prefer search.
        assertEquals(ClipboardAction.WebSearch("4839201"), resolve("4839201"))
    }

    // ---------- real phone numbers DO dial (DIAL, never CALL) ----------

    @Test
    fun `international number with plus dials`() {
        assertEquals(ClipboardAction.Dial("+41 79 123 45 67"), resolve("+41 79 123 45 67"))
    }

    @Test
    fun `formatted number with separators dials`() {
        assertEquals(ClipboardAction.Dial("(079) 123-4567"), resolve("(079) 123-4567"))
    }

    @Test
    fun `bare ten-digit number dials`() {
        assertEquals(ClipboardAction.Dial("0791234567"), resolve("0791234567"))
    }

    @Test
    fun `sixteen-digit string (e_g_ card number) is not a phone`() {
        assertEquals(ClipboardAction.WebSearch("1234567890123456"), resolve("1234567890123456"))
    }

    // ---------- dotted / spaced numerics are NOT phones ----------

    @Test
    fun `a copied date does not dial`() {
        assertEquals(ClipboardAction.WebSearch("25.07.2026"), resolve("25.07.2026"))
    }

    @Test
    fun `an IP address does not dial`() {
        assertEquals(ClipboardAction.WebSearch("192.168.1.1"), resolve("192.168.1.1"))
    }

    @Test
    fun `an amount with dot separators does not dial`() {
        assertEquals(ClipboardAction.WebSearch("12.345.678"), resolve("12.345.678"))
    }

    @Test
    fun `an amount with space separators does not dial`() {
        assertEquals(ClipboardAction.WebSearch("1 234 567"), resolve("1 234 567"))
    }

    @Test
    fun `a dotted international number still dials`() {
        // The leading '+' is the unambiguous marker, so dots are fine there.
        assertEquals(ClipboardAction.Dial("+41.79.123.45.67"), resolve("+41.79.123.45.67"))
    }

    // ---------- URLs ----------

    @Test
    fun `full https url opens as-is`() {
        assertEquals(
            ClipboardAction.OpenUrl("https://example.org/a/b?c=d"),
            resolve("https://example.org/a/b?c=d"),
        )
    }

    @Test
    fun `bare domain with common tld gets https prepended`() {
        assertEquals(ClipboardAction.OpenUrl("https://google.com", "google.com"), resolve("google.com"))
    }

    @Test
    fun `bare domain with path opens`() {
        assertEquals(
            ClipboardAction.OpenUrl("https://github.com/reygnn", "github.com/reygnn"),
            resolve("github.com/reygnn"),
        )
    }

    @Test
    fun `uppercase bare domain opens rather than being searched`() {
        // The IANA TLD alternation is lowercase-only and compiled without
        // CASE_INSENSITIVE, so an autocapitalised copy used to fall through
        // to a web search.
        assertEquals(
            ClipboardAction.OpenUrl("https://EXAMPLE.COM", "EXAMPLE.COM"),
            resolve("EXAMPLE.COM"),
        )
        assertEquals(
            ClipboardAction.OpenUrl("https://Google.com", "Google.com"),
            resolve("Google.com"),
        )
    }

    @Test
    fun `displayText of a bare domain is what the user copied`() {
        // The dialog previews and Share forwards displayText — showing the
        // synthesized https:// form would hide a misclassification such as
        // `install.sh` behind a string the user never had.
        val action = resolve("install.sh") as ClipboardAction.OpenUrl
        assertEquals("https://install.sh", action.url)
        assertEquals("install.sh", action.displayText)
    }

    @Test
    fun `a very long dotted string is searched without blowing the stack`() {
        // Matching the full text against the ~1500-TLD alternation recursed
        // per label and threw StackOverflowError on a 1 MB stack; only the
        // host (<= 253 chars) reaches the matcher now.
        val bomb = "a.".repeat(4090) + "zzzz"
        assertTrue(resolve(bomb) is ClipboardAction.WebSearch)
    }

    @Test
    fun `filename with non-tld extension stays a search`() {
        // Final labels that are not IANA TLDs must never become hostnames.
        listOf("report.txt", "index.html", "report.odt", "song.flac", "data.db")
            .forEach { assertEquals(ClipboardAction.WebSearch(it), resolve(it)) }
    }

    @Test
    fun `tlds that collide with file extensions still open`() {
        // .zip and .mov are ICANN TLDs since 2023, .md is Moldova — the old
        // extension denylist wrongly downgraded all three to a search.
        assertEquals(ClipboardAction.OpenUrl("https://docs.zip", "docs.zip"), resolve("docs.zip"))
        assertEquals(ClipboardAction.OpenUrl("https://nic.md", "nic.md"), resolve("nic.md"))
    }

    @Test
    fun `bare IP address stays a search`() {
        assertEquals(ClipboardAction.WebSearch("192.168.1.1"), resolve("192.168.1.1"))
    }

    @Test
    fun `shortlink with an uncommon tld opens`() {
        assertEquals(ClipboardAction.OpenUrl("https://bit.ly/3xYzQ", "bit.ly/3xYzQ"), resolve("bit.ly/3xYzQ"))
    }

    @Test
    fun `modern and country tlds outside any allowlist open`() {
        assertEquals(ClipboardAction.OpenUrl("https://shop.swiss", "shop.swiss"), resolve("shop.swiss"))
        assertEquals(ClipboardAction.OpenUrl("https://example.tech", "example.tech"), resolve("example.tech"))
    }

    @Test
    fun `url followed by prose is searched, not opened`() {
        val text = "https://example.com is a great site"
        assertEquals(ClipboardAction.WebSearch(text), resolve(text))
    }

    @Test
    fun `bare scheme without a host is searched, not opened`() {
        assertEquals(ClipboardAction.WebSearch("https://"), resolve("https://"))
    }

    // ---------- oversized clipboard ----------

    @Test
    fun `oversized clipboard text is capped before being forwarded`() {
        val huge = "a".repeat(50_000)
        val result = resolve(huge) as ClipboardAction.WebSearch
        assertEquals(8192, result.query.length)
    }

    @Test
    fun `oversized url is searched, never opened truncated`() {
        // Truncating first and classifying after would open a URL cut off
        // mid-query — a silently mangled link is worse than a search for it.
        val longUrl = "https://example.com/callback?token=" + "x".repeat(9000)
        val result = resolve(longUrl)
        assertTrue(result is ClipboardAction.WebSearch)
    }

    @Test
    fun `decimal number is not a url`() {
        assertEquals(ClipboardAction.WebSearch("3.14"), resolve("3.14"))
    }

    // ---------- email ----------

    @Test
    fun `email address composes a mail`() {
        assertEquals(ClipboardAction.Email("ulrich@kolibri.io"), resolve("ulrich@kolibri.io"))
    }

    // ---------- fallback + hygiene ----------

    @Test
    fun `free text falls back to web search`() {
        assertEquals(ClipboardAction.WebSearch("kolibri launcher"), resolve("kolibri launcher"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(ClipboardAction.OpenUrl("https://google.com", "google.com"), resolve("  google.com  "))
    }

    @Test
    fun `null clipboard resolves to null`() {
        assertNull(resolve(null))
    }

    @Test
    fun `blank clipboard resolves to null`() {
        assertNull(resolve("   "))
    }
}
