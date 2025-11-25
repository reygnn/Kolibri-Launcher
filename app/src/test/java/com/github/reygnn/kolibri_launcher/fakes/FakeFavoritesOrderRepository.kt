package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import kotlinx.coroutines.flow.MutableStateFlow

class FakeFavoritesOrderRepository : FavoritesOrderRepository {
    private val flow = MutableStateFlow(listOf<String>())

    var order: List<String>
        get() = flow.value
        set(value) {
            flow.value = value
        }

    override val favoriteComponentsOrderFlow = flow

    override suspend fun sortFavoriteComponents(favoriteApps: List<AppInfo>, order: List<String>) =
        favoriteApps

    override suspend fun saveOrder(orderedComponentNames: List<String>): Boolean {
        order = orderedComponentNames
        return true
    }

    override suspend fun purgeRepository() {
        order = emptyList()
    }
}