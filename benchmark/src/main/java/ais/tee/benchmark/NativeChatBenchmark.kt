package ais.tee.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class NativeChatBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Before
    fun startFromCleanArchive() = clearTargetAppData()

    @Test
    fun listDetailRoundTrip() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = 5,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                openStudioTab()
            }
        ) {
            openNativeConversationList()
            createNativeConversation()
            returnToNativeConversationList()
        }
    }

    @Test
    fun openLongConversation() {
        // Expanded layouts keep the fixture's detail pane visible beside the list, so there is no reopen to time.
        assumeTrue(isCompactWidthDevice())
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = 5,
            setupBlock = {
                pressHome()
                startActivityInCompareHub()
                ensureBenchmarkConversation()
            }
        ) {
            openBenchmarkConversation()
            returnToNativeConversationList()
        }
    }

    @Test
    fun longConversationScroll() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = 5,
            setupBlock = {
                pressHome()
                startActivityInCompareHub()
                ensureBenchmarkConversation()
                openBenchmarkConversation()
            }
        ) {
            scrollNativeChatHistory()
        }
    }
}
