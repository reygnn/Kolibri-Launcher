package com.github.reygnn.kolibri_launcher

/**
 * Definiert die einmaligen Events, die vom HiddenAppsViewModel an die HiddenAppsActivity gesendet werden.
 */

sealed class HiddenAppsEvent {
    data class ShowError(val message: String) : HiddenAppsEvent()
    object FinishScreen : HiddenAppsEvent()
}
