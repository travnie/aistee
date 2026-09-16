package ais.tee.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "ais.tee"
private const val UI_TIMEOUT_MS = 5_000L
private const val UI_POLL_INTERVAL_MS = 50L

internal fun MacrobenchmarkScope.switchWebProvider(providerName: String) {
    val providerSelector = By.desc("Switch to $providerName")
    val visibleProvider = device.findObject(providerSelector)
    if (visibleProvider != null && !visibleProvider.visibleBounds.isEmpty) {
        visibleProvider.click()
        device.waitForIdle()
        return
    }

    val switcher = device.wait(
        Until.findObject(By.desc("Switch AI service")),
        UI_TIMEOUT_MS
    ) ?: error("Web provider switcher did not become available")
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
