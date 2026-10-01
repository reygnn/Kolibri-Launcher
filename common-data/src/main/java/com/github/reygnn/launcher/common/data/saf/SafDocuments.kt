package com.github.reygnn.launcher.common.data.saf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.KolibriLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android half of a backup to or from a document the user picked (SAF), shared by both
 * apps (SPEC_NYX_REWRITE 2b-4a). The plain-JVM half lives in `:feature-backup`:
 * `writeOrDiscard` takes [openOutput] and [discard] as its lambdas, `BackupEngine.readStaged`
 * takes [openInput] and [declaredSize].
 *
 * Every operation accepts only `content://` and `file://` ([documentUri]); anything else is
 * refused before the content resolver is asked at all (D1).
 */
@Singleton
class SafDocuments @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * The document URI for [uriString], or [InvalidDocumentLocationException] naming why not —
     * the app turns the [InvalidDocumentLocationException.reason] into its own text (D2).
     */
    fun documentUri(uriString: String): Uri {
        if (uriString.isBlank()) throw InvalidDocumentLocationException(InvalidDocumentLocationException.Reason.BLANK, null)
        val uri = try {
            uriString.toUri()
        } catch (e: IllegalArgumentException) {
            throw InvalidDocumentLocationException(InvalidDocumentLocationException.Reason.MALFORMED, null, e)
        }
        val scheme = uri.scheme
        if (scheme != AppConstants.SCHEME_CONTENT && scheme != AppConstants.SCHEME_FILE) {
            throw InvalidDocumentLocationException(InvalidDocumentLocationException.Reason.UNSUPPORTED_SCHEME, scheme)
        }
        return uri
    }

    fun openInput(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw DocumentUnavailableException("Cannot read from selected location")

    /** The output stream, or [DocumentUnavailableException] when the provider gives none (D3). */
    fun openOutput(uri: Uri): OutputStream =
        context.contentResolver.openOutputStream(uri) ?: throw DocumentUnavailableException("Cannot write to selected location")

    /**
     * Removes a half-written document (U3). A failure propagates to `writeOrDiscard`, which logs
     * it and never lets it mask the export's own failure.
     */
    fun discard(uri: Uri) {
        if (uri.scheme == AppConstants.SCHEME_FILE) {
            uri.path?.let { File(it).delete() }
        } else {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    /**
     * The document's size for the engine's archive cap, or [UNKNOWN_SIZE] when the provider
     * can't tell (no descriptor, `statSize == -1`, or a failing probe).
     */
    fun declaredSize(uri: Uri): Long = try {
        context.contentResolver.openFileDescriptor(uri, AppConstants.MODE_READ_ONLY)?.use { it.statSize } ?: UNKNOWN_SIZE
    } catch (e: Exception) {
        // No suspension point in this block — synchronous I/O only.
        // Exception sufficient (pure I/O probe, no allocation path → no Error).
        KolibriLog.w(e, "Could not determine the backup file size")
        UNKNOWN_SIZE
    }

    companion object {
        /** Same value as the engine's `UNKNOWN_SIZE`; `:common-data` does not depend on `:feature-backup`. */
        const val UNKNOWN_SIZE = -1L
    }
}

/** A location that is no `content://` or `file://` document (D2). */
class InvalidDocumentLocationException(
    val reason: Reason,
    /** The refused scheme for [Reason.UNSUPPORTED_SCHEME], else null. */
    val scheme: String?,
    cause: Throwable? = null,
) : IOException("Invalid document location: $reason${scheme?.let { " ($it)" } ?: ""}", cause) {
    enum class Reason { BLANK, MALFORMED, UNSUPPORTED_SCHEME }
}

/** The provider handed out no stream for the document (D3); [message] is the shared text. */
class DocumentUnavailableException(message: String) : IOException(message)
