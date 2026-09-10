package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.repository.FakeInstalledAppsRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class GetDrawerAppsUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun app(label: String, custom: String? = null) = LauncherApp(
        key = ComponentKey("pkg.$label", "pkg.$label.Main"),
        label = label,
        customName = custom,
    )

    @Test
    fun sorts_by_display_name_case_insensitively() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeInstalledAppsRepository(listOf(app("banana"), app("Apple"), app("cherry")))
        val result = GetDrawerAppsUseCase(repo, mainDispatcherRule.dispatcher)()
        assertThat(result.map { it.label }).containsExactly("Apple", "banana", "cherry").inOrder()
    }

    @Test
    fun custom_name_overrides_label_for_sorting() = runTest(mainDispatcherRule.dispatcher) {
        // Labelled "zzz" but custom-named "aaa" ⇒ sorts first.
        val repo = FakeInstalledAppsRepository(listOf(app("mango"), app("zzz", custom = "aaa")))
        val result = GetDrawerAppsUseCase(repo, mainDispatcherRule.dispatcher)()
        assertThat(result.first().customName).isEqualTo("aaa")
    }
}
