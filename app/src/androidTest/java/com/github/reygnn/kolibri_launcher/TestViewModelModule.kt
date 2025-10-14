package com.github.reygnn.kolibri_launcher

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.testing.TestInstallIn

/**
 * Dieses Hilt-Modul wird NUR in Tests verwendet.
 * Es ersetzt das echte ViewModel durch seine Fake-Implementierung.
 */
@Module
@TestInstallIn(
    components = [ViewModelComponent::class],
    replaces = [ViewModelModule::class]
)
abstract class TestViewModelModule {
    @Binds
    abstract fun bindOnboardingViewModel(
        fakeViewModel: FakeOnboardingViewModel
    ): OnboardingViewModelInterface
}