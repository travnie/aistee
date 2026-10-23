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
    direction: WebUserTurnDirection
): String? {
    // No provider-controlled URL is included in the executable script.
    // The native call site validates the URL separately.
    val anchors = ProviderWebRegistry.turnAnchors(service) ?: return null
    val previous = direction == WebUserTurnDirection.PREVIOUS
    val selector = javascriptStringLiteral(anchors.userSelector)
    return """
        (function() {
            ${providerRuntimeGuardScript(service, "'off-provider'")}
            // The native preflight binds an exact route in this page's own JS context.
            // Never splice an untrusted URL into executable JavaScript.
            if (String(window.__aisteeVerifiedTurnRoute || '') !==
                String(location.href || '')) return 'off-provider';
            try {
                var matches = document.querySelectorAll($selector);
                var turns = [];
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
                var anchor = -1;
                if (last && last.url === String(location.href || '')) {
                    for (var a = 0; a < turns.length; a++) {
                        if (turns[a].node === last.node) {
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

internal fun parseWebUserTurnJumpResult(raw: String?): WebUserTurnJumpResult = when (
    raw?.trim()?.removeSurrounding("\"")
) {
    "moved" -> WebUserTurnJumpResult.MOVED
    "no-turns" -> WebUserTurnJumpResult.NO_TURNS
    "boundary" -> WebUserTurnJumpResult.BOUNDARY
    "off-provider" -> WebUserTurnJumpResult.OFF_PROVIDER
    else -> WebUserTurnJumpResult.FAILED
}

internal fun jumpToWebUserTurn(
    webView: WebView,
    service: WebAiService,
    direction: WebUserTurnDirection,
    onResult: (WebUserTurnJumpResult) -> Unit
) {
    val expectedUrl = webView.url
    if (expectedUrl == null || !providerUrlMatches(service, expectedUrl) ||
        webView.settings.allowContentAccess || webView.settings.allowFileAccess
    ) {
        onResult(WebUserTurnJumpResult.OFF_PROVIDER)
        return
    }
    val script = webUserTurnJumpScript(service, direction)
    if (script == null) {
        onResult(WebUserTurnJumpResult.OFF_PROVIDER)
        return
    }
    // This script is constant: the browser reports its location rather than native code
    // embedding a provider-controlled URL into an executable JavaScript string.
    webView.evaluateJavascript(
        "(function() {" +
            "window.__aisteeVerifiedTurnRoute = String(location.href || '');" +
            "return window.__aisteeVerifiedTurnRoute;" +
        "})()"
    ) { rawLocation ->
        val currentLocation = runCatching {
            org.json.JSONTokener(rawLocation).nextValue() as? String
        }.getOrNull()
        if (currentLocation != expectedUrl || webView.url != expectedUrl) {
            onResult(WebUserTurnJumpResult.OFF_PROVIDER)
        } else {
            webView.evaluateJavascript(script) { raw ->
                onResult(parseWebUserTurnJumpResult(raw))
            }
        }
    }
}
