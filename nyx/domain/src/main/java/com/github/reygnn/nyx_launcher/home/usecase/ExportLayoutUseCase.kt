package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.di.DefaultDispatcher
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.LayoutSerializer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Serializes the current layout to a string for the caller to write to a file. */
class ExportLayoutUseCase @Inject constructor(
    private val repository: HomeLayoutRepository,
    private val serializer: LayoutSerializer,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(): String = withContext(dispatcher) {
        serializer.serialize(repository.layout().first())
    }
}
