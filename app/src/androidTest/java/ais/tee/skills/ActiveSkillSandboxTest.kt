package ais.tee.skills

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.data.skills.ACTIVE_SKILL_ENTRY_FILE
import ais.tee.data.skills.ACTIVE_SKILL_RUNTIME_WEBVIEW_V1
import ais.tee.data.skills.ActiveSkillBundle
import ais.tee.data.skills.ActiveSkillDeclaration
import ais.tee.data.skills.ActiveSkillOutcome
import ais.tee.data.skills.ActiveSkillSandbox
import ais.tee.data.skills.activeSkillBundleDigest
import ais.tee.data.skills.activeSkillRequestJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device checks required by `docs/skills-runtime.md` before the flag can be turned on. */
@RunWith(AndroidJUnit4::class)
class ActiveSkillSandboxTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val offline = ActiveSkillDeclaration(ACTIVE_SKILL_RUNTIME_WEBVIEW_V1, emptySet(), emptyList())
    private val request = activeSkillRequestJson(buildJsonObject { put("n", 2) }, "en-US", "2026-09-26T12:00:00Z")

    private fun bundle(script: String): ActiveSkillBundle {
        val files = mapOf(ACTIVE_SKILL_ENTRY_FILE to "<!doctype html><script>$script</script>".toByteArray())
        return ActiveSkillBundle("probe", activeSkillBundleDigest(files), files)
    }

    private fun run(script: String, timeoutMs: Long = 5_000): ActiveSkillOutcome = runBlocking {
        ActiveSkillSandbox(context, timeoutMs).run(bundle(script), offline, request)
    }

    private fun resultOf(script: String): JsonObject {
        val outcome = run(script)
        assertTrue("$outcome", outcome is ActiveSkillOutcome.Result)
        return (outcome as ActiveSkillOutcome.Result).result as JsonObject
    }

    @Test
    fun synchronousResultReceivesOnlyTheRequest() {
        val result = resultOf(
            """window.aistee_skill_run = (req) => {
                 const r = JSON.parse(req);
                 return JSON.stringify({ result: { n: r.input.n * 2, keys: Object.keys(r).join(',') } });
               };"""
        )
        assertEquals("4", result["n"]!!.jsonPrimitive.content)
        assertEquals("version,input,locale,now", result["keys"]!!.jsonPrimitive.content)
    }

    @Test
    fun promiseResultIsAwaited() {
        val result = resultOf(
            """window.aistee_skill_run = () => new Promise(resolve =>
                 setTimeout(() => resolve({ result: { late: true } }), 200));"""
        )
        assertEquals(JsonPrimitive(true), result["late"])
    }

    @Test
    fun neverResolvingSkillTimesOut() {
        val outcome = run("window.aistee_skill_run = () => new Promise(() => {});", timeoutMs = 1_500)
        assertTrue("$outcome", outcome is ActiveSkillOutcome.Error && outcome.message.contains("did not finish"))
    }

    @Test
    fun oversizedAndMissingOutputFail() {
        val big = run("window.aistee_skill_run = () => JSON.stringify({ result: 'x'.repeat(70000) });")
        assertTrue("$big", big is ActiveSkillOutcome.Error)
        val missing = run("")
        assertTrue("$missing", missing is ActiveSkillOutcome.Error && missing.message.contains("aistee_skill_run"))
    }

    @Test
    fun networkStorageCookiesAndWorkersAreUnavailable() {
        val result = resultOf(
            """window.aistee_skill_run = async () => {
                 const fetchState = await fetch('https://example.com/')
                   .then(r => r.ok ? 'reached' : 'blocked', () => 'blocked');
                 const socketState = await new Promise(resolve => {
                   try {
                     const ws = new WebSocket('wss://example.com/');
                     ws.onopen = () => resolve('reached');
                     ws.onerror = () => resolve('blocked');
                     setTimeout(() => resolve('blocked'), 3000);
                   } catch (e) { resolve('blocked'); }
                 });
                 let storage;
                 try { storage = window.localStorage === null ? 'off' : 'on'; } catch (e) { storage = 'off'; }
                 document.cookie = 'probe=1; path=/';
                 const worker = await new Promise(resolve => {
                   try {
                     const w = new Worker('data:text/javascript,postMessage(1)');
                     w.onmessage = () => resolve('reached');
                     w.onerror = () => resolve('blocked');
                     setTimeout(() => resolve('blocked'), 1000);
                   } catch (e) { resolve('blocked'); }
                 });
                 return { result: {
                   fetch: fetchState, socket: socketState, storage: storage,
                   cookie: document.cookie, serviceWorker: String(navigator.serviceWorker),
                   rtc: typeof window.RTCPeerConnection, bridge: typeof window.Android,
                   worker: worker,
                 } };
               };"""
        )
        assertEquals("blocked", result["fetch"]!!.jsonPrimitive.content)
        assertEquals("blocked", result["socket"]!!.jsonPrimitive.content)
        assertEquals("off", result["storage"]!!.jsonPrimitive.content)
        assertEquals("", result["cookie"]!!.jsonPrimitive.content)
        assertEquals("undefined", result["serviceWorker"]!!.jsonPrimitive.content)
        assertEquals("undefined", result["rtc"]!!.jsonPrimitive.content)
        assertEquals("undefined", result["bridge"]!!.jsonPrimitive.content)
        assertEquals("blocked", result["worker"]!!.jsonPrimitive.content)
    }

    @Test
    fun anotherBundlePathIsNotServed() {
        val otherDigest = "f".repeat(64)
        val result = resultOf(
            """window.aistee_skill_run = async () => {
                 const own = await fetch('index.html').then(r => r.status);
                 const other = await fetch('/skills/$otherDigest/index.html').then(r => r.status, () => 0);
                 const outside = await fetch('/SKILL.md').then(r => r.status, () => 0);
                 return { result: { own: own, other: other, outside: outside } };
               };"""
        )
        assertEquals("200", result["own"]!!.jsonPrimitive.content)
        assertTrue(result["other"]!!.jsonPrimitive.content != "200")
        assertTrue(result["outside"]!!.jsonPrimitive.content != "200")
    }

    @Test
    fun navigationAwayIsCancelled() {
        val result = resultOf(
            """window.aistee_skill_run = () => new Promise(resolve => {
                 const start = location.href;
                 const frame = document.createElement('iframe');
                 frame.src = 'https://example.com/';
                 document.body.appendChild(frame);
                 location.href = 'https://example.com/';
                 setTimeout(() => resolve({ result: { same: location.href === start } }), 1000);
               });"""
        )
        assertEquals(JsonPrimitive(true), result["same"])
    }

    @Test
    fun skillsRunInTheCookielessSkillsProcess() {
        val report = runBlocking {
            ActiveSkillSandbox(context).runWithReport(
                bundle("window.aistee_skill_run = () => '{\"result\":1}';"),
                offline,
                request,
            )
        }
        assertTrue("${report.outcome}", report.outcome is ActiveSkillOutcome.Result)
        assertTrue(report.processId != null && report.processId != android.os.Process.myPid())
        assertEquals(false, report.cookiesAccepted)
        // The app process, where account-backed chats live, keeps its own cookie setting.
        assertTrue(android.webkit.CookieManager.getInstance().acceptCookie())
    }

    @Test
    fun networkedSkillsDoNotRun() {
        val outcome = runBlocking {
            ActiveSkillSandbox(context).run(
                bundle("window.aistee_skill_run = () => '{\"result\":1}';"),
                offline.copy(networkOrigins = listOf("https://api.example.com")),
                request,
            )
        }
        assertTrue("$outcome", outcome is ActiveSkillOutcome.Error)
    }
}
