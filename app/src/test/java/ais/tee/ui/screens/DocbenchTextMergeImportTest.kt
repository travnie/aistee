package ais.tee.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocbenchTextMergeImportTest {
    @Test
    fun acceptsProviderDeclaredMarkdownAndPlainText() {
        assertTrue(isDocbenchTextMergeCandidate("README", "text/markdown"))
        assertTrue(isDocbenchTextMergeCandidate("notes.anything", "text/plain; charset=utf-8"))
    }

    @Test
    fun octetStreamRequiresARecognizedTextExtension() {
        assertTrue(isDocbenchTextMergeCandidate("README.md", "application/octet-stream"))
        assertTrue(isDocbenchTextMergeCandidate("notes.TXT", "application/octet-stream"))
        assertFalse(isDocbenchTextMergeCandidate("data.json", "application/octet-stream"))
        assertFalse(isDocbenchTextMergeCandidate("image.png", "application/octet-stream"))
    }

    @Test
    fun unrelatedTypedDocumentsAreRejected() {
        assertFalse(isDocbenchTextMergeCandidate("data.json", "application/json"))
        assertFalse(isDocbenchTextMergeCandidate("page.xml", "application/xml"))
    }

    @Test
    fun syntheticNamesCannotAuthorizeAnUnknownDocumentType() {
        assertFalse(isDocbenchTextMergeCandidate("document.txt", null, hasProviderDisplayName = false))
        assertFalse(isDocbenchTextMergeCandidate("document.txt", "application/octet-stream", hasProviderDisplayName = false))
        assertTrue(isDocbenchTextMergeCandidate("document.txt", "text/plain", hasProviderDisplayName = false))
        assertTrue(isDocbenchTextMergeCandidate("document.txt", "text/markdown", hasProviderDisplayName = false))
        assertTrue(isDocbenchTextMergeCandidate("README.md", null, hasProviderDisplayName = true))
    }
}
