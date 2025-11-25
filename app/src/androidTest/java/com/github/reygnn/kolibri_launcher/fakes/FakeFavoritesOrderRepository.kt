package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeFavoritesOrderRepository : FavoritesOrderRepository, Purgeable {
    private val orderState = MutableStateFlow<List<String>>(emptyList())
    override val favoriteComponentsOrderFlow: Flow<List<String>> = orderState
    var savedOrder: List<String>? = null
        private set
    var saveOrderCallCount = 0
        private set

    override suspend fun saveOrder(orderedComponentNames: List<String>): Boolean {
        println(">>> FakeFavoritesOrderRepository.saveOrder CALLED")
        println(">>> Thread: ${Thread.currentThread().name}")
        println(">>> componentNames = $orderedComponentNames")
        println(">>> saveOrderCallCount BEFORE = $saveOrderCallCount")

        savedOrder = orderedComponentNames
        saveOrderCallCount++

        println(">>> saveOrderCallCount AFTER = $saveOrderCallCount")
        orderState.value = orderedComponentNames
        return true
    }

    override suspend fun sortFavoriteComponents(
        favoriteApps: List<AppInfo>,
        order: List<String>
    ): List<AppInfo> {
        if (order.isEmpty()) return favoriteApps.sortedBy { it.displayName };
        val appMap =
            favoriteApps.associateBy { it.componentName }; return order.mapNotNull { appMap[it] } + (favoriteApps - appMap.keys.mapNotNull { appMap[it] }
            .toSet())
    }

    override suspend fun purgeRepository() {
        orderState.value = emptyList(); savedOrder = null; saveOrderCallCount = 0
    }
}