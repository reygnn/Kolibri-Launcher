package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Cold stream of the current home layout for the UI to render. */
class ObserveHomeLayoutUseCase @Inject constructor(
    private val repository: HomeLayoutRepository,
) {
    operator fun invoke(): Flow<HomeLayout> = repository.layout()
}
