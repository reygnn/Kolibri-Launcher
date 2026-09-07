package com.github.reygnn.kolibri_launcher.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins how [BackupSerializer] parses the string-list settings arrays
 * (`favoriteComponents`, `favoritesOrder`, `hiddenComponents`) when a hand-edited
 * or corrupt backup mixes non-string junk into them.
 *
 * `validateJsonTypes` only checks that each of these fields IS a JSON array within
 * the size cap — NOT its element types. A mixed array therefore passes validation,
 * makes the kotlinx decode throw (`Set<String>`/`List<String>` vs a number/null/
 * object), and falls to the strict org.json path where `getStrictStringList` is the
 * OPERATIVE guard: `jsonArray.opt(i)` + `is String` keeps only the string elements.
 * The size-attack path is covered by BackupRepositoryImplSecurityTest; the
 * element-type filtering had no test. These lock it so a regression to `getString(i)`
 * (which would coerce `42` to "42" or throw on null) is caught.
 *
 * Not runnable on a plain JVM here (kotlinx-serialization + org.json); expected
 * results were derived from the parse flow (non-lenient Json, Set/List field types,
 * getStrictStringList's filter) and run under `:data:test`.
 */
class BackupSerializerComponentArrayTest {

    private val serializer = BackupSerializer()

    private fun backupJson(settingsBody: String): String = """
        {
          "version": "1.0.0",
          "timestamp": 0,
          "appVersion": "",
          "settings": { $settingsBody }
        }
    """.trimIndent()

    @Test
    fun `parse drops non-string and null elements from favoriteComponents (a Set)`() {
        val parsed = serializer.parseBackupData(
            backupJson(
                "\"favoriteComponents\": [\"com.a/A\", 42, null, {\"x\": 1}, \"com.b/B\"]",
            ),
        )

        assertThat(parsed).isNotNull()
        // Only the two String elements survive; the number, null and nested object
        // are silently filtered — not imported as junk components, not a crash.
        assertThat(parsed!!.settings.favoriteComponents)
            .containsExactly("com.a/A", "com.b/B")
    }

    @Test
    fun `parse drops non-string elements from hiddenComponents (a Set)`() {
        val parsed = serializer.parseBackupData(
            backupJson("\"hiddenComponents\": [true, \"com.hidden/H\", 7]"),
        )

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.settings.hiddenComponents).containsExactly("com.hidden/H")
    }

    @Test
    fun `parse keeps favoritesOrder as an ordered list with duplicates, filtering only the junk`() {
        // favoritesOrder is a List (NOT a Set like the other two): its order and any
        // duplicates are preserved, and only non-string elements are removed. This
        // pins the List-vs-Set distinction alongside the element filtering.
        val parsed = serializer.parseBackupData(
            backupJson(
                "\"favoritesOrder\": [\"com.b/B\", 42, \"com.a/A\", null, \"com.b/B\"]",
            ),
        )

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.settings.favoritesOrder)
            .containsExactly("com.b/B", "com.a/A", "com.b/B")
            .inOrder()
    }

    @Test
    fun `parse drops non-string customAppNames values (map), mirroring the arrays`() {
        // customAppNames is a MAP; its values had asymmetric handling vs the arrays —
        // getString(key) COERCED a non-string (123 -> "123") instead of dropping it, so a
        // type-confusion payload survived as a garbage name. The fix filters to String
        // values only: the numeric entry is dropped (app keeps its real name), the string
        // entry is kept. (kotlinx rejects the mixed map → strict org.json fallback applies.)
        val parsed = serializer.parseBackupData(
            backupJson("\"customAppNames\": {\"com.a/A\": 123, \"com.b/B\": \"Custom\"}"),
        )

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.settings.customAppNames).containsExactly("com.b/B", "Custom")
    }
}
