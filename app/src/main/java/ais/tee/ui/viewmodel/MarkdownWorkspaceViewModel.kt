package ais.tee.ui.viewmodel

import ais.tee.data.document.DocumentDiagnostics
import ais.tee.data.document.LineEnding
import ais.tee.data.document.MarkdownDocumentFileAccess
import ais.tee.data.document.MarkdownWorkspaceRecoverySnapshot
import ais.tee.data.document.MarkdownWorkspaceRecoverySource
import ais.tee.data.document.MarkdownWorkspaceRecoveryStore
import ais.tee.data.document.TextDocument
import ais.tee.data.document.TextDocumentCodec
import ais.tee.data.skills.localSkillSourceDigest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val DEFAULT_MARKDOWN_NAME = "untitled.md"
private const val SHARED_MARKDOWN_NAME = "shared-text.md"
private const val RECOVERY_DEBOUNCE_MS = 650L
internal const val MAX_EDITABLE_MARKDOWN_CHARS = 1_000_000

sealed interface MarkdownWorkspaceOrigin {
    data class LocalSkill(
        val name: String,
        val sourceDigest: String = "",
    ) : MarkdownWorkspaceOrigin

    data class ProjectLibrary(
        val assetId: String,
        val revision: Long,
    ) : MarkdownWorkspaceOrigin
}

data class MarkdownWorkspaceUiState(
    val text: String = "",
    val hadUtf8Bom: Boolean = false,
    val displayName: String = DEFAULT_MARKDOWN_NAME,
    val isDirty: Boolean = false,
    val revision: Long = 0L,
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val isRecoveryLoading: Boolean = false,
    val openMarkdownRequestId: Long = 0L,
    val origin: MarkdownWorkspaceOrigin? = null,
) {
    val isBusy: Boolean
        get() = isRecoveryLoading || isExporting || isImporting

    val canChangeUtf8Bom: Boolean
        get() = !isBusy && origin == null

    val isLargeDocumentReadOnly: Boolean
        get() = text.length > MAX_EDITABLE_MARKDOWN_CHARS

    val isEditorLocked: Boolean
        get() = isRecoveryLoading || isImporting || isLargeDocumentReadOnly
}

data class MarkdownExportSnapshot(
    val operationId: Long,
    val revision: Long,
    val document: TextDocument,
    val displayName: String,
    val origin: MarkdownWorkspaceOrigin?,
)

enum class ExternalMarkdownOpenResult {
    OPENED,
    NEEDS_DISCARD,
    BUSY,
    TOO_LARGE,
}

class MarkdownWorkspaceViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MarkdownWorkspaceUiState())
    val uiState: StateFlow<MarkdownWorkspaceUiState> = _uiState.asStateFlow()

    private var recoveryStore: MarkdownWorkspaceRecoveryStore? = null
    private var recoveryLoadJob: Job? = null
    private var recoveryJob: Job? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var nextExportOperationId = 1L
    private var activeExportOperationId: Long? = null
    private var nextOpenMarkdownRequestId = 1L

    private val recoveryLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    flushRecoveryNow()
                } catch (_: IOException) {
                    // Local recovery is best-effort; stopping the activity must remain safe.
                }
            }
        }
    }

    internal fun attachRecoveryStore(store: MarkdownWorkspaceRecoveryStore) {
        val shouldAttach = synchronized(this) {
            if (recoveryStore != null) {
                false
            } else {
                recoveryStore = store
                _uiState.value = _uiState.value.copy(isRecoveryLoading = true)
                true
            }
        }
        if (!shouldAttach) return

        recoveryLoadJob = viewModelScope.launch {
            val result = runCatching { store.load() }
            val failure = result.exceptionOrNull()
            if (failure is CancellationException) throw failure
            val snapshot = result.getOrNull()
            synchronized(this@MarkdownWorkspaceViewModel) {
                val state = _uiState.value
                _uiState.value = if (snapshot != null && state.isPristineForRecovery()) {
                    recoveredState(snapshot)
                } else {
                    state.copy(isRecoveryLoading = false)
                }
            }
        }
    }

    internal fun attachLifecycle(owner: LifecycleOwner) {
        synchronized(this) {
            if (lifecycleOwner === owner) return
            lifecycleOwner?.lifecycle?.removeObserver(recoveryLifecycleObserver)
            lifecycleOwner = owner
            owner.lifecycle.addObserver(recoveryLifecycleObserver)
        }
    }

    internal suspend fun flushRecoveryNow() = withContext(NonCancellable) {
        val store = recoveryStore ?: return@withContext
        recoveryLoadJob?.join()
        if (_uiState.value.isRecoveryLoading) return@withContext
        recoveryJob?.cancelAndJoin()
        store.save(recoverySnapshot())
    }

    fun newDocument() {
        val changed = synchronized(this) {
            val state = _uiState.value
            if (state.isBusy) return@synchronized false
            _uiState.value = MarkdownWorkspaceUiState(revision = state.revision + 1)
            true
        }
        if (changed) scheduleRecovery(delayMs = 0L)
    }

    fun beginImport(): Boolean = synchronized(this) {
        val state = _uiState.value
        if (state.isBusy) return@synchronized false
        _uiState.value = state.copy(isImporting = true)
        true
    }

    fun cancelImport() {
        _uiState.update { state ->
            if (state.isImporting) state.copy(isImporting = false) else state
        }
    }

    fun completeImport(displayName: String, document: TextDocument): Boolean {
        val changed = synchronized(this) {
            val state = _uiState.value
            if (!state.isImporting) return@synchronized false
            _uiState.value = MarkdownWorkspaceUiState(
                text = document.text,
                hadUtf8Bom = document.hadUtf8Bom,
                displayName = MarkdownDocumentFileAccess.normalizeDisplayName(displayName),
                isDirty = false,
                revision = state.revision + 1,
            )
            true
        }
        if (changed) scheduleRecovery(delayMs = 0L)
        return changed
    }

    fun openExternalText(
        text: String,
        displayName: String = SHARED_MARKDOWN_NAME,
        allowDiscardDirty: Boolean = false,
        origin: MarkdownWorkspaceOrigin? = null,
        markDirty: Boolean = true,
    ): ExternalMarkdownOpenResult {
        if (text.encodeToByteArray().size > MarkdownDocumentFileAccess.MAX_DOCUMENT_BYTES) {
            return ExternalMarkdownOpenResult.TOO_LARGE
        }
        val resolvedOrigin = when (origin) {
            is MarkdownWorkspaceOrigin.LocalSkill -> {
                if (origin.sourceDigest.isBlank()) {
                    origin.copy(sourceDigest = localSkillSourceDigest(text))
                } else {
                    origin
                }
            }
            is MarkdownWorkspaceOrigin.ProjectLibrary -> origin
            null -> null
        }

        val result = synchronized(this) {
            val state = _uiState.value
            when {
                state.isBusy -> ExternalMarkdownOpenResult.BUSY
                state.isDirty && !allowDiscardDirty -> ExternalMarkdownOpenResult.NEEDS_DISCARD
                else -> {
                    _uiState.value = MarkdownWorkspaceUiState(
                        text = text,
                        displayName = MarkdownDocumentFileAccess.normalizeDisplayName(displayName),
                        isDirty = markDirty,
                        revision = state.revision + 1,
                        openMarkdownRequestId = nextOpenMarkdownRequestId++,
                        origin = resolvedOrigin,
                    )
                    ExternalMarkdownOpenResult.OPENED
                }
            }
        }
        if (result == ExternalMarkdownOpenResult.OPENED) scheduleRecovery(delayMs = 0L)
        return result
    }

    fun consumeOpenMarkdownRequest(requestId: Long): Boolean = synchronized(this) {
        val state = _uiState.value
        if (requestId <= 0L || state.openMarkdownRequestId != requestId) return@synchronized false
        _uiState.value = state.copy(openMarkdownRequestId = 0L)
        true
    }

    fun updateText(text: String): Boolean {
        var changed = false
        _uiState.update { state ->
            if (
                state.isEditorLocked ||
                state.text == text ||
                text.length > MAX_EDITABLE_MARKDOWN_CHARS
            ) {
                state
            } else {
                changed = true
                state.copy(text = text, isDirty = true, revision = state.revision + 1)
            }
        }
        if (changed) scheduleRecovery()
        return changed
    }

    fun setUtf8Bom(include: Boolean): Boolean {
        var changed = false
        _uiState.update { state ->
            if (!state.canChangeUtf8Bom || state.hadUtf8Bom == include) {
                state
            } else {
                changed = true
                state.copy(
                    hadUtf8Bom = include,
                    isDirty = true,
                    revision = state.revision + 1,
                )
            }
        }
        if (changed) scheduleRecovery()
        return changed
    }

    fun currentDocument(): TextDocument = documentFrom(_uiState.value)

    fun normalizeLineEndings(target: LineEnding): Boolean {
        val before = _uiState.value
        if (before.isEditorLocked) return false
        val repaired = DocumentDiagnostics.repair(documentFrom(before), normalizeTo = target)
        if (repaired.document.text == before.text) return false
        if (repaired.document.text.length > MAX_EDITABLE_MARKDOWN_CHARS) return false
        var changed = false
        _uiState.update { state ->
            if (state.revision != before.revision || state.isEditorLocked) {
                state
            } else {
                changed = true
                state.copy(
                    text = repaired.document.text,
                    isDirty = true,
                    revision = state.revision + 1,
                )
            }
        }
        if (changed) scheduleRecovery()
        return changed
    }

    fun beginExport(): MarkdownExportSnapshot? = synchronized(this) {
        val state = _uiState.value
        if (state.isBusy) return@synchronized null
        val operationId = nextExportOperationId++
        activeExportOperationId = operationId
        val snapshot = MarkdownExportSnapshot(
            operationId = operationId,
            revision = state.revision,
            document = documentFrom(state),
            displayName = state.displayName,
            origin = state.origin,
        )
        _uiState.value = state.copy(isExporting = true)
        snapshot
    }

    fun completeExport(snapshot: MarkdownExportSnapshot, displayName: String): Boolean {
        val stillCurrent = synchronized(this) {
            val state = _uiState.value
            if (!state.isExporting || activeExportOperationId != snapshot.operationId) return@synchronized false
            activeExportOperationId = null
            val current = state.revision == snapshot.revision
            _uiState.value = when {
                !current -> state.copy(isExporting = false)
                snapshot.origin != null -> state.copy(isExporting = false)
                else -> state.copy(
                    displayName = MarkdownDocumentFileAccess.normalizeDisplayName(displayName),
                    isDirty = false,
                    isExporting = false,
                )
            }
            current
        }
        if (stillCurrent) scheduleRecovery(delayMs = 0L)
        return stillCurrent
    }

    fun completeSourceSave(
        snapshot: MarkdownExportSnapshot,
        persistedOrigin: MarkdownWorkspaceOrigin,
    ): Boolean {
        val stillCurrent = synchronized(this) {
            val state = _uiState.value
            if (!state.isExporting || activeExportOperationId != snapshot.operationId) return@synchronized false
            activeExportOperationId = null
            val sameSource = snapshot.origin != null && state.origin == snapshot.origin
            val current = sameSource && state.revision == snapshot.revision
            _uiState.value = if (sameSource) {
                state.copy(
                    origin = persistedOrigin,
                    isDirty = !current,
                    isExporting = false,
                )
            } else {
                state.copy(isExporting = false)
            }
            current
        }
        scheduleRecovery(delayMs = 0L)
        return stillCurrent
    }

    fun failExport(snapshot: MarkdownExportSnapshot) {
        synchronized(this) {
            if (activeExportOperationId != snapshot.operationId) return
            activeExportOperationId = null
            _uiState.update { state ->
                if (state.isExporting) state.copy(isExporting = false) else state
            }
        }
    }

    fun recoverySnapshot(): MarkdownWorkspaceRecoverySnapshot {
        val state = _uiState.value
        val source = when (val origin = state.origin) {
            is MarkdownWorkspaceOrigin.LocalSkill -> MarkdownWorkspaceRecoverySource(
                localSkillName = origin.name,
                sourceDigest = origin.sourceDigest,
            )
            // Recovered Library drafts are deliberately detached from their source. A stale
            // process-recovery snapshot must never regain permission to overwrite a newer revision.
            is MarkdownWorkspaceOrigin.ProjectLibrary -> null
            null -> null
        }
        return MarkdownWorkspaceRecoverySnapshot(
            text = state.text,
            hadUtf8Bom = state.hadUtf8Bom,
            displayName = state.displayName,
            isDirty = state.isDirty,
            source = source,
        )
    }

    fun restoreRecovery(snapshot: MarkdownWorkspaceRecoverySnapshot): Boolean = synchronized(this) {
        if (!_uiState.value.isPristineForRecovery(includeLoading = false)) return@synchronized false
        _uiState.value = recoveredState(snapshot)
        true
    }

    override fun onCleared() {
        synchronized(this) {
            lifecycleOwner?.lifecycle?.removeObserver(recoveryLifecycleObserver)
            lifecycleOwner = null
        }
        super.onCleared()
    }

    private fun scheduleRecovery(delayMs: Long = RECOVERY_DEBOUNCE_MS) {
        val store = recoveryStore ?: return
        if (_uiState.value.isRecoveryLoading) return
        recoveryJob?.cancel()
        recoveryJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            val snapshot = recoverySnapshot()
            val result = runCatching { store.save(snapshot) }
            val failure = result.exceptionOrNull()
            if (failure is CancellationException) throw failure
        }
    }

    private fun MarkdownWorkspaceUiState.isPristineForRecovery(includeLoading: Boolean = true): Boolean =
        text.isEmpty() &&
            !hadUtf8Bom &&
            displayName == DEFAULT_MARKDOWN_NAME &&
            !isDirty &&
            revision == 0L &&
            !isExporting &&
            !isImporting &&
            openMarkdownRequestId == 0L &&
            origin == null &&
            (includeLoading || !isRecoveryLoading)

    private fun recoveredState(snapshot: MarkdownWorkspaceRecoverySnapshot): MarkdownWorkspaceUiState =
        MarkdownWorkspaceUiState(
            text = snapshot.text,
            hadUtf8Bom = snapshot.hadUtf8Bom,
            displayName = MarkdownDocumentFileAccess.normalizeDisplayName(
                snapshot.displayName,
                DEFAULT_MARKDOWN_NAME,
            ),
            isDirty = snapshot.isDirty,
            revision = 1L,
            isRecoveryLoading = false,
            origin = snapshot.source?.let { source ->
                MarkdownWorkspaceOrigin.LocalSkill(
                    name = source.localSkillName,
                    sourceDigest = source.sourceDigest,
                )
            },
        )

    private fun documentFrom(state: MarkdownWorkspaceUiState): TextDocument = TextDocument(
        text = state.text,
        hadUtf8Bom = state.hadUtf8Bom,
        lineEndings = TextDocumentCodec.detectLineEndings(state.text),
    )
}
