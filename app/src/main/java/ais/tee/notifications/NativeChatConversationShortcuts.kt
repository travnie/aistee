package ais.tee.notifications

import android.content.Context
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import ais.tee.MainActivity
import ais.tee.navigation.AisteeQuickActionNavigation
import java.security.MessageDigest

private const val SHORTCUT_ID_PREFIX = "native-chat:"
private const val GENERIC_SHORTCUT_LABEL = "AI chat"
private const val MAX_SHORT_LABEL_CHARS = 25
private const val MAX_LONG_LABEL_CHARS = 64

/** Direct Share category declared by `res/xml/shortcuts.xml`. */
internal const val NATIVE_CHAT_SHARE_TARGET_CATEGORY = "ais.tee.category.NATIVE_CHAT_SHARE_TARGET"
private const val HEX_DIGITS = "0123456789abcdef"

internal fun nativeChatConversationShortcutId(conversationId: String): String? {
    val normalizedId = conversationId.trim().takeIf { it.isNotEmpty() } ?: return null
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalizedId.toByteArray(Charsets.UTF_8))
    return SHORTCUT_ID_PREFIX + buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
}

/**
 * Label and Direct Share eligibility for a conversation shortcut.
 *
 * Every sharesheet target would look identical with the generic label, so a conversation is offered
 * as a Direct Share target only when its title may be shown (notification titles enabled and Quick
 * privacy off). Otherwise the shortcut keeps the generic label and no share category.
 */
internal data class NativeChatShortcutPresentation(
    val shortLabel: String,
    val longLabel: String,
    val isShareTarget: Boolean,
)

internal fun nativeChatShortcutPresentation(
    title: String?,
    showConversationTitles: Boolean,
): NativeChatShortcutPresentation {
    val visibleTitle = title?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { showConversationTitles && it.isNotEmpty() }
        ?: return NativeChatShortcutPresentation(GENERIC_SHORTCUT_LABEL, GENERIC_SHORTCUT_LABEL, isShareTarget = false)
    return NativeChatShortcutPresentation(
        shortLabel = visibleTitle.take(MAX_SHORT_LABEL_CHARS),
        longLabel = visibleTitle.take(MAX_LONG_LABEL_CHARS),
        isShareTarget = true,
    )
}

/** Resolves a hashed shortcut ID back to one of [conversationIds], or null for unknown IDs. */
internal fun nativeChatConversationIdForShortcut(
    shortcutId: String?,
    conversationIds: Iterable<String>,
): String? {
    val target = shortcutId?.trim()?.takeIf { it.startsWith(SHORTCUT_ID_PREFIX) } ?: return null
    return conversationIds.firstOrNull { nativeChatConversationShortcutId(it) == target }
}

internal object NativeChatConversationShortcuts {
    private val shortcutLock = Any()

    fun publish(context: Context, conversationId: String, title: String?): String? = synchronized(shortcutLock) {
        val shortcutId = nativeChatConversationShortcutId(conversationId) ?: return null
        val appContext = context.applicationContext
        val presentation = nativeChatShortcutPresentation(
            title = title,
            showConversationTitles = effectiveNativeChatNotificationPreferences(appContext).showConversationTitles,
        )
        val assistant = Person.Builder()
            .setName("Aistee")
            .setBot(true)
            .build()
        val launchIntent =
            AisteeQuickActionNavigation.nativeConversationLaunchIntent(appContext, conversationId)
                .setClass(appContext, MainActivity::class.java)
        val shortcut = ShortcutInfoCompat.Builder(appContext, shortcutId)
            .setShortLabel(presentation.shortLabel)
            .setLongLabel(presentation.longLabel)
            .setIntent(launchIntent)
            .setPerson(assistant)
            .setLocusId(LocusIdCompat(shortcutId))
            .setLongLived(true)
            .setIsConversation()
            .apply {
                if (presentation.isShareTarget) setCategories(setOf(NATIVE_CHAT_SHARE_TARGET_CATEGORY))
            }
            .build()

        runCatching {
            if (ShortcutManagerCompat.pushDynamicShortcut(appContext, shortcut)) shortcutId else null
        }.getOrNull()
    }

    fun remove(context: Context, conversationId: String) {
        synchronized(shortcutLock) {
            val shortcutId = nativeChatConversationShortcutId(conversationId) ?: return@synchronized
            runCatching {
                ShortcutManagerCompat.removeLongLivedShortcuts(
                    context.applicationContext,
                    listOf(shortcutId),
                )
            }
        }
    }

    /**
     * Removes conversation shortcuts that carry a chat title, e.g. after titles are hidden or Quick
     * privacy is turned on. Generic shortcuts stay so posted conversation notifications keep working.
     */
    fun removeTitled(context: Context) {
        synchronized(shortcutLock) {
            val appContext = context.applicationContext
            runCatching {
                val titled = ShortcutManagerCompat.getShortcuts(
                    appContext,
                    ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED,
                ).filter { shortcut ->
                    shortcut.id.startsWith(SHORTCUT_ID_PREFIX) &&
                        shortcut.shortLabel.toString() != GENERIC_SHORTCUT_LABEL
                }.map { it.id }
                if (titled.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(appContext, titled)
            }
        }
    }
}
