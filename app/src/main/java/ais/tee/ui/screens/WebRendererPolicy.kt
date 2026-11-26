package ais.tee.ui.screens

import ais.tee.data.model.WebAiService
import ais.tee.data.preferences.safeWebChatBookmarkUrl
import java.net.URI
import ais.tee.data.model.WebChatActivityStatus
import ais.tee.data.model.WebChatGenerationObservation
import ais.tee.web.providerDiagnosticsDocumentMatches

internal const val MAX_LIVE_WEBVIEWS = 2

internal enum class WebRendererRecoveryAction {
    RECREATE_LAST_URL,
    DEFER_UNTIL_ACTIVE,
    EVICT_UNTIL_SELECTED,
    REQUIRE_USER_RETRY
}

internal fun webRendererRecoveryAction(
    didCrash: Boolean,
    isSelected: Boolean,
    isWebChatActive: Boolean
): WebRendererRecoveryAction = when {
    didCrash -> WebRendererRecoveryAction.REQUIRE_USER_RETRY
    isSelected && isWebChatActive -> WebRendererRecoveryAction.RECREATE_LAST_URL
    isSelected -> WebRendererRecoveryAction.DEFER_UNTIL_ACTIVE
    else -> WebRendererRecoveryAction.EVICT_UNTIL_SELECTED
}

/** Explicit crash recovery only. Recognized conversation paths; never replay auth or transient URLs. */
internal fun webRendererCrashConversationUrl(service: WebAiService, lastUrl: String?): String? {
    val safe = safeWebChatBookmarkUrl(service, lastUrl) ?: return null
    val uri = URI(safe)
    val prefix = when (service) {
        WebAiService.CHATGPT -> "/c/"
        WebAiService.CLAUDE -> "/chat/"
        WebAiService.GEMINI -> "/app/"
        WebAiService.DEEPSEEK -> "/a/chat/s/"
        else -> return null
    }
    if (!uri.host.equals(URI(service.url).host, ignoreCase = true)) return null
    val path = uri.rawPath
    if (!path.startsWith(prefix)) return null
    val id = path.removePrefix(prefix)
    if (id.length !in 1..128 || id.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '-' && it != '_' }) return null
    return safe
}

internal fun rendererInactivityConfirmedByObservation(
    observation: WebChatGenerationObservation
): Boolean = when (observation) {
    WebChatGenerationObservation.IDLE,
    WebChatGenerationObservation.COMPLETED,
    WebChatGenerationObservation.COMPLETED_WHILE_SELECTED -> true
    WebChatGenerationObservation.GENERATING,
    WebChatGenerationObservation.UNKNOWN -> false
}

internal fun rendererPriorityWaivedWhenNotVisible(
    isSelected: Boolean,
    trackingSupported: Boolean,
    inactivityConfirmed: Boolean
): Boolean = !isSelected && trackingSupported && inactivityConfirmed

internal fun rendererInactivityConfirmedAfterObservation(
    wasConfirmed: Boolean,
    observation: WebChatGenerationObservation
): Boolean = when (observation) {
    WebChatGenerationObservation.GENERATING,
    WebChatGenerationObservation.UNKNOWN -> false
    WebChatGenerationObservation.IDLE,
    WebChatGenerationObservation.COMPLETED,
    WebChatGenerationObservation.COMPLETED_WHILE_SELECTED -> wasConfirmed
}

internal fun webServicesForActivation(
    current: List<WebAiService>,
    activationTarget: WebAiService,
    crashedServices: Set<WebAiService>
): List<WebAiService> = current.filter { service ->
    service == activationTarget || service !in crashedServices
}

internal fun providerWebViewVisibility(isCurrentService: Boolean): Int =
    if (isCurrentService) android.view.View.VISIBLE else android.view.View.GONE

internal fun nextWebViewLru(
    current: List<WebAiService>,
    selected: WebAiService,
    protectedServices: Set<WebAiService> = emptySet()
): List<WebAiService> = buildList {
    add(selected)
    current.filterTo(this) { it != selected && it in protectedServices }
    current.filterTo(this) { it != selected && it !in protectedServices }
}.distinct().take(MAX_LIVE_WEBVIEWS)

internal fun webGenerationProbeDocumentMatches(
    expectedRevision: Int,
    currentRevision: Int,
    expectedUrl: String?,
    currentUrl: String?
): Boolean = expectedRevision == currentRevision &&
    providerDiagnosticsDocumentMatches(expectedUrl, currentUrl)

internal fun webChatActivityStatusAfterFreshLruProbe(
    previous: WebChatActivityStatus,
    observation: WebChatGenerationObservation,
    observedService: WebAiService,
    activationTarget: WebAiService
): WebChatActivityStatus = if (observation == WebChatGenerationObservation.UNKNOWN) {
    previous
} else {
    nextObservedWebChatActivityStatus(
        previous = previous,
        observation = observation,
        isSelected = observedService == activationTarget,
        isLiveService = true
    )
}

internal fun protectedWebServicesForLru(
    knownGenerating: Set<WebAiService>,
    freshObservations: Map<WebAiService, WebChatGenerationObservation>,
    invalidatedServices: Set<WebAiService> = emptySet()
): Set<WebAiService> = (knownGenerating - invalidatedServices).toMutableSet().apply {
    freshObservations.forEach { (service, observation) ->
        when (observation) {
            WebChatGenerationObservation.GENERATING -> add(service)
            WebChatGenerationObservation.IDLE,
            WebChatGenerationObservation.COMPLETED,
            WebChatGenerationObservation.COMPLETED_WHILE_SELECTED -> remove(service)
            WebChatGenerationObservation.UNKNOWN -> Unit
        }
    }
}
