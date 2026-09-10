package com.github.reygnn.kolibri_launcher.ui.util

import android.os.Parcel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trip tests for the hand-rolled `Parcelable` implementation. Lives on
 * Robolectric because `android.os.Parcel` is an Android-SDK type with no
 * usable JVM stub — same reason `WallpaperRepositoryImplTest` uses it.
 *
 * These tests are the safety net for [AppInfoParcelable]'s manual write/read
 * pairing: if the field order in `writeToParcel` and `createFromParcel` ever
 * drifts apart, the round-trip assertion fails immediately.
 */
@RunWith(RobolectricTestRunner::class)
class AppInfoParcelableTest {

    @Test
    fun `round-trip preserves all fields when isFavorite is true`() {
        val original = AppInfoParcelable(
            originalName = "Original Display",
            displayName = "Custom Display",
            packageName = "com.example.app",
            className = "com.example.app.MainActivity",
            isFavorite = true
        )

        val restored = roundTrip(original)

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `round-trip preserves all fields when isFavorite is false`() {
        val original = AppInfoParcelable(
            originalName = "Original",
            displayName = "Display",
            packageName = "com.example.app",
            className = "com.example.app.Main",
            isFavorite = false
        )

        val restored = roundTrip(original)

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `round-trip reads back the isFavorite flag value, not just equality`() {
        val favorite = AppInfoParcelable(
            originalName = "X", displayName = "Y", packageName = "Z",
            className = "W", isFavorite = true
        )
        val nonFavorite = AppInfoParcelable(
            originalName = "X", displayName = "Y", packageName = "Z",
            className = "W", isFavorite = false
        )

        assertThat(roundTrip(favorite).isFavorite).isTrue()
        assertThat(roundTrip(nonFavorite).isFavorite).isFalse()
    }

    @Test
    fun `round-trip preserves empty strings`() {
        val original = AppInfoParcelable(
            originalName = "",
            displayName = "",
            packageName = "",
            className = "",
            isFavorite = false
        )

        assertThat(roundTrip(original)).isEqualTo(original)
    }

    @Test
    fun `describeContents returns 0 (no file descriptors)`() {
        val instance = AppInfoParcelable(
            originalName = "X", displayName = "Y", packageName = "Z",
            className = "W", isFavorite = false
        )
        assertThat(instance.describeContents()).isEqualTo(0)
    }

    @Test
    fun `newArray returns an array of the requested size with null elements`() {
        val arr = AppInfoParcelable.CREATOR.newArray(3)
        assertThat(arr).hasLength(3)
        assertThat(arr.toList()).containsExactly(null, null, null)
    }

    private fun roundTrip(original: AppInfoParcelable): AppInfoParcelable {
        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return AppInfoParcelable.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
