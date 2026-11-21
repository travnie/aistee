package ais.tee.ui.screens

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Looper
import ais.tee.data.model.WebAiService
import ais.tee.data.model.onboardingCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProviderBrowserTabTest {
    @Test
    fun providerEntriesUseExplicitBrowserPackage() {
        WebAiService.entries.forEach { service ->
            listOfNotNull(service.url, service.onboardingCapabilities().signInUrl).forEach { url ->
                val uri = Uri.parse(url)
                listOf("test.browser", "other.browser").forEach { browserPackage ->
                    val intent = requireNotNull(providerBrowserTabIntent(uri, browserPackage))
                    assertEquals(Intent.ACTION_VIEW, intent.action)
                    assertEquals(uri, intent.data)
                    assertEquals(browserPackage, intent.`package`)
                    assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
                }
            }
        }
    }

    @Test
    fun alternateBrowserCandidatesAreFilteredAndDeduplicated() {
        assertEquals(
            listOf("browser.a", "browser.b"),
            compatibleBrowserPackages(listOf("browser.b", "app.link", "browser.a", "browser.b")) {
                it.startsWith("browser.")
            },
        )
        assertTrue(compatibleBrowserPackages(listOf("app.link")) { false }.isEmpty())
    }

    @Test
    fun pickerWaitsForUserAndReportsCancellationWithoutLaunching() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val results = mutableListOf<BrowserTabResult>()
        showProviderBrowserPicker(
            activity, listOf("browser.a" to "A", "browser.b" to "B"),
            launch = { error("Cancelled picker must not launch") },
            onResult = { results.add(it) },
        )
        assertTrue(results.isEmpty())
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(BrowserTabResult.CANCELLED), results)
    }

    @Test
    fun pickerReportsSelectedPackageLaunchResult() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val results = mutableListOf<BrowserTabResult>()
        val launched = mutableListOf<String>()
        showProviderBrowserPicker(
            activity, listOf("browser.a" to "A", "browser.b" to "B"),
            launch = { launched.add(it); false },
            onResult = { results.add(it) },
        )
        assertTrue(results.isEmpty())
        val list = ShadowAlertDialog.getLatestAlertDialog().listView
        list.performItemClick(list.getChildAt(1), 1, list.adapter.getItemId(1))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("browser.b"), launched)
        assertEquals(listOf(BrowserTabResult.FAILED), results)
    }

    @Test
    fun rejectsNonHttpsMalformedAndCredentialBearingEntries() {
        listOf(
            "http://example.com",
            "javascript:alert(1)",
            "intent://example.com",
            "https:example.com",
            "https://user:password@example.com",
            "https://example.com:8443",
        ).forEach { url ->
            assertNull(url, providerBrowserTabIntent(Uri.parse(url), "test.browser"))
            assertTrue(url, !isBrowserTabEntry(Uri.parse(url)))
        }
        assertNull(providerBrowserTabIntent(Uri.parse("https://example.com"), ""))
    }
}
