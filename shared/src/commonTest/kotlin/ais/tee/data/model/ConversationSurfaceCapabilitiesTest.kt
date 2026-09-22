package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationSurfaceCapabilitiesTest {
    @Test
    fun nativeProvidersExposeOnlyLocallyOwnedConversationFeatures() {
        AiProvider.entries.forEach { provider ->
            val capabilities = provider.conversationSurfaceCapabilities()

            assertTrue(capabilities.supports(ConversationSurfaceCapability.MESSAGE_HISTORY))
            assertTrue(capabilities.supports(ConversationSurfaceCapability.COMPLETION_NOTIFICATION))
            assertTrue(capabilities.supports(ConversationSurfaceCapability.DIRECT_REPLY))
            assertTrue(capabilities.supports(ConversationSurfaceCapability.DEEP_LINK))
            assertFalse(capabilities.supports(ConversationSurfaceCapability.DRAFT_REPLY))
        }
    }

    @Test
    fun accountWebChatsRemainConservativeUntilBridgesAreShipped() {
        WebAiService.entries.forEach { service ->
            val capabilities = service.conversationSurfaceCapabilities()

            assertTrue(capabilities.supports(ConversationSurfaceCapability.DEEP_LINK))
            assertFalse(capabilities.supports(ConversationSurfaceCapability.MESSAGE_HISTORY))
            assertFalse(capabilities.supports(ConversationSurfaceCapability.COMPLETION_NOTIFICATION))
            assertFalse(capabilities.supports(ConversationSurfaceCapability.DIRECT_REPLY))
            assertFalse(capabilities.supports(ConversationSurfaceCapability.DRAFT_REPLY))
        }
    }
}
