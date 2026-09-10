package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.nyx_launcher.home.model.HomeLayout

/**
 * Serializes the layout to/from an opaque string (JSON in `:data`). The seam that
 * both the DataStore repository and backup/restore share, so the format lives in
 * exactly one place. [deserialize] returns `null` on unparseable input.
 */
interface LayoutSerializer {
    fun serialize(layout: HomeLayout): String
    fun deserialize(raw: String): HomeLayout?
}
