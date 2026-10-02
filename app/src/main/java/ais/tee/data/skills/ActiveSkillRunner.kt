package ais.tee.data.skills

import android.content.Context
import ais.tee.data.model.CapabilityDecision
import ais.tee.data.preferences.ActiveSkillsPreferencesStore
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.json.JsonElement

/** SHA-256 over every file (sorted path, size, bytes); any edit changes it and clears trust. */
internal fun activeSkillBundleDigest(files: Map<String, ByteArray>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    files.toSortedMap().forEach { (path, bytes) ->
        val name = path.toByteArray(Charsets.UTF_8)
        digest.update(name.size.toBigInteger().toByteArray())
        digest.update(name)
        digest.update(bytes.size.toBigInteger().toByteArray())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * The only entry point that executes a skill. It checks the feature flag, digest-bound trust
 * and the invocation policy before the sandbox sees anything; with the flag off nothing runs.
 */
internal data class ActiveSkillExecutionReport(
    val outcome: ActiveSkillOutcome,
    /** True only when the dedicated skills process replied, proving the sandbox process started. */
    val sandboxStarted: Boolean,
)

internal class ActiveSkillRunner(
    context: Context,
    private val preferences: ActiveSkillsPreferencesStore = ActiveSkillsPreferencesStore(context),
    private val sandbox: ActiveSkillSandbox = ActiveSkillSandbox(context),
) {
    suspend fun run(
        invoker: ActiveSkillInvoker,
        manifest: AgentSkillManifest,
        bundle: ActiveSkillBundle,
        input: JsonElement,
        isIncognitoChat: Boolean,
        isQuickPrivacyOn: Boolean,
    ): ActiveSkillOutcome =
        runWithReport(invoker, manifest, bundle, input, isIncognitoChat, isQuickPrivacyOn).outcome

    suspend fun runWithReport(
        invoker: ActiveSkillInvoker,
        manifest: AgentSkillManifest,
        bundle: ActiveSkillBundle,
        input: JsonElement,
        isIncognitoChat: Boolean,
        isQuickPrivacyOn: Boolean,
    ): ActiveSkillExecutionReport {
        val declaration = activeSkillDeclaration(manifest).declaration
            ?: return ActiveSkillExecutionReport(
                ActiveSkillOutcome.Error("This skill has no valid runtime declaration."),
                sandboxStarted = false,
            )
        if (bundle.skillName != manifest.name || bundle.digest != activeSkillBundleDigest(bundle.files)) {
            return ActiveSkillExecutionReport(
                ActiveSkillOutcome.Error("The skill files changed. Review and trust the skill again."),
                sandboxStarted = false,
            )
        }
        val decision = activeSkillInvocationDecision(
            invoker,
            declaration,
            ActiveSkillInvocationContext(
                featureEnabled = preferences.isEnabled(),
                executionTrusted = isActiveSkillTrusted(
                    preferences.trustFor(manifest.name),
                    manifest.name,
                    bundle.digest,
                    declaration,
                ),
                isIncognitoChat = isIncognitoChat,
                isQuickPrivacyOn = isQuickPrivacyOn,
            ),
        )
        if (decision != CapabilityDecision.ALLOW) {
            return ActiveSkillExecutionReport(
                ActiveSkillOutcome.Error("This skill cannot run here."),
                sandboxStarted = false,
            )
        }
        val request = activeSkillRequestJson(input, Locale.getDefault().toLanguageTag(), Instant.now().toString())
        val report = sandbox.runWithReport(bundle, declaration, request)
        return ActiveSkillExecutionReport(
            outcome = report.outcome,
            sandboxStarted = report.processId != null,
        )
    }
}
