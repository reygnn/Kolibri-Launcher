package com.github.reygnn.kolibri_launcher.fakes

// TIMESTAMP 2025-12-04 19:59

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeFavoritesOrderRepository : FavoritesOrderRepository {
    private val orderState = MutableStateFlow<List<String>>(emptyList())

    override val favoriteComponentsOrderFlow: Flow<List<String>> = orderState

    var order: List<String>
        get() = orderState.value
        set(value) {
            orderState.value = value
        }

    var savedOrder: List<String>? = null
        private set
    var saveOrderCallCount = 0
        private set

    override suspend fun saveOrder(orderedComponentNames: List<String>): Boolean {
        savedOrder = orderedComponentNames
        saveOrderCallCount++
        orderState.value = orderedComponentNames
        return true
    }

    override suspend fun sortFavoriteComponents(
        favoriteApps: List<AppInfo>,
        order: List<String>
    ): List<AppInfo> {
        if (favoriteApps.isEmpty()) return emptyList()
        if (order.isEmpty()) return favoriteApps.sortedBy { it.displayName.lowercase() }

        val appMap = favoriteApps.associateBy { it.componentName }
        val orderedApps = order.distinct().mapNotNull { appMap[it] }
        val remainingApps = favoriteApps.filter { it.componentName !in order }
            .sortedBy { it.displayName.lowercase() }

        return orderedApps + remainingApps
    }

    override suspend fun purgeRepository() {
        orderState.value = emptyList()
        savedOrder = null
        saveOrderCallCount = 0
    }
}