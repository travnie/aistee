package ais.tee.web

import ais.tee.data.model.WebAiService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context

class WebUserTurnNavigationTest {
    private companion object {
        const val PAGE = "https://chatgpt.com/c/chat-one"
    }

    @Test
    fun onlyRegisteredProvidersExposeNavigationScripts() {
        assertTrue(webUserTurnJumpScript(
            WebAiService.CHATGPT, WebUserTurnDirection.NEXT
        ) != null)
        assertNull(webUserTurnJumpScript(WebAiService.QWEN, WebUserTurnDirection.NEXT))
        assertTrue(providerUrlMatches(WebAiService.CHATGPT, PAGE))
        assertTrue(!providerUrlMatches(WebAiService.CHATGPT, "https://chatgpt.com.attacker.test/"))
        assertTrue(!providerUrlMatches(WebAiService.CHATGPT, "http://chatgpt.com/"))
    }

    @Test
    fun betaNavigationOptOutIsIndependentForEachProvider() {
        val disabled = setOf(WebAiService.GEMINI)
        assertTrue(webUserTurnNavigationEnabled(WebAiService.CHATGPT, disabled))
        assertTrue(webUserTurnNavigationEnabled(WebAiService.CLAUDE, disabled))
        assertTrue(!webUserTurnNavigationEnabled(WebAiService.GEMINI, disabled))
        assertTrue(!webUserTurnNavigationEnabled(WebAiService.QWEN, emptySet()))
    }

    @Test
    fun untrustedProviderPathNeverBecomesJavaScriptSource() {
        val path = "https://chatgpt.com/c/123?x=%22%3Balert(1)%2F%2F#fragment"
        val script = requireNotNull(
            webUserTurnJumpScript(WebAiService.CHATGPT, WebUserTurnDirection.NEXT)
        )
        assertTrue(!script.contains(path))
        assertTrue(!script.contains("alert(1)"))
        assertTrue(!script.contains("123?x"))
        assertTrue(!script.contains("fragment"))
    }

    @Test
    fun previousAndNextUseVisibleUserTurnsOnly() {
        assertEquals("moved" to 1, runJump(
            WebUserTurnDirection.PREVIOUS, listOf(-100, 50, 310, 550)
        ))
        assertEquals("moved" to 2, runJump(
            WebUserTurnDirection.NEXT, listOf(-100, 50, 310, 550)
        ))
    }

    @Test
    fun failsClosedWithoutMatchingTurnsOrAtBoundaries() {
        assertEquals("no-turns" to -1, runJump(WebUserTurnDirection.NEXT, emptyList()))
        assertEquals("boundary" to -1, runJump(
            WebUserTurnDirection.PREVIOUS, listOf(300, 450)
        ))
        assertEquals("boundary" to -1, runJump(
            WebUserTurnDirection.NEXT, listOf(-120, 20)
        ))
    }

    @Test
    fun doesNotActAfterNavigationOrProviderChange() {
        assertEquals("off-provider" to -1, runJump(
            WebUserTurnDirection.NEXT,
            listOf(350),
            host = "accounts.google.com"
        ))
        assertEquals("off-provider" to -1, runJump(
            WebUserTurnDirection.NEXT,
            listOf(350),
            href = "https://chatgpt.com/c/chat-two"
        ))
    }

    @Test
    fun previousAndNextSkipTheCurrentTallTurn() {
        val positions = listOf(-180, 40, 600)
        val heights = listOf(60, 360, 60)
        assertEquals("moved" to 0, runJump(
            WebUserTurnDirection.PREVIOUS, positions, heights = heights
        ))
        assertEquals("moved" to 2, runJump(
            WebUserTurnDirection.NEXT, positions, heights = heights
        ))
    }

    @Test
    fun repeatedPreviousAndNextMoveToDistinctTurns() {
        val previous = runJumpSequence(
            directions = listOf(WebUserTurnDirection.PREVIOUS, WebUserTurnDirection.PREVIOUS),
            positions = listOf(-410, -160, 105, 390),
            heights = listOf(100, 100, 100, 100)
        )
        assertEquals(listOf("moved" to 1, "moved" to 0), previous)
        val next = runJumpSequence(
            directions = listOf(WebUserTurnDirection.NEXT, WebUserTurnDirection.NEXT),
            positions = listOf(80, 350, 620),
            heights = listOf(100, 100, 100)
        )
        assertEquals(listOf("moved" to 1, "moved" to 2), next)
    }

