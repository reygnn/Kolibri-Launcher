package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import java.io.IOException

class FakeAppNamesRepository : CustomNamesRepository {
    private val customNames = mutableMapOf<String, String>()

    // Für AppNamesViewModelTest
    var onUpdateTrigger: (suspend () -> Unit)? = null
    var shouldFailOnSet = false

    // Für BackupManagerTest
    var batchSetCalled = false
    var shouldFailOnBatch = false

    override suspend fun getDisplayNameForPackage(packageName: String, originalName: String): String {
        return customNames.getOrDefault(packageName, originalName)
    }

    override suspend fun setCustomNameForPackage(packageName: String, customName: String): Boolean {
        if (shouldFailOnSet) {
            throw IOException("Simulated failure")
        }
        customNames[packageName] = customName
        triggerCustomNameUpdate()
        return true
    }

    override suspend fun removeCustomNameForPackage(packageName: String): Boolean {
        val success = customNames.remove(packageName) != null
        if (success) {
            triggerCustomNameUpdate()
        }
        return success
    }

    override suspend fun hasCustomNameForPackage(packageName: String): Boolean {
        return customNames.containsKey(packageName)
    }

    override suspend fun triggerCustomNameUpdate() {
        onUpdateTrigger?.invoke()
    }

    override suspend fun getAllCustomNames(): Map<String, String> {
        return customNames.toMap()
    }

    override suspend fun setCustomNamesInBatch(names: Map<String, String>): Boolean {
        if (shouldFailOnBatch) {
            return false
        }

        if (names.isEmpty()) {
            return true // Empty batch is success, but NO batchSetCalled
        }

        batchSetCalled = true  // ← NUR wenn nicht leer!
        customNames.putAll(names)
        triggerCustomNameUpdate()
        return true
    }

    override suspend fun purgeRepository() {
        customNames.clear()
        onUpdateTrigger = null
        shouldFailOnSet = false
        shouldFailOnBatch = false
        batchSetCalled = false
    }
}