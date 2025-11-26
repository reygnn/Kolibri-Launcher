package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetSwipeRightAppUseCase @Inject constructor(
    private val repository: SwipeActionsRepository
) {
    operator fun invoke(): Flow<String?> {
        return repository.swipeRightAppFlow
    }
}