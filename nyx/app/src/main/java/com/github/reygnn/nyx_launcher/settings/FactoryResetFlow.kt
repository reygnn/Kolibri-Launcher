package com.github.reygnn.nyx_launcher.settings

import androidx.annotation.StringRes
import com.github.reygnn.nyx_launcher.R

/**
 * PURE ORCHESTRATION of the settings' factory reset (2b-4c, step 3), without Android: reset,
 * seed the defaults again, pick the message.
 *
 * R2 (amended 30.09.): seed after EVERY reset, also an incomplete one — with per-store isolation a
 * reset that only seeded on success could leave an empty home screen behind. Safe because each
 * data key and its seed flag go in the same edit: a store whose purge failed still has its flag,
 * and seeding leaves it alone.
 *
 * @return the message to show (a string resource id): complete, or incomplete (S3). Not annotated
 *   `@StringRes`: a suspend function returns `Object` on the JVM, and Lint rejects the annotation
 *   there (SupportAnnotationUsage); [factoryResetMessage] carries it.
 */
suspend fun performFactoryReset(reset: suspend () -> Boolean, seedDefaults: suspend () -> Unit): Int {
    val complete = reset()
    seedDefaults()
    return factoryResetMessage(complete)
}

/** S3: an incomplete reset says so instead of claiming success. */
@StringRes
fun factoryResetMessage(complete: Boolean): Int =
    if (complete) R.string.factory_reset_done else R.string.factory_reset_incomplete
