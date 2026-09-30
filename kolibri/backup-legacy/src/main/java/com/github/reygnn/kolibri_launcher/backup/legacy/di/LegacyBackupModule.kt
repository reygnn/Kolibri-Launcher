package com.github.reygnn.kolibri_launcher.backup.legacy.di

import com.github.reygnn.kolibri_launcher.backup.legacy.KolibriLegacyFormatReader
import com.github.reygnn.launcher.feature.backup.engine.LegacyFormatReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes Kolibri's legacy reader to the engine's set — until the module's sunset. */
@Module
@InstallIn(SingletonComponent::class)
abstract class LegacyBackupModule {
    @Binds
    @IntoSet
    abstract fun bindKolibriLegacyReader(reader: KolibriLegacyFormatReader): LegacyFormatReader
}
