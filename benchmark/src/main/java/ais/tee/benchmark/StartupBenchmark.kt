package ais.tee.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = measureStartup(StartupMode.COLD)

    @Test
    fun warmStartup() = measureStartup(StartupMode.WARM)

    private fun measureStartup(startupMode: StartupMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = startupMode,
            iterations = 5,
            setupBlock = { pressHome() }
        ) {
            startActivityAndWait()
        }
    }
}
