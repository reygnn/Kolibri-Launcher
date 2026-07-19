package com.github.reygnn.kolibri_launcher.domain.repository

/**
 * Interface for repositories whose persisted state can be wiped.
 *
 * Drives the user-facing factory reset: [ResetRepository]'s implementation
 * iterates [purgeRepository] over its Purgeable children (and the contract
 * tests exercise the same path). This is a production reset contract, not a
 * test-only hook.
 */
interface Purgeable {
    suspend fun purgeRepository()
}