package com.github.reygnn.kolibri_launcher.di

import com.github.reygnn.kolibri_launcher.OnboardingViewModelInterface
import com.github.reygnn.kolibri_launcher.ui.OnboardingViewModel
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