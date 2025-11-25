package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.ui.util.AppUpdateSignal
import kotlinx.coroutines.runBlocking

open class FakeAppUpdateSignal : AppUpdateSignal(), Purgeable {
    var signalSentCount = 0
        private set

    fun reset() {
        runBlocking { purgeRepository() }
    }

    override suspend fun purgeRepository() {
        signalSentCount = 0
    }

    override suspend fun sendUpdateSignal() {
        signalSentCount++; super.sendUpdateSignal()
    }
}