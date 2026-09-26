package ais.tee.ui.viewmodel

import ais.tee.data.model.NativeChatArchive
import ais.tee.data.model.NativeChatConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class IncognitoNativeChatPersistenceTest {
    private val saved = NativeChatConversation(id = "saved", createdAtEpochMs = 1L, updatedAtEpochMs = 5L)
    private val incognito = NativeChatConversation(id = "incognito", createdAtEpochMs = 2L, updatedAtEpochMs = 9L)
    private val visible = NativeChatArchive(activeConversationId = "incognito", conversations = listOf(incognito, saved))

    @Test
    fun persistedArchiveSkipsTheIncognitoConversationAndItsActiveState() {
        val persisted = visible.withoutIncognito("incognito")

        assertEquals(listOf("saved"), persisted.conversations.map { it.id })
        assertEquals("saved", persisted.activeConversationId)
    }

    @Test
    fun archivesWithoutIncognitoAreWrittenUnchanged() {
        val archive = NativeChatArchive(activeConversationId = "saved", conversations = listOf(saved))

        assertSame(archive, archive.withoutIncognito(null))
        assertSame(archive, archive.withoutIncognito("incognito"))
    }

    @Test
    fun diskRefreshKeepsTheInMemoryIncognitoConversationActive() {
        val fromDisk = NativeChatArchive(activeConversationId = "saved", conversations = listOf(saved))

        val restored = fromDisk.withIncognitoFrom(visible, "incognito")

        assertEquals(listOf("incognito", "saved"), restored.conversations.map { it.id })
        assertEquals("incognito", restored.activeConversationId)
        assertSame(fromDisk, fromDisk.withIncognitoFrom(visible, null))
    }
}
