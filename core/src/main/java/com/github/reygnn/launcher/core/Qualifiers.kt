package com.github.reygnn.launcher.core

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

/**
 * The installed-apps reload trigger [kotlinx.coroutines.flow.MutableSharedFlow]`<Unit>` shared
 * between the package-event producer and the installed-apps loader. Qualified so this
 * maximally-generic type can't be silently shared with (or collide against) any other
 * unqualified `MutableSharedFlow<Unit>` binding a future consumer might add.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppsUpdateTrigger
