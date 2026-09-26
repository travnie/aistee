package ais.tee.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.Base64

internal const val TARGET_PACKAGE = "ais.tee"
private const val UI_TIMEOUT_MS = 5_000L
private const val UI_POLL_INTERVAL_MS = 50L
private const val BENCHMARK_FIXTURE_FILE = "aistee-benchmark-chat.md"
private const val BENCHMARK_FIXTURE_PATH = "/sdcard/Download/$BENCHMARK_FIXTURE_FILE"

internal fun MacrobenchmarkScope.switchWebProvider(providerName: String) {
    val providerSelector = By.desc("Switch to $providerName")
    val visibleProvider = device.findObject(providerSelector)
    if (visibleProvider != null && !visibleProvider.visibleBounds.isEmpty) {
        visibleProvider.click()
        device.waitForIdle()
        return
    }

    val switcher = waitForObject(By.desc("Switch AI service"), "Web provider switcher")
    switcher.click()

    val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
    var provider = device.findObject(providerSelector)
    while (
        (provider == null || provider.visibleBounds.isEmpty) &&
        SystemClock.uptimeMillis() < deadline
    ) {
        SystemClock.sleep(UI_POLL_INTERVAL_MS)
        provider = device.findObject(providerSelector)
    }

    val visibleDrawerProvider = provider?.takeIf { !it.visibleBounds.isEmpty }
        ?: error("Web provider $providerName did not become visible")
    visibleDrawerProvider.click()
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.openStudioTab() {
    waitForObject(By.desc("Studio"), "Studio navigation item").click()
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.openNativeConversationList() {
    waitForObject(By.desc("AI Compare Hub"), "Compare navigation item").click()
    device.waitForIdle()

    device.findObject(By.desc("Native conversations"))
        ?.takeIf { !it.visibleBounds.isEmpty }
        ?.click()

    waitForObject(By.text("New conversation"), "Native conversation list")
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.createNativeConversation() {
    waitForObject(By.text("New conversation"), "New conversation button").click()
    waitForObject(By.desc("More chat actions"), "Native chat detail")
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.returnToNativeConversationList() {
    waitForObject(By.desc("Native conversations"), "Native conversations button").click()
    waitForObject(By.text("New conversation"), "Native conversation list")
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.importBenchmarkConversationFixture() {
    createNativeConversation()
    writeBenchmarkConversationFixture()

    waitForObject(By.desc("More chat actions"), "More chat actions").click()
    waitForObject(
        By.text("Import Aistee chat Markdown"),
        "Import Aistee chat Markdown action"
    ).click()

    val fixture = device.wait(
        Until.findObject(By.text(BENCHMARK_FIXTURE_FILE)),
        UI_TIMEOUT_MS
    ) ?: error("Benchmark chat fixture did not appear in the document picker")
    fixture.click()

    waitForObject(
        By.textContains("Benchmark answer 11"),
        "Imported benchmark conversation",
        timeoutMs = UI_TIMEOUT_MS * 2
    )
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.scrollNativeChatHistory() {
    val x = device.displayWidth / 2
    val top = device.displayHeight * 3 / 10
    val bottom = device.displayHeight * 7 / 10

    repeat(4) {
        device.swipe(x, top, x, bottom, 18)
    }
    repeat(4) {
        device.swipe(x, bottom, x, top, 18)
    }
    device.waitForIdle()
}

private fun MacrobenchmarkScope.writeBenchmarkConversationFixture() {
    val encoded = Base64.getEncoder().encodeToString(
        benchmarkConversationMarkdown().toByteArray()
    )
    device.executeShellCommand(
        "mkdir -p /sdcard/Download; printf '%s' '$encoded' | base64 -d > $BENCHMARK_FIXTURE_PATH"
    )
}

private fun benchmarkConversationMarkdown(): String = buildString {
    append("# Aistee chat\n\n<!-- llmbench-chat:v1 -->\n\n")
    repeat(12) { index ->
        appendBenchmarkTurn(
            role = "user",
            heading = "You",
            body = "Benchmark question $index. Exercise native chat layout, stable item keys, and local scrolling."
        )
        appendBenchmarkTurn(
            role = "assistant",
            heading = "Assistant",
            body = "Benchmark answer $index. This deterministic local fixture exists only for performance testing.\n" +
                "It is intentionally long enough to exercise message measurement, lazy list reuse, and scrolling.\n" +
                "No provider API, account session, network request, or stored API key is used."
        )
    }
}

private fun StringBuilder.appendBenchmarkTurn(
    role: String,
    heading: String,
    body: String
) {
    val bodyBytes = body.toByteArray().size
    append("<!-- llmbench-message:v1 role=$role bytes=$bodyBytes -->\n")
    append("## $heading\n\n")
    append(body)
    append("\n<!-- llmbench-message-end -->\n\n")
}

private fun MacrobenchmarkScope.waitForObject(
    selector: BySelector,
    description: String,
    timeoutMs: Long = UI_TIMEOUT_MS
): UiObject2 =
    device.wait(Until.findObject(selector), timeoutMs)
        ?: error("$description did not become available")
