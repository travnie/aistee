package ais.tee.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedFileChooserTest {
    private companion object {
        const val PDF_MIME_TYPE = "application/pdf"
        const val FILE_MODE_OPEN = 0
        const val FILE_MODE_OPEN_MULTIPLE = 1
        const val FILE_MODE_OPEN_FOLDER = 2
        const val FILE_MODE_SAVE = 3
    }

    @Test
    fun acceptsEmptyWildcardExactAndFamilyMimeFilters() {
        assertTrue(fileChooserAcceptsMimeType(emptyArray(), null))
        assertTrue(fileChooserAcceptsMimeType(arrayOf("*/*"), null))
        assertTrue(fileChooserAcceptsMimeType(arrayOf(PDF_MIME_TYPE), PDF_MIME_TYPE))
        assertTrue(fileChooserAcceptsMimeType(arrayOf("image/*"), "image/png"))
        assertTrue(fileChooserAcceptsMimeType(arrayOf("audio/*"), "audio/mpeg"))
        assertTrue(fileChooserAcceptsMimeType(arrayOf("video/*"), "video/mp4"))
        assertTrue(
            fileChooserAcceptsMimeType(
                arrayOf("image/*, application/pdf"),
                "application/pdf; charset=binary"
            )
        )
    }

    @Test
    fun supportsExtensionFiltersWhenDisplayNameIsKnown() {
        assertTrue(
            fileChooserAcceptsMimeType(
                acceptTypes = arrayOf(".pdf"),
                actualMimeType = null,
                displayName = "report.PDF"
            )
        )
        assertFalse(
            fileChooserAcceptsMimeType(
                acceptTypes = arrayOf(".pdf"),
                actualMimeType = PDF_MIME_TYPE,
                displayName = "image.png"
            )
        )
    }

    @Test
    fun rejectsMismatchesAndUnknownTypes() {
        assertFalse(fileChooserAcceptsMimeType(arrayOf("image/*"), PDF_MIME_TYPE))
        assertFalse(fileChooserAcceptsMimeType(arrayOf(PDF_MIME_TYPE), null))
        assertFalse(fileChooserAcceptsMimeType(arrayOf("not-a-mime"), PDF_MIME_TYPE))
    }

    @Test
    fun usesIncomingMimeHintWhenResolverTypeIsMissingOrGeneric() {
        assertEquals(PDF_MIME_TYPE, sharedUploadMimeType(null, PDF_MIME_TYPE))
        assertEquals(PDF_MIME_TYPE, sharedUploadMimeType("application/octet-stream", PDF_MIME_TYPE))
        assertEquals(PDF_MIME_TYPE, sharedUploadMimeType("application/*", PDF_MIME_TYPE))
        assertEquals("image/png", sharedUploadMimeType("image/png", PDF_MIME_TYPE))
        assertNull(sharedUploadMimeType(null, null))
    }

    @Test
    fun stagedUploadsOnlyUseOpenModes() {
        assertTrue(fileChooserModeAllowsStagedUpload(FILE_MODE_OPEN))
        assertTrue(fileChooserModeAllowsStagedUpload(FILE_MODE_OPEN_MULTIPLE))
        assertFalse(fileChooserModeAllowsStagedUpload(FILE_MODE_OPEN_FOLDER))
        assertFalse(fileChooserModeAllowsStagedUpload(FILE_MODE_SAVE))
    }
}
