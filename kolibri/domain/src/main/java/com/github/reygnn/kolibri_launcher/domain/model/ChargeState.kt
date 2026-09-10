package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Mutually exclusive charge states shown next to the battery percentage on the home
 * screen. Derived from the battery Intent's status + plugged extras (there is no
 * public API for the OEM "battery protection" toggle, so [PROTECTED] is inferred from
 * the observable "plugged in but charge held" state).
 */
enum class ChargeState {
    /** On battery, or plugged with no special state — no indicator. */
    NONE,

    /** Actively charging (or full on the charger) — bolt indicator. */
    CHARGING,

    /**
     * Plugged in but the charge is being deliberately held (status NOT_CHARGING while
     * plugged): a charge limit / battery protection / adaptive-charging hold — shield
     * indicator. Not a read of the protection *setting*, but of the held state it
     * produces (e.g. capped at 80 %).
     */
    PROTECTED
}
