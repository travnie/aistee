package ais.tee.data.engine

import android.content.Context
import ais.tee.data.model.CapabilityDecision
import ais.tee.data.model.MAX_NATIVE_TOOL_RESULT_CHARS
import ais.tee.data.model.NativeToolCall
import ais.tee.data.model.NativeToolDefinition
import ais.tee.data.model.NativeToolResult
import ais.tee.data.preferences.ActiveSkillsPreferencesStore
import ais.tee.data.skills.ACTIVE_SKILL_CHAT_INPUT_FIELD
import ais.tee.data.skills.ACTIVE_SKILL_MAX_CARDS_PER_MESSAGE
import ais.tee.data.skills.ActiveSkillBundle
import ais.tee.data.skills.ActiveSkillCard
import ais.tee.data.skills.ActiveSkillInvocationContext
import ais.tee.data.skills.ActiveSkillInvoker
import ais.tee.data.skills.ActiveSkillOutcome
import ais.tee.data.skills.ActiveSkillRunner
import ais.tee.data.skills.ActiveSkillToolAction
import ais.tee.data.skills.ActiveSkillToolPerformer
import ais.tee.data.skills.ActiveSkillToolValidation
import ais.tee.data.skills.AgentSkillManifest
import ais.tee.data.skills.LocalSkillLibraryStore
import ais.tee.data.skills.activeSkillBundleDigest
import ais.tee.data.skills.activeSkillChatInput
import ais.tee.data.skills.activeSkillChatToolDescription
import ais.tee.data.skills.activeSkillChatToolName
import ais.tee.data.skills.activeSkillDeclaration
import ais.tee.data.skills.activeSkillInvocationDecision
import ais.tee.data.skills.activeSkillUserInput
import ais.tee.data.skills.isActiveSkillTrusted
import ais.tee.data.skills.validateActiveSkillToolRequest
import ais.tee.security.QuickPrivacyModeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val MAX_TRANSCRIPT_INPUT_CHARS = 1_000

/** One step of a model-initiated skill call the user must answer in the chat. */
sealed interface ActiveSkillChatStage {
    /** The model wants to run the skill with exactly this input. */
    data class Run(val input: String) : ActiveSkillChatStage
    /** The skill asked for a native action; the chip performs it with an Activity context. */
    data class Action(val action: ActiveSkillToolAction) : ActiveSkillChatStage
    /** What would go back to the model; [cardTitle] is shown under the reply either way. */
    data class Result(val output: String, val isError: Boolean, val cardTitle: String? = null) : ActiveSkillChatStage
}

sealed interface ActiveSkillChatAnswer {
    data object Declined : ActiveSkillChatAnswer
    /** [actionOutcome] is set for an approved [ActiveSkillChatStage.Action]. */
    data class Approved(val actionOutcome: String? = null) : ActiveSkillChatAnswer
}

/**
 * Trusted, enabled, offline active skills offered to the model as function tools in a foreground
 * single-provider native chat (`docs/skills-runtime.md`). Every call asks the user first, shows the
 * exact input, confirms each native action and shows the result before it reaches the model.
 * With the active skills switch off nothing is read and no tool is offered.
 */
