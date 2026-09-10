package com.github.reygnn.kolibri_launcher.core

import javax.inject.Qualifier

/**
 * Hilt qualifier annotations for the four cross-cutting injection points
 * used across all layers: the three `CoroutineDispatcher`s (Default, IO,
 * Main) and the application-scoped `CoroutineScope`.
 *
 * Lives in `core/` rather than `di/` so that domain and data code can
 * reference the qualifiers without depending back into the di package
 * (which would cycle when the module split lands). The actual `@Provides`
 * bindings stay in `di/DispatcherModule.kt`.
 */

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
