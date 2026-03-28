package com.github.reygnn.kolibri_launcher.fakes

// NUR FÜR androidTest !!!
// TIMESTAMP 2025-12-04 19:22

import com.github.reygnn.kolibri_launcher.data.TestDataSource
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable

/**
 * Verwaltet die Namensänderungen. Es aktualisiert die zentrale `TestDataSource`
 * und ruft dann den `onNameChanged`-Callback auf, um die reaktive Kette auszulösen.
 */
class FakeCustomNamesRepository(
    private val onNameChanged: () -> Unit
) : CustomNamesRepository, Purgeable {

    override suspend fun getDisplayNameForPackage(
        packageName: String,
        originalName: String
    ): String {
        return TestDataSource.getDisplayName(packageName, originalName)
    }

    override suspend fun setCustomNameForPackage(packageName: String, customName: String): Boolean {
        if (customName.isBlank()) {
            TestDataSource.removeCustomName(packageName)
        } else {
            TestDataSource.setCustomName(packageName, customName.trim())
        }
        onNameChanged()
        return true
    }

    override suspend fun removeCustomNameForPackage(packageName: String): Boolean {
        TestDataSource.removeCustomName(packageName)
        onNameChanged()
        return true
    }

    override suspend fun hasCustomNameForPackage(packageName: String): Boolean {
        return TestDataSource.hasCustomName(packageName)
    }

    override suspend fun getAllCustomNames(): Map<String, String> {
        return TestDataSource.getAllCustomNames()
    }

    override suspend fun setCustomNamesInBatch(names: Map<String, String>): Boolean {
        if (names.isEmpty()) {
            return true
        }

        names.forEach { (packageName, customName) ->
            if (customName.isNotBlank()) {
                TestDataSource.setCustomName(packageName, customName.trim())
            }
        }
        onNameChanged()
        return true
    }

    override suspend fun triggerCustomNameUpdate() {
        onNameChanged()
    }

    override suspend fun purgeRepository() {
        TestDataSource.clearCustomNames()
        onNameChanged()
    }
}