package ais.tee.web

import android.webkit.WebView
import ais.tee.data.model.WebAiService

/** An explicit, current-page-only navigation action. Never reads or retains message content. */
internal enum class WebUserTurnDirection { PREVIOUS, NEXT }

internal enum class WebUserTurnJumpResult {
    MOVED,
    NO_TURNS,
    BOUNDARY,
    OFF_PROVIDER,
    STALE,
    FAILED
}

/** Provider-scoped opt-out for experimental, user-triggered navigation. */
internal fun webUserTurnNavigationEnabled(
    service: WebAiService,
    disabledServices: Set<WebAiService>
): Boolean = ProviderWebRegistry.turnAnchors(service) != null && service !in disabledServices

/**
 * The registry contains candidate selectors, not an assertion of compatibility. On each user
 * action, verify visible matching nodes in the loaded document and fail closed if none match.
 */
internal fun webUserTurnJumpScript(
    service: WebAiService,
    direction: WebUserTurnDirection,
    expectedGeneration: Long? = null
): String? {
    // No provider-controlled URL is included in the executable script.
    // The native call site validates the URL separately.
    val anchors = ProviderWebRegistry.turnAnchors(service) ?: return null
    val previous = direction == WebUserTurnDirection.PREVIOUS
    val selector = javascriptStringLiteral(anchors.userSelector)
    val routeGuard = expectedGeneration?.let { generation ->
        """
            var routeTracker = window.__aisteeTurnRouteTracker;
            if (!routeTracker || routeTracker.generation !== $generation ||
                routeTracker.route !== String(location.href || '') ||
                history.pushState !== routeTracker.pushStateHook ||
                history.replaceState !== routeTracker.replaceStateHook ||
                typeof routeTracker.turnMatches !== 'function' ||
                !routeTracker.turnMatches()) return 'stale';
        """.trimIndent()
    }.orEmpty()
    return """
        (function() {
            ${providerRuntimeGuardScript(service, "'off-provider'")}
            $routeGuard
            // The native preflight binds an exact route in this page's own JS context.
            // Never splice an untrusted URL into executable JavaScript.
            if (String(window.__aisteeVerifiedTurnRoute || '') !==
                String(location.href || '')) return 'off-provider';
            try {
                var matches = document.querySelectorAll($selector);
                var turns = [];
                ${webUserTurnVisibilityScript()}
                for (var i = 0; i < matches.length; i++) {
                    var node = matches[i];
                    var rect = node.getBoundingClientRect();
                    if (rect && rect.width > 0 && rect.height > 0 && isVisible(node)) {
                        turns.push({ node: node, rect: rect });
                    }
                }
                if (turns.length === 0) return 'no-turns';

                var height = window.innerHeight || document.documentElement.clientHeight || 0;
                if (height <= 0) return 'failed';
                var middle = height / 2;
                var margin = 8;
                var target = null;
                // A real touch, mouse, wheel or key action can move the page independently
                // of Aistee's programmatic jumps. Forget the prior DOM anchor on interaction.
                // Install only one tiny page-local listener set; do not inspect message text.
                if (!window.__aisteeTurnGestureResetInstalled &&
                    typeof window.addEventListener === 'function') {
                    var clearJumpCursor = function() {
                        window.__aisteeUserTurnCursor = null;
                    };
                    ['touchstart', 'pointerdown', 'wheel', 'keydown'].forEach(function(type) {
                        window.addEventListener(type, clearJumpCursor, true);
                    });
                    window.__aisteeTurnGestureResetInstalled = true;
                }
                // The last jump may have been clamped by the provider's scroll container.
                // Keep only an ephemeral DOM reference, never text, message IDs or history.
                var last = window.__aisteeUserTurnCursor;
                var lastNode = last && last.nodeRef ? last.nodeRef.deref() : last && last.node;
                var anchor = -1;
                if (last && last.url === String(location.href || '')) {
                    for (var a = 0; a < turns.length; a++) {
                        if (turns[a].node === lastNode) {
                            var oldRect = turns[a].rect;
                            if (oldRect.bottom > 0 && oldRect.top < height) anchor = a;
                            break;
                        }
                    }
                }
                if (anchor >= 0) {
                    var adjacent = anchor + ($previous ? -1 : 1);
                    if (adjacent >= 0 && adjacent < turns.length) target = turns[adjacent];
                } else if ($previous) {
                    // A tall, centered turn has a top above the midpoint; requiring
                    // its bottom to be above the midpoint excludes the current turn.
                    for (var j = 0; j < turns.length; j++) {
                        if (turns[j].rect.bottom < middle - margin) {
                            if (!target || turns[j].rect.bottom > target.rect.bottom) {
                                target = turns[j];
                            }
                        }
                    }
                } else {
                    for (var k = 0; k < turns.length; k++) {
                        if (turns[k].rect.top > middle + margin) {
                            if (!target || turns[k].rect.top < target.rect.top) {
                                target = turns[k];
                            }
                        }
                    }
                }
                if (!target) return 'boundary';
                target.node.scrollIntoView({ behavior: 'auto', block: 'center' });
                $routeGuard
                window.__aisteeUserTurnCursor = {
                    node: target.node,
                    url: String(location.href || '')
                };
                return 'moved';
            } catch (e) {
                return 'failed';
            }
        })();
    """.trimIndent()
}

/**
 * Page-local route revision, installed only on explicit turn navigation. No JS bridge,
 * page text or persisted identifiers. Detects same-URL SPA history changes and usual
 * conversation rerenders, including calls through pre-captured history methods.
 */
internal fun webUserTurnRouteTrackerScript(service: WebAiService): String? {
    val anchors = ProviderWebRegistry.turnAnchors(service) ?: return null
    val selector = javascriptStringLiteral(anchors.userSelector)
    return """
        (function() {
            var route = String(location.href || '');
            var tracker = window.__aisteeTurnRouteTracker;
            if (!tracker) {
                tracker = {
                    generation: -1,
                    route: route,
                    advance: function() {
                        this.generation++;
                        this.route = String(location.href || '');
                    }
                };
                window.__aisteeTurnRouteTracker = tracker;
                window.addEventListener('popstate', function() { tracker.advance(); }, true);
                window.addEventListener('hashchange', function() { tracker.advance(); }, true);
            }

            // Providers can replace history methods after our first jump.
            // Restore hooks on the next explicit jump; existing requests fail closed.
            if (typeof history.pushState !== 'function' ||
                typeof history.replaceState !== 'function') return '';
            if (history.pushState !== tracker.pushStateHook) {
                var pushState = history.pushState;
                tracker.pushStateHook = function() {
                    var result = pushState.apply(this, arguments);
                    tracker.advance();
                    return result;
                };
                history.pushState = tracker.pushStateHook;
            }
            if (history.replaceState !== tracker.replaceStateHook) {
                var replaceState = history.replaceState;
                tracker.replaceStateHook = function() {
                    var result = replaceState.apply(this, arguments);
                    tracker.advance();
                    return result;
                };
                history.replaceState = tracker.replaceStateHook;
            }

            // Explicit requests also invalidate earlier pending callbacks.
            tracker.advance();
            var turns = document.querySelectorAll($selector);
            tracker.turnCount = turns.length;
            tracker.firstTurn = turns.length ? turns[0] : null;
            tracker.lastTurn = turns.length ? turns[turns.length - 1] : null;
            // A provider may have cached the original history method before our hook
            // was installed. Check DOM identity to reject ordinary same-URL rerenders.
            // No message content, IDs or attributes are read or stored.
            tracker.turnMatches = function() {
                var current = document.querySelectorAll($selector);
                return current.length === this.turnCount &&
                    (!current.length ||
                        (current[0] === this.firstTurn &&
                         current[current.length - 1] === this.lastTurn));
            };
            window.__aisteeVerifiedTurnRoute = route;
            return route + '\n' + String(tracker.generation);
        })();
    """.trimIndent()
}

/** Verify a completed jump against both route generation and user-turn DOM identity. */
internal fun webUserTurnRouteVerificationScript(): String = """
    (function() {
        var tracker = window.__aisteeTurnRouteTracker;
        if (!tracker || tracker.route !== String(location.href || '') ||
            history.pushState !== tracker.pushStateHook ||
            history.replaceState !== tracker.replaceStateHook ||
            typeof tracker.turnMatches !== 'function' ||
            !tracker.turnMatches()) return '-1';
        return String(tracker.generation);
    })();
""".trimIndent()

internal fun parseWebUserTurnJumpResult(raw: String?): WebUserTurnJumpResult = when (
    raw?.trim()?.removeSurrounding("\"")
) {
    "moved" -> WebUserTurnJumpResult.MOVED
    "no-turns" -> WebUserTurnJumpResult.NO_TURNS
    "boundary" -> WebUserTurnJumpResult.BOUNDARY
    "off-provider" -> WebUserTurnJumpResult.OFF_PROVIDER
    else -> WebUserTurnJumpResult.FAILED
}

internal fun webUserTurnResultForRoute(
    raw: String?,
    expectedUrl: String,
    currentUrl: String?,
    expectedGeneration: Long? = null,
    currentGeneration: Long? = null
): WebUserTurnJumpResult =
    if (currentUrl == expectedUrl &&
        (expectedGeneration == null || currentGeneration == expectedGeneration)
    ) parseWebUserTurnJumpResult(raw)
    else WebUserTurnJumpResult.STALE

internal fun jumpToWebUserTurn(
    webView: WebView,
    service: WebAiService,
    direction: WebUserTurnDirection,
    isCurrent: () -> Boolean,
    onResult: (WebUserTurnJumpResult) -> Unit
) {
    val expectedUrl = webView.url
    if (expectedUrl == null || !providerUrlMatches(service, expectedUrl) ||
        webView.settings.allowContentAccess || webView.settings.allowFileAccess
    ) {
        onResult(WebUserTurnJumpResult.OFF_PROVIDER)
        return
    }
    if (webUserTurnJumpScript(service, direction) == null) {
        onResult(WebUserTurnJumpResult.OFF_PROVIDER)
        return
    }
    // Install a page-local SPA generation guard without interpolating the provider URL.
    webView.evaluateJavascript(requireNotNull(webUserTurnRouteTrackerScript(service))) preflight@{ rawLocation ->
        // Every release path removes the view from the live map before destroy().
        // Check ownership BEFORE any further WebView access in asynchronous callbacks.
        if (!isCurrent()) return@preflight
        val reported = runCatching {
            org.json.JSONTokener(rawLocation).nextValue() as? String
        }.getOrNull()
        val expectedPrefix = "$expectedUrl\n"
        val expectedGeneration = reported?.takeIf { it.startsWith(expectedPrefix) }
            ?.removePrefix(expectedPrefix)?.toLongOrNull()
        if (expectedGeneration == null || webView.url != expectedUrl) {
            onResult(WebUserTurnJumpResult.STALE)
            return@preflight
        }
        val script = requireNotNull(
            webUserTurnJumpScript(service, direction, expectedGeneration)
        )
        webView.evaluateJavascript(script) jump@{ raw ->
            if (!isCurrent()) return@jump
            // A same-URL SPA transition can occur after JS finishes but before
            // Android delivers the result. Re-read the page-local generation.
            webView.evaluateJavascript(
                webUserTurnRouteVerificationScript()
            ) verify@{ rawGeneration ->
                if (!isCurrent()) return@verify
                val currentGeneration = runCatching {
                    (org.json.JSONTokener(rawGeneration).nextValue() as? String)
                        ?.toLongOrNull()
                }.getOrNull()
                onResult(
                    webUserTurnResultForRoute(
                        raw, expectedUrl, webView.url, expectedGeneration, currentGeneration
                    )
                )
            }
        }
    }
}

internal fun webUserTurnVisibilityScript(): String = """
    function isVisible(node) {
        var parent = node;
        while (parent && parent.nodeType === 1) {
            if (parent.matches &&
                parent.matches('[hidden], [inert], [aria-hidden="true"]')) {
                return false;
            }
            var style = window.getComputedStyle ? window.getComputedStyle(parent) : null;
            if (style && (style.display === 'none' ||
                style.visibility === 'hidden' || style.visibility === 'collapse' ||
                Number(style.opacity) === 0)) return false;
            parent = parent.parentElement;
        }
        return true;
    }
""".trimIndent()
