package com.github.reygnn.kolibri_launcher

/**
 * Ein Interface für androidTest Repositories, deren Zustand in Tests zurückgesetzt werden kann.
 * Dies ist Teil der App-Architektur, um Testbarkeit zu gewährleisten.
 */
interface Purgeable {
    fun purgeRepository()
}