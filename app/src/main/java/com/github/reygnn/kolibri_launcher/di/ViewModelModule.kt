package com.github.reygnn.kolibri_launcher.di

import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingViewModel
import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingViewModelInterface
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
abstract class ViewModelModule {

    @Binds
    abstract fun bindOnboardingViewModel(
        viewModel: OnboardingViewModel
    ): OnboardingViewModelInterface
}