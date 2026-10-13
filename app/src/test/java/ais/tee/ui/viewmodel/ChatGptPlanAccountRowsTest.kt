package ais.tee.ui.viewmodel

import ais.tee.data.security.ChatGptPlanCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatGptPlanAccountRowsTest {
    @Test
    fun sharedEmailsGetDistinctStableLabels() {
        val rows = accountRows(listOf(
            account("oaiapp_aaaa1111", "me@example.com"),
            account("oaiapp_bbbb2222", "me@example.com"),
            account("oaiapp_cccc3333", "other@example.com"),
            account("oaiapp_dddd4444", null),
        ))
        assertEquals(
            listOf("me@example.com (…1111)", "me@example.com (…2222)", "other@example.com", "ChatGPT account"),
            rows.map { it.label },
        )
    }

    private fun account(clientId: String, email: String?) =
        ChatGptPlanCredentialStore.Account(clientId, "sub-$clientId", email, true, true, null)
}
