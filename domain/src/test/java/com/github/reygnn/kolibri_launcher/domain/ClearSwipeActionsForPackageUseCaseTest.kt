package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.usecase.ClearSwipeActionsForPackageUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Pins the event-driven swipe-cleanup introduced for TODO §24: on a genuine
 * package removal, the swipe slot(s) pointing at that package are cleared,
 * and nothing else is touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClearSwipeActionsForPackageUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var swipeActionsRepository: FakeSwipeActionsRepository
    private lateinit var useCase: ClearSwipeActionsForPackageUseCase

    @Before
    fun setup() {
        swipeActionsRepository = FakeSwipeActionsRepository()
        useCase = ClearSwipeActionsForPackageUseCase(swipeActionsRepository)
    }

    @Test
    fun `clears LEFT when its package was removed`() = runTest {
        swipeActionsRepository.swipeLeftApp = "com.example.a/com.example.a.Main"

        useCase("com.example.a")

        assertThat(swipeActionsRepository.swipeLeftApp).isNull()
    }

    @Test
    fun `clears RIGHT when its package was removed`() = runTest {
        swipeActionsRepository.swipeRightApp = "com.example.b/com.example.b.Main"

        useCase("com.example.b")

        assertThat(swipeActionsRepository.swipeRightApp).isNull()
    }

    @Test
    fun `clears BOTH slots when they point at the same removed package`() = runTest {
        // Different activities of the same package in each slot.
        swipeActionsRepository.swipeLeftApp = "com.example.a/com.example.a.Main"
        swipeActionsRepository.swipeRightApp = "com.example.a/com.example.a.Second"

        useCase("com.example.a")

        assertThat(swipeActionsRepository.swipeLeftApp).isNull()
        assertThat(swipeActionsRepository.swipeRightApp).isNull()
    }

    @Test
    fun `leaves the other slot untouched when only one package matches`() = runTest {
        swipeActionsRepository.swipeLeftApp = "com.example.a/com.example.a.Main"
        swipeActionsRepository.swipeRightApp = "com.example.b/com.example.b.Main"

        useCase("com.example.a")

        assertThat(swipeActionsRepository.swipeLeftApp).isNull()
        assertThat(swipeActionsRepository.swipeRightApp)
            .isEqualTo("com.example.b/com.example.b.Main")
    }

    @Test
    fun `does not clear when no slot matches the removed package`() = runTest {
        swipeActionsRepository.swipeLeftApp = "com.example.a/com.example.a.Main"
        swipeActionsRepository.swipeRightApp = "com.example.b/com.example.b.Main"

        useCase("com.example.zzz")

        assertThat(swipeActionsRepository.swipeLeftApp)
            .isEqualTo("com.example.a/com.example.a.Main")
        assertThat(swipeActionsRepository.swipeRightApp)
            .isEqualTo("com.example.b/com.example.b.Main")
    }

    @Test
    fun `does not clear on a package-name prefix collision`() = runTest {
        // "com.foo" removed must not clear a "com.foobar" assignment.
        swipeActionsRepository.swipeLeftApp = "com.foobar/com.foobar.Main"

        useCase("com.foo")

        assertThat(swipeActionsRepository.swipeLeftApp)
            .isEqualTo("com.foobar/com.foobar.Main")
    }

    @Test
    fun `no-op when both slots are empty`() = runTest {
        swipeActionsRepository.swipeLeftApp = null
        swipeActionsRepository.swipeRightApp = null

        useCase("com.example.a")

        assertThat(swipeActionsRepository.swipeLeftApp).isNull()
        assertThat(swipeActionsRepository.swipeRightApp).isNull()
    }

    @Test
    fun `blank package name clears nothing`() = runTest {
        swipeActionsRepository.swipeLeftApp = "com.example.a/com.example.a.Main"

        useCase("")

        assertThat(swipeActionsRepository.swipeLeftApp)
            .isEqualTo("com.example.a/com.example.a.Main")
    }
}
