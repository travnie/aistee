package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatModelsTest {
    @Test
    fun favoriteWebChatsMoveToTheTopWithoutDuplication() {
        val sections = webChatSections(setOf(WebAiService.QWEN, WebAiService.CHATGPT))

        assertEquals(listOf(WebAiService.CHATGPT, WebAiService.QWEN), sections.favorites)
        assertFalse(WebAiService.CHATGPT in sections.primary)
        assertFalse(WebAiService.QWEN in sections.additional)
        assertEquals(WebAiService.entries.size, sections.all.size)
        assertEquals(WebAiService.entries.toSet(), sections.all.toSet())
    }

    @Test
    fun webChatGroupsCoverEveryAccountBackedProviderExactlyOnce() {
        assertEquals(
            listOf(
                WebAiService.CLAUDE,
                WebAiService.CHATGPT,
                WebAiService.GEMINI,
                WebAiService.DEEPSEEK,
                WebAiService.KIMI,
                WebAiService.VIBE
            ),
            WebAiService.primaryChats
        )
        assertTrue(
            WebAiService.primaryChats.toSet()
                .intersect(WebAiService.additionalChats.toSet())
                .isEmpty()
        )
        assertEquals(
            WebAiService.entries.toSet(),
            (WebAiService.primaryChats + WebAiService.additionalChats).toSet()
        )
    }

    @Test
    fun vibeWebProviderUsesCanonicalMistralChatEndpoint() {
        assertEquals("https://chat.mistral.ai", WebAiService.VIBE.url)
        assertEquals("Vibe", WebAiService.VIBE.shortName)
    }

    @Test
    fun additionalWebProvidersUseCanonicalEndpoints() {
        val endpoints = mapOf(
            WebAiService.QWEN to "https://chat.qwen.ai",
            WebAiService.COPILOT to "https://copilot.microsoft.com",
            WebAiService.ZAI to "https://chat.z.ai",
            WebAiService.GROK to "https://grok.com",
            WebAiService.CHARACTER_AI to "https://character.ai",
            WebAiService.VENICE to "https://venice.ai",
            WebAiService.META_AI to "https://www.meta.ai"
        )

        endpoints.forEach { (service, url) ->
            assertEquals(url, service.url)
            assertEquals(service, WebAiService.fromId(service.id))
        }
    }

    @Test
    fun concreteProviderDefaultsAreSelectable() {
        AiProvider.concreteProviders.forEach { provider ->
            assertTrue(
                provider.defaultModel in provider.availableModels,
                "${provider.id} default model must be present in availableModels"
            )
        }
    }

    @Test
    fun defaultCompareUsesDirectProvidersOnly() {
        assertFalse(AiProvider.ALL in AiProvider.concreteProviders)
        assertFalse(AiProvider.OPENROUTER in AiProvider.concreteProviders)
        assertFalse(AiProvider.AIHUBMIX in AiProvider.concreteProviders)
        assertFalse(AiProvider.VERCEL in AiProvider.concreteProviders)
        assertEquals(
            setOf(
                AiProvider.CLAUDE,
                AiProvider.CHATGPT,
                AiProvider.GEMINI,
                AiProvider.DEEPSEEK,
                AiProvider.KIMI
            ),
            AiProvider.concreteProviders.toSet()
        )
    }

    @Test
    fun openRouterDefaultsToFreeModelRouter() {
        assertEquals("openrouter/free", AiProvider.OPENROUTER.defaultModel)
        assertTrue(AiProvider.OPENROUTER.defaultModel in AiProvider.OPENROUTER.availableModels)
    }

    @Test
    fun aiHubMixDefaultsToFreeCatalogModel() {
        assertEquals("hy3-free", AiProvider.AIHUBMIX.defaultModel)
        assertTrue(AiProvider.AIHUBMIX.availableModels.all { it.endsWith("-free") })
    }

    @Test
    fun vercelDefaultsToSmokeTestedGatewayModel() {
        assertEquals("alibaba/qwen3-coder-30b-a3b", AiProvider.VERCEL.defaultModel)
        assertTrue(AiProvider.VERCEL.defaultModel in AiProvider.VERCEL.availableModels)
    }

    @Test
    fun configuredDirectProvidersIncludeOnlyKeyedDirectProviders() {
        val config = ApiKeyConfig(
            geminiKey = "   ",
            openAiKey = "sk-live",
            kimiKey = "  kimi-key  ",
            openRouterKey = "sk-or-v1-gateway"
        )

        assertEquals(
            listOf(AiProvider.CHATGPT, AiProvider.KIMI),
            config.configuredDirectProviders()
        )
    }

    @Test
    fun chatGptPlanBindingRunsChatGptWithoutAnApiKey() {
        val config = ApiKeyConfig(kimiKey = "kimi-key")

        assertFalse(config.canRunDirect(AiProvider.CHATGPT, null))
        assertTrue(config.canRunDirect(AiProvider.CHATGPT, "oaiapp_a"))
        assertFalse(config.canRunDirect(AiProvider.CLAUDE, "oaiapp_a"))
        assertEquals(listOf(AiProvider.KIMI), config.directProvidersFor(null))
        assertEquals(listOf(AiProvider.CHATGPT, AiProvider.KIMI), config.directProvidersFor("oaiapp_a"))
    }

    @Test
    fun freeGatewayCatalogsKeepOnlyFreeTextModels() {
        val catalog = listOf(
            GatewayModelCatalogEntry("free-a", 0.0, 0.0, supportsTextOutput = true),
            GatewayModelCatalogEntry("paid", 0.0, 0.1, supportsTextOutput = true),
            GatewayModelCatalogEntry("image-free", 0.0, 0.0, supportsTextOutput = false),
            GatewayModelCatalogEntry("free-a", 0.0, 0.0, supportsTextOutput = true),
            GatewayModelCatalogEntry("free-b", 0.0, 0.0, supportsTextOutput = true)
        )

        assertEquals(
            listOf("free-a", "free-b"),
            gatewayModelOptions(AiProvider.OPENROUTER, catalog)
        )
        assertEquals(
            listOf("free-a", "free-b"),
            gatewayModelOptions(AiProvider.AIHUBMIX, catalog)
        )
    }

    @Test
    fun vercelGatewayCatalogKeepsTextModelsAndSortsCheapestFirst() {
        val catalog = listOf(
            GatewayModelCatalogEntry("vendor/expensive", 0.000002, 0.000006, supportsTextOutput = true),
            GatewayModelCatalogEntry("vendor/image", 0.0, 0.0, supportsTextOutput = false),
            GatewayModelCatalogEntry("vendor/cheap", 0.0000001, 0.0000002, supportsTextOutput = true),
            GatewayModelCatalogEntry("vendor/unknown-price", null, null, supportsTextOutput = true)
        )

        assertEquals(
            listOf("vendor/cheap", "vendor/expensive", "vendor/unknown-price"),
            gatewayModelOptions(AiProvider.VERCEL, catalog)
        )
    }

    @Test
    fun successfulEmptyLiveGatewayCatalogStaysEmpty() {
        assertEquals(
            emptyList<String>(),
            gatewayModelOptions(AiProvider.AIHUBMIX, emptyList())
        )
    }

    @Test
    fun gatewayKeysDoNotMakeDefaultCompareConfigured() {
        val config = ApiKeyConfig(
            openRouterKey = "sk-or-v1-gateway",
            aiHubMixKey = "gateway-key",
            vercelAiGatewayKey = "vercel-gateway-key"
        )

        assertTrue(config.hasKeyFor(AiProvider.OPENROUTER))
        assertTrue(config.hasKeyFor(AiProvider.AIHUBMIX))
        assertTrue(config.hasKeyFor(AiProvider.VERCEL))
        assertFalse(config.hasKeyFor(AiProvider.ALL))
        assertTrue(config.configuredDirectProviders().isEmpty())
    }

}
