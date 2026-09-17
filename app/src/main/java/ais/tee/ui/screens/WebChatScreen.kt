package ais.tee.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ais.tee.data.model.WebAiService
import ais.tee.data.model.webChatSections
import ais.tee.data.model.WebChatActivityStatus
import ais.tee.data.model.WebChatGenerationObservation
import ais.tee.data.model.markWebChatActivityRead
import ais.tee.data.model.webChatActivityStatusAfterEviction
import ais.tee.share.IncomingSharePayload
import ais.tee.ui.theme.*
import ais.tee.ui.viewmodel.StudioUiState
import ais.tee.ui.viewmodel.StudioViewModel
import ais.tee.web.ProviderDiagnosticsProbeResult
import ais.tee.web.StudioPromptApplyResult
import ais.tee.web.probeProviderDiagnostics
import ais.tee.web.providerDiagnosticsHost
import ais.tee.web.providerDiagnosticsPageHost
import ais.tee.web.providerDiagnosticsDocumentMatches
import ais.tee.web.applyProviderWebTweaks
import ais.tee.web.applyStudioPromptToFocusedEditor
import ais.tee.web.installProviderGenerationTracker
import ais.tee.web.installStudioPromptTargetTracker
import ais.tee.web.probeProviderGenerationActivity
import ais.tee.web.providerUrlMatches
import ais.tee.web.providerGenerationTrackingSupported
import ais.tee.web.sanitizeProviderAcceptTypes
import ais.tee.web.setProviderGenerationTrackerSelected
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val WEB_ACTIVITY_POLL_MS = 1_200L
private const val LRU_GENERATION_PROBE_TIMEOUT_MS = 500L
private const val RENDERER_INACTIVITY_CONFIRM_DELAY_MS = 500L

internal fun studioPromptForWebChat(renderedInstructions: String): String? =
    renderedInstructions.takeUnless(String::isBlank)

internal fun webProviderNavigationIsPersistent(type: NavigationSuiteType): Boolean =
    type == NavigationSuiteType.NavigationRail ||
        type == NavigationSuiteType.WideNavigationRailCollapsed ||
        type == NavigationSuiteType.WideNavigationRailExpanded

internal enum class WebViewEvictionMode {
    NONE,
    PRESERVE_GENERATING,
    SELECTED_ONLY
}

@Suppress("DEPRECATION")
internal fun webViewEvictionModeForTrimMemory(level: Int): WebViewEvictionMode = when {
    level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> WebViewEvictionMode.NONE
    level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> WebViewEvictionMode.SELECTED_ONLY
    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> WebViewEvictionMode.PRESERVE_GENERATING
    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> WebViewEvictionMode.SELECTED_ONLY
    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> WebViewEvictionMode.PRESERVE_GENERATING
    else -> WebViewEvictionMode.NONE
}

internal fun webServicesToKeepAfterMemoryPressure(
    liveServices: List<WebAiService>,
    selectedService: WebAiService,
    generatingServices: Set<WebAiService>,
    mode: WebViewEvictionMode
): List<WebAiService> = when (mode) {
    WebViewEvictionMode.NONE -> liveServices
    WebViewEvictionMode.PRESERVE_GENERATING -> liveServices.filter { service ->
        service == selectedService || service in generatingServices
    }
    WebViewEvictionMode.SELECTED_ONLY -> listOf(selectedService)
}

private data class PendingSharedUploadConfirmation(
    val service: WebAiService,
    val shareId: Long,
    val uris: List<Uri>,
    val callback: ValueCallback<Array<Uri>>,
    val requestId: Int
)

private data class PendingSharedTextInsertion(
    val service: WebAiService,
    val shareId: Long,
    val webView: WebView
)

private data class WebGenerationProbeTarget(
    val service: WebAiService,
    val webView: WebView,
    val documentRevision: Int,
    val url: String?
)

