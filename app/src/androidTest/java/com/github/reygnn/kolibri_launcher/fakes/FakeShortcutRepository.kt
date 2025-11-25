package com.github.reygnn.kolibri_launcher.fakes

import android.content.pm.ShortcutInfo
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.domain.repository.ShortcutRepository

class FakeShortcutRepository : ShortcutRepository, Purgeable {
    override fun getShortcutsForPackage(packageName: String): List<ShortcutInfo> = emptyList()
    override suspend fun purgeRepository() {}
}