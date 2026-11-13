package ais.tee.data.preferences

import ais.tee.data.model.WebAiService
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class WebChatBookmarkStoreTest {
    private val service = WebAiService.CHATGPT
    private val address = "https://chatgpt.com/c/bookmark-123"

    @Test
    fun explicitProviderBoundLinksOnlyAndNoQueryOrFragment() {
        assertEquals(address, safeWebChatBookmarkUrl(service, address))
        listOf(
            "https://chatgpt.com/",
            "https://chatgpt.com/c/id?share=token",
            "https://chatgpt.com/c/id#hidden",
            "https://chatgpt.com/login",
            "https://chatgpt.com/settings",
            "https://chatgpt.com:444/c/id",
            "https://user:secret@chatgpt.com/c/id",
            "http://chatgpt.com/c/id",
            "https://chatgpt.com.evil.test/c/id",
            "https://claude.ai/chat/id",
            "https://chatgpt.com/c/../settings",
            "https://chatgpt.com/c/..",
            "https://chatgpt.com/c/%2e%2e",
            "https://chatgpt.com/c/%2E",
            "https://chatgpt.com/c/id\n"
        ).forEach { assertNull(it, safeWebChatBookmarkUrl(service, it)) }
    }

    @Test
    fun supportsAllRegisteredProviderOriginsWithoutScrapingPages() {
        WebAiService.entries.forEach { provider ->
            val canonical = java.net.URI(provider.url)
            val url = "https://${canonical.host}/c/user-selected"
            assertEquals(provider.id, url, safeWebChatBookmarkUrl(provider, url))
        }
    }

    @Test
    fun foldersSupportBoundedNestingButRejectUnsafeOrAmbiguousNames() {
        assertEquals("", cleanWebBookmarkFolder(" "))
        assertEquals("Work/Research/AI", cleanWebBookmarkFolder(" Work/Research/AI "))
        listOf("a//b", "/a", "a/", "../b", "a/../b", "a/b/c/d",
            "x".repeat(33), "a\nb", "a /b").forEach {
            assertNull(it, cleanWebBookmarkFolder(it))
        }
        assertNull(cleanWebBookmarkTitle("   "))
        assertNull(cleanWebBookmarkTitle("x".repeat(81)))
    }

    @Test
    fun encryptedStoreSeamSupportsSaveRenameDedupAndRemove() {
        val persistence = FakePersistence()
        val store = WebChatBookmarkStore(persistence)
        assertEquals(emptyList<WebChatBookmark>(), store.load().bookmarks)
        val first = store.save(service, address, "Old title", "Work/AI") as WebChatBookmarkSaveResult.Saved
        assertEquals("Work/AI", first.bookmark.folder)
        val second = store.save(service, address, "New title", "Personal") as WebChatBookmarkSaveResult.Saved
        assertEquals(first.bookmark.id, second.bookmark.id)
        val snapshot = store.load()
        assertTrue(snapshot.readable)
        assertEquals(1, snapshot.bookmarks.size)
        assertEquals("New title", snapshot.bookmarks.single().title)
        assertEquals("Personal", snapshot.bookmarks.single().folder)
        assertTrue(store.remove(first.bookmark.id))
        assertEquals(emptyList<WebChatBookmark>(), store.load().bookmarks)
        assertFalse(store.remove(first.bookmark.id))
        assertFalse(persistence.value?.contains(address) == true)
    }

    @Test
    fun rejectsInvalidSaveWithoutChangingStorage() {
        val persistence = FakePersistence()
        val store = WebChatBookmarkStore(persistence)
        assertTrue(store.save(service, address, "Ok", "") is WebChatBookmarkSaveResult.Saved)
        val previous = persistence.value
        assertTrue(store.save(service, address + "?auth=x", "Bad", "") is WebChatBookmarkSaveResult.Rejected)
        assertTrue(store.save(service, address, "", "") is WebChatBookmarkSaveResult.Rejected)
        assertTrue(store.save(service, address, "Ok", "folder//invalid") is WebChatBookmarkSaveResult.Rejected)
        assertEquals(previous, persistence.value)
    }

    @Test
    fun unreadableOrWriteFailedDataNeverSilentlyOverwritten() {
        val persistence = FakePersistence(value = "{bad")
        val store = WebChatBookmarkStore(persistence)
        assertFalse(store.load().readable)
        assertTrue(store.save(service, address, "New", "") is WebChatBookmarkSaveResult.Rejected)
        assertEquals("{bad", persistence.value)
        assertTrue(store.reset())
        assertTrue(store.load().readable)
        persistence.allowWrite = false
        assertTrue(store.save(service, address, "New", "") is WebChatBookmarkSaveResult.Rejected)
        assertEquals(null, persistence.value)
    }

    @Test
    fun portableBackupRoundTripsAndMergesWithoutOverwritingLocalNames() {
        val source = WebChatBookmarkStore(FakePersistence())
        assertTrue(source.save(service, address, "Exported", "Work/AI") is WebChatBookmarkSaveResult.Saved)
        val json = requireNotNull(source.exportPortableArchive())
        assertTrue(json.contains("\"version\":1"))
        assertTrue(json.contains("\"bookmarks\""))
        val local = WebChatBookmarkStore(FakePersistence())
        assertEquals(WebChatBookmarkImportResult.Imported(1, 0), local.importPortableArchive(json))
        assertEquals("Exported", local.load().bookmarks.single().title)
        assertEquals(WebChatBookmarkImportResult.Imported(0, 1), local.importPortableArchive(json))
        val sameAddress = json.replace("chatgpt.com/c/", "CHATGPT.COM.:443/c/")
        assertEquals(WebChatBookmarkImportResult.Imported(0, 1), local.importPortableArchive(sameAddress))
        assertEquals(1, local.load().bookmarks.size)
        assertTrue(local.save(service, address, "Local title", "Personal") is WebChatBookmarkSaveResult.Saved)
        assertEquals(WebChatBookmarkImportResult.Imported(0, 1), local.importPortableArchive(json))
        assertEquals("Local title", local.load().bookmarks.single().title)
    }

    @Test
    fun legacyArchiveWithEquivalentUrlsRestoresOtherBookmarks() {
        val old = FakePersistence(
            """{"version":1,"bookmarks":[
                {"id":"a","providerId":"chatgpt","url":"$address","title":"First","folder":""},
                {"id":"b","providerId":"chatgpt","url":"https://CHATGPT.COM.:443/c/bookmark-123","title":"Second","folder":"Legacy"},
                {"id":"c","providerId":"chatgpt","url":"https://chatgpt.com/c/other","title":"Other","folder":""}
            ]}"""
        )
        val backup = requireNotNull(WebChatBookmarkStore(old).exportPortableArchive())
        val restored = WebChatBookmarkStore(FakePersistence())
        assertEquals(WebChatBookmarkImportResult.Imported(2, 1), restored.importPortableArchive(backup))
        assertEquals(listOf("First", "Other"), restored.load().bookmarks.map { it.title })
    }

    @Test
    fun malformedOrUnsafeImportCannotReplaceEncryptedStorage() {
        val persistence = FakePersistence()
        val local = WebChatBookmarkStore(persistence)
        assertTrue(local.save(service, address, "Safe", "") is WebChatBookmarkSaveResult.Saved)
        val previous = persistence.value
        val backup = requireNotNull(local.exportPortableArchive())
        listOf(
            "{", backup.replace("\"version\":1", "\"version\":2"),
            backup.replace("chatgpt.com", "chatgpt.com.evil.test"),
            backup.replace("https://chatgpt.com/c/bookmark-123", "https://chatgpt.com/c/id?secret=x"),
            backup.replace("\"title\":\"Safe\"", "\"title\":\"\"")
        ).forEach { bad ->
            assertTrue(local.importPortableArchive(bad) is WebChatBookmarkImportResult.Rejected)
            assertEquals(previous, persistence.value)
        }
        val oversized = "x".repeat(WEB_BOOKMARK_BACKUP_MAX_CHARS + 1)
        assertNull(readWebChatBookmarkBackup(ByteArrayInputStream(oversized.toByteArray())))
        assertNull(readWebChatBookmarkBackup(ByteArrayInputStream(
            byteArrayOf('{'.code.toByte(), 0xC3.toByte(), '}'.code.toByte())
        )))
    }

    @Test
    fun importedRecordsCannotOverwriteUnreadableStore() {
        val source = WebChatBookmarkStore(FakePersistence())
        source.save(service, address, "Saved", "")
        val backup = requireNotNull(source.exportPortableArchive())
        val damaged = FakePersistence("{bad")
        val local = WebChatBookmarkStore(damaged)
        assertTrue(local.importPortableArchive(backup) is WebChatBookmarkImportResult.Rejected)
        assertEquals("{bad", damaged.value)
        assertNull(local.exportPortableArchive())
    }

    private class FakePersistence(
        var value: String? = null,
        var allowWrite: Boolean = true,
    ) : WebChatBookmarkPersistence {
        override fun read(): String? = value
        override fun hasStoredData(): Boolean = value != null
        override fun write(value: String): Boolean {
            if (!allowWrite) return false
            this.value = value
            return true
        }
        override fun clear(): Boolean {
            value = null
            return true
        }
    }
}
