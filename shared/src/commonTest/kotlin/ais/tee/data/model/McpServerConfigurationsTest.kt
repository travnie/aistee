package ais.tee.data.model

import kotlin.test.*

class McpServerConfigurationsTest {
    private val first = McpServerConfiguration(FIRST_ID, "Work", "https://tools.example.org/mcp")

    @Test fun generatedIdentityRetriesCollisionsAndFailsWithoutChangingTheList() {
        val existing = listOf(first)
        var calls = 0
        val result = McpServerConfigurations.upsert(existing, null, PERSONAL_LABEL, OTHER_ENDPOINT) {
            if (calls++ == 0) FIRST_ID else SECOND_ID
        } as McpServerSaveResult.Saved
        assertEquals(SECOND_ID, result.server.id)
        assertEquals(2, calls)
        assertEquals(McpServerSaveResult.Failure(McpServerSaveFailure.STORAGE),
            McpServerConfigurations.upsert(existing, null, PERSONAL_LABEL, OTHER_ENDPOINT) { FIRST_ID })
        assertEquals(listOf(first), existing)
    }

    @Test fun namesAreDistinctIgnoringCaseAndUpdatesPreserveOrder() {
        assertEquals(McpServerSaveResult.Failure(McpServerSaveFailure.DUPLICATE_NAME),
            McpServerConfigurations.upsert(listOf(first), null, " work ", OTHER_ENDPOINT) { SECOND_ID })
        val second = first.copy(id = SECOND_ID, label = PERSONAL_LABEL, endpoint = OTHER_ENDPOINT)
        val changed = first.copy(label = "Renamed")
        assertEquals(listOf(changed, second), McpServerConfigurations.replace(listOf(first, second), changed))
        assertFalse(McpServerConfigurations.validArchive(listOf(first, second.copy(label = "work"))))
    }
}

private const val FIRST_ID = "first"
private const val SECOND_ID = "second"
private const val PERSONAL_LABEL = "Personal"
private const val OTHER_ENDPOINT = "https://other.example.org/mcp"
