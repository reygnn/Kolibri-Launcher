package com.github.reygnn.kolibri_launcher.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `a mixed-case scheme is still recognised as a url`() {
        // Autocapitalise on a soft keyboard yields HTTP:// / Https://. The scheme
        // regex is IGNORE_CASE, so these must open rather than fall through to a
        // search; the scheme branch launches the text verbatim.
        assertEquals(ClipboardAction.OpenUrl("HTTP://EXAMPLE.COM"), resolve("HTTP://EXAMPLE.COM"))
        assertEquals(ClipboardAction.OpenUrl("Https://example.com/x"), resolve("Https://example.com/x"))
    }

    // =====================================================================
    // ADVERSARIAL — the clipboard is attacker-controlled
    // =====================================================================
    // Anything can be put on the clipboard by any app, or copied by a user
    // off a phishing page. The invariant these tests defend is the one in
    // asUrl's KDoc: THE AUTHORITY WE VALIDATE MUST BE THE AUTHORITY THAT
    // GETS LAUNCHED. A regression here is not a wrong suggestion, it is the
    // launcher opening a host the user did not see.
    //
    // The confirmation dialog is explicitly NOT the backstop: it renders one
    // ellipsised line, which is exactly what userinfo spoofing defeats.

    /** The URL the user would actually be sent to, or null if not an OpenUrl. */
    private fun launchedUrl(text: String) = (resolve(text) as? ClipboardAction.OpenUrl)?.url

    @Test
    fun `userinfo spoof is never opened - scheme-less`() {
        // host cut at ':' used to validate as paypal.com and launch evil.tld.
        listOf(
            "paypal.com:x@evil.tld/login",
            "example.com:80@evil.com/x",
            "example.com@evil.com/x",
            "google.com:x@evil.tld",
        ).forEach { assertNull("must not open: $it", launchedUrl(it)) }
    }

    @Test
    fun `userinfo spoof is never opened - with scheme`() {
        listOf(
            "https://paypal.com@evil.tld",
            "https://paypal.com:x@evil.tld/login",
            "http://user:pass@evil.tld/",
            "https://bank.example.com@192.0.2.1/",
        ).forEach { assertNull("must not open: $it", launchedUrl(it)) }
    }

    @Test
    fun `a copied credential url is not opened and stays local to the search branch`() {
        // Refusing to launch it is the point; the dialog then shows the user
        // what a search would forward, and dismissing costs one tap.
        val credentials = "https://admin:hunter2@intranet.example.com/"
        assertEquals(ClipboardAction.WebSearch(credentials), resolve(credentials))
    }

    @Test
    fun `only http and https are ever launched`() {
        listOf(
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "content://com.example.provider/secret",
            "intent://evil.tld#Intent;scheme=http;end",
            "jar:http://evil.tld!/",
            "ftp://files.example.com/pub",
        ).forEach { assertNull("must not open: $it", launchedUrl(it)) }
    }

    @Test
    fun `a colon in the authority must introduce a numeric port`() {
        assertNull(launchedUrl("example.com:"))
        assertNull(launchedUrl("example.com:notaport"))
        assertNull(launchedUrl("example.com:80x"))
        // A real port is fine.
        assertEquals("https://ratio.is:3", launchedUrl("ratio.is:3"))
        assertEquals("https://example.com:8080/x", launchedUrl("example.com:8080/x"))
    }

    @Test
    fun `an empty authority is never opened`() {
        listOf("https://", "http://", "https:///etc/passwd", "://example.com")
            .forEach { assertNull("must not open: $it", launchedUrl(it)) }
    }

    @Test
    fun `ipv6 literals are not opened scheme-less`() {
        assertNull(launchedUrl("[::1]"))
        assertNull(launchedUrl("[2001:db8::1]:8080"))
    }

    @Test
    fun `whitespace anywhere disqualifies a url`() {
        // Newlines and tabs are how a payload hides a second line in a clip.
        listOf(
            "https://example.com\n@evil.tld",
            "https://example.com\t/path",
            "google.com evil.tld",
        ).forEach { assertNull("must not open: $it", launchedUrl(it)) }
    }

    @Test
    fun `a scheme-prefixed url keeps working for hosts without a public tld`() {
        // The scheme branch deliberately does NOT apply the TLD rule: router
        // admin pages and intranet hosts are legitimate and must still open.
        assertEquals("http://192.168.1.1/admin", launchedUrl("http://192.168.1.1/admin"))
        assertEquals("http://nas/share", launchedUrl("http://nas/share"))
        assertEquals("https://localhost:8080/x", launchedUrl("https://localhost:8080/x"))
    }

    @Test
    fun `the launched url always starts with the authority that was validated`() {
        // Property-style backstop: for every input we agree to open, the host
        // that survives validation must be a prefix of the launched authority.
        // This is what actually broke — a targeted test per exploit shape can
        // always miss the next shape; this catches the class.
        listOf(
            "google.com", "bit.ly/3xYzQ", "github.com/reygnn", "EXAMPLE.COM",
            "example.com:8080/x", "https://example.org/a/b?c=d",
            "http://192.168.1.1/admin", "shop.swiss", "docs.zip",
            "paypal.com:x@evil.tld/login", "https://paypal.com@evil.tld",
            "javascript:alert(1)", "example.com:notaport", "[::1]",
        ).forEach { input ->
            val url = launchedUrl(input) ?: return@forEach
            val authority = url.removePrefix("https://").removePrefix("http://")
                .substringBefore('/').substringBefore('?').substringBefore('#')
            assertFalse(
                "launched authority of '$input' carries userinfo: $authority",
                '@' in authority,
            )
            assertTrue(
                "launched url '$url' does not contain the copied text '$input'",
                url.contains(input.removePrefix("https://").removePrefix("http://")),
            )
        }
    }

    // =====================================================================
    // LAUNCH SPEC — what each action actually fires
    // =====================================================================
    // These decisions used to live in MainActivity, out of reach of a JVM
    // test — which is exactly where both authority-spoofing bugs hid.

    @Test
    fun `a phone number opens the dialer, never places a call`() {
        // ACTION_CALL would need CALL_PHONE and would dial immediately. The
        // whole feature is built on never doing that.
        val spec = ClipboardAction.Dial("+41 79 123 45 67").launchSpec()
        assertEquals("android.intent.action.DIAL", spec.intentAction)
        assertEquals("tel:+41 79 123 45 67", spec.dataUri)
        assertNull(spec.fallbackUri)
    }

    @Test
    fun `a url opens with ACTION_VIEW and no fallback`() {
        val spec = ClipboardAction.OpenUrl("https://example.org", "example.org").launchSpec()
        assertEquals("android.intent.action.VIEW", spec.intentAction)
        assertEquals("https://example.org", spec.dataUri)
        assertNull(spec.queryExtra)
    }

    @Test
    fun `an email composes rather than opening a browser`() {
        val spec = ClipboardAction.Email("ulrich@kolibri.io").launchSpec()
        assertEquals("android.intent.action.SENDTO", spec.intentAction)
        assertEquals("mailto:ulrich@kolibri.io", spec.dataUri)
    }

    @Test
    fun `a web search carries a query extra and a browser fallback`() {
        // ACTION_WEB_SEARCH resolves to nothing on de-Googled ROMs, so this is
        // the one action that must degrade to something every device can open.
        val spec = ClipboardAction.WebSearch("kolibri launcher").launchSpec()
        assertEquals("android.intent.action.WEB_SEARCH", spec.intentAction)
        assertEquals("kolibri launcher", spec.queryExtra)
        assertNull(spec.dataUri)
        assertEquals("https://duckduckgo.com/?q=kolibri+launcher", spec.fallbackUri)
    }

    @Test
    fun `the search fallback escapes query characters that would break the url`() {
        val spec = ClipboardAction.WebSearch("a&b=c?d #e").launchSpec()
        val query = spec.fallbackUri!!.substringAfter("?q=")
        listOf("&", "=", "?", "#", " ").forEach {
            assertFalse("unescaped '$it' in fallback query: $query", it in query)
        }
    }

    @Test
    fun `every action carries a confirm label and only one payload channel`() {
        // The dialog needs a label for each; and dataUri/queryExtra are
        // documented as mutually exclusive, so a future action cannot set both.
        listOf(
            ClipboardAction.OpenUrl("https://example.org"),
            ClipboardAction.Email("a@b.io"),
            ClipboardAction.Dial("+41791234567"),
            ClipboardAction.WebSearch("x"),
        ).forEach { action ->
            val spec = action.launchSpec()
            assertTrue("missing label for $action", spec.confirmLabel != 0)
            assertTrue(
                "both payload channels set for $action",
                spec.dataUri == null || spec.queryExtra == null,
            )
        }
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
    fun `oversized url behind leading whitespace is searched, not opened truncated`() {
        // Regression guard for the cap-then-trim off-by-one: leading whitespace
        // used to eat the one char of headroom the over-length check depends on,
        // so a 9000-char URL behind a newline measured "exactly at the limit"
        // and got opened truncated. Trimming by index before the length check
        // keeps it a search.
        val longUrl = "\n  https://example.com/callback?token=" + "x".repeat(9000)
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

    @Test
    fun `empty clipboard resolves to null`() {
        // Not null, not blank-with-spaces: the trim collapses start onto end and
        // the empty result must never reach the classifier as a search for "".
        assertNull(resolve(""))
    }
}