/** Hosts account-backed AI services with mobile-first WebView controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebChatScreen(
    viewModel: StudioViewModel,
    uiState: StudioUiState,
    onOpenNativeCompare: () -> Unit,
    onOpenStudio: () -> Unit,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val navigationSuiteType = NavigationSuiteScaffoldDefaults.navigationSuiteType(
        currentWindowAdaptiveInfoV2()
    )
    val persistentProviderNavigation = webProviderNavigationIsPersistent(navigationSuiteType)
    val selectedService by rememberUpdatedState(uiState.selectedWebService)
    val currentPendingWebShare by rememberUpdatedState(uiState.pendingWebShare)
    var currentUrl by remember { mutableStateOf(selectedService.url) }
    var loadingProgress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showPromptHelperDialog by remember { mutableStateOf(false) }
    var showProviderDiagnosticsDialog by remember { mutableStateOf(false) }
    var diagnosticsProbeResult by remember { mutableStateOf<ProviderDiagnosticsProbeResult?>(null) }
    var diagnosticsProbeRequestId by remember { mutableIntStateOf(0) }
    var pendingExternalIntentUri by remember { mutableStateOf<Uri?>(null) }
    val pendingFileCallback = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val pendingFileService = remember { mutableStateOf<WebAiService?>(null) }
    val pendingFileRequestId = remember { mutableStateOf<Int?>(null) }
    var pendingSharedUploadConfirmation by remember {
        mutableStateOf<PendingSharedUploadConfirmation?>(null)
    }
    val pendingSharedTextInsertion = remember {
        mutableStateOf<PendingSharedTextInsertion?>(null)
    }
    var nextFileChooserRequestId by remember { mutableIntStateOf(0) }
    val fileChooserLatestRequestIds = remember { mutableStateMapOf<WebAiService, Int>() }
    val fileChooserRequestCounts = remember { mutableStateMapOf<WebAiService, Int>() }
    val fileChooserAcceptTypes = remember { mutableStateMapOf<WebAiService, String>() }
    val fileChooserModes = remember { mutableStateMapOf<WebAiService, String>() }
    val fileChooserHosts = remember { mutableStateMapOf<WebAiService, String>() }
    val fileChooserOutcomes = remember { mutableStateMapOf<WebAiService, String>() }
    val documentRevisions = remember { mutableStateMapOf<WebAiService, Int>() }

    fun recordFileChooserRequest(
        service: WebAiService,
        params: WebChromeClient.FileChooserParams,
        pageUrl: String?,
        outcome: String
    ): Int {
        val requestId = nextFileChooserRequestId + 1
        nextFileChooserRequestId = requestId
        fileChooserLatestRequestIds[service] = requestId
        fileChooserRequestCounts[service] = (fileChooserRequestCounts[service] ?: 0) + 1
        fileChooserHosts[service] = providerDiagnosticsPageHost(service, pageUrl)
        fileChooserModes[service] = when (params.mode) {
            WebChromeClient.FileChooserParams.MODE_OPEN -> "single"
            WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE -> "multiple"
            WebChromeClient.FileChooserParams.MODE_SAVE -> "save"
            else -> "other (${params.mode})"
        }
        fileChooserAcceptTypes[service] = sanitizeProviderAcceptTypes(params.acceptTypes)
        fileChooserOutcomes[service] = outcome
        return requestId
    }

    fun updateFileChooserOutcome(service: WebAiService, requestId: Int, outcome: String) {
        if (fileChooserLatestRequestIds[service] == requestId) {
            fileChooserOutcomes[service] = outcome
        }
    }

    LaunchedEffect(uiState.pendingWebShare?.id, uiState.pendingWebShare?.service) {
        val confirmation = pendingSharedUploadConfirmation ?: return@LaunchedEffect
        val pending = uiState.pendingWebShare
        if (pending == null || pending.id != confirmation.shareId || pending.service != confirmation.service) {
            pendingSharedUploadConfirmation = null
            updateFileChooserOutcome(
                confirmation.service,
                confirmation.requestId,
                "shared upload stale / cancelled"
            )
            confirmation.callback.onReceiveValue(null)
        }
    }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileCallback.value ?: return@rememberLauncherForActivityResult
        val service = pendingFileService.value
        val requestId = pendingFileRequestId.value
        pendingFileCallback.value = null
        pendingFileService.value = null
        pendingFileRequestId.value = null
        val resultData = result.data
        val selectedUris = if (result.resultCode == Activity.RESULT_OK && resultData != null) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, resultData)
                ?.filter { isAllowedUploadUri(context, it) }
                ?.toTypedArray()
                ?.takeIf { it.isNotEmpty() }
        } else null
        if (service != null && requestId != null) {
            updateFileChooserOutcome(
                service,
                requestId,
                selectedUris?.let { uris -> "selected (${uris.size})" }
                    ?: "cancelled / no accepted file"
            )
        }
        callback.onReceiveValue(selectedUris)
    }

    var liveServices by remember { mutableStateOf(listOf(selectedService)) }
    val webViewMap = remember { mutableStateMapOf<WebAiService, WebView>() }
    val lastKnownUrls = remember { mutableStateMapOf<WebAiService, String>() }
    val activityStatuses = remember { mutableStateMapOf<WebAiService, WebChatActivityStatus>() }
    val desktopModes = remember { mutableStateMapOf<WebAiService, Boolean>() }
    val pendingDesktopModes = remember { mutableStateMapOf<WebAiService, Boolean>() }
    val providerFavicons = remember { mutableStateMapOf<WebAiService, Bitmap>() }
    val webViewInstanceRevisions = remember { mutableStateMapOf<WebAiService, Int>() }
    val rendererCrashServices = remember { mutableStateMapOf<WebAiService, Boolean>() }
    val rendererInactivityConfirmed = remember { mutableStateMapOf<WebAiService, Boolean>() }
    val rendererPriorityProbeRequestIds = remember { mutableMapOf<WebAiService, Int>() }
    val currentSelectedService by rememberUpdatedState(selectedService)
    var livePoolDecisionRequestId by remember { mutableIntStateOf(0) }

    fun releaseSharedTextClaimFor(webView: WebView) {
        val insertion = pendingSharedTextInsertion.value
            ?.takeIf { it.webView === webView }
            ?: return
        pendingSharedTextInsertion.value = null
        viewModel.releasePendingWebShareTextClaim(insertion.service, insertion.shareId)
    }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    LaunchedEffect(isActive) {
        if (!isActive) drawerState.close()
    }

    fun bumpWebViewInstance(service: WebAiService) {
        webViewInstanceRevisions[service] = (webViewInstanceRevisions[service] ?: 0) + 1
    }

    fun cancelPendingUploadFor(service: WebAiService) {
        if (pendingFileService.value == service) {
            val requestId = pendingFileRequestId.value
            pendingFileCallback.value?.onReceiveValue(null)
            pendingFileCallback.value = null
            pendingFileService.value = null
            pendingFileRequestId.value = null
            requestId?.let { updateFileChooserOutcome(service, it, "renderer terminated") }
        }
        pendingSharedUploadConfirmation
            ?.takeIf { it.service == service }
            ?.let { confirmation ->
                pendingSharedUploadConfirmation = null
                updateFileChooserOutcome(service, confirmation.requestId, "renderer terminated")
                confirmation.callback.onReceiveValue(null)
            }
    }

    fun invalidateRendererWaiveEligibility(service: WebAiService): Int {
        rendererInactivityConfirmed[service] = false
        val requestId = (rendererPriorityProbeRequestIds[service] ?: 0) + 1
        rendererPriorityProbeRequestIds[service] = requestId
        return requestId
    }

    fun protectRendererUntilFreshInactivity(service: WebAiService) {
        val requestId = invalidateRendererWaiveEligibility(service)
        val webView = webViewMap[service] ?: return
        if (!providerGenerationTrackingSupported(service)) return
        val expectedRevision = documentRevisions[service] ?: 0
        val expectedUrl = webView.url
        drawerScope.launch {
            delay(RENDERER_INACTIVITY_CONFIRM_DELAY_MS)
            if (rendererPriorityProbeRequestIds[service] != requestId || webViewMap[service] !== webView) return@launch
            probeProviderGenerationActivity(webView, service, consumeCompletion = false) { observation ->
                if (rendererPriorityProbeRequestIds[service] != requestId || webViewMap[service] !== webView) return@probeProviderGenerationActivity
                val sameDocument = webGenerationProbeDocumentMatches(
                    expectedRevision, documentRevisions[service] ?: 0, expectedUrl, webView.url
                )
                rendererInactivityConfirmed[service] = sameDocument && rendererInactivityConfirmedByObservation(observation)
            }
        }
    }

    fun handleRendererGone(service: WebAiService, deadView: WebView, didCrash: Boolean) {
        val isCurrentInstance = webViewMap[service] === deadView
        releaseSharedTextClaimFor(deadView)
        if (isCurrentInstance) {
            invalidateRendererWaiveEligibility(service)
            // Renderer loss must not cancel an in-flight provider selection. Existing
            // probe callbacks/timeouts will settle this dead target as UNKNOWN via
            // the document-revision and WebView-identity guards below.
            documentRevisions[service] = (documentRevisions[service] ?: 0) + 1
            cancelPendingUploadFor(service)
            activityStatuses[service]?.let { status ->
                activityStatuses[service] = webChatActivityStatusAfterEviction(status)
            }
            webViewMap.remove(service)
            if (selectedService == service) {
                canGoBack = false
                canGoForward = false
                loadingProgress = 0
                isLoading = false
                currentUrl = lastKnownUrls[service] ?: service.url
            }
            val isSelectedService = selectedService == service
            when (webRendererRecoveryAction(didCrash, isSelectedService)) {
                WebRendererRecoveryAction.RECREATE_LAST_URL -> {
                    rendererCrashServices.remove(service)
                    bumpWebViewInstance(service)
                }
                WebRendererRecoveryAction.EVICT_UNTIL_SELECTED -> {
                    rendererCrashServices.remove(service)
                    liveServices = liveServices.filterNot { it == service }
                }
                WebRendererRecoveryAction.REQUIRE_USER_RETRY -> {
                    rendererCrashServices[service] = true
                    if (!isSelectedService) {
                        liveServices = liveServices.filterNot { it == service }
                    }
                    viewModel.showSnackbar("${service.shortName} web renderer crashed")
                }
            }
        }
        releaseTerminatedWebView(deadView)
    }

    fun retryRendererAfterCrash(service: WebAiService) {
        if (rendererCrashServices.remove(service) != true) return
        lastKnownUrls[service] = service.url
        if (selectedService == service) currentUrl = service.url
        bumpWebViewInstance(service)
    }

    fun updateLiveServices(nextServices: List<WebAiService>) {
        val evictedServices = liveServices.filterNot(nextServices.toSet()::contains)
        evictedServices.forEach { service ->
            invalidateRendererWaiveEligibility(service)
            activityStatuses[service]?.let { status ->
                activityStatuses[service] = webChatActivityStatusAfterEviction(status)
            }
        }
        liveServices = nextServices
    }

    fun applyPendingDesktopModeIfSafe(
        service: WebAiService,
        observation: WebChatGenerationObservation
    ) {
        if (service != selectedService) return
        val nextDesktopMode = pendingDesktopModes[service] ?: return
        val webView = webViewMap[service] ?: return
        val pageUrl = webView.url
        val isStableOffProviderPage = pageUrl != null &&
            !providerUrlMatches(service, pageUrl) &&
            webView.progress >= 100
        if (!shouldApplyPendingDesktopMode(
                observation = observation,
                trackingSupported = providerGenerationTrackingSupported(service),
                isStableOffProviderPage = isStableOffProviderPage
            )
        ) return
        pendingDesktopModes.remove(service)
        desktopModes[service] = nextDesktopMode
        applyUserAgent(webView, nextDesktopMode)
        webView.reload()
    }

    fun probeServiceActivity(service: WebAiService) {
        val webView = webViewMap[service] ?: return
        probeProviderGenerationActivity(webView, service) { observation ->
            val mappedWebView = webViewMap[service]
            val shouldApply = shouldApplyWebChatObservation(
                observation = observation,
                isLiveService = service in liveServices,
                isSameWebView = mappedWebView === webView,
                hasCurrentWebView = mappedWebView != null
            )
            if (!shouldApply) return@probeProviderGenerationActivity
            rendererInactivityConfirmed[service] = rendererInactivityConfirmedAfterObservation(
                wasConfirmed = rendererInactivityConfirmed[service] == true,
                observation = observation
            )
            val previous = activityStatuses[service] ?: WebChatActivityStatus.IDLE
            activityStatuses[service] = nextObservedWebChatActivityStatus(
                previous = previous,
                observation = observation,
                isSelected = selectedService == service,
                isLiveService = service in liveServices
            )
            applyPendingDesktopModeIfSafe(service, observation)
        }
    }

    fun finishActivateService(
        service: WebAiService,
        protectedServices: Set<WebAiService>
    ) {
        val previousService = currentSelectedService
        if (previousService != service) {
            webViewMap[previousService]?.let { webView ->
                setProviderGenerationTrackerSelected(webView, previousService, isSelected = false)
            }
            protectRendererUntilFreshInactivity(previousService)
            invalidateRendererWaiveEligibility(service)
            webViewMap[service]?.let { webView ->
                setProviderGenerationTrackerSelected(webView, service, isSelected = true)
            }
        }
        val nextWebView = webViewMap[service]
        currentUrl = nextWebView?.url ?: lastKnownUrls[service] ?: service.url
        canGoBack = nextWebView?.canGoBack() ?: false
        canGoForward = nextWebView?.canGoForward() ?: false
        loadingProgress = nextWebView?.progress ?: 0
        isLoading = nextWebView?.let { it.progress < 100 } ?: false
        viewModel.selectWebService(service)
        activityStatuses[service]?.let { status ->
            activityStatuses[service] = markWebChatActivityRead(status)
        }
        val crashedServices = rendererCrashServices.filterValues { it }.keys.toSet()
        val eligibleLiveServices = webServicesForActivation(
            current = liveServices,
            activationTarget = service,
            crashedServices = crashedServices
        )
        updateLiveServices(nextWebViewLru(eligibleLiveServices, service, protectedServices))
    }

    fun currentGeneratingServices(): Set<WebAiService> = activityStatuses
        .filterValues { it == WebChatActivityStatus.GENERATING }
        .keys
        .toSet()

    fun generationProbeTargets(services: List<WebAiService>): List<WebGenerationProbeTarget> =
        services.mapNotNull { candidate ->
            val webView = webViewMap[candidate] ?: return@mapNotNull null
            if (!providerGenerationTrackingSupported(candidate)) return@mapNotNull null
            WebGenerationProbeTarget(
                service = candidate,
                webView = webView,
                documentRevision = documentRevisions[candidate] ?: 0,
                url = webView.url
            )
        }

    fun activateWithoutProbe(
        service: WebAiService,
        knownGenerating: Set<WebAiService>
    ) {
        livePoolDecisionRequestId++
        finishActivateService(service, knownGenerating)
    }

    fun probeBeforeActivatingService(
        service: WebAiService,
        knownGenerating: Set<WebAiService>,
        probeTargets: List<WebGenerationProbeTarget>
    ) {
        val requestId = ++livePoolDecisionRequestId
        val observations = mutableMapOf<WebAiService, WebChatGenerationObservation>()
        val probeTargetsByService = probeTargets.associateBy(WebGenerationProbeTarget::service)
        var remaining = probeTargets.size

        fun finishProbeDecisionIfReady() {
            if (remaining != 0 || requestId != livePoolDecisionRequestId) return
            val invalidatedServices = mutableSetOf<WebAiService>()
            val validatedObservations = observations.mapValues { (observedService, freshObservation) ->
                val target = probeTargetsByService[observedService]
                if (target == null || webViewMap[observedService] !== target.webView) {
                    invalidatedServices += observedService
                    WebChatGenerationObservation.UNKNOWN
                } else {
                    val sameDocument = webGenerationProbeDocumentMatches(
                        expectedRevision = target.documentRevision,
                        currentRevision = documentRevisions[observedService] ?: 0,
                        expectedUrl = target.url,
                        currentUrl = target.webView.url
                    )
                    if (sameDocument) freshObservation else {
                        invalidatedServices += observedService
                        WebChatGenerationObservation.UNKNOWN
                    }
                }
            }
            validatedObservations.forEach { (observedService, freshObservation) ->
                val previous = activityStatuses[observedService] ?: WebChatActivityStatus.IDLE
                activityStatuses[observedService] = webChatActivityStatusAfterFreshLruProbe(
                    previous = previous,
                    observation = freshObservation,
                    observedService = observedService,
                    activationTarget = service
                )
            }
            finishActivateService(
                service,
                protectedWebServicesForLru(
                    knownGenerating = knownGenerating,
                    freshObservations = validatedObservations,
                    invalidatedServices = invalidatedServices
                )
            )
        }

        probeTargets.forEach { target ->
            val candidate = target.service
            val webView = target.webView
            var settled = false

            fun settleProbe(observation: WebChatGenerationObservation) {
                if (requestId != livePoolDecisionRequestId || settled) return
                settled = true
                val sameDocument = webGenerationProbeDocumentMatches(
                    expectedRevision = target.documentRevision,
                    currentRevision = documentRevisions[candidate] ?: 0,
                    expectedUrl = target.url,
                    currentUrl = webView.url
                )
                observations[candidate] = if (webViewMap[candidate] === webView && sameDocument) {
                    observation
                } else {
                    WebChatGenerationObservation.UNKNOWN
                }
                remaining--
                finishProbeDecisionIfReady()
            }

            drawerScope.launch {
                delay(LRU_GENERATION_PROBE_TIMEOUT_MS)
                settleProbe(WebChatGenerationObservation.UNKNOWN)
            }
            probeProviderGenerationActivity(
                webView = webView,
                service = candidate,
                consumeCompletion = false,
                onResult = ::settleProbe
            )
        }
    }

    fun activateService(service: WebAiService) {
        val crashedServices = rendererCrashServices.filterValues { it }.keys.toSet()
        val currentServices = webServicesForActivation(liveServices, service, crashedServices)
        if (currentServices != liveServices) liveServices = currentServices
        val knownGenerating = currentGeneratingServices()
        if (service in currentServices || currentServices.size < MAX_LIVE_WEBVIEWS) {
            activateWithoutProbe(service, knownGenerating)
            return
        }

        val probeTargets = generationProbeTargets(currentServices)
        if (probeTargets.isEmpty()) {
            activateWithoutProbe(service, knownGenerating)
            return
        }

        probeBeforeActivatingService(service, knownGenerating, probeTargets)
    }

    fun evictInactiveWebViews(mode: WebViewEvictionMode) {
        if (mode == WebViewEvictionMode.NONE) return
        livePoolDecisionRequestId++
        updateLiveServices(
            webServicesToKeepAfterMemoryPressure(
                liveServices = liveServices,
                selectedService = currentSelectedService,
                generatingServices = currentGeneratingServices(),
                mode = mode
            )
        )
    }

    val appContext = context.applicationContext
    DisposableEffect(appContext) {
        val callbacks = object : ComponentCallbacks2 {
            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) {
                evictInactiveWebViews(webViewEvictionModeForTrimMemory(level))
            }

            @Suppress("DEPRECATION")
            override fun onLowMemory() =
                evictInactiveWebViews(WebViewEvictionMode.SELECTED_ONLY)
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        appContext.registerComponentCallbacks(callbacks)
        onDispose { appContext.unregisterComponentCallbacks(callbacks) }
    }

    val lifecycleStarted = rememberWebViewLifecycleStarted(
        webViewMap = webViewMap,
        selectedService = currentSelectedService
    )

    LaunchedEffect(lifecycleStarted, liveServices) {
        if (!lifecycleStarted) return@LaunchedEffect
        var pollTick = 0
        while (true) {
            liveServices.forEach { service ->
                if (shouldProbeWebChatActivity(
                        trackingSupported = providerGenerationTrackingSupported(service),
                        isSelected = service == selectedService,
                        pollTick = pollTick
                    )
                ) {
                    probeServiceActivity(service)
                }
            }
            pollTick = (pollTick + 1) % INACTIVE_WEB_ACTIVITY_POLL_EVERY
            delay(WEB_ACTIVITY_POLL_MS)
        }
    }

    DisposableEffect(webViewMap) {
        onDispose {
            pendingFileCallback.value?.onReceiveValue(null)
            pendingFileCallback.value = null
            pendingFileService.value = null
            pendingFileRequestId.value = null
            pendingSharedUploadConfirmation?.callback?.onReceiveValue(null)
            pendingSharedUploadConfirmation = null
            pendingSharedTextInsertion.value?.let { insertion ->
                viewModel.releasePendingWebShareTextClaim(insertion.service, insertion.shareId)
                pendingSharedTextInsertion.value = null
            }
            val webViews = webViewMap.values.toList()
            webViewMap.clear()
            webViews.forEach(::releaseWebView)
        }
    }

    val activeWebView = webViewMap[selectedService]
    val isDesktopMode = desktopModes[selectedService] == true
    val studioPrompt = studioPromptForWebChat(uiState.renderedInstructions)

    fun copyStudioPrompt(message: String) {
        val prompt = studioPrompt ?: run {
            viewModel.showSnackbar("No Studio instructions are active.")
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AI Profile Instructions", prompt))
        viewModel.showSnackbar(message)
    }

    fun refreshProviderDiagnostics() {
        val service = selectedService
        val webView = webViewMap[service]
        val requestedUrl = webView?.url
        val requestedDocumentRevision = documentRevisions[service] ?: 0
        val requestId = diagnosticsProbeRequestId + 1
        diagnosticsProbeRequestId = requestId
        diagnosticsProbeResult = null
        if (webView == null || requestedUrl == null) {
            diagnosticsProbeResult = ProviderDiagnosticsProbeResult.Failed
            return
        }
        probeProviderDiagnostics(webView, service) { result ->
            val stillCurrent = diagnosticsProbeRequestId == requestId &&
                selectedService == service &&
                webViewMap[service] === webView &&
                (documentRevisions[service] ?: 0) == requestedDocumentRevision &&
                providerDiagnosticsDocumentMatches(requestedUrl, webView.url)
            if (stillCurrent) diagnosticsProbeResult = result
        }
    }

    fun openProviderDiagnostics() {
        showProviderDiagnosticsDialog = true
        refreshProviderDiagnostics()
    }

    fun applyStudioPrompt() {
        val prompt = studioPrompt ?: run {
            viewModel.showSnackbar("No Studio instructions are active.")
            return
        }
        val webView = activeWebView
        if (webView == null) {
            copyStudioPrompt("Provider is not ready yet; Studio instructions copied instead.")
            return
        }
        applyStudioPromptToFocusedEditor(webView, selectedService, prompt) { result ->
            when (result) {
                StudioPromptApplyResult.INSERTED ->
                    viewModel.showSnackbar("Studio instructions inserted into ${selectedService.shortName}.")
                StudioPromptApplyResult.NOT_EMPTY ->
                    copyStudioPrompt("Composer already has text; Studio instructions copied instead.")
                StudioPromptApplyResult.NO_EDITOR ->
                    copyStudioPrompt("Tap the provider composer first; Studio instructions copied too.")
                StudioPromptApplyResult.REJECTED ->
                    copyStudioPrompt("The provider rejected insertion; Studio instructions copied instead.")
                StudioPromptApplyResult.OFF_PROVIDER ->
                    copyStudioPrompt("Return to the provider chat; Studio instructions copied instead.")
                StudioPromptApplyResult.FAILED ->
                    copyStudioPrompt("Could not insert Studio instructions; copied them instead.")
            }
        }
    }

    fun copyClaimedSharedText(
        service: WebAiService,
        shareId: Long,
        text: String,
        successMessage: String
    ) {
        val copied = runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Shared text", text))
        }.isSuccess
        if (!copied) {
            viewModel.releasePendingWebShareTextClaim(service, shareId)
            viewModel.showSnackbar("Could not insert or copy shared text.")
            return
        }
        if (viewModel.completePendingWebShareText(service, shareId)) {
            viewModel.showSnackbar(successMessage)
        }
    }

    fun hasSharedTextClaim(service: WebAiService, shareId: Long): Boolean =
        currentPendingWebShare?.let { pending ->
            pending.service == service && pending.id == shareId && pending.isTextClaimed
        } == true

    fun finishSharedTextInsertion(
        insertion: PendingSharedTextInsertion,
        text: String,
        result: StudioPromptApplyResult
    ) {
        if (pendingSharedTextInsertion.value != insertion) return
        pendingSharedTextInsertion.value = null
        if (result == StudioPromptApplyResult.INSERTED) {
            if (viewModel.completePendingWebShareText(insertion.service, insertion.shareId)) {
                viewModel.showSnackbar("Shared text inserted into ${insertion.service.shortName}.")
            }
            return
        }
        if (hasSharedTextClaim(insertion.service, insertion.shareId)) {
            copyClaimedSharedText(
                insertion.service,
                insertion.shareId,
                text,
                "Could not insert shared text; copied it instead."
            )
        }
    }

    fun applySharedText() {
        val service = selectedService
        val pending = currentPendingWebShare?.takeIf { it.service == service } ?: return
        val text = viewModel.claimPendingWebShareText(service, pending.id) ?: return
        val webView = webViewMap[service]
        if (webView == null) {
            copyClaimedSharedText(
                service,
                pending.id,
                text,
                "Provider is not ready yet; shared text copied instead."
            )
            return
        }

        val insertion = PendingSharedTextInsertion(service, pending.id, webView)
        pendingSharedTextInsertion.value = insertion
        val insertionError = runCatching {
            applyStudioPromptToFocusedEditor(webView, service, text) { result ->
                finishSharedTextInsertion(insertion, text, result)
            }
        }.exceptionOrNull()
        if (insertionError == null || pendingSharedTextInsertion.value != insertion) return
        pendingSharedTextInsertion.value = null
        copyClaimedSharedText(
            service,
            pending.id,
            text,
            "Could not insert shared text; copied it instead."
        )
    }

    BackHandler(enabled = isActive && canGoBack && drawerState.currentValue == DrawerValue.Closed) {
        activeWebView?.let {
            if (it.canGoBack()) {
                it.goBack()
            }
        }
    }

    val providerDrawerContent: @Composable (Boolean) -> Unit = { persistent ->
        WebProviderDrawer(
            persistent = persistent,
            selectedService = selectedService,
            activityStatuses = activityStatuses,
            providerFavicons = providerFavicons,
            favoriteServices = uiState.favoriteWebServices,
            onToggleFavorite = viewModel::toggleFavoriteWebService,
            onSelectService = { service ->
                activateService(service)
                if (!persistent) drawerScope.launch { drawerState.close() }
            },
            onOpenNativeCompare = {
                if (persistent) {
                    onOpenNativeCompare()
                } else {
                    drawerScope.launch {
                        drawerState.close()
                        onOpenNativeCompare()
                    }
                }
            },
            onOpenStudio = {
                if (persistent) {
                    onOpenStudio()
                } else {
                    drawerScope.launch {
                        drawerState.close()
                        onOpenStudio()
                    }
                }
            }
        )
    }

    val webChatContent: @Composable () -> Unit = {
        Scaffold(
            topBar = {
                WebChatToolbar(
                    activeWebView = activeWebView,
                    selectedService = selectedService,
                    activityStatus = activityStatuses[selectedService] ?: WebChatActivityStatus.IDLE,
                    providerFavicon = providerFavicons[selectedService],
                    currentUrl = currentUrl,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    isDesktopMode = isDesktopMode,
                    isLoading = isLoading,
                    loadingProgress = loadingProgress,
                    showProviderDrawerButton = !persistentProviderNavigation,
                    onOpenDrawer = { drawerScope.launch { drawerState.open() } },
                    onApplyStudio = ::applyStudioPrompt,
                    onShowPromptHelper = { showPromptHelperDialog = true },
                    onShowDiagnostics = ::openProviderDiagnostics,
                    onToggleDesktopMode = {
                        val service = selectedService
                        val currentMode = pendingDesktopModes[service] ?: isDesktopMode
                        val nextDesktopMode = !currentMode
                        if (webViewMap[service] == null) {
                            pendingDesktopModes.remove(service)
                            desktopModes[service] = nextDesktopMode
                        } else {
                            pendingDesktopModes[service] = nextDesktopMode
                            probeServiceActivity(service)
                        }
                    },
                    onShowSnackbar = viewModel::showSnackbar
                )
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
            // Keep only a tiny MRU set of provider WebViews alive. Cookies and storage remain provider-owned.
            liveServices.forEach { service ->
                key(service, webViewInstanceRevisions[service] ?: 0) {
                    val isCurrentService = selectedService == service
                    val isRendererInactivityConfirmed = rendererInactivityConfirmed[service] == true

                    Box(
                        modifier = if (isCurrentService) {
                            Modifier.fillMaxSize()
                        } else {
                            // Do not chain size(0.dp) after fillMaxSize(): the exact full-size
                            // constraints win, leaving the inactive WebView visible on top.
                            Modifier.size(0.dp)
                        }
                    ) {
                        if (rendererCrashServices[service] == true) {
                            if (isCurrentService) {
                                WebRendererCrashFallback(
                                    service = service,
                                    onRetry = { retryRendererAfterCrash(service) }
                                )
                            }
                        } else {
                            AndroidView(
                        factory = { ctx ->
                            val pendingDesktopMode = pendingDesktopModes[service]
                            val initialDesktopMode = pendingDesktopMode ?: (desktopModes[service] == true)
                            createConfiguredWebView(
                                context = ctx,
                                service = service,
                                initialUrl = lastKnownUrls[service] ?: service.url,
                                isDesktop = initialDesktopMode,
                                isServiceSelected = { selectedService == service },
                                isRendererInactivityConfirmed = {
                                    rendererInactivityConfirmed[service] == true
                                },
                                onDocumentStarted = {
                                    invalidateRendererWaiveEligibility(service)
                                    documentRevisions[service] = (documentRevisions[service] ?: 0) + 1
                                    if (selectedService == service && showProviderDiagnosticsDialog) {
                                        diagnosticsProbeResult = ProviderDiagnosticsProbeResult.Failed
                                    }
                                },
                                onUrlChanged = { url ->
                                    lastKnownUrls[service] = url
                                    if (selectedService == service) {
                                        currentUrl = url
                                    }
                                },
                                onTitleChanged = {},
                                onFaviconChanged = { favicon ->
                                    providerFavicons[service] = favicon
                                },
                                onProgressChanged = { progress ->
                                    if (selectedService == service) {
                                        loadingProgress = progress
                                        isLoading = progress < 100
                                    }
                                },
                                onNavStateChanged = { back, fwd ->
                                    if (selectedService == service) {
                                        canGoBack = back
                                        canGoForward = fwd
                                    }
                                },
                                onExternalIntentRequested = { uri ->
                                    pendingExternalIntentUri = uri
                                },
                                onExternalNavigationFailed = {
                                    viewModel.showSnackbar("Could not open external link")
                                },
                                onRendererGone = { deadView, didCrash ->
                                    handleRendererGone(service, deadView, didCrash)
                                },
                                onFileChooserRequested = { callback, params, pageUrl ->
                                    if (pendingFileCallback.value != null ||
                                        pendingSharedUploadConfirmation != null
                                    ) {
                                        recordFileChooserRequest(
                                            service,
                                            params,
                                            pageUrl,
                                            "rejected: chooser already active"
                                        )
                                        callback.onReceiveValue(null)
                                        true
                                    } else {
                                        val stagedShare = currentPendingWebShare
                                            ?.takeIf { it.service == service }
                                        val sharedUris = sharedUrisForFileChooser(
                                            context = context,
                                            params = params,
                                            uriStrings = stagedShare?.payload?.uriStrings.orEmpty(),
                                            mimeTypeHint = stagedShare?.payload?.mimeTypeHint
                                        )
                                        if (stagedShare != null && sharedUris.isNotEmpty()) {
                                            val requestId = recordFileChooserRequest(
                                                service,
                                                params,
                                                pageUrl,
                                                "awaiting shared upload confirmation"
                                            )
                                            pendingSharedUploadConfirmation = PendingSharedUploadConfirmation(
                                                service = service,
                                                shareId = stagedShare.id,
                                                uris = sharedUris,
                                                callback = callback,
                                                requestId = requestId
                                            )
                                            true
                                        } else {
                                            val requestId = recordFileChooserRequest(
                                                service,
                                                params,
                                                pageUrl,
                                                "picker launched"
                                            )
                                            pendingFileCallback.value = callback
                                            pendingFileService.value = service
                                            pendingFileRequestId.value = requestId
                                            val launchError = runCatching {
                                                fileChooserLauncher.launch(params.createIntent())
                                            }.exceptionOrNull()
                                            if (launchError == null) {
                                                true
                                            } else {
                                                val outcome = when (launchError) {
                                                    is ActivityNotFoundException -> {
                                                        Log.w(WEBVIEW_LOG_TAG, "No file picker available", launchError)
                                                        viewModel.showSnackbar("No file picker available")
                                                        "no picker available"
                                                    }
                                                    is SecurityException -> {
                                                        Log.w(WEBVIEW_LOG_TAG, "File picker launch blocked", launchError)
                                                        viewModel.showSnackbar("File picker was blocked")
                                                        "picker blocked"
                                                    }
                                                    is IllegalStateException -> {
                                                        Log.w(WEBVIEW_LOG_TAG, "File picker already active", launchError)
                                                        "picker already active"
                                                    }
                                                    else -> {
                                                        pendingFileCallback.value = null
                                                        pendingFileService.value = null
                                                        pendingFileRequestId.value = null
                                                        callback.onReceiveValue(null)
                                                        throw launchError
                                                    }
                                                }
                                                pendingFileCallback.value = null
                                                pendingFileService.value = null
                                                pendingFileRequestId.value = null
                                                updateFileChooserOutcome(service, requestId, outcome)
                                                callback.onReceiveValue(null)
                                                true
                                            }
                                        }
                                    }
                                }
                            ).also { wv ->
                                wv.visibility = providerWebViewVisibility(isCurrentService)
                                webViewMap[service] = wv
                                if (pendingDesktopMode != null &&
                                    pendingDesktopModes[service] == pendingDesktopMode
                                ) {
                                    desktopModes[service] = pendingDesktopMode
                                    pendingDesktopModes.remove(service)
                                }
                            }
                        },
                        update = { wv ->
                            wv.visibility = providerWebViewVisibility(isCurrentService)
                            wv.setRendererPriorityPolicy(
                                WebView.RENDERER_PRIORITY_IMPORTANT,
                                rendererPriorityWaivedWhenNotVisible(
                                    isSelected = isCurrentService,
                                    trackingSupported = providerGenerationTrackingSupported(service),
                                    inactivityConfirmed = isRendererInactivityConfirmed
                                )
                            )
                            if (isCurrentService && lifecycleStarted) {
                                wv.onResume()
                            } else {
                                wv.onPause()
                            }
                            if (isCurrentService) {
                                canGoBack = wv.canGoBack()
                                canGoForward = wv.canGoForward()
                                wv.url?.let { currentUrl = it }
                            }
                        },
                        onRelease = { wv ->
                            releaseSharedTextClaimFor(wv)
                            if (webViewMap.remove(service) === wv) {
                                releaseWebView(wv)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                currentPendingWebShare
                    ?.takeIf { it.service == selectedService }
                    ?.let { pending ->
                        SharedContentBanner(
                            service = pending.service,
                            payload = pending.payload,
                            isTextClaimed = pending.isTextClaimed,
                            onInsertText = ::applySharedText,
                            onDismiss = {
                                viewModel.dismissPendingWebShare(pending.service, pending.id)
                            },
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
            }
        }
        }

    }

    if (persistentProviderNavigation) {
        PermanentNavigationDrawer(
            drawerContent = { providerDrawerContent(true) },
            modifier = modifier.fillMaxSize()
        ) {
            webChatContent()
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = false,
            drawerContent = { providerDrawerContent(false) },
            modifier = modifier.fillMaxSize()
        ) {
            webChatContent()
        }
    }

    // Quick Prompt / Profile Copier Dialog
    ExternalIntentConfirmationDialog(
        uri = pendingExternalIntentUri.takeIf { isActive },
        onDismiss = { pendingExternalIntentUri = null },
        onConfirm = { pendingUri ->
            pendingExternalIntentUri = null
            openExternalIntentUri(context, pendingUri)
        }
    )

    pendingSharedUploadConfirmation?.takeIf { isActive }?.let { confirmation ->
        SharedUploadConfirmationDialog(
            service = confirmation.service,
            attachmentCount = confirmation.uris.size,
            onDismiss = {
                if (pendingSharedUploadConfirmation == confirmation) {
                    pendingSharedUploadConfirmation = null
                    updateFileChooserOutcome(
                        confirmation.service,
                        confirmation.requestId,
                        "shared upload cancelled"
                    )
                    confirmation.callback.onReceiveValue(null)
                }
            },
            onConfirm = {
                if (pendingSharedUploadConfirmation == confirmation) {
                    pendingSharedUploadConfirmation = null
                    val consumed = viewModel.consumePendingWebShareUris(
                        service = confirmation.service,
                        shareId = confirmation.shareId,
                        uriStrings = confirmation.uris.map(Uri::toString)
                    )
                    if (consumed) {
                        updateFileChooserOutcome(
                            confirmation.service,
                            confirmation.requestId,
                            "shared upload confirmed (${confirmation.uris.size})"
                        )
                        confirmation.callback.onReceiveValue(confirmation.uris.toTypedArray())
                    } else {
                        updateFileChooserOutcome(
                            confirmation.service,
                            confirmation.requestId,
                            "shared upload stale / cancelled"
                        )
                        confirmation.callback.onReceiveValue(null)
                        viewModel.showSnackbar("Shared content changed; upload cancelled.")
                    }
                }
            }
        )
    }

    if (isActive && showProviderDiagnosticsDialog) {
        val webViewPackage = WebView.getCurrentWebViewPackage()?.let { packageInfo ->
            listOfNotNull(packageInfo.packageName, packageInfo.versionName).joinToString(" ")
        } ?: "Unavailable"
        ProviderDiagnosticsDialog(
            service = selectedService,
            host = providerDiagnosticsHost(currentUrl) ?: "Unavailable",
            providerOwned = providerUrlMatches(selectedService, currentUrl),
            webViewPackage = webViewPackage,
            isDesktopMode = isDesktopMode,
            activityTrackingSupported = providerGenerationTrackingSupported(selectedService),
            activityStatus = activityStatuses[selectedService] ?: WebChatActivityStatus.IDLE,
            fileChooserRequests = fileChooserRequestCounts[selectedService] ?: 0,
            fileChooserMode = fileChooserModes[selectedService],
            fileChooserHost = fileChooserHosts[selectedService],
            fileChooserAcceptTypes = fileChooserAcceptTypes[selectedService],
            fileChooserOutcome = fileChooserOutcomes[selectedService],
            probeResult = diagnosticsProbeResult,
            onRefresh = ::refreshProviderDiagnostics,
            onDismiss = { showProviderDiagnosticsDialog = false },
            onCopyReport = { report ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Aistee provider diagnostics", report))
                viewModel.showSnackbar("Copied privacy-safe provider diagnostics.")
            }
        )
    }

    if (isActive && showPromptHelperDialog) {
        AlertDialog(
            onDismissRequest = { showPromptHelperDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, tint = AccentCyan)
                    Text("Quick Prompt & Profile", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Tap ${selectedService.shortName}'s composer, then apply the current Studio profile. Aistee only inserts into an empty focused editor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                    ) {
                        Text(
                            text = studioPrompt ?: "No Studio instructions are active. Configure a profile in Studio first.",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    Button(
                        onClick = {
                            applyStudioPrompt()
                            showPromptHelperDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                        enabled = studioPrompt != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Apply Studio to focused composer")
                    }

                    OutlinedButton(
                        onClick = {
                            copyStudioPrompt("Copied Studio instructions to clipboard.")
                            showPromptHelperDialog = false
                        },
                        enabled = studioPrompt != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy Studio Instructions")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPromptHelperDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun WebChatToolbar(
    activeWebView: WebView?,
    selectedService: WebAiService,
    activityStatus: WebChatActivityStatus,
    providerFavicon: Bitmap?,
    currentUrl: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isDesktopMode: Boolean,
    isLoading: Boolean,
    loadingProgress: Int,
    showProviderDrawerButton: Boolean,
    onOpenDrawer: () -> Unit,
    onApplyStudio: () -> Unit,
    onShowPromptHelper: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onShowSnackbar: (String) -> Unit
) {
    val context = LocalContext.current
    val brandColor = Color(selectedService.brandHexColor)
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showProviderDrawerButton) {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("btn_web_provider_drawer")
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = "Switch AI service")
                    }
                }

                WebProviderIdentityIcon(
                    service = selectedService,
                    favicon = providerFavicon,
                    fallbackTint = brandColor,
                    size = 20.dp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = selectedService.shortName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                WebProviderActivityIndicator(selectedService, activityStatus, brandColor)
                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = onApplyStudio,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("btn_web_apply_studio")
                ) {
                    Icon(Icons.Default.Tune, contentDescription = "Apply Studio profile")
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("btn_web_more")
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Web chat options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Back") },
                            leadingIcon = { Icon(Icons.Default.ArrowBack, contentDescription = null) },
                            enabled = canGoBack,
                            onClick = {
                                activeWebView?.goBack()
                                menuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Forward") },
                            leadingIcon = { Icon(Icons.Default.ArrowForward, contentDescription = null) },
                            enabled = canGoForward,
                            onClick = {
                                activeWebView?.goForward()
                                menuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Reload") },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            onClick = {
                                activeWebView?.reload()
                                menuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Provider home") },
                            leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                            onClick = {
                                activeWebView?.loadUrl(selectedService.url)
                                menuExpanded = false
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Copy address") },
                            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("URL", currentUrl))
                                onShowSnackbar("Copied address")
                                menuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Quick prompt / profile") },
                            leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onShowPromptHelper()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Provider diagnostics") },
                            leadingIcon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onShowDiagnostics()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isDesktopMode) "Use mobile site" else "Use desktop site") },
                            leadingIcon = {
                                Icon(
                                    if (isDesktopMode) Icons.Default.Smartphone else Icons.Default.Laptop,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                if (providerGenerationTrackingSupported(selectedService)) {
                                    onToggleDesktopMode()
                                } else {
                                    onShowSnackbar(
                                        "Display mode switching is disabled until ${selectedService.shortName} activity tracking is verified."
                                    )
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Open in browser") },
                            leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                            onClick = {
                                if (!openExternalUri(context, Uri.parse(currentUrl))) {
                                    onShowSnackbar("Could not launch external browser")
                                }
                                menuExpanded = false
                            }
                        )
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(
                    progress = { loadingProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = brandColor,
                    trackColor = Color.Transparent
                )
            }
        }
    }
}

@Composable
private fun WebProviderDrawer(
    persistent: Boolean,
    selectedService: WebAiService,
    activityStatuses: Map<WebAiService, WebChatActivityStatus>,
    providerFavicons: Map<WebAiService, Bitmap>,
    favoriteServices: Set<WebAiService>,
    onToggleFavorite: (WebAiService) -> Unit,
    onSelectService: (WebAiService) -> Unit,
    onOpenNativeCompare: () -> Unit,
    onOpenStudio: () -> Unit
) {
    val sections = webChatSections(favoriteServices)
    val drawerContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Chats",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp)
            )

            if (sections.favorites.isNotEmpty()) {
                Text(
                    text = "Favorites",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 4.dp)
                )
                sections.favorites.forEach { service ->
                    WebProviderDrawerItem(
                        service = service,
                        isSelected = selectedService == service,
                        isFavorite = true,
                        activityStatus = activityStatuses[service] ?: WebChatActivityStatus.IDLE,
                        favicon = providerFavicons[service],
                        onToggleFavorite = { onToggleFavorite(service) },
                        onSelect = { onSelectService(service) }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            sections.primary.forEach { service ->
                WebProviderDrawerItem(
                    service = service,
                    isSelected = selectedService == service,
                    isFavorite = service in favoriteServices,
                    activityStatus = activityStatuses[service] ?: WebChatActivityStatus.IDLE,
                    favicon = providerFavicons[service],
                    onToggleFavorite = { onToggleFavorite(service) },
                    onSelect = { onSelectService(service) }
                )
            }

            if (sections.additional.isNotEmpty()) {
                Text(
                    text = "More chats",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)
                )
                sections.additional.forEach { service ->
                    WebProviderDrawerItem(
                        service = service,
                        isSelected = selectedService == service,
                        isFavorite = service in favoriteServices,
                        activityStatus = activityStatuses[service] ?: WebChatActivityStatus.IDLE,
                        favicon = providerFavicons[service],
                        onToggleFavorite = { onToggleFavorite(service) },
                        onSelect = { onSelectService(service) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(
                label = { Text("Compare Hub") },
                selected = false,
                onClick = onOpenNativeCompare,
                shape = MaterialTheme.shapes.medium,
                icon = {
                    Icon(
                        Icons.Default.CompareArrows,
                        contentDescription = null,
                        tint = AccentCyan
                    )
                },
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .testTag("btn_switch_to_native_hub")
            )
            NavigationDrawerItem(
                label = { Text("Studio") },
                selected = false,
                onClick = onOpenStudio,
                shape = MaterialTheme.shapes.medium,
                icon = {
                    Icon(Icons.Default.Tune, contentDescription = null)
                },
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .testTag("btn_switch_to_studio")
            )
        }
    }
    if (persistent) {
        PermanentDrawerSheet(modifier = Modifier.width(292.dp)) {
            drawerContent()
        }
    } else {
        ModalDrawerSheet(modifier = Modifier.width(292.dp)) {
            drawerContent()
        }
    }
}

@Composable
private fun WebProviderDrawerItem(
    service: WebAiService,
    isSelected: Boolean,
    isFavorite: Boolean,
    activityStatus: WebChatActivityStatus,
    favicon: Bitmap?,
    onToggleFavorite: () -> Unit,
    onSelect: () -> Unit
) {
    val brandColor = Color(service.brandHexColor)
    NavigationDrawerItem(
        label = {
            Text(
                text = service.shortName,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        selected = isSelected,
        onClick = onSelect,
        shape = MaterialTheme.shapes.medium,
        icon = {
            WebProviderIdentityIcon(
                service = service,
                favicon = favicon,
                fallbackTint = if (isSelected) brandColor else MaterialTheme.colorScheme.onSurfaceVariant,
                size = 24.dp
            )
        },
        badge = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WebProviderActivityIndicator(service, activityStatus, brandColor)
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("btn_favorite_web_service_${service.id}")
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (isFavorite) {
                            "Remove ${service.shortName} from favorites"
                        } else {
                            "Add ${service.shortName} to favorites"
                        },
                        tint = if (isFavorite) brandColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = brandColor.copy(alpha = 0.12f),
            selectedIconColor = brandColor
        ),
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .semantics {
                contentDescription = "Switch to ${service.shortName}"
                when (activityStatus) {
                    WebChatActivityStatus.GENERATING -> stateDescription = "Generating response"
                    WebChatActivityStatus.UNREAD -> stateDescription = "Unread response"
                    WebChatActivityStatus.PENDING ->
                        stateDescription = "Response status pending after tab eviction"
                    WebChatActivityStatus.IDLE -> Unit
                }
            }
            .testTag("tab_web_service_${service.id}")
    )
}

@Composable
private fun WebProviderIdentityIcon(
    service: WebAiService,
    favicon: Bitmap?,
    fallbackTint: Color,
    size: androidx.compose.ui.unit.Dp
) {
    if (favicon != null) {
        Image(
            bitmap = favicon.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(MaterialTheme.shapes.extraSmall)
        )
    } else {
        Icon(
            imageVector = getWebServiceIcon(service),
            contentDescription = null,
            tint = fallbackTint,
            modifier = Modifier.size(size)
        )
    }
}

@Composable
private fun WebProviderActivityIndicator(
    service: WebAiService,
    activityStatus: WebChatActivityStatus,
    brandColor: Color
) {
    when (activityStatus) {
        WebChatActivityStatus.GENERATING -> CircularProgressIndicator(
            modifier = Modifier
                .size(10.dp)
                .testTag("status_web_service_${service.id}_generating"),
            strokeWidth = 1.5.dp,
            color = brandColor
        )
        WebChatActivityStatus.UNREAD -> Box(
            modifier = Modifier
                .size(8.dp)
                .background(brandColor, CircleShape)
                .testTag("status_web_service_${service.id}_unread")
        )
        WebChatActivityStatus.PENDING -> Box(
            modifier = Modifier
                .size(8.dp)
                .border(1.dp, brandColor, CircleShape)
                .testTag("status_web_service_${service.id}_pending")
        )
        WebChatActivityStatus.IDLE -> Unit
    }
}

private fun releaseTerminatedWebView(webView: WebView) {
    (webView.parent as? ViewGroup)?.removeView(webView)
    webView.destroy()
}

private fun releaseWebView(webView: WebView) {
    (webView.parent as? ViewGroup)?.removeView(webView)
    webView.onPause()
    webView.stopLoading()
    webView.webChromeClient = null
    webView.webViewClient = WebViewClient()
    webView.destroy()
}

/** Builds the least-privileged WebView needed by account-backed providers. */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createConfiguredWebView(
    context: Context,
    service: WebAiService,
    initialUrl: String,
    isDesktop: Boolean,
    isServiceSelected: () -> Boolean,
    isRendererInactivityConfirmed: () -> Boolean,
    onDocumentStarted: () -> Unit,
    onUrlChanged: (String) -> Unit,
    onTitleChanged: (String) -> Unit,
    onFaviconChanged: (Bitmap) -> Unit,
    onProgressChanged: (Int) -> Unit,
    onNavStateChanged: (canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    onExternalIntentRequested: (Uri) -> Unit,
    onExternalNavigationFailed: () -> Unit,
    onRendererGone: (WebView, Boolean) -> Unit,
    onFileChooserRequested: (
        ValueCallback<Array<Uri>>,
        WebChromeClient.FileChooserParams,
        pageUrl: String?
    ) -> Boolean
): WebView {
    val webView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Only hidden tracked chats with freshly confirmed inactivity may waive priority.
        setRendererPriorityPolicy(
            WebView.RENDERER_PRIORITY_IMPORTANT,
            rendererPriorityWaivedWhenNotVisible(
                isSelected = isServiceSelected(),
                trackingSupported = providerGenerationTrackingSupported(service),
                inactivityConfirmed = isRendererInactivityConfirmed()
            )
        )
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.requestFocusFromTouch()
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE ->
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        // Web Settings configured for modern SPA web applications (ChatGPT, Claude, Gemini, DeepSeek, Kimi, Vibe)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowContentAccess = false
            allowFileAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
        }

        // Enable Cookies and 3rd-party cookies for OAuth logins (Google, Apple, Microsoft, Auth0)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)

        applyUserAgent(this, isDesktop)

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgressChanged(newProgress)
                onNavStateChanged(view?.canGoBack() ?: false, view?.canGoForward() ?: false)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                title?.let { onTitleChanged(it) }
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                val pageUrl = view?.url ?: return
                if (icon != null && providerUrlMatches(service, pageUrl)) {
                    onFaviconChanged(icon)
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val callback = filePathCallback ?: return false
                val params = fileChooserParams ?: return false
                return onFileChooserRequested(callback, params, webView?.url)
            }
        }

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                onDocumentStarted()
                url?.let { pageUrl ->
                    onUrlChanged(pageUrl)
                    if (favicon != null && providerUrlMatches(service, pageUrl)) {
                        onFaviconChanged(favicon)
                    }
                }
                onNavStateChanged(view?.canGoBack() ?: false, view?.canGoForward() ?: false)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                url?.let { pageUrl ->
                    onUrlChanged(pageUrl)
                    view?.let { webView ->
                        applyProviderWebTweaks(webView, service, pageUrl)
                        installStudioPromptTargetTracker(webView, service, pageUrl)
                        installProviderGenerationTracker(
                            webView,
                            service,
                            isSelected = isServiceSelected()
                        )
                    }
                }
                onNavStateChanged(view?.canGoBack() ?: false, view?.canGoForward() ?: false)
                CookieManager.getInstance().flush()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val navigation = request ?: return true
                return handleMainFrameNavigation(
                    context,
                    service,
                    navigation,
                    onExternalIntentRequested,
                    onExternalNavigationFailed
                )
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                onRendererGone(view, detail.didCrash())
                return true
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.cancel()
            }
        }

        loadUrl(initialUrl)
    }

    return webView
}

