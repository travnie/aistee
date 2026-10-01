package ais.tee.web

import ais.tee.data.model.ProviderIdentityMethod
import ais.tee.data.model.WebAiService
import ais.tee.data.model.onboardingCapabilities
import java.net.URI

/**
 * Candidate selectors for conversation turns on a loaded provider page. They are unverified until
 * provider diagnostics confirms non-zero counts on device; no feature may rely on them before that.
 * [stableIdAttribute] names an attribute whose presence (never its value) diagnostics counts.
 */
internal data class ProviderTurnAnchors(
    val userSelector: String,
    val assistantSelector: String,
    val stableIdAttribute: String? = null
)

private data class ProviderWebProfile(
    val ownedHostAliases: Set<String> = emptySet(),
    val turnAnchors: ProviderTurnAnchors? = null,
    val verifiedTopLevelNavigationIdentityMethods: Set<ProviderIdentityMethod> = emptySet(),
    val generationSelectors: List<String> = emptyList(),
    val generationIdleSelectors: List<String> = emptyList()
)

internal object ProviderWebRegistry {
    private const val STOP_BUTTON_TEST_ID_SELECTOR = "[data-testid=\"stop-button\"]"
    private const val VIBE_STOP_ICON_SELECTOR = "button[type=\"submit\"] svg rect"
    private const val VIBE_SEND_ICON_SELECTOR =
        "button[type=\"submit\"] svg path[d^=\"M12 18v4h4v-4h-4ZM16 14v4h4v-4h-4\"]"

    private val identityAuthHosts = mapOf(
        ProviderIdentityMethod.GOOGLE to setOf("accounts.google.com"),
        ProviderIdentityMethod.GITHUB to setOf("github.com"),
        ProviderIdentityMethod.MICROSOFT to setOf("login.live.com", "login.microsoftonline.com")
    )

    // Provider-scoped WebView policy. Canonical hosts stay in WebAiService.url.
    private val profiles = mapOf(
        WebAiService.CHATGPT to ProviderWebProfile(
            generationSelectors = listOf(STOP_BUTTON_TEST_ID_SELECTOR),
            turnAnchors = ProviderTurnAnchors(
                userSelector = "[data-message-author-role=\"user\"]",
                assistantSelector = "[data-message-author-role=\"assistant\"]",
                stableIdAttribute = "data-message-id"
            )
        ),
        WebAiService.CLAUDE to ProviderWebProfile(
            generationSelectors = listOf(
                STOP_BUTTON_TEST_ID_SELECTOR,
                "button[aria-label=\"Stop Response\" i]"
            ),
            turnAnchors = ProviderTurnAnchors(
                userSelector = "[data-testid=\"user-message\"]",
                assistantSelector = "[data-is-streaming]"
            )
        ),
        WebAiService.GEMINI to ProviderWebProfile(
            generationSelectors = listOf("[data-test-id=\"send-button-container\"].stop"),
            turnAnchors = ProviderTurnAnchors(
                userSelector = "user-query",
                assistantSelector = "model-response"
            )
        ),
        WebAiService.DEEPSEEK to ProviderWebProfile(
            generationSelectors = listOf(
                "div[role=\"button\"] svg[viewBox^=\"0 0 16\"] " +
                    "path[d^=\"M2 4.88C2 3.68009 2 3.08013 2.30557 2.65954\"]"
            )
        ),
        WebAiService.KIMI to ProviderWebProfile(
            ownedHostAliases = setOf("kimi.com"),
            generationSelectors = listOf("div.send-button-container.stop")
        ),
        // Vibe keeps a submit button while its SVG switches between send and stop icons.
        WebAiService.VIBE to ProviderWebProfile(
            generationSelectors = listOf(VIBE_STOP_ICON_SELECTOR),
            generationIdleSelectors = listOf(VIBE_SEND_ICON_SELECTOR)
        ),
        WebAiService.QWEN to ProviderWebProfile(
            ownedHostAliases = setOf("qwen.ai"),
            verifiedTopLevelNavigationIdentityMethods = setOf(
                ProviderIdentityMethod.GOOGLE,
                ProviderIdentityMethod.GITHUB
            )
        ),
        WebAiService.COPILOT to ProviderWebProfile(
            ownedHostAliases = setOf(
                "copilot.com",
                "copilot.ai",
                "copilot.cloud.microsoft",
                "m365.cloud.microsoft",
                "m365copilot.com"
            ),
            verifiedTopLevelNavigationIdentityMethods = setOf(ProviderIdentityMethod.MICROSOFT)
        ),
        WebAiService.ZAI to ProviderWebProfile(
            verifiedTopLevelNavigationIdentityMethods = setOf(
                ProviderIdentityMethod.GOOGLE,
                ProviderIdentityMethod.GITHUB
            )
        ),
        WebAiService.META_AI to ProviderWebProfile(
            ownedHostAliases = setOf("alpha.meta.ai")
        )
    )

    private fun profile(service: WebAiService): ProviderWebProfile =
        profiles[service] ?: ProviderWebProfile()

    fun ownedHosts(service: WebAiService): Set<String> = buildSet {
        URI(service.url).host?.lowercase()?.let(::add)
        addAll(profile(service).ownedHostAliases)
    }

    // Keep OAuth navigation verification scoped to an exact provider + identity method.
    // Portable onboarding metadata must not silently widen embedded WebView redirects.
    fun isIdentityMethodVerifiedForNavigation(
        service: WebAiService,
        method: ProviderIdentityMethod
    ): Boolean = method in profile(service).verifiedTopLevelNavigationIdentityMethods &&
        method in service.onboardingCapabilities().preferredIdentityMethods

    fun hasVerifiedTopLevelNavigationPolicy(service: WebAiService): Boolean =
        profile(service).verifiedTopLevelNavigationIdentityMethods.isNotEmpty()

    fun topLevelNavigationAuthHosts(service: WebAiService): Set<String> =
        profile(service).verifiedTopLevelNavigationIdentityMethods
            .filter { method -> isIdentityMethodVerifiedForNavigation(service, method) }
            .flatMap { method -> identityAuthHosts[method].orEmpty() }
            .toSet()

    fun generationSelectors(service: WebAiService): List<String> =
        profile(service).generationSelectors

    fun generationIdleSelectors(service: WebAiService): List<String> =
        profile(service).generationIdleSelectors

    fun turnAnchors(service: WebAiService): ProviderTurnAnchors? = profile(service).turnAnchors
}

internal fun providerHostMatches(service: WebAiService, host: String?): Boolean {
    val normalizedHost = host?.trimEnd('.')?.lowercase() ?: return false
    return ProviderWebRegistry.ownedHosts(service).any { ownedHost ->
        normalizedHost == ownedHost || normalizedHost.endsWith(".$ownedHost")
    }
}

internal fun providerUrlMatches(service: WebAiService, url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && providerHostMatches(service, uri.host)
}

internal fun providerNavigationUrlMatches(service: WebAiService, url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (providerHostMatches(service, uri.host)) return true

    val normalizedHost = uri.host?.trimEnd('.')?.lowercase() ?: return false
    return normalizedHost in ProviderWebRegistry.topLevelNavigationAuthHosts(service)
}

internal fun shouldLoadHttpsInProviderWebView(service: WebAiService, url: String): Boolean {
    if (!ProviderWebRegistry.hasVerifiedTopLevelNavigationPolicy(service)) return true
    return providerNavigationUrlMatches(service, url)
}
