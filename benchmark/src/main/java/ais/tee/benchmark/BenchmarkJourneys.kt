package ais.tee.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "ais.tee"
private const val UI_TIMEOUT_MS = 5_000L

internal fun MacrobenchmarkScope.switchWebProvider(providerName: String) {
    val switcher = device.wait(
        Until.findObject(By.desc("Switch AI service")),
        UI_TIMEOUT_MS
    ) ?: error("Web provider switcher did not become available")
    switcher.click()

    val provider = device.wait(
        Until.findObject(By.text(providerName)),
        UI_TIMEOUT_MS
    ) ?: error("Web provider $providerName did not become available")
    provider.click()
    device.waitForIdle()
}