internal class NativeActiveSkillChatTools(
    context: Context,
    private val store: LocalSkillLibraryStore,
    private val ask: suspend (skillName: String, stage: ActiveSkillChatStage) -> ActiveSkillChatAnswer,
    private val preferences: ActiveSkillsPreferencesStore = ActiveSkillsPreferencesStore(context),
    private val runner: ActiveSkillRunner = ActiveSkillRunner(context, preferences),
) {
    private val appContext = context.applicationContext
    private val skillsByTool = LinkedHashMap<String, String>()
    private val notes = mutableListOf<String>()
    private val cards = mutableListOf<ActiveSkillCard>()

    /** Visible transcript lines for this send; stored with the reply, never exported. */
    val transcriptNotes: List<String> get() = synchronized(notes) { notes.toList() }

    /** Cards from skills that ran in this send, shown under the reply and never sent to the model. */
    val skillCards: List<ActiveSkillCard> get() = synchronized(cards) { cards.toList() }

    fun handles(toolName: String): Boolean = toolName in skillsByTool

    suspend fun definitions(): List<NativeToolDefinition> {
        skillsByTool.clear()
        if (!preferences.isEnabled() || quickPrivacyOn()) return emptyList()
        return withContext(Dispatchers.IO) {
            store.load().filter { it.enabled && it.hasActiveRuntime }.mapNotNull { summary ->
                runCatching {
                    val loaded = loadRunnable(summary.name) ?: return@runCatching null
                    val toolName = activeSkillChatToolName(summary.name)
                        ?.takeIf { it !in skillsByTool } ?: return@runCatching null
                    NativeToolDefinition(
                        name = toolName,
                        description = activeSkillChatToolDescription(summary.name, loaded.first.description),
                        inputSchema = inputSchema,
                    ).also { skillsByTool[toolName] = summary.name }
                }.getOrNull()
            }
        }
    }

    suspend fun execute(call: NativeToolCall): NativeToolResult {
        val skillName = skillsByTool[call.name] ?: return error(call, "Unknown skill.")
        val input = activeSkillChatInput(call.arguments)
            ?: return error(call, "Pass the skill input as one string field named '$ACTIVE_SKILL_CHAT_INPUT_FIELD'.")
        val (manifest, bundle) = withContext(Dispatchers.IO) { loadRunnable(skillName) }
            ?: return error(call, "This skill cannot run here.")

        if (ask(skillName, ActiveSkillChatStage.Run(input)) !is ActiveSkillChatAnswer.Approved) {
            note("Skill $skillName was not run. Input: ${input.take(MAX_TRANSCRIPT_INPUT_CHARS)}")
            return error(call, "The user declined to run this skill.")
        }
        note("Skill $skillName ran with input: ${input.take(MAX_TRANSCRIPT_INPUT_CHARS)}")
        val outcome = try {
            runner.run(
                invoker = ActiveSkillInvoker.MODEL,
                manifest = manifest,
                bundle = bundle,
                input = activeSkillUserInput(input),
                isIncognitoChat = false,
                isQuickPrivacyOn = quickPrivacyOn(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Shown to the user like any other skill failure; details stay out of the chat.
            ActiveSkillOutcome.Error("The skill could not run.")
        }
        val (output, isError) = when (outcome) {
            is ActiveSkillOutcome.Error -> outcome.message to true
            // Only the JSON result goes back; the card stays with the user.
            is ActiveSkillOutcome.Result -> outcome.result.toString() to false
            is ActiveSkillOutcome.ToolRequests -> performActions(skillName, outcome) to false
        }
        val card = (outcome as? ActiveSkillOutcome.Result)?.card
            ?.copy(skillName = skillName, bundleDigest = bundle.digest)
            ?.takeIf { addCard(it) }
        if (output.length > MAX_NATIVE_TOOL_RESULT_CHARS) {
            note("Skill $skillName result was too large to share.")
            return error(call, "The skill result is too large to return.")
        }
        if (ask(skillName, ActiveSkillChatStage.Result(output, isError, card?.title)) !is ActiveSkillChatAnswer.Approved) {
            note("Skill $skillName result was not shared.")
            return error(call, "The user chose not to share the skill result.")
        }
        return NativeToolResult(call.callId, call.name, output, isError)
    }

    private suspend fun performActions(skillName: String, outcome: ActiveSkillOutcome.ToolRequests): String {
        val outcomes = outcome.requests.map { request ->
            val message = when (val validation = validateActiveSkillToolRequest(request)) {
                is ActiveSkillToolValidation.Invalid -> validation.reason
                // Only side-effect-free tools (ALLOW) run without a prompt.
                is ActiveSkillToolValidation.Valid -> if (request.decision == CapabilityDecision.ALLOW) {
                    ActiveSkillToolPerformer.perform(appContext, validation.action)
                } else {
                    when (val answer = ask(skillName, ActiveSkillChatStage.Action(validation.action))) {
                        is ActiveSkillChatAnswer.Approved -> answer.actionOutcome ?: "Done."
                        ActiveSkillChatAnswer.Declined -> "Declined by the user."
                    }
                }
            }
            request.tool.id to message
        }
        return buildJsonObject {
            putJsonArray("actions") {
                outcomes.forEach { (tool, message) ->
                    add(buildJsonObject {
                        put("tool", tool)
                        put("outcome", message)
                    })
                }
            }
        }.toString()
    }

    /** Re-reads the skill so an edit after the tool list was built clears trust before it runs. */
    private suspend fun loadRunnable(skillName: String): Pair<AgentSkillManifest, ActiveSkillBundle>? {
        val manifest = store.read(skillName)?.manifest ?: return null
        val declaration = activeSkillDeclaration(manifest).declaration ?: return null
        val files = store.readBundleFiles(skillName) ?: return null
        val bundle = ActiveSkillBundle(skillName, activeSkillBundleDigest(files), files)
        val decision = activeSkillInvocationDecision(
            ActiveSkillInvoker.MODEL,
            declaration,
            ActiveSkillInvocationContext(
                featureEnabled = preferences.isEnabled(),
                executionTrusted = isActiveSkillTrusted(preferences.trustFor(skillName), skillName, bundle.digest, declaration),
                isIncognitoChat = false,
                isQuickPrivacyOn = quickPrivacyOn(),
            ),
        )
        return if (decision == CapabilityDecision.ALLOW) manifest to bundle else null
    }

    /** Read on every call so turning Quick privacy on mid-reply blocks the next skill call. */
    private fun quickPrivacyOn(): Boolean = QuickPrivacyModeStore.get(appContext).enabled.value

    private fun addCard(card: ActiveSkillCard): Boolean = synchronized(cards) {
        (cards.size < ACTIVE_SKILL_MAX_CARDS_PER_MESSAGE).also { if (it) cards += card }
    }

    private fun note(text: String) {
        synchronized(notes) { notes += text }
    }

    private fun error(call: NativeToolCall, message: String) =
        NativeToolResult(call.callId, call.name, message, isError = true)

    private companion object {
        val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put(ACTIVE_SKILL_CHAT_INPUT_FIELD, buildJsonObject {
                    put("type", "string")
                    put("description", "JSON or plain text input for the skill. The user sees it before the skill runs.")
                })
            })
            put("required", JsonArray(listOf(JsonPrimitive(ACTIVE_SKILL_CHAT_INPUT_FIELD))))
            put("additionalProperties", false)
        }
    }
}
