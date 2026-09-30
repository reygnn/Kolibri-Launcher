package com.github.reygnn.launcher.feature.backup.di

import com.github.reygnn.launcher.feature.backup.engine.LegacyFormatReader
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Declares the (possibly empty) set of [LegacyFormatReader]s. Kolibri contributes one
 * from :kolibri:backup-legacy until its sunset; Nyx contributes none. With an empty set,
 * a pre-E5a backup reads as BackupRead.OutdatedFormat — the targeted message stays even
 * after the legacy module is gone.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupEngineModule {
    @Multibinds
    abstract fun legacyFormatReaders(): Set<LegacyFormatReader>
}
