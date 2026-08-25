package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Marks onboarding as completed WITHOUT touching the favorites.
 *
 * Unlike [CompleteOnboardingUseCase] — which always saves the favorites
 * first and then sets the flag — this use case only flips the
 * onboarding-completed flag. It exists for the "restore a full backup during
 * initial setup" path: at that point the favorites have already been
 * persisted by [ImportBackupUseCase], so running the normal done-path (with
 * the still-empty in-memory selection) would overwrite the just-restored
 * favorites with an empty list. See OnboardingViewModel.restoreBackupAndFinish.
 */
class MarkOnboardingCompletedUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke() {
        settingsRepository.setOnboardingCompleted()
    }
}
