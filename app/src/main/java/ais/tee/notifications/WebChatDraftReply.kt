package ais.tee.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import ais.tee.R
import ais.tee.data.model.ConversationSurfaceCapability
import ais.tee.data.model.WebAiService
import ais.tee.data.model.conversationSurfaceCapabilities
import ais.tee.data.preferences.WebChatDraftStore
import ais.tee.navigation.AisteeQuickActionNavigation

internal const val WEB_CHAT_DRAFT_REPLY_RESULT_KEY = "web_chat_draft_reply"

internal object WebChatDraftReply {
    fun action(context: Context, service: WebAiService): NotificationCompat.Action? {
        if (!service.conversationSurfaceCapabilities().supports(ConversationSurfaceCapability.DRAFT_REPLY)) {
            return null
        }
        val appContext = context.applicationContext
        val launchIntent = AisteeQuickActionNavigation.webDraftReplyLaunchIntent(appContext, service.id)
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            service.id.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(WEB_CHAT_DRAFT_REPLY_RESULT_KEY)
            .setLabel(appContext.getString(R.string.notification_draft_reply_action))
            .build()
        return NotificationCompat.Action.Builder(
            R.drawable.ic_quick_settings,
            appContext.getString(R.string.notification_draft_reply_action),
            pendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()
    }

    fun replyText(intent: Intent): String? =
        normalizeDraftReplyText(
            RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(WEB_CHAT_DRAFT_REPLY_RESULT_KEY)
                ?.toString()
        )

    internal fun normalizeDraftReplyText(value: String?): String? =
        value
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { it.length <= WebChatDraftStore.MAX_DRAFT_CHARS }
}
