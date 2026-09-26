package ais.tee.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import ais.tee.MainActivity
import ais.tee.ui.viewmodel.NavigationTab

/** Where a quick action (launcher shortcut, widget button) leads. */
internal sealed interface QuickActionDestination {
    data class Tab(val tab: NavigationTab) : QuickActionDestination
    data object NewNativeChat : QuickActionDestination
    data object ProjectLibrary : QuickActionDestination
}

internal object AisteeQuickActionNavigation {
    const val ACTION_OPEN_DESTINATION = "ais.tee.action.OPEN_DESTINATION"
    const val EXTRA_DESTINATION = "ais.tee.extra.DESTINATION"
    const val ACTION_OPEN_NATIVE_CONVERSATION = "ais.tee.action.OPEN_NATIVE_CONVERSATION"
    const val EXTRA_NATIVE_CONVERSATION_ID = "ais.tee.extra.NATIVE_CONVERSATION_ID"
    const val ACTION_OPEN_WEB_DRAFT_REPLY = "ais.tee.action.OPEN_WEB_DRAFT_REPLY"
    const val EXTRA_WEB_SERVICE_ID = "ais.tee.extra.WEB_SERVICE_ID"
    const val LEGACY_WIDGET_ACTION_OPEN_DESTINATION = "ais.tee.action.OPEN_WIDGET_DESTINATION"
    const val LEGACY_WIDGET_EXTRA_DESTINATION = "ais.tee.extra.WIDGET_DESTINATION"

    private const val URI_SCHEME = "aistee"
    private const val NATIVE_CHAT_HOST = "native-chat"
    private const val WEB_CHAT_HOST = "web-chat"

    const val DESTINATION_WEB_AI = "web_ai"
    const val DESTINATION_COMPARE = "compare"
    const val DESTINATION_STUDIO = "studio"
    const val DESTINATION_NEW_NATIVE_CHAT = "new_native_chat"
    const val DESTINATION_LIBRARY = "library"

    fun launchIntent(context: Context, destination: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_DESTINATION
            data = Uri.parse("aistee://quick-action/$destination")
            putExtra(EXTRA_DESTINATION, destination)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    fun nativeConversationLaunchIntent(context: Context, conversationId: String): Intent {
        val normalizedId = conversationId.trim()
        require(normalizedId.isNotEmpty()) { "conversationId must not be blank" }
        return Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_NATIVE_CONVERSATION
            data = Uri.Builder()
                .scheme(URI_SCHEME)
                .authority(NATIVE_CHAT_HOST)
                .appendPath(normalizedId)
                .build()
            putExtra(EXTRA_NATIVE_CONVERSATION_ID, normalizedId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    fun webDraftReplyLaunchIntent(context: Context, serviceId: String): Intent {
        val normalizedId = serviceId.trim()
        require(normalizedId.isNotEmpty()) { "serviceId must not be blank" }
        return Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_WEB_DRAFT_REPLY
            data = Uri.Builder()
                .scheme(URI_SCHEME)
                .authority(WEB_CHAT_HOST)
                .appendPath(normalizedId)
                .build()
            putExtra(EXTRA_WEB_SERVICE_ID, normalizedId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    fun isOpenDestinationAction(value: String?): Boolean =
        value == ACTION_OPEN_DESTINATION || value == LEGACY_WIDGET_ACTION_OPEN_DESTINATION

    fun isOpenNativeConversationAction(value: String?): Boolean =
        value == ACTION_OPEN_NATIVE_CONVERSATION

    fun isOpenWebDraftReplyAction(value: String?): Boolean =
        value == ACTION_OPEN_WEB_DRAFT_REPLY

    fun nativeConversationId(
        currentExtra: String?,
        dataScheme: String?,
        dataHost: String?,
        dataLastPathSegment: String?
    ): String? {
        currentExtra?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (dataScheme != URI_SCHEME || dataHost != NATIVE_CHAT_HOST) return null
        return dataLastPathSegment?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun webServiceId(
        currentExtra: String?,
        dataScheme: String?,
        dataHost: String?,
        dataLastPathSegment: String?
    ): String? {
        currentExtra?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (dataScheme != URI_SCHEME || dataHost != WEB_CHAT_HOST) return null
        return dataLastPathSegment?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun destinationId(currentExtra: String?, legacyExtra: String?, dataLastPathSegment: String?): String? =
        currentExtra ?: legacyExtra ?: dataLastPathSegment

    fun destination(value: String?): NavigationTab? = when (value) {
        DESTINATION_WEB_AI -> NavigationTab.WEB_CHATS
        DESTINATION_COMPARE -> NavigationTab.COMPARE_HUB
        DESTINATION_STUDIO -> NavigationTab.STUDIO
        else -> null
    }

    fun quickActionDestination(value: String?): QuickActionDestination? = when (value) {
        DESTINATION_NEW_NATIVE_CHAT -> QuickActionDestination.NewNativeChat
        DESTINATION_LIBRARY -> QuickActionDestination.ProjectLibrary
        else -> destination(value)?.let(QuickActionDestination::Tab)
    }
}
