package com.github.reygnn.nyx_launcher.home.model

/** Outcome of importing a backed-up layout. */
sealed interface ImportResult {
    data object Success : ImportResult
    data object InvalidData : ImportResult
}
