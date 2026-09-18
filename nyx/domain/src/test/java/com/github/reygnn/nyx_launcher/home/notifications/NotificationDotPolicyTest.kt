package com.github.reygnn.nyx_launcher.home.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins [NotificationDotPolicy.dotPackages]: only real, clearable, non-ongoing
 * notifications produce a dot; the result is a deduped set of package names.
 */
class NotificationDotPolicyTest {

    private fun n(pkg: String, ongoing: Boolean = false, clearable: Boolean = true) =
        NotificationSummary(packageName = pkg, isOngoing = ongoing, isClearable = clearable)

    @Test
    fun `a clearable non-ongoing notification produces a dot`() {
        assertThat(NotificationDotPolicy.dotPackages(listOf(n("com.a"))))
            .containsExactly("com.a")
    }

    @Test
    fun `ongoing notifications are excluded`() {
        assertThat(NotificationDotPolicy.dotPackages(listOf(n("com.fg", ongoing = true))))
            .isEmpty()
    }

    @Test
    fun `non-clearable notifications are excluded`() {
        assertThat(NotificationDotPolicy.dotPackages(listOf(n("com.persist", clearable = false))))
            .isEmpty()
    }

    @Test
    fun `multiple notifications from the same package dedupe to one entry`() {
        assertThat(NotificationDotPolicy.dotPackages(listOf(n("com.a"), n("com.a"), n("com.b"))))
            .containsExactly("com.a", "com.b")
    }

    @Test
    fun `a package with both an ongoing and a clearable notification still gets a dot`() {
        val result = NotificationDotPolicy.dotPackages(
            listOf(n("com.chat", ongoing = true), n("com.chat", ongoing = false)),
        )
        assertThat(result).containsExactly("com.chat")
    }

    @Test
    fun `no active notifications yields an empty set`() {
        assertThat(NotificationDotPolicy.dotPackages(emptyList())).isEmpty()
    }
}
