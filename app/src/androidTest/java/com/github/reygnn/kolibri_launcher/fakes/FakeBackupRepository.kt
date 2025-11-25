package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable

class FakeBackupRepository : BackupRepository, Purgeable {
    var lastExportedJson: String? = null
        private set
    var lastImportedJson: String? = null
        private set
    var lastImportOptions: ImportOptions? = null
        private set

    override suspend fun exportToJson(): String {
        val json = """
        {
            "version": "1.0.0",
            "timestamp": ${System.currentTimeMillis()},
            "settings": {
                "favoriteComponents": [],
                "favoritesOrder": [],
                "hiddenComponents": [],
                "customAppNames": {},
                "swipe_left_app": null,
                "swipe_right_app": null,
                "text_color": 0,
                "chip_bg_color": 0,
                "text_shadow_enabled": true,
                "double_tap_to_lock_enabled": false,
                "swipe_down_to_notifications_enabled": false,
                "show_calendar_event": false,
                "show_alarm": false
            }
        }
        """.trimIndent()
        lastExportedJson = json
        return json
    }

    override suspend fun importFromJson(jsonString: String, options: ImportOptions): ImportResult {
        lastImportedJson = jsonString
        lastImportOptions = options
        return ImportResult.Success(
            importedCount = 0,
            skippedCount = 0,
            missingApps = emptySet()
        )
    }

    override suspend fun saveBackupToFile(uriString: String): Boolean {
        return true
    }

    override suspend fun loadBackupFromFile(
        uriString: String,
        options: ImportOptions
    ): ImportResult {
        lastImportOptions = options
        return ImportResult.Success(
            importedCount = 0,
            skippedCount = 0,
            missingApps = emptySet()
        )
    }

    override suspend fun previewBackup(uriString: String): BackupPreview? {
        return BackupPreview(
            version = "1.0.0",
            timestamp = System.currentTimeMillis(),
            favoriteCount = 0,
            orderCount = 0,
            hiddenCount = 0,
            customNamesCount = 0,
            hasSwipeLeft = false,
            hasSwipeRight = false,
            hasThemeSettings = false,
            hasTimeBasedEvents = false,
            hasGestureSettings = false,
            hasQualityOfLife = false,
            hasPowerUserSettings = false
        )
    }

    override suspend fun purgeRepository() {
        lastExportedJson = null
        lastImportedJson = null
        lastImportOptions = null
    }
}