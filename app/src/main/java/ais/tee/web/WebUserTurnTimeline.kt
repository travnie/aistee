package ais.tee.web

import android.webkit.WebView
import ais.tee.data.model.WebAiService
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val WEB_TIMELINE_MAX_TURNS = 200
private const val WEB_TIMELINE_MAX_CONTAINERS = 2_048

internal data class WebUserTurnTimeline(val count: Int, val limited: Boolean)

internal fun parseWebUserTurnTimeline(raw: String?): WebUserTurnTimeline? = runCatching {
    require(raw != null && raw.length <= 128)
    val value = Json.parseToJsonElement(raw).jsonObject
    val count = value.getValue("count").jsonPrimitive
    val limited = value.getValue("limited").jsonPrimitive
    require(!count.isString && !limited.isString)
    val size = requireNotNull(count.intOrNull)
    val capped = requireNotNull(limited.booleanOrNull)
    require(size in 0..WEB_TIMELINE_MAX_TURNS && (!capped || size == WEB_TIMELINE_MAX_TURNS))
    WebUserTurnTimeline(size, capped)
}.getOrNull()

private fun timelineTurnCollector(service: WebAiService): String? {
    val anchors = ProviderWebRegistry.turnAnchors(service) ?: return null
    return """
        ${webUserTurnVisibilityScript()}
        function collectTurns() {
            var matches = document.querySelectorAll(${javascriptStringLiteral(anchors.userSelector)});
            var turns = [];
            for (var i = 0; i < matches.length; i++) {
                var node = matches[i];
                var rect = node.getBoundingClientRect();
                if (rect && rect.width > 0 && rect.height > 0 && isVisible(node)) {
                    turns.push(node);
                    if (turns.length > $WEB_TIMELINE_MAX_TURNS) break;
                }
            }
            return turns;
        }
    """.trimIndent()
}

internal fun webUserTurnTimelineReadScript(service: WebAiService, token: String): String? {
    val collector = timelineTurnCollector(service) ?: return null
    return """
        (function() {
            ${providerRuntimeGuardScript(service, "null")}
            if (String(window.__aisteeVerifiedTurnRoute || '') !== String(location.href || '')) return null;
            try {
                if (typeof MutationObserver !== 'function' || typeof WeakRef !== 'function') return null;
                $collector
                var turns = collectTurns();
                var limited = turns.length > $WEB_TIMELINE_MAX_TURNS;
                turns = turns.slice(0, $WEB_TIMELINE_MAX_TURNS);
                var previous = window.__aisteeTurnTimeline;
                if (previous && previous.observer) previous.observer.disconnect();
                var snapshot = {
                    token: ${javascriptStringLiteral(token)}, url: String(location.href || ''),
                    nodes: turns, limited: limited, invalid: false
                };
                snapshot.observer = new MutationObserver(function() { snapshot.invalid = true; });
                for (var i = 0; i < turns.length; i++) {
                    snapshot.observer.observe(turns[i], {
                        subtree: true, childList: true, characterData: true, attributes: true
                    });
                }
                var containers = [];
                for (var j = 0; j < turns.length; j++) {
                    var parent = turns[j].parentElement;
                    while (parent && parent.nodeType === 1) {
                        if (containers.indexOf(parent) !== -1) break;
                        if (containers.length >= $WEB_TIMELINE_MAX_CONTAINERS) {
                            snapshot.observer.disconnect();
                            return null;
                        }
                        containers.push(parent);
                        if (turns.indexOf(parent) === -1) snapshot.observer.observe(parent, { childList: true });
                        parent = parent.parentElement;
                    }
                }
                window.__aisteeTurnTimeline = snapshot;
                return { count: turns.length, limited: limited };
            } catch (e) { return null; }
        })();
    """.trimIndent()
}

private fun timelineSnapshotValidator(
    service: WebAiService, token: String, scanTargets: Boolean = true
): String? {
    val collector = if (scanTargets) timelineTurnCollector(service) ?: return null else ""
    val checkTargets = if (scanTargets) """
        var current = collectTurns();
        var limited = current.length > $WEB_TIMELINE_MAX_TURNS;
        current = current.slice(0, $WEB_TIMELINE_MAX_TURNS);
        if (limited !== snapshot.limited || current.length !== snapshot.nodes.length) return false;
        for (var i = 0; i < current.length; i++) {
            if (current[i] !== snapshot.nodes[i]) return false;
        }
    """ else """
        for (var j = 0; j < snapshot.nodes.length; j++) {
            if (snapshot.nodes[j].isConnected === false) return false;
        }
    """
    return """
        $collector
        function snapshotValid(snapshot) {
            if (!snapshot || snapshot.token !== ${javascriptStringLiteral(token)} ||
                snapshot.url !== String(location.href || '') || snapshot.invalid) return false;
            if (snapshot.observer.takeRecords().length > 0) { snapshot.invalid = true; return false; }
            $checkTargets
            return true;
        }
    """.trimIndent()
}

