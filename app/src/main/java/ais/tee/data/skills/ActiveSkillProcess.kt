package ais.tee.data.skills

import android.app.Application
import android.os.Build
import android.webkit.WebView
import java.io.File

/** Process name suffix declared for [ActiveSkillService] in the manifest. */
internal const val ACTIVE_SKILL_PROCESS_SUFFIX = ":skills"
private const val ACTIVE_SKILL_WEBVIEW_DATA_SUFFIX = "active_skills"

/**
 * Isolation state of the dedicated skills process. Account-backed chat WebViews never run there,
 * and the skills WebView data directory (cookies, caches) is separate from the app process's.
 */
internal object ActiveSkillProcess {
    @Volatile
    var isDataDirectoryIsolated: Boolean = false
        private set

    fun isCurrent(application: Application): Boolean =
        currentProcessName(application)?.endsWith(ACTIVE_SKILL_PROCESS_SUFFIX) == true

    /**
     * Must run in the skills process before any WebView is created there. Android 8.x has no data
     * directory suffix, so skills fail closed there.
     */
    fun isolateWebViewData() {
        isDataDirectoryIsolated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && runCatching {
            WebView.setDataDirectorySuffix(ACTIVE_SKILL_WEBVIEW_DATA_SUFFIX)
        }.isSuccess
    }

    private fun currentProcessName(application: Application): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000').trim() }.getOrNull()
        }
}
