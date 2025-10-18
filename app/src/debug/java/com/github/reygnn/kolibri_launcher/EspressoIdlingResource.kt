package com.github.reygnn.kolibri_launcher

import androidx.test.espresso.idling.CountingIdlingResource

/**
 * Ein globaler IdlingResource, der für Espresso-Tests verwendet wird.
 * Er teilt Espresso mit, wenn die App mit einer langwierigen Operation
 * (wie dem Diffing in einem ListAdapter) beschäftigt ist.
 *
 * WICHTIG: Dieser Code sollte nur im 'debug'-Build-Typ vorhanden sein
 * und durch ProGuard/R8 im 'release'-Build entfernt werden.
 */
object EspressoIdlingResource {

    private const val RESOURCE = "GLOBAL"

    @JvmField
    val countingIdlingResource = CountingIdlingResource(RESOURCE)

    fun increment() {
        countingIdlingResource.increment()
    }

    fun decrement() {
        if (!countingIdlingResource.isIdleNow) {
            countingIdlingResource.decrement()
        }
    }
}