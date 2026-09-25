package com.github.reygnn.kolibri_launcher.fakes

// NUR FÜR unitTest !!!
// TIMESTAMP 2025-12-04 19:22

import com.github.reygnn.launcher.core.isEffectivelyBlank
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException

class FakeCustomNamesRepository : CustomNamesRepository {
    private val customNames = mutableMapOf<String, String>()

    private val customNamesState = MutableStateFlow<Map<String, String>>(emptyMap())
    override val customNamesFlow: Flow<Map<String, String>> = customNamesState

    /** Republish the backing map onto [customNamesFlow] after every mutation. */
    private fun syncFlow() {
        customNamesState.value = customNames.toMap()
    }

    var shouldFailOnSet = false

    // Für BackupRepositoryImplTest
    var batchSetCalled = false
    var shouldFailOnBatch = false

    override suspend fun getDisplayNameForPackage(packageName: String, originalName: String): String {
        return customNames.getOrDefault(packageName, originalName)
    }

    override suspend fun setCustomNameForPackage(packageName: String, customName: String): Boolean {
        if (shouldFailOnSet) {
            throw IOException("Simulated failure")
        }

        // Mirror the impl: effectively-blank (empty / whitespace / combining-only)
        // is a remove on the single-set path.
        if (customName.isEffectivelyBlank()) {
            customNames.remove(packageName)
        } else {
            customNames[packageName] = customName.trim()
        }

        syncFlow()
        return true
    }

    override suspend fun removeCustomNameForPackage(packageName: String): Boolean {
        // Idempotent — analog zu CustomNamesRepositoryImpl.removeCustomNameForPackage:
        // der Zielzustand "kein Custom-Name für packageName" ist nach dem Aufruf
        // erreicht, also Erfolg, unabhängig davon ob vorher ein Eintrag da war.
        // Konsistent zur gleichen Idempotenz-Regel bei
        // FakeFavoritesRepository.removeFavoriteComponent und
        // FakeHiddenAppsRepository.showComponent.
        customNames.remove(packageName)
        syncFlow()
        return true
    }

    override suspend fun hasCustomNameForPackage(packageName: String): Boolean {
        return customNames.containsKey(packageName)
    }

    override suspend fun getAllCustomNames(): Map<String, String> {
        return customNames.toMap()
    }

    override suspend fun setCustomNamesInBatch(names: Map<String, String>): Boolean {
        if (shouldFailOnBatch) {
            return false
        }

        if (names.isEmpty()) {
            return true
        }

        batchSetCalled = true
        names.forEach { (packageName, customName) ->
            // Mirror the impl: batch ignores (skips) effectively-blank values.
            if (!customName.isEffectivelyBlank()) {
                customNames[packageName] = customName.trim()
            }
        }
        syncFlow()
        return true
    }

    override suspend fun purgeRepository() {
        customNames.clear()
        syncFlow()
        shouldFailOnSet = false
        shouldFailOnBatch = false
        batchSetCalled = false
    }
}