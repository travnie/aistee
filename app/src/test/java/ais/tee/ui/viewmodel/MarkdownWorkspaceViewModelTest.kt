package ais.tee.ui.viewmodel

import ais.tee.data.document.LineEnding
import ais.tee.data.document.LineEndingStyle
import ais.tee.data.document.MarkdownDocumentFileAccess
import ais.tee.data.document.MarkdownWorkspaceRecoverySnapshot
import ais.tee.data.document.MarkdownWorkspaceRecoverySource
import ais.tee.data.document.TextDocument
import ais.tee.data.document.TextDocumentCodec
import ais.tee.data.skills.localSkillSourceDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownWorkspaceViewModelTest {
    private companion object {
        const val PROMPT_NAME = "prompt.md"
        const val EXTERNAL_TEXT = "shared text"
        const val LOCAL_SKILL_NAME = "release-checklist"
        const val SKILL_FILE_NAME = "SKILL.md"
        const val PROJECT_LIBRARY_ASSET_ID = "asset-1"
        const val EDITED_LIBRARY_TEXT = "edited"
        const val VERSION_TWO = "version two"
        const val FILL_TEXT = "a"
    }

    @Test
    fun editsStayLocalAndDeriveCurrentMetadataOnDemand() {
        val viewModel = MarkdownWorkspaceViewModel()
        val document = TextDocumentCodec.decodeUtf8("one\r\ntwo".encodeToByteArray())
        viewModel.completeImportAfterBegin(PROMPT_NAME, document)

        viewModel.updateText("one\r\ntwo\nthree")

        val state = viewModel.uiState.value
        assertTrue(state.isDirty)
        assertEquals("one\r\ntwo\nthree", state.text)
        assertEquals(LineEndingStyle.MIXED, viewModel.currentDocument().lineEndings.style)
    }

    @Test
    fun explicitNormalizationUpdatesDraftText() {
        val viewModel = MarkdownWorkspaceViewModel()
        val document = TextDocumentCodec.decodeUtf8("a\r\nb\nc".encodeToByteArray())
        viewModel.completeImportAfterBegin("mixed.md", document)

        viewModel.normalizeLineEndings(LineEnding.LF)

        assertTrue(viewModel.uiState.value.isDirty)
        assertEquals("a\nb\nc", viewModel.uiState.value.text)
        assertEquals(LineEndingStyle.LF, viewModel.currentDocument().lineEndings.style)
    }

    @Test
    fun docbenchRepairEditsBoundLibraryDraftWithoutSavingSource() {
        val viewModel = MarkdownWorkspaceViewModel()
        val origin = MarkdownWorkspaceOrigin.ProjectLibrary(PROJECT_LIBRARY_ASSET_ID, 4L)
        assertEquals(
            ExternalMarkdownOpenResult.OPENED,
            viewModel.openExternalText("~~~\ncode", PROMPT_NAME, origin = origin, markDirty = false),
        )
        val revision = viewModel.uiState.value.revision

        assertTrue(viewModel.applyTransformedText(revision, "~~~\ncode\n~~~"))
        assertEquals("~~~\ncode\n~~~", viewModel.uiState.value.text)
        assertTrue(viewModel.uiState.value.isDirty)
        assertEquals(origin, viewModel.uiState.value.origin)
        assertEquals(revision + 1, viewModel.uiState.value.revision)
    }

    @Test
    fun staleDocbenchPreviewDoesNotOverwriteNewerDraft() {
        val viewModel = MarkdownWorkspaceViewModel()
        assertTrue(viewModel.updateText("~~~\nold"))
        val revision = viewModel.uiState.value.revision
        assertTrue(viewModel.updateText("newer"))

        assertFalse(viewModel.applyTransformedText(revision, "~~~\nold\n~~~"))
        assertEquals("newer", viewModel.uiState.value.text)
    }

    @Test
    fun docbenchRepairCannotEditBusyOrOversizedDraft() {
        val viewModel = MarkdownWorkspaceViewModel()
        assertTrue(viewModel.updateText("~~~\ntext"))
        val revision = viewModel.uiState.value.revision
        val snapshot = requireNotNull(viewModel.beginExport())
        assertFalse(viewModel.applyTransformedText(revision, "~~~\ntext\n~~~"))
        viewModel.failExport(snapshot)
        assertFalse(viewModel.applyTransformedText(revision, "x".repeat(MAX_EDITABLE_MARKDOWN_CHARS + 1)))
        assertFalse(viewModel.applyTransformedText(revision, "~~~\ntext"))
    }

    @Test
    fun explicitUtf8BomChoiceUpdatesExportMetadata() {
        val viewModel = MarkdownWorkspaceViewModel()
        val document = TextDocumentCodec.decodeUtf8("# prompt".encodeToByteArray())
        viewModel.completeImportAfterBegin(PROMPT_NAME, document)

        assertTrue(viewModel.uiState.value.canChangeUtf8Bom)
        assertTrue(viewModel.setUtf8Bom(true))

        val state = viewModel.uiState.value
        assertTrue(state.hadUtf8Bom)
        assertTrue(state.isDirty)
        assertTrue(viewModel.currentDocument().hadUtf8Bom)

        val snapshot = requireNotNull(viewModel.beginExport())
        assertTrue(snapshot.document.hadUtf8Bom)
        viewModel.failExport(snapshot)
    }

    @Test
    fun boundLocalSkillDoesNotTreatBomAsPersistableSourceState() {
        val viewModel = boundSkillWorkspace()

        assertFalse(viewModel.uiState.value.canChangeUtf8Bom)
        assertFalse(viewModel.setUtf8Bom(true))

        val state = viewModel.uiState.value
        assertFalse(state.hadUtf8Bom)
        assertFalse(state.isDirty)
    }

    @Test
    fun successfulExportClearsDirtyFlagForMatchingRevision() {
        val viewModel = MarkdownWorkspaceViewModel()
        viewModel.updateText("# prompt")
        val snapshot = requireNotNull(viewModel.beginExport())

        assertTrue(viewModel.completeExport(snapshot, PROMPT_NAME))

        val state = viewModel.uiState.value
        assertFalse(state.isDirty)
        assertFalse(state.isExporting)
        assertEquals(PROMPT_NAME, state.displayName)
    }

    @Test
    fun exportCompletionDoesNotMarkNewerEditAsExported() {
        val viewModel = MarkdownWorkspaceViewModel()
        viewModel.updateText("version one")
        val snapshot = requireNotNull(viewModel.beginExport())
        viewModel.updateText(VERSION_TWO)

        assertFalse(viewModel.completeExport(snapshot, "snapshot.md"))

        val state = viewModel.uiState.value
        assertTrue(state.isDirty)
        assertFalse(state.isExporting)
        assertEquals(VERSION_TWO, state.text)
    }

    @Test
    fun staleExportCallbackCannotFinishReplacementExport() {
        val viewModel = MarkdownWorkspaceViewModel()
        viewModel.updateText("same revision")
        val first = requireNotNull(viewModel.beginExport())
        viewModel.failExport(first)
        val second = requireNotNull(viewModel.beginExport())

        assertFalse(viewModel.completeExport(first, "stale.md"))
        assertTrue(viewModel.uiState.value.isExporting)
        assertEquals("untitled.md", viewModel.uiState.value.displayName)

        assertTrue(viewModel.completeExport(second, "current.md"))
        assertFalse(viewModel.uiState.value.isExporting)
        assertEquals("current.md", viewModel.uiState.value.displayName)
    }

    @Test
    fun boundLocalSkillStartsCleanAndTracksSourceIdentity() {
        val viewModel = MarkdownWorkspaceViewModel()

        assertEquals(
            ExternalMarkdownOpenResult.OPENED,
            viewModel.openExternalText(
                text = EXTERNAL_TEXT,
                displayName = SKILL_FILE_NAME,
                origin = MarkdownWorkspaceOrigin.LocalSkill(LOCAL_SKILL_NAME),
                markDirty = false,
            ),
        )

        val state = viewModel.uiState.value
        assertFalse(state.isDirty)
        assertEquals(localSkillOrigin(EXTERNAL_TEXT), state.origin)
        assertEquals(SKILL_FILE_NAME, state.displayName)
    }

    @Test
    fun exportingBoundLocalSkillWritesCopyWithoutMarkingSourceSaved() {
        val viewModel = boundSkillWorkspace()
        viewModel.updateText("edited skill")
        val snapshot = requireNotNull(viewModel.beginExport())

        assertTrue(viewModel.completeExport(snapshot, "exported-skill.md"))

        val state = viewModel.uiState.value
        assertTrue(state.isDirty)
        assertFalse(state.isExporting)
        assertEquals(SKILL_FILE_NAME, state.displayName)
        assertEquals(localSkillOrigin(EXTERNAL_TEXT), state.origin)
    }

    @Test
    fun successfulBoundSourceSaveClearsDirtyAndAdvancesSourceDigest() {
        val viewModel = boundSkillWorkspace()
        val savedText = "edited skill"
        viewModel.updateText(savedText)
        val snapshot = requireNotNull(viewModel.beginExport())
        val persistedOrigin = localSkillOrigin(savedText)

        assertTrue(viewModel.completeSourceSave(snapshot, persistedOrigin))

        val state = viewModel.uiState.value
        assertFalse(state.isDirty)
        assertFalse(state.isExporting)
        assertEquals(persistedOrigin, state.origin)
    }

    @Test
    fun boundSourceSaveAdvancesBaselineWithoutClearingNewerEdit() {
        val viewModel = boundSkillWorkspace()
        val savedText = "version one"
        viewModel.updateText(savedText)
        val snapshot = requireNotNull(viewModel.beginExport())
        viewModel.updateText(VERSION_TWO)
        val persistedOrigin = localSkillOrigin(savedText)

        assertFalse(viewModel.completeSourceSave(snapshot, persistedOrigin))

        val state = viewModel.uiState.value
        assertTrue(state.isDirty)
        assertFalse(state.isExporting)
        assertEquals(VERSION_TWO, state.text)
        assertEquals(persistedOrigin, state.origin)
    }

    @Test
    fun importLocksEditsUntilResultOrCancel() {
        val viewModel = MarkdownWorkspaceViewModel()
        viewModel.updateText("keep me")
        assertTrue(viewModel.beginImport())

        assertFalse(viewModel.updateText("must not replace while picker is active"))
        assertEquals("keep me", viewModel.uiState.value.text)
        assertTrue(viewModel.uiState.value.isImporting)

        viewModel.cancelImport()
        assertTrue(viewModel.updateText("editable again"))
        assertEquals("editable again", viewModel.uiState.value.text)
    }

    @Test
    fun completedImportAtomicallyReplacesTheLockedWorkspace() {
        val viewModel = MarkdownWorkspaceViewModel()
        viewModel.updateText("old")
        val document = TextDocumentCodec.decodeUtf8("new\r\ntext".encodeToByteArray())

        assertTrue(viewModel.beginImport())
        assertTrue(viewModel.completeImport(PROMPT_NAME, document))

        val state = viewModel.uiState.value
        assertFalse(state.isDirty)
        assertFalse(state.isImporting)
        assertEquals("new\r\ntext", state.text)
        assertEquals(PROMPT_NAME, state.displayName)
        assertNull(state.origin)
    }

    @Test
    fun externalTextRequiresExplicitDiscardOfDirtyDraft() {
        val viewModel = MarkdownWorkspaceViewModel()
        viewModel.updateText("keep this draft")

        assertEquals(
            ExternalMarkdownOpenResult.NEEDS_DISCARD,
            viewModel.openExternalText(EXTERNAL_TEXT),
        )
        assertEquals("keep this draft", viewModel.uiState.value.text)

        assertEquals(
            ExternalMarkdownOpenResult.OPENED,
            viewModel.openExternalText(EXTERNAL_TEXT, allowDiscardDirty = true),
        )
        val state = viewModel.uiState.value
        assertEquals(EXTERNAL_TEXT, state.text)
        assertEquals("shared-text.md", state.displayName)
        assertTrue(state.isDirty)
        assertTrue(state.openMarkdownRequestId > 0L)
        assertNull(state.origin)
    }

    @Test
    fun externalTextOpenRequestIsConsumedOnlyOnce() {
        val viewModel = MarkdownWorkspaceViewModel()
        assertEquals(ExternalMarkdownOpenResult.OPENED, viewModel.openExternalText("selected text"))
        val requestId = viewModel.uiState.value.openMarkdownRequestId

        assertTrue(viewModel.consumeOpenMarkdownRequest(requestId))
        assertFalse(viewModel.consumeOpenMarkdownRequest(requestId))
        assertEquals(0L, viewModel.uiState.value.openMarkdownRequestId)
    }

    @Test
    fun oversizedExternalTextIsRejectedWithoutChangingTheDraft() {
        val viewModel = MarkdownWorkspaceViewModel()
        val oversized = FILL_TEXT.repeat(MarkdownDocumentFileAccess.MAX_DOCUMENT_BYTES + 1)

        assertEquals(ExternalMarkdownOpenResult.TOO_LARGE, viewModel.openExternalText(oversized))
        assertEquals("", viewModel.uiState.value.text)
        assertFalse(viewModel.uiState.value.isDirty)
    }

    @Test
    fun projectLibrarySourceSaveAdvancesBoundRevision() {
        val viewModel = MarkdownWorkspaceViewModel()
        assertEquals(
            ExternalMarkdownOpenResult.OPENED,
            viewModel.openExternalText(
                text = EXTERNAL_TEXT,
                displayName = PROMPT_NAME,
                origin = MarkdownWorkspaceOrigin.ProjectLibrary(assetId = PROJECT_LIBRARY_ASSET_ID, revision = 3L),
                markDirty = false,
            ),
        )
        assertTrue(viewModel.updateText(EDITED_LIBRARY_TEXT))

        val snapshot = requireNotNull(viewModel.beginExport())
        assertTrue(
            viewModel.completeSourceSave(
                snapshot = snapshot,
                persistedOrigin = MarkdownWorkspaceOrigin.ProjectLibrary(
                    assetId = PROJECT_LIBRARY_ASSET_ID,
                    revision = 4L,
                ),
            ),
        )

        assertFalse(viewModel.uiState.value.isDirty)
        assertEquals(
            MarkdownWorkspaceOrigin.ProjectLibrary(assetId = PROJECT_LIBRARY_ASSET_ID, revision = 4L),
            viewModel.uiState.value.origin,
        )
    }

    @Test
    fun androidDocumentImportAllowsExplicitSourceSaveAndUpdatesDigest() {
        val viewModel = MarkdownWorkspaceViewModel()
        val initial = TextDocumentCodec.decodeUtf8("""{"version":1}""".encodeToByteArray())
        val origin = MarkdownWorkspaceOrigin.AndroidDocument(
            uriString = "content://provider/file-1",
            sourceDigest = "old-sha256",
        )
        assertTrue(viewModel.beginImport())
        assertTrue(viewModel.completeImport("script.py", initial, origin))
        assertEquals(origin, viewModel.uiState.value.origin)
        assertFalse(viewModel.uiState.value.canChangeUtf8Bom)
        assertTrue(viewModel.updateText("print('hello')"))
        val snapshot = requireNotNull(viewModel.beginExport())
        val saved = origin.copy(sourceDigest = "new-sha256")
        assertTrue(viewModel.completeSourceSave(snapshot, saved))
        assertFalse(viewModel.uiState.value.isDirty)
        assertEquals(saved, viewModel.uiState.value.origin)
        assertEquals("script.py", viewModel.uiState.value.displayName)
        assertFalse(saved.toString().contains("provider"))
    }

    @Test
    fun androidDocumentRecoveryDetachesWriteBinding() {
        val viewModel = MarkdownWorkspaceViewModel()
        val origin = MarkdownWorkspaceOrigin.AndroidDocument("content://provider/file-2", "digest")
        assertTrue(viewModel.beginImport())
        assertTrue(viewModel.completeImport("config.json", TextDocumentCodec.decodeUtf8("{}".encodeToByteArray()), origin))
        assertTrue(viewModel.updateText("""{"new":true}"""))
        val snapshot = viewModel.recoverySnapshot()
        assertNull(snapshot.source)
        val restored = MarkdownWorkspaceViewModel()
        assertTrue(restored.restoreRecovery(snapshot))
        assertEquals("""{"new":true}""", restored.uiState.value.text)
        assertNull(restored.uiState.value.origin)
    }

    @Test
    fun projectLibraryRecoveryDetachesStaleSourceBinding() {
        val viewModel = MarkdownWorkspaceViewModel()
        assertEquals(
            ExternalMarkdownOpenResult.OPENED,
            viewModel.openExternalText(
                text = EXTERNAL_TEXT,
                displayName = PROMPT_NAME,
                origin = MarkdownWorkspaceOrigin.ProjectLibrary(assetId = PROJECT_LIBRARY_ASSET_ID, revision = 7L),
                markDirty = false,
            ),
        )
        assertTrue(viewModel.updateText(EDITED_LIBRARY_TEXT))

        val recovery = viewModel.recoverySnapshot()
        assertNull(recovery.source)

        val restored = MarkdownWorkspaceViewModel()
        assertTrue(restored.restoreRecovery(recovery))
        assertEquals(EDITED_LIBRARY_TEXT, restored.uiState.value.text)
        assertTrue(restored.uiState.value.isDirty)
        assertNull(restored.uiState.value.origin)
    }

    @Test
    fun recoveryRestoresOrdinaryMarkdownWithoutSourceIdentity() {
        val viewModel = MarkdownWorkspaceViewModel()
        val recovered = MarkdownWorkspaceRecoverySnapshot(
            text = "# recovered",
            hadUtf8Bom = true,
            displayName = PROMPT_NAME,
            isDirty = true,
        )

        assertTrue(viewModel.restoreRecovery(recovered))

        val state = viewModel.uiState.value
        assertEquals("# recovered", state.text)
        assertTrue(state.hadUtf8Bom)
        assertTrue(state.isDirty)
        assertEquals(PROMPT_NAME, state.displayName)
        assertNull(state.origin)
    }

    @Test
    fun recoveryRestoresBoundLocalSkillSourceIdentity() {
        val viewModel = MarkdownWorkspaceViewModel()
        val digest = localSkillSourceDigest(EXTERNAL_TEXT)
        val recovered = MarkdownWorkspaceRecoverySnapshot(
            text = EXTERNAL_TEXT,
            hadUtf8Bom = false,
            displayName = SKILL_FILE_NAME,
            isDirty = true,
            source = MarkdownWorkspaceRecoverySource(
                localSkillName = LOCAL_SKILL_NAME,
                sourceDigest = digest,
            ),
        )

        assertTrue(viewModel.restoreRecovery(recovered))

        assertEquals(
            MarkdownWorkspaceOrigin.LocalSkill(LOCAL_SKILL_NAME, digest),
            viewModel.uiState.value.origin,
        )
    }

    @Test
    fun interactiveEditsStopAtTheEditorThreshold() {
        val viewModel = MarkdownWorkspaceViewModel()
        val maximumInteractiveDraft = FILL_TEXT.repeat(MAX_EDITABLE_MARKDOWN_CHARS)

        assertTrue(viewModel.updateText(maximumInteractiveDraft))
        assertFalse(viewModel.updateText(maximumInteractiveDraft + "x"))
        assertEquals(MAX_EDITABLE_MARKDOWN_CHARS, viewModel.uiState.value.text.length)
    }

    @Test
    fun largeImportedDocumentsStayReadOnlyButExportable() {
        val viewModel = MarkdownWorkspaceViewModel()
        val largeText = FILL_TEXT.repeat(MAX_EDITABLE_MARKDOWN_CHARS + 1)
        val document = TextDocumentCodec.decodeUtf8(largeText.encodeToByteArray())

        viewModel.completeImportAfterBegin("large.md", document)

        assertTrue(viewModel.uiState.value.isLargeDocumentReadOnly)
        assertTrue(viewModel.uiState.value.isEditorLocked)
        assertFalse(viewModel.updateText("must not replace large import"))
        val export = requireNotNull(viewModel.beginExport())
        assertEquals(largeText, export.document.text)
        viewModel.failExport(export)
    }

    @Test
    fun providerDisplayNamesAreBoundedBeforeRecovery() {
        val viewModel = MarkdownWorkspaceViewModel()
        val oversizedName = "n".repeat(300_000) + ".markdown"
        val document = TextDocumentCodec.decodeUtf8("body".encodeToByteArray())

        viewModel.completeImportAfterBegin(oversizedName, document)

        val displayName = viewModel.uiState.value.displayName
        assertTrue(displayName.length <= MarkdownDocumentFileAccess.MAX_DISPLAY_NAME_CHARS)
        assertTrue(displayName.endsWith(".markdown"))
        assertEquals(displayName, viewModel.recoverySnapshot().displayName)
    }

    @Test
    fun missingProviderDisplayNameKeepsPickerFallback() {
        assertEquals(
            "picked-name.md",
            MarkdownDocumentFileAccess.resolveDisplayName(null, "picked-name.md"),
        )
    }

    @Test
    fun newDocumentDropsImportedContentOnlyWhenExplicitlyRequested() {
        val viewModel = boundSkillWorkspace()
        viewModel.updateText("changed")

        viewModel.newDocument()

        val state = viewModel.uiState.value
        assertEquals("", state.text)
        assertEquals("untitled.md", state.displayName)
        assertFalse(state.isDirty)
        assertNull(state.origin)
    }

    private fun localSkillOrigin(source: String): MarkdownWorkspaceOrigin.LocalSkill =
        MarkdownWorkspaceOrigin.LocalSkill(
            name = LOCAL_SKILL_NAME,
            sourceDigest = localSkillSourceDigest(source),
        )

    private fun boundSkillWorkspace(): MarkdownWorkspaceViewModel =
        MarkdownWorkspaceViewModel().also { viewModel ->
            assertEquals(
                ExternalMarkdownOpenResult.OPENED,
                viewModel.openExternalText(
                    text = EXTERNAL_TEXT,
                    displayName = SKILL_FILE_NAME,
                    origin = MarkdownWorkspaceOrigin.LocalSkill(LOCAL_SKILL_NAME),
                    markDirty = false,
                ),
            )
        }

    private fun MarkdownWorkspaceViewModel.completeImportAfterBegin(
        name: String,
        document: TextDocument,
    ) {
        assertTrue(beginImport())
        assertTrue(completeImport(name, document))
    }
}
