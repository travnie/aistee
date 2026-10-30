package ais.tee.data.appfunctions

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionDeniedException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import ais.tee.data.engine.SystemAssistantTokenCountAction
import ais.tee.data.engine.SystemAssistantTokenCountResult
import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BuiltInBenchTool
import ais.tee.data.preferences.BuiltInBenchPreferencesStore
import ais.tee.security.AppLockStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Debug-only service. Release registration awaits supported-device verification. */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "AisteeAppFunctionService",
    appFunctionXmlFileName = "aistee_app_functions",
)
abstract class BaseAisteeAppFunctionService : AppFunctionService() {
    /**
     * Counts tokens locally in caller-supplied inline text using the o200k tokenizer.
     * Requires separate Aistee Privacy consent, an enabled Docbench tool and App lock off. Never reads
     * conversations, files, provider accounts or credentials. No network or text retention.
     *
     * @param text Nonblank, explicitly selected text, at most 20,000 UTF-16 characters.
     * @param userRequested True only for a fresh, explicit user request. This caller assertion
     * does not grant permission or override Aistee's persisted consent, tool or App lock policy.
     * @return Token count and encoding label only; never includes the supplied text.
     */
    @AppFunctionDeclaration(isEnabled = false, isDescribedByKDoc = true)
    suspend fun countTokens(text: String, userRequested: Boolean = false): AssistantTokenCount {
        return withContext(Dispatchers.IO) {
            val consent = SystemAssistantConsentStore(applicationContext)
            if (!consent.isEnabled() || AppLockStore.get(applicationContext).enabled.value) {
                throw AppFunctionDeniedException("Enable system assistant access in Privacy; App lock must be off.")
            }
            val result = SystemAssistantTokenCountAction.execute(
                text = text,
                systemAssistantEnabled = consent.isEnabled(),
                userInitiated = userRequested,
                enabledBenchTools = BuiltInBenchPreferencesStore(applicationContext).loadEnabledTools(),
                grantedPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT),
            )
            // Consent/tool revocation during tokenization discards the result.
            if (!consent.isEnabled() || AppLockStore.get(applicationContext).enabled.value ||
                BuiltInBenchTool.DOCBENCH_DOCUMENT !in
                BuiltInBenchPreferencesStore(applicationContext).loadEnabledTools()
            ) {
                throw AppFunctionDeniedException("System assistant access is disabled.")
            }
            when (result) {
                is SystemAssistantTokenCountResult.Count -> AssistantTokenCount(result.tokens, result.encoding)
                SystemAssistantTokenCountResult.Blocked ->
                    throw AppFunctionDeniedException("Explicit user request and enabled Docbench are required.")
                SystemAssistantTokenCountResult.InvalidInput ->
                    throw AppFunctionInvalidArgumentException("Supply 1 to 20,000 nonblank characters.")
            }
        }
    }
}

/** Local count; no caller content or identifiers. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AssistantTokenCount(
    /** Number of o200k tokens. */
    val tokens: Int,
    /** Bundled tokenizer's encoding label. */
    val encoding: String,
)
