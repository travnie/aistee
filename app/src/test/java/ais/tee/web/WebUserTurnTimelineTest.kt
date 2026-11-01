package ais.tee.web

import ais.tee.data.model.WebAiService
import org.junit.Assert.*
import org.junit.Test
import org.mozilla.javascript.Context

class WebUserTurnTimelineTest {
    private companion object {
        const val TOKEN = "test"
        const val OFF_PROVIDER = "off-provider"
        const val JUMPED = "jumped"
        const val MOVED = "moved"
        const val TRUE = "true"
    }

    @Test
    fun snapshotIsBoundedAndContainsOnlyCounts() = inPage(230) { cx, scope ->
        assertEquals("{\"count\":200,\"limited\":true}", snapshot(cx, scope))
        assertEquals(MOVED, evaluate(cx, scope, jump(199)))
        assertEquals("199", evaluate(cx, scope, JUMPED))
        assertNull(webUserTurnTimelineJumpScript(WebAiService.CHATGPT, TOKEN, 200))
        assertNull(webUserTurnTimelineJumpScript(WebAiService.CHATGPT, TOKEN, -1))
    }

    @Test
    fun hiddenTurnsAreExcludedAndNoTextOrIdentifiersAreRead() = inPage(4) { cx, scope ->
        evaluate(cx, scope, "nodes[0].hidden = true; nodes[2].hidden = true;")
        assertEquals("{\"count\":2,\"limited\":false}", snapshot(cx, scope))
        assertEquals(MOVED, evaluate(cx, scope, jump(1)))
        assertEquals("3", evaluate(cx, scope, JUMPED))
    }

    @Test
    fun routeOrOriginChangesInvalidateSnapshot() {
        listOf(
            "location.href += '#another-chat';",
            "location.href = 'https://chatgpt.com/c/new';",
            "location.hostname = 'accounts.google.com';",
            "location.protocol = 'http:';"
        ).forEach { mutation -> inPage(3) { cx, scope ->
            evaluate(cx, scope, read)
            evaluate(cx, scope, mutation)
            assertEquals(OFF_PROVIDER, evaluate(cx, scope, jump(1)))
            assertEquals("-1", evaluate(cx, scope, JUMPED))
        } }
    }

    @Test
    fun removedReplacedHiddenOrReorderedNodesNeverBecomeDifferentTargets() {
        listOf(
            "nodes.pop();",
            "nodes[1] = Object.assign({}, nodes[1]);",
            "nodes[1].hidden = true;",
            "nodes.reverse();",
            "nodes[1].isConnected = false;",
            "observerCallback();",
            "pendingMutations = [{}];"
        ).forEach { mutation -> inPage(3) { cx, scope ->
            evaluate(cx, scope, read)
            evaluate(cx, scope, mutation)
            assertEquals("failed", evaluate(cx, scope, jump(1)))
            assertEquals("-1", evaluate(cx, scope, JUMPED))
        } }
    }

    @Test
    fun oldHandleCannotAddressNewSnapshotOrReloadedDocument() = inPage(3) { cx, scope ->
        evaluate(cx, scope, read)
        evaluate(cx, scope, requireNotNull(webUserTurnTimelineReadScript(WebAiService.CHATGPT, "new")))
        assertEquals(OFF_PROVIDER, evaluate(cx, scope, jump(1)))
        evaluate(cx, scope, "delete window.__aisteeTurnTimeline;")
        assertEquals(OFF_PROVIDER, evaluate(cx, scope, jump(1)))
    }

    @Test
    fun readRequiresNativePreflightAndRegisteredProvider() = inPage(0) { cx, scope ->
        assertEquals("{\"count\":0,\"limited\":false}", snapshot(cx, scope))
        evaluate(cx, scope, "window.__aisteeVerifiedTurnRoute = 'https://chatgpt.com/c/old';")
        assertEquals("null", evaluate(cx, scope, read))
        assertNull(webUserTurnTimelineReadScript(WebAiService.QWEN, TOKEN))
    }

