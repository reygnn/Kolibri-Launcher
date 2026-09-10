package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import java.util.UUID
import javax.inject.Inject

/** Production [ItemIdFactory]: random UUIDs. Tests inject a deterministic stub. */
class UuidItemIdFactory @Inject constructor() : ItemIdFactory {
    override fun next(): ItemId = ItemId(UUID.randomUUID().toString())
}
