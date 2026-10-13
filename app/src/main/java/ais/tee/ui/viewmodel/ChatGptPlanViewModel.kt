package ais.tee.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ais.tee.data.security.ChatGptPlanCredentialStore
import ais.tee.data.security.ChatGptPlanSessions
import ais.tee.data.security.ChatGptPlanSignIn
import ais.tee.data.security.ChatGptPlanTokenExchange
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sign in with ChatGPT account management. Connecting stores a verified grant only; nothing
 * here selects the account-plan route or falls back to API-key billing.
 */
internal class ChatGptPlanViewModel(application: Application) : AndroidViewModel(application) {
    data class AccountRow(
        val clientId: String,
        val label: String,
        val connected: Boolean,
        val planUsageGranted: Boolean,
    )

    enum class Notice {
        CONNECTED, CONNECTED_WITHOUT_PLAN, CANCELLED, TIMED_OUT, FAILED, ACCOUNT_LIMIT,
        BROWSER_UNAVAILABLE, SIGNED_OUT, SIGNED_OUT_UNCONFIRMED, STORAGE_ERROR,
    }

    data class UiState(
        val accounts: List<AccountRow> = emptyList(),
        val signingIn: Boolean = false,
        val signingOut: String? = null,
        val notice: Notice? = null,
    )

    private val vault = ChatGptPlanCredentialStore(application)
    private val exchange = ChatGptPlanTokenExchange()
    private val sessions = ChatGptPlanSessions(vault, exchange)
    private val signIn = ChatGptPlanSignIn(vault, exchange)
    private var stored: Map<String, ChatGptPlanCredentialStore.Account> = emptyMap()

    @Volatile private var attempt: ChatGptPlanSignIn.Attempt? = null
    @Volatile private var cancelRequested = false

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    /**
     * [clientId] null adds an account; otherwise reauthorizes that registration. [launch] opens
     * the URL in a browser/Custom Tab on the main thread and reports whether one was opened.
     */
    fun signIn(clientId: String?, launch: (String) -> Boolean) {
        val state = _uiState.value
        if (state.signingIn || state.signingOut != null) return
        val account = clientId?.let { stored[it] ?: return }
        cancelRequested = false
        _uiState.update { it.copy(signingIn = true, notice = null) }
        viewModelScope.launch {
            var started: ChatGptPlanSignIn.Attempt? = null
            val notice = try {
                val current = withContext(Dispatchers.IO) { signIn.start(account) }
                started = current
                attempt = current
                when {
                    current == null -> Notice.ACCOUNT_LIMIT
                    cancelRequested -> Notice.CANCELLED
                    !launch(current.authorizationUrl) -> Notice.BROWSER_UNAVAILABLE
                    else -> withContext(Dispatchers.IO) { current.finish() }.toNotice()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                if (cancelRequested) Notice.CANCELLED else Notice.FAILED
            } catch (_: Exception) {
                Notice.FAILED
            } finally {
                started?.close()
                attempt = null
            }
            _uiState.update { it.copy(signingIn = false, notice = notice) }
            reload()
        }
    }

    /** Closes the loopback port; the pending [signIn] reports [Notice.CANCELLED]. */
    fun cancelSignIn() {
        cancelRequested = true
        attempt?.close()
    }

    fun signOut(clientId: String) {
        val state = _uiState.value
        if (state.signingIn || state.signingOut != null) return
        _uiState.update { it.copy(signingOut = clientId, notice = null) }
        viewModelScope.launch {
            val notice = try {
                when (withContext(Dispatchers.IO) { sessions.signOut(clientId) }) {
                    ChatGptPlanSessions.SignOut.REVOKED -> Notice.SIGNED_OUT
                    ChatGptPlanSessions.SignOut.CLEARED_UNCONFIRMED -> Notice.SIGNED_OUT_UNCONFIRMED
                    ChatGptPlanSessions.SignOut.NOT_CONNECTED -> null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Notice.STORAGE_ERROR
            }
            _uiState.update { it.copy(signingOut = null, notice = notice) }
            reload()
        }
    }

    override fun onCleared() {
        cancelRequested = true
        attempt?.close() // accept() ignores coroutine cancellation.
    }

    private suspend fun reload() {
        val accounts = try {
            withContext(Dispatchers.IO) { vault.accounts() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _uiState.update { it.copy(notice = Notice.STORAGE_ERROR) }
            return
        }
        stored = accounts.associateBy { it.clientId }
        _uiState.update { it.copy(accounts = accountRows(accounts)) }
    }

    private fun ChatGptPlanSignIn.Result.toNotice(): Notice = when (this) {
        ChatGptPlanSignIn.Result.CONNECTED -> Notice.CONNECTED
        ChatGptPlanSignIn.Result.CONNECTED_WITHOUT_PLAN -> Notice.CONNECTED_WITHOUT_PLAN
        ChatGptPlanSignIn.Result.CANCELLED -> Notice.CANCELLED
        ChatGptPlanSignIn.Result.TIMED_OUT -> Notice.TIMED_OUT
        ChatGptPlanSignIn.Result.FAILED -> Notice.FAILED
        ChatGptPlanSignIn.Result.ACCOUNT_LIMIT -> Notice.ACCOUNT_LIMIT
    }
}

/** Distinct, stable labels: same email across registrations gets a client-ID suffix. */
internal fun accountRows(accounts: List<ChatGptPlanCredentialStore.Account>): List<ChatGptPlanViewModel.AccountRow> {
    val base = accounts.map { it.email?.takeIf(String::isNotBlank) ?: "ChatGPT account" }
    return accounts.mapIndexed { index, account ->
        val label = if (base.count { it == base[index] } > 1) {
            "${base[index]} (…${account.clientId.takeLast(4)})"
        } else {
            base[index]
        }
        ChatGptPlanViewModel.AccountRow(account.clientId, label, account.connected, account.planUsageGranted)
    }
}