    @Test
    fun clampedScrollingStillAdvancesAndReachesBoundaries() {
        val next = runJumpSequence(
            directions = List(4) { WebUserTurnDirection.NEXT },
            positions = listOf(220, 320, 375),
            clampScrolling = true
        )
        assertEquals(
            listOf("moved" to 0, "moved" to 1, "moved" to 2, "boundary" to -1),
            next
        )
        val previous = runJumpSequence(
            directions = List(3) { WebUserTurnDirection.PREVIOUS },
            positions = listOf(-20, 90, 180),
            clampScrolling = true
        )
        assertEquals(
            listOf("moved" to 1, "moved" to 0, "boundary" to -1),
            previous
        )
    }

    @Test
    fun manualScrollClearsTheLastTurnAndUsesTheNewViewport() {
        val jumps = runJumpSequence(
            directions = listOf(WebUserTurnDirection.NEXT, WebUserTurnDirection.NEXT),
            positions = listOf(80, 320, 550, 820),
            heights = listOf(24, 24, 24, 24),
            manualPositionsAfterFirst = listOf(-400, 10, 190, 450)
        )
        assertEquals(listOf("moved" to 1, "moved" to 3), jumps)
    }

    @Test
    fun skipsCssHiddenAndAriaHiddenTurnsAndAncestors() {
        val positions = listOf(30, 260, 340, 450)
        assertEquals("moved" to 3, runJump(
            WebUserTurnDirection.NEXT, positions,
            ariaHidden = setOf(1),
            opacityZero = setOf(2)
        ))
        assertEquals("moved" to 3, runJump(
            WebUserTurnDirection.NEXT, positions,
            hiddenAncestor = setOf(1, 2)
        ))
    }

    @Test
    fun fragmentOnlyConversationSwitchIsNotAValidJumpTarget() {
        assertEquals("off-provider" to -1, runJump(
            WebUserTurnDirection.NEXT, listOf(250),
            href = "$PAGE#different-conversation"
        ))
    }

    @Test
    fun scriptNeverExtractsMessageTextOrIdentifiers() {
        val script = requireNotNull(
            webUserTurnJumpScript(WebAiService.CHATGPT, WebUserTurnDirection.NEXT)
        )
        assertTrue(!script.contains("innerText"))
        assertTrue(!script.contains("textContent"))
        assertTrue(!script.contains("outerHTML"))
        assertTrue(!script.contains("getAttribute("))
        assertTrue(script.contains("scrollIntoView"))
        assertTrue(!script.contains("/c/chat-one"))
        assertTrue(!script.contains("llmbenchDocumentUrl"))
    }

    @Test
    fun parserRejectsUnexpectedCallbackData() {
        assertEquals(WebUserTurnJumpResult.MOVED, parseWebUserTurnJumpResult("\"moved\""))
        assertEquals(WebUserTurnJumpResult.NO_TURNS, parseWebUserTurnJumpResult("\"no-turns\""))
        assertEquals(WebUserTurnJumpResult.BOUNDARY, parseWebUserTurnJumpResult("\"boundary\""))
        assertEquals(WebUserTurnJumpResult.OFF_PROVIDER, parseWebUserTurnJumpResult("\"off-provider\""))
        assertEquals(WebUserTurnJumpResult.FAILED, parseWebUserTurnJumpResult(null))
        assertEquals(WebUserTurnJumpResult.FAILED, parseWebUserTurnJumpResult("\"unexpected\""))
    }

    private fun runJump(
        direction: WebUserTurnDirection,
        positions: List<Int>,
        host: String = "chatgpt.com",
        href: String = PAGE,
        heights: List<Int> = List(positions.size) { 24 },
        ariaHidden: Set<Int> = emptySet(),
        opacityZero: Set<Int> = emptySet(),
        hiddenAncestor: Set<Int> = emptySet()
    ): Pair<String, Int> = runJumpSequence(
        directions = listOf(direction),
        positions = positions,
        heights = heights,
        host = host,
        href = href,
        ariaHidden = ariaHidden,
        opacityZero = opacityZero,
        hiddenAncestor = hiddenAncestor
    ).single()