internal fun webUserTurnTimelineCheckScript(
    service: WebAiService, token: String, allowMissingSnapshot: Boolean = false
): String? {
    val validator = timelineSnapshotValidator(service, token, scanTargets = false) ?: return null
    return """
        (function() {
            ${providerRuntimeGuardScript(service, "null")}
            try {
                $validator
                var snapshot = window.__aisteeTurnTimeline;
                return { route: String(location.href || ''), current: snapshot ? snapshotValid(snapshot) : $allowMissingSnapshot };
            } catch (e) { return null; }
        })();
    """.trimIndent()
}

internal fun webUserTurnTimelineJumpScript(service: WebAiService, token: String, index: Int): String? {
    if (index !in 0 until WEB_TIMELINE_MAX_TURNS) return null
    val validator = timelineSnapshotValidator(service, token) ?: return null
    return """
        (function() {
            ${providerRuntimeGuardScript(service, "'off-provider'")}
            try {
                var snapshot = window.__aisteeTurnTimeline;
                if (!snapshot || snapshot.token !== ${javascriptStringLiteral(token)} ||
                    snapshot.url !== String(location.href || '')) return 'off-provider';
                $validator
                if (!snapshotValid(snapshot)) return 'failed';
                var node = snapshot.nodes[$index];
                if (!node || node.isConnected === false) return 'failed';
                node.scrollIntoView({ behavior: 'auto', block: 'center' });
                window.__aisteeUserTurnCursor = { nodeRef: new WeakRef(node), url: String(location.href || '') };
                return 'moved';
            } catch (e) { return 'failed'; }
        })();
    """.trimIndent()
}

/** Gate WebView access before asynchronous callbacks can touch a released renderer. */
internal fun webTimelineLeaseValid(
    closed: Boolean,
    isCurrentWebView: () -> Boolean,
    inspectPage: () -> Boolean
): Boolean = !closed && isCurrentWebView() && inspectPage()

// Foreground, page-bound handle. DOM references stay in the renderer; no message IDs or text.
internal class WebUserTurnTimelineSession(
    private val webView: WebView,
    private val service: WebAiService,
    private val isCurrentWebView: () -> Boolean
) {
    private val pageUrl = webView.url
    private val token = UUID.randomUUID().toString()
    private var closed = false
    private var hasSnapshot = false

    private fun current(): Boolean = webTimelineLeaseValid(closed, isCurrentWebView) {
        pageUrl != null && webView.url == pageUrl &&
            providerUrlMatches(service, pageUrl) &&
            !webView.settings.allowContentAccess && !webView.settings.allowFileAccess
    }

    fun read(onResult: (WebUserTurnTimeline?) -> Unit) {
        if (!current()) { onResult(null); return }
        val script = webUserTurnTimelineReadScript(service, token)
        if (script == null) { onResult(null); return }
        webView.evaluateJavascript(
            "(function(){window.__aisteeVerifiedTurnRoute=String(location.href||'');return window.__aisteeVerifiedTurnRoute;})()"
        ) { raw ->
            val observed = runCatching { Json.parseToJsonElement(raw).jsonPrimitive }.getOrNull()
            if (!current() || observed?.isString != true || observed.content != pageUrl) {
                if (!closed) onResult(null)
            } else {
                webView.evaluateJavascript(script) { result ->
                    if (!closed) {
                        val snapshot = if (current()) parseWebUserTurnTimeline(result) else null
                        hasSnapshot = snapshot != null
                        onResult(snapshot)
                    }
                }
            }
        }
    }

    fun jump(index: Int, onResult: (WebUserTurnJumpResult) -> Unit) {
        val script = webUserTurnTimelineJumpScript(service, token, index)
        if (!current() || script == null) { onResult(WebUserTurnJumpResult.OFF_PROVIDER); return }
        webView.evaluateJavascript(script) { raw ->
            if (!closed) onResult(
                if (current()) parseWebUserTurnJumpResult(raw) else WebUserTurnJumpResult.OFF_PROVIDER
            )
        }
    }

    fun checkCurrent(onResult: (Boolean) -> Unit) {
        val script = webUserTurnTimelineCheckScript(service, token, allowMissingSnapshot = !hasSnapshot)
        if (!current() || script == null) { onResult(false); return }
        webView.evaluateJavascript(script) { raw ->
            if (!closed) {
                val valid = runCatching {
                    require(raw.length <= 16_384)
                    val result = Json.parseToJsonElement(raw).jsonObject
                    val route = result.getValue("route").jsonPrimitive
                    val targets = result.getValue("current").jsonPrimitive
                    route.isString && route.content == pageUrl && !targets.isString && targets.booleanOrNull == true
                }.getOrDefault(false)
                onResult(current() && valid)
            }
        }
    }

    fun close() {
        if (closed) return
        val stillOwned = isCurrentWebView()
        closed = true
        // Never execute cleanup against an evicted or destroyed renderer.
        if (!stillOwned) return
        runCatching {
            webView.evaluateJavascript(
                "(function(){var s=window.__aisteeTurnTimeline;" +
                    "if(s&&s.token===${javascriptStringLiteral(token)}) {" +
                    "s.observer.disconnect();delete window.__aisteeTurnTimeline;}})()",
                null
            )
        }
    }
}