/** Switches between the installed WebView mobile UA and a desktop-shaped variant. */
private fun applyUserAgent(webView: WebView, isDesktop: Boolean) {
    val defaultUserAgent = WebSettings.getDefaultUserAgent(webView.context)
    webView.settings.userAgentString = if (isDesktop) {
        defaultUserAgent
            .replace(Regex("\\([^)]*Android[^)]*\\)"), "(X11; Linux x86_64)")
            .replace(" Version/4.0", "")
            .replace(Regex("\\s+Mobile(?=\\s|$)"), "")
    } else {
        defaultUserAgent
    }
}

/** Maps a provider to its lightweight toolbar icon. */
fun getWebServiceIcon(service: WebAiService): ImageVector {
    return when (service) {
        WebAiService.CLAUDE -> Icons.Default.Flare
        WebAiService.CHATGPT -> Icons.Default.SmartToy
        WebAiService.GEMINI -> Icons.Default.AutoAwesome
        WebAiService.DEEPSEEK -> Icons.Default.Psychology
        WebAiService.KIMI -> Icons.Default.ElectricBolt
        WebAiService.VIBE -> Icons.Default.Air
        WebAiService.QWEN -> Icons.Default.Hub
        WebAiService.COPILOT -> Icons.Default.AutoAwesome
        WebAiService.ZAI -> Icons.Default.Memory
        WebAiService.GROK -> Icons.Default.Public
        WebAiService.CHARACTER_AI -> Icons.Default.Groups
        WebAiService.VENICE -> Icons.Default.Lock
        WebAiService.META_AI -> Icons.Default.AllInclusive
    }
}
