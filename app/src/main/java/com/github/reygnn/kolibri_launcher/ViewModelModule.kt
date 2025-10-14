package com.github.reygnn.kolibri_launcher

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