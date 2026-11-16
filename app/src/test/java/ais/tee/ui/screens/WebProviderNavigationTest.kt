package ais.tee.ui.screens

import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebProviderNavigationTest {
    @Test
    fun compactNavigationKeepsProviderDrawerModal() {
        assertFalse(webProviderNavigationIsPersistent(NavigationSuiteType.NavigationBar))
        assertFalse(webProviderNavigationIsPersistent(NavigationSuiteType.ShortNavigationBarCompact))
        assertFalse(webProviderNavigationIsPersistent(NavigationSuiteType.ShortNavigationBarMedium))
    }

    @Test
    fun railNavigationKeepsProviderPickerVisible() {
        assertTrue(webProviderNavigationIsPersistent(NavigationSuiteType.NavigationRail))
        assertTrue(webProviderNavigationIsPersistent(NavigationSuiteType.WideNavigationRailCollapsed))
        assertTrue(webProviderNavigationIsPersistent(NavigationSuiteType.WideNavigationRailExpanded))
    }

    @Test
    fun providerPagesGetNoCleartextNotEvenToLoopback() {
        listOf("http", "HTTP").forEach { assertTrue(isCleartextWebScheme(it)) }
        listOf("https", "blob", "data", "ws", null).forEach { assertFalse(isCleartextWebScheme(it)) }
    }
}
