package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderIdFactory
import java.util.UUID
import javax.inject.Inject

/** Production [DrawerFolderIdFactory]: random UUIDs. Tests inject a deterministic stub. */
class UuidDrawerFolderIdFactory @Inject constructor() : DrawerFolderIdFactory {
    override fun next(): DrawerFolderId = DrawerFolderId(UUID.randomUUID().toString())
}
