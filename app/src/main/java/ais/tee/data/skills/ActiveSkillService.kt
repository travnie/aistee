package ais.tee.data.skills

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.webkit.CookieManager
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal const val MSG_RUN_SKILL = 1
internal const val MSG_SKILL_RESULT = 2
internal const val KEY_BUNDLE_DIR = "bundle_dir"
internal const val KEY_SKILL_NAME = "skill_name"
internal const val KEY_DIGEST = "digest"
internal const val KEY_REQUEST = "request"
internal const val KEY_TIMEOUT_MS = "timeout_ms"
internal const val KEY_OUTPUT = "output"
internal const val KEY_ERROR = "error"
internal const val KEY_PID = "pid"
internal const val KEY_COOKIES_ACCEPTED = "cookies_accepted"

/** Upper bound for a whole bundle handed to the skills process. */
internal const val ACTIVE_SKILL_MAX_BUNDLE_BYTES = 8 * 1024 * 1024

/**
 * Hosts skill WebViews in the dedicated `:skills` process (`docs/skills-runtime.md`). Before any
 * skill loads it disables cookies for this process's own WebView data directory and verifies it,
 * failing closed otherwise. Not exported; only Aistee binds it.
 */
class ActiveSkillService : Service() {
    private val scope = MainScope()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message -> handle(message) })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handle(message: Message): Boolean {
        if (message.what != MSG_RUN_SKILL) return false
        val replyTo = message.replyTo ?: return true
        val data = Bundle(message.data)
        scope.launch {
            val reply = Bundle().apply { putInt(KEY_PID, Process.myPid()) }
            val outcome = runSkill(data)
            reply.putBoolean(KEY_COOKIES_ACCEPTED, CookieManager.getInstance().acceptCookie())
            when (outcome) {
                is ActiveSkillHostMessage.Output -> reply.putString(KEY_OUTPUT, outcome.raw)
                is ActiveSkillHostMessage.Failed -> reply.putString(KEY_ERROR, outcome.message)
                ActiveSkillHostMessage.Rejected -> reply.putString(KEY_ERROR, "The skill failed.")
            }
            try {
                replyTo.send(Message.obtain(null, MSG_SKILL_RESULT).apply { this.data = reply })
            } catch (_: RemoteException) {
                // The caller went away; nothing to deliver.
            }
        }
        return true
    }

    private suspend fun runSkill(data: Bundle): ActiveSkillHostMessage {
        if (!ActiveSkillProcess.isDataDirectoryIsolated) {
            return ActiveSkillHostMessage.Failed("Skills cannot run: the skills process is not isolated.")
        }
        if (!disableCookies()) {
            return ActiveSkillHostMessage.Failed("Skills cannot run: cookies could not be disabled.")
        }
        val directory = data.getString(KEY_BUNDLE_DIR)?.let(::File)
            ?: return ActiveSkillHostMessage.Failed("The skill files are missing.")
        val files = readBundle(directory) ?: return ActiveSkillHostMessage.Failed("The skill files could not be read.")
        val bundle = runCatching {
            ActiveSkillBundle(data.getString(KEY_SKILL_NAME).orEmpty(), data.getString(KEY_DIGEST).orEmpty(), files)
        }.getOrNull() ?: return ActiveSkillHostMessage.Failed("The skill files could not be read.")
        if (bundle.digest != activeSkillBundleDigest(files)) {
            return ActiveSkillHostMessage.Failed("The skill files changed. Review and trust the skill again.")
        }
        val request = data.getString(KEY_REQUEST) ?: return ActiveSkillHostMessage.Failed("The request is missing.")
        val timeoutMs = data.getLong(KEY_TIMEOUT_MS, ACTIVE_SKILL_TIMEOUT_MS).coerceIn(1L, ACTIVE_SKILL_TIMEOUT_MS)
        return ActiveSkillWebViewRunner(this, timeoutMs).run(bundle, request)
    }

    /** Process-wide in the skills process only; the app process keeps its own cookie settings. */
    private suspend fun disableCookies(): Boolean {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(false)
        suspendCancellableCoroutine { continuation ->
            cookies.removeAllCookies { if (continuation.isActive) continuation.resume(Unit) }
        }
        cookies.flush()
        return !cookies.acceptCookie() && !cookies.hasCookies()
    }

    private fun readBundle(directory: File): Map<String, ByteArray>? = runCatching {
        val root = directory.canonicalFile
        var total = 0L
        root.walkTopDown().filter { it.isFile }.associate { file ->
            total += file.length()
            check(total <= ACTIVE_SKILL_MAX_BUNDLE_BYTES)
            file.canonicalFile.relativeTo(root).invariantSeparatorsPath to file.readBytes()
        }
    }.getOrNull()
}
