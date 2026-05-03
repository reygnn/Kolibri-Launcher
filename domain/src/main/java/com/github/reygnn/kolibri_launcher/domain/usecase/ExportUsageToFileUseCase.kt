package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.UsageExportRepository
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject

class ExportUsageToFileUseCase @Inject constructor(
    private val repository: UsageExportRepository
) {
    /**
     * Führt den Export durch.
     * Kapselt die Boolean-Rückgabe des Repositories in ein kotlinesques Result<Unit>.
     */
    suspend operator fun invoke(uriString: String): Result<Unit> {
        return try {
            val success = repository.saveToFile(uriString)
            if (success) {
                Result.success(Unit)
            } else {
                // Wir wandeln das 'false' in eine explizite Exception für den Consumer um
                Result.failure(IOException("Could not write file"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}