package com.github.reygnn.launcher.common.data.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.FileDescriptor
import kotlin.test.assertFailsWith

/**
 * The Android half of SAF documents shared by both apps (2b-4a). D1: only `content://` and
 * `file://`, refused before the resolver is asked; D2: the reason of a refused location; D3:
 * a missing stream is its own exception. Robolectric for `Uri`.
 */
@RunWith(RobolectricTestRunner::class)
class SafDocumentsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val resolver = mockk<ContentResolver>()
    private val documents = SafDocuments(mockk<Context> { every { contentResolver } returns resolver })

    @Test
    fun content_and_file_documents_are_accepted() {
        assertThat(documents.documentUri("content://provider/doc").scheme).isEqualTo("content")
        assertThat(documents.documentUri("file:///tmp/backup.zip").scheme).isEqualTo("file")
    }

    @Test
    fun other_locations_are_refused_with_their_reason_and_never_reach_the_resolver() {
        val blank = assertFailsWith<InvalidDocumentLocationException> { documents.documentUri("  ") }
        assertThat(blank.reason).isEqualTo(InvalidDocumentLocationException.Reason.BLANK)

        val web = assertFailsWith<InvalidDocumentLocationException> { documents.documentUri("https://example.org/backup.zip") }
        assertThat(web.reason).isEqualTo(InvalidDocumentLocationException.Reason.UNSUPPORTED_SCHEME)
        assertThat(web.scheme).isEqualTo("https")

        val bare = assertFailsWith<InvalidDocumentLocationException> { documents.documentUri("backup.zip") }
        assertThat(bare.reason).isEqualTo(InvalidDocumentLocationException.Reason.UNSUPPORTED_SCHEME)
        assertThat(bare.scheme).isNull()

        verify(exactly = 0) { resolver.openInputStream(any()) }
        verify(exactly = 0) { resolver.openOutputStream(any()) }
    }

    @Test
    fun a_missing_stream_is_document_unavailable_with_the_shared_text() {
        val uri = Uri.parse("content://provider/doc")
        every { resolver.openInputStream(uri) } returns null
        every { resolver.openOutputStream(uri) } returns null

        assertThat(assertFailsWith<DocumentUnavailableException> { documents.openInput(uri) })
            .hasMessageThat().isEqualTo("Cannot read from selected location")
        assertThat(assertFailsWith<DocumentUnavailableException> { documents.openOutput(uri) })
            .hasMessageThat().isEqualTo("Cannot write to selected location")
    }

    @Test
    fun discarding_a_file_document_deletes_the_file() {
        val file = tmp.newFile("half.zip")

        documents.discard(Uri.fromFile(file))

        assertThat(file.exists()).isFalse()
    }

    @Test
    fun the_declared_size_is_the_providers_or_unknown() {
        val uri = Uri.parse("content://provider/doc")
        fun reporting(size: Long) = mockk<ParcelFileDescriptor>(relaxed = true) {
            every { statSize } returns size
            every { fileDescriptor } returns FileDescriptor()
        }

        every { resolver.openFileDescriptor(uri, any()) } returns reporting(4096L)
        assertThat(documents.declaredSize(uri)).isEqualTo(4096L)

        every { resolver.openFileDescriptor(uri, any()) } returns reporting(-1L) // provider can't tell
        assertThat(documents.declaredSize(uri)).isEqualTo(SafDocuments.UNKNOWN_SIZE)

        every { resolver.openFileDescriptor(uri, any()) } returns null
        assertThat(documents.declaredSize(uri)).isEqualTo(SafDocuments.UNKNOWN_SIZE)

        every { resolver.openFileDescriptor(uri, any()) } throws SecurityException("no access")
        assertThat(documents.declaredSize(uri)).isEqualTo(SafDocuments.UNKNOWN_SIZE)
    }
}
