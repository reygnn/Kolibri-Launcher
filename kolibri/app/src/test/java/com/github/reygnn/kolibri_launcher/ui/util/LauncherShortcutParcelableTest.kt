package com.github.reygnn.kolibri_launcher.ui.util

import android.os.Parcel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trip tests for the hand-rolled `Parcelable` implementation. See
 * [AppInfoParcelableTest] for the rationale behind Robolectric here.
 *
 * Extra case vs. AppInfoParcelable: `shortLabel` is nullable, and
 * `Parcel.writeString` / `readString` round-trip null natively. Both states
 * (null + non-null) are pinned.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherShortcutParcelableTest {

    @Test
    fun `round-trip preserves all fields with non-null shortLabel`() {
        val original = LauncherShortcutParcelable(
            id = "shortcut-id-42",
            packageName = "com.example.app",
            shortLabel = "Compose Mail"
        )

        assertThat(roundTrip(original)).isEqualTo(original)
    }

    @Test
    fun `round-trip preserves null shortLabel as null (no sentinel-string drift)`() {
        val original = LauncherShortcutParcelable(
            id = "shortcut-id-42",
            packageName = "com.example.app",
            shortLabel = null
        )

        val restored = roundTrip(original)

        assertThat(restored).isEqualTo(original)
        assertThat(restored.shortLabel).isNull()
    }

    @Test
    fun `round-trip preserves empty shortLabel as empty string (not null)`() {
        val original = LauncherShortcutParcelable(
            id = "shortcut-id-42",
            packageName = "com.example.app",
            shortLabel = ""
        )

        val restored = roundTrip(original)

        assertThat(restored).isEqualTo(original)
        assertThat(restored.shortLabel).isEqualTo("")
        assertThat(restored.shortLabel).isNotNull()
    }

    @Test
    fun `describeContents returns 0 (no file descriptors)`() {
        val instance = LauncherShortcutParcelable(
            id = "x", packageName = "y", shortLabel = null
        )
        assertThat(instance.describeContents()).isEqualTo(0)
    }

    @Test
    fun `newArray returns an array of the requested size with null elements`() {
        val arr = LauncherShortcutParcelable.CREATOR.newArray(2)
        assertThat(arr).hasLength(2)
        assertThat(arr.toList()).containsExactly(null, null)
    }

    private fun roundTrip(original: LauncherShortcutParcelable): LauncherShortcutParcelable {
        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return LauncherShortcutParcelable.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
