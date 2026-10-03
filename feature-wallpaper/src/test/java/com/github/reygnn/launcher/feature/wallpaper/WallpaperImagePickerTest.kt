package com.github.reygnn.launcher.feature.wallpaper

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Test

/**
 * Pins W8 (3a-6): every wallpaper image is picked through `GetContent()` with the image MIME type
 * (any subtype). A silent "modernization" to `PickVisualMedia` would lose Downloads and file
 * managers again; one to
 * `OpenDocument` would bring back the persistable-permission question — needless today, because
 * the picked image is copied right away and only the internal copy is persisted.
 */
class WallpaperImagePickerTest {

    @Test
    fun the_contract_is_get_content() {
        val contract: ActivityResultContract<String, Uri?> = WallpaperImagePicker.contract()

        assertThat(contract).isInstanceOf(ActivityResultContracts.GetContent::class.java)
    }

    @Test
    fun launch_asks_for_images() {
        // A relaxed launcher stands in for the host's registered one; the argument is captured.
        val launcher = mockk<ActivityResultLauncher<String>>(relaxed = true)
        val input = slot<String>()

        WallpaperImagePicker.launch(launcher)

        verify(exactly = 1) { launcher.launch(capture(input)) }
        assertThat(input.captured).isEqualTo("image/*")
    }
}
