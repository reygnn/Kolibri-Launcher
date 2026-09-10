package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.nyx_launcher.home.model.HomeLayout

/** Configurable [LayoutSerializer] double for use-case tests (no real JSON). */
class FakeLayoutSerializer(
    private val onSerialize: (HomeLayout) -> String = { "serialized" },
    private val onDeserialize: (String) -> HomeLayout? = { null },
) : LayoutSerializer {
    override fun serialize(layout: HomeLayout): String = onSerialize(layout)
    override fun deserialize(raw: String): HomeLayout? = onDeserialize(raw)
}
