package ais.tee

import android.content.Context
import androidx.annotation.XmlRes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class BackupBoundaryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun legacyBackupAllowsOnlyNonSensitiveWebChatPreferences() {
        assertEquals(
            setOf(BackupInclude(scope = "full-backup-content", domain = "sharedpref", path = SAFE_PREFERENCES)),
            readBackupIncludes(R.xml.backup_rules),
        )
    }

    @Test
    fun modernCloudAndDeviceTransferKeepTheSameNarrowAllowlist() {
        assertEquals(
            setOf(
                BackupInclude(scope = "cloud-backup", domain = "sharedpref", path = SAFE_PREFERENCES),
                BackupInclude(scope = "device-transfer", domain = "sharedpref", path = SAFE_PREFERENCES),
            ),
            readBackupIncludes(R.xml.data_extraction_rules),
        )
    }

    private fun readBackupIncludes(@XmlRes resourceId: Int): Set<BackupInclude> {
        val parser = context.resources.getXml(resourceId)
        val includes = linkedSetOf<BackupInclude>()
        var scope = ""

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "full-backup-content", "cloud-backup", "device-transfer" -> scope = parser.name
                        "include" -> includes += BackupInclude(
                            scope = scope,
                            domain = parser.getAttributeValue(null, "domain").orEmpty(),
                            path = parser.getAttributeValue(null, "path").orEmpty(),
                        )
                    }
                    XmlPullParser.END_TAG -> if (
                        parser.name == "full-backup-content" ||
                        parser.name == "cloud-backup" ||
                        parser.name == "device-transfer"
                    ) {
                        scope = ""
                    }
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return includes
    }

    private data class BackupInclude(
        val scope: String,
        val domain: String,
        val path: String,
    )

    private companion object {
        const val SAFE_PREFERENCES = "web_chat_preferences.xml"
    }
}
