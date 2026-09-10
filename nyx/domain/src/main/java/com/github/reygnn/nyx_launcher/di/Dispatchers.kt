package com.github.reygnn.nyx_launcher.di

import javax.inject.Qualifier

/** The CPU-bound default dispatcher (pure use-case work runs here). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** The IO dispatcher (DataStore, disk, PackageManager/LauncherApps in `:data`). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
