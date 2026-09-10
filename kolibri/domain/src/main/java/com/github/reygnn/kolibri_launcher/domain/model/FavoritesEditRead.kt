package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Outcome of reading the current favorites for the EDIT-favorites editor
 * (DATASTORE_READ_SPEC Belang C). The fail-CLOSED sibling of the fail-open
 * `getFavoriteComponentsSnapshot()`: a read that fails must stay distinguishable
 * from "the user has no favorites", because the editor pre-selection feeds a
 * subsequent SAVE.
 *
 * The hazard mirrors [com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentReadResult]:
 * collapsing an unreadable store into an empty pre-selection looks harmless, but
 * a `saveFavoriteComponents(<empty>)` from that state would WIPE the user's real
 * favorites (AUDIT-13's deliberately-deferred edge). Keeping [Unavailable]
 * distinguishable lets the ViewModel save-gate do the only correct thing on an
 * unknown state: block the save (DSR-INV-4).
 *
 * Not thrown for I/O: cancellation still propagates, an [java.io.IOException]
 * surfaces as [Unavailable]; a genuine programmer error still propagates.
 */
sealed interface FavoritesEditRead {

    /** The store was read successfully; [components] is the current favorites set. */
    data class Loaded(val components: Set<String>) : FavoritesEditRead

    /**
     * The favorites could not be read (I/O). [cause] is the caught exception,
     * carried for optional caller-side logging, not for control flow — the
     * save-gate branches on the type, not the cause.
     */
    data class Unavailable(val cause: Throwable) : FavoritesEditRead
}