    @Test
    fun callbackParserRejectsMalformedOrUnboundedMetadata() {
        assertEquals(WebUserTurnTimeline(3, false), parseWebUserTurnTimeline("{\"count\":3,\"limited\":false}"))
        listOf(null, "null", "[]", "{}", "{\"count\":201,\"limited\":true}",
            "{\"count\":-1,\"limited\":false}", "{\"count\":3,\"limited\":true}",
            "{\"count\":\"3\",\"limited\":false}", "{\"count\":3,\"limited\":\"false\"}", "x".repeat(129)
        ).forEach { assertNull(parseWebUserTurnTimeline(it)) }
    }

    @Test
    fun pollingDetectsChangesBeforeSelectionAndAllowsInitialRead() = inPage(3) { cx, scope ->
        val check = requireNotNull(webUserTurnTimelineCheckScript(WebAiService.CHATGPT, TOKEN, allowMissingSnapshot = true))
        val current = "(" + check.removeSuffix(";") + ").current"
        assertEquals(TRUE, evaluate(cx, scope, current))
        evaluate(cx, scope, read)
        assertEquals(TRUE, evaluate(cx, scope, current))
        evaluate(cx, scope, "document.querySelectorAll = function(){ throw Error('Idle layout scan'); };")
        assertEquals(TRUE, evaluate(cx, scope, current))
        evaluate(cx, scope, "pendingMutations = [{}];")
        assertEquals("false", evaluate(cx, scope, current))
    }

    @Test
    fun pickerCursorPreservesBoundaryWhenFirstMessageCannotBeCentered() = inPage(3) { cx, scope ->
        evaluate(cx, scope, read)
        assertEquals(MOVED, evaluate(cx, scope, jump(0)))
        val previous = requireNotNull(webUserTurnJumpScript(WebAiService.CHATGPT, WebUserTurnDirection.PREVIOUS))
        assertEquals("boundary", evaluate(cx, scope, previous))
        assertEquals(TRUE, evaluate(cx, scope, "window.__aisteeUserTurnCursor.node === undefined"))
        assertEquals(TRUE, evaluate(cx, scope, "window.__aisteeUserTurnCursor.nodeRef.deref() === nodes[0]"))
    }

    private fun snapshot(cx: Context, scope: org.mozilla.javascript.Scriptable): String =
        evaluate(cx, scope, "JSON.stringify($read)")

    private val read get() = requireNotNull(webUserTurnTimelineReadScript(WebAiService.CHATGPT, TOKEN)).removeSuffix(";")
    private fun jump(index: Int) = requireNotNull(webUserTurnTimelineJumpScript(WebAiService.CHATGPT, TOKEN, index))

    private fun evaluate(cx: Context, scope: org.mozilla.javascript.Scriptable, script: String): String =
        Context.toString(cx.evaluateString(scope, script, "timeline", 1, null))

    private fun inPage(count: Int, test: (Context, org.mozilla.javascript.Scriptable) -> Unit) {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            evaluate(cx, scope, """
                var location = { hostname: 'chatgpt.com', protocol: 'https:', href: 'https://chatgpt.com/c/test' };
                var window = { __aisteeVerifiedTurnRoute: location.href, innerHeight: 400 };
                var observerCallback;
                var pendingMutations = [];
                function MutationObserver(callback) {
                    observerCallback = callback;
                    this.observe = function() {};
                    this.disconnect = function() {};
                    this.takeRecords = function() { return pendingMutations; };
                }
                function WeakRef(node) { this.deref = function() { return node; }; }
                var jumped = -1;
                var nodes = [];
                for (var i = 0; i < $count; i++) {
                    var node = {
                        index: i, nodeType: 1, parentElement: null, isConnected: true,
                        matches: function() { return this.hidden === true; },
                        getBoundingClientRect: function() { return { width: 100, height: 20, top: this.index*100, bottom: this.index*100+20 }; },
                        scrollIntoView: function() { jumped = this.index; }
                    };
                    ['innerText', 'textContent', 'outerHTML', 'id'].forEach(function(key) {
                        Object.defineProperty(node, key, { get: function() { throw Error('Private content read'); } });
                    });
                    node.getAttribute = function() { throw Error('Provider ID read'); };
                    nodes.push(node);
                }
                var document = { querySelectorAll: function() { return nodes; } };
            """.trimIndent())
            test(cx, scope)
        } finally { Context.exit() }
    }
}
