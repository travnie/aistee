package ais.tee.data.model

/** Identity providers surfaced by a provider's own verified sign-in page. */
enum class ProviderIdentityMethod {
    GOOGLE,
    GITHUB,
    MICROSOFT
}

/**
 * How much Aistee currently knows about returning an authenticated provider session to its WebView.
 *
 * This intentionally describes session handoff, not whether the upstream identity provider itself
 * can authenticate the user. Aistee never copies cookies or OAuth tokens between surfaces.
 */
enum class EmbeddedSessionHandoff {
    VERIFIED,
    UNVERIFIED,
    UNSUPPORTED
}

data class ProviderOnboardingCapabilities(
    val preferredIdentityMethods: List<ProviderIdentityMethod> = emptyList(),
    val embeddedSessionHandoff: EmbeddedSessionHandoff = EmbeddedSessionHandoff.UNVERIFIED,
    /** Provider-owned entry page; never a constructed identity-provider OAuth request. */
    val signInUrl: String? = null
) {
    val hasIdentityAssistedPath: Boolean
        get() = preferredIdentityMethods.isNotEmpty()
}

/**
 * Conservative provider-onboarding metadata.
 *
 * Only identity methods already verified on provider-owned sign-in surfaces belong here. A listed
 * method does not imply that OAuth succeeds inside Android WebView or that a browser session can be
 * transferred back into it. Unknown providers deliberately return no preferred identity method.
 */
fun WebAiService.onboardingCapabilities(): ProviderOnboardingCapabilities = when (this) {
    WebAiService.QWEN -> ProviderOnboardingCapabilities(
        preferredIdentityMethods = listOf(
            ProviderIdentityMethod.GOOGLE,
            ProviderIdentityMethod.GITHUB
        ),
        signInUrl = "https://chat.qwen.ai/auth?action=signin"
    )
    WebAiService.COPILOT -> ProviderOnboardingCapabilities(
        preferredIdentityMethods = listOf(ProviderIdentityMethod.MICROSOFT),
        signInUrl = url
    )
    WebAiService.ZAI -> ProviderOnboardingCapabilities(
        preferredIdentityMethods = listOf(
            ProviderIdentityMethod.GOOGLE,
            ProviderIdentityMethod.GITHUB
        ),
        signInUrl = "https://chat.z.ai/auth"
    )
    else -> ProviderOnboardingCapabilities(
        embeddedSessionHandoff = EmbeddedSessionHandoff.UNSUPPORTED
    )
}

/**
 * Resolves the locally preferred identity method against this provider's verified capabilities.
 *
 * A supported user preference wins. Otherwise the provider's first verified method is the safe
 * provider-specific fallback. Providers without a verified identity-assisted path return null.
 * This selects a sign-in option only; it does not change or imply embedded-session handoff support.
 */
fun WebAiService.resolveOnboardingIdentityMethod(
    preferred: ProviderIdentityMethod?
): ProviderIdentityMethod? {
    val supported = onboardingCapabilities().preferredIdentityMethods
    return preferred?.takeIf(supported::contains) ?: supported.firstOrNull()
}
