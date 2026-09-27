package ais.tee.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ais.tee.data.engine.ActiveSkillChatAnswer
import ais.tee.data.engine.ActiveSkillChatStage
import ais.tee.data.skills.ActiveSkillToolPerformer
import ais.tee.ui.viewmodel.PendingActiveSkillPrompt

/**
 * Inline consent for a model-initiated skill call: the skill name and the exact input before it
 * runs, each native action before it happens, and the result before it goes back to the model.
 */
@Composable
internal fun ActiveSkillChatPromptChip(
    prompt: PendingActiveSkillPrompt,
    onAnswer: (ActiveSkillChatAnswer) -> Unit,
) {
    val context = LocalContext.current
    val stage = prompt.stage
    val (heading, body, allowLabel) = when (stage) {
        is ActiveSkillChatStage.Run -> Triple(
            "The model wants to run ${prompt.skillName} with this input:",
            stage.input.ifEmpty { "(empty)" },
            "Run once",
        )
        is ActiveSkillChatStage.Action -> Triple("${prompt.skillName} wants to…", describeAction(stage.action), "Allow once")
        is ActiveSkillChatStage.Result -> Triple(
            if (stage.isError) "${prompt.skillName} failed. Send this to the model?" else "Send this ${prompt.skillName} result to the model?",
            stage.output + (stage.cardTitle?.let { "\n\nCard \"$it\" stays with you, under the reply." } ?: ""),
            "Send",
        )
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .testTag("active_skill_chat_prompt")
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
            Text(heading, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(top = 4.dp, end = 8.dp)
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("active_skill_chat_prompt_body")
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { onAnswer(ActiveSkillChatAnswer.Declined) },
                    modifier = Modifier.testTag("btn_active_skill_chat_deny"),
                ) { Text(if (stage is ActiveSkillChatStage.Result) "Don't send" else "Deny") }
                TextButton(
                    onClick = {
                        // Actions start system screens, so they run here with the Activity context.
                        val outcome = (stage as? ActiveSkillChatStage.Action)
                            ?.let { ActiveSkillToolPerformer.perform(context, it.action) }
                        onAnswer(ActiveSkillChatAnswer.Approved(outcome))
                    },
                    modifier = Modifier.testTag("btn_active_skill_chat_allow"),
                ) { Text(allowLabel) }
            }
        }
    }
}