    private fun runJumpSequence(
        directions: List<WebUserTurnDirection>,
        positions: List<Int>,
        heights: List<Int> = List(positions.size) { 24 },
        host: String = "chatgpt.com",
        href: String = PAGE,
        ariaHidden: Set<Int> = emptySet(),
        opacityZero: Set<Int> = emptySet(),
        hiddenAncestor: Set<Int> = emptySet(),
        clampScrolling: Boolean = false,
        manualPositionsAfterFirst: List<Int>? = null
    ): List<Pair<String, Int>> {
        require(heights.size == positions.size)
        val positionsLiteral = positions.joinToString(prefix = "[", postfix = "]")
        val heightsLiteral = heights.joinToString(prefix = "[", postfix = "]")
        val ariaLiteral = ariaHidden.joinToString(prefix = "[", postfix = "]")
        val opacityLiteral = opacityZero.joinToString(prefix = "[", postfix = "]")
        val ancestorLiteral = hiddenAncestor.joinToString(prefix = "[", postfix = "]")
        val setup = """
            var location = { hostname: "$host", protocol: "https:", href: "$href" };
            var jumped = -1;
            var positions = $positionsLiteral;
            var heights = $heightsLiteral;
            var ariaHidden = $ariaLiteral;
            var opacityZero = $opacityLiteral;
            var hiddenAncestor = $ancestorLiteral;
            var clampScrolling = $clampScrolling;
            var hiddenParent = {
                nodeType: 1, parentElement: null,
                matches: function() { return true; }
            };
            var window = {
                innerHeight: 400,
                __aisteeVerifiedTurnRoute: "$PAGE",
                listeners: {},
                addEventListener: function(type, handler) {
                    this.listeners[type] = handler;
                },
                getComputedStyle: function(node) {
                    return {
                        display: 'block', visibility: 'visible',
                        opacity: node.invisible ? '0' : '1'
                    };
                }
            };
            var turns = [];
            for (var i = 0; i < positions.length; i++) {
                turns.push({
                    index: i, nodeType: 1, top: positions[i], height: heights[i],
                    ariaHidden: ariaHidden.indexOf(i) !== -1,
                    invisible: opacityZero.indexOf(i) !== -1,
                    parentElement: hiddenAncestor.indexOf(i) !== -1 ? hiddenParent : null,
                    matches: function() { return this.ariaHidden; },
                    getBoundingClientRect: function() {
                        return {
                            top: this.top, bottom: this.top + this.height,
                            width: 100, height: this.height
                        };
                    },
                    scrollIntoView: function() {
                        jumped = this.index;
                        if (!clampScrolling) {
                            var delta = this.top + this.height / 2 - 200;
                            for (var j = 0; j < turns.length; j++) turns[j].top -= delta;
                        }
                    }
                });
            }
            var document = {
                documentElement: { clientHeight: 400 },
                querySelectorAll: function(selector) {
                    return selector === '[data-message-author-role="user"]' ? turns : [];
                }
            };
        """.trimIndent()
        val context = Context.enter()
        try {
            context.optimizationLevel = -1
            context.languageVersion = Context.VERSION_ES6
            val scope = context.initStandardObjects()
            context.evaluateString(scope, setup, "turn-setup", 1, null)
            return directions.mapIndexed { index, direction ->
                if (index == 1 && manualPositionsAfterFirst != null) {
                    require(manualPositionsAfterFirst.size == positions.size)
                    val positionsAfterScroll = manualPositionsAfterFirst.joinToString(
                        prefix = "[", postfix = "]"
                    )
                    context.evaluateString(
                        scope,
                        "window.listeners.touchstart();" +
                            "var manualPositions = $positionsAfterScroll;" +
                            "for (var i = 0; i < turns.length; i++) {" +
                            " turns[i].top = manualPositions[i]; }",
                        "manual-scroll",
                        1,
                        null
                    )
                }
                context.evaluateString(scope, "jumped = -1;", "reset-jump", 1, null)
                val script = requireNotNull(
                    webUserTurnJumpScript(WebAiService.CHATGPT, direction)
                )
                val status = Context.toString(
                    context.evaluateString(scope, script, "turn-jump", 1, null)
                )
                val jumped = Context.toNumber(
                    context.evaluateString(scope, "jumped", "turn-result", 1, null)
                ).toInt()
                status to jumped
            }
        } finally {
            Context.exit()
        }
    }
}
