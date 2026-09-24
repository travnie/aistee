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

internal object NativeChatConversationShortcuts {
    private val shortcutLock = Any()

    fun publish(context: Context, conversationId: String): String? = synchronized(shortcutLock) {
        val shortcutId = nativeChatConversationShortcutId(conversationId) ?: return null
        val appContext = context.applicationContext
        val assistant = Person.Builder()
            .setName("Aistee")
            .setBot(true)
            .build()
        val launchIntent =
            AisteeQuickActionNavigation.nativeConversationLaunchIntent(appContext, conversationId)
                .setClass(appContext, MainActivity::class.java)
        val shortcut = ShortcutInfoCompat.Builder(appContext, shortcutId)
            .setShortLabel(GENERIC_SHORTCUT_LABEL)
            .setIntent(launchIntent)
            .setPerson(assistant)
            .setLocusId(LocusIdCompat(shortcutId))
            .setLongLived(true)
            .setIsConversation()
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
}
