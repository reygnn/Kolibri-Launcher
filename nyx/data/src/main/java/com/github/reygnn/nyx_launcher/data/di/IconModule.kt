package com.github.reygnn.nyx_launcher.data.di

import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.data.icon.IconLoaderImpl
import com.github.reygnn.nyx_launcher.data.icon.IconSource
import com.github.reygnn.nyx_launcher.data.icon.LauncherAppsIconSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the hand-rolled [IconLoader] (one cache/process) and its [IconSource]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class IconModule {

    @Binds
    @Singleton
    abstract fun bindIconLoader(impl: IconLoaderImpl): IconLoader

    @Binds
    abstract fun bindIconSource(impl: LauncherAppsIconSource): IconSource
}
