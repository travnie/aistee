package ais.tee.data.skills

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Diagnostics the skills process reports with each result; used by device tests. */
internal data class ActiveSkillRunReport(
    val outcome: ActiveSkillOutcome,
    val processId: Int?,
    val cookiesAccepted: Boolean?,
)

/**
 * App-process side of the skills sandbox: hands one bundle and request to [ActiveSkillService]
 * in the `:skills` process and validates what comes back. The bundle travels as a private,
 * per-call directory that is deleted afterwards; nothing else crosses the process boundary.
 */
internal class ActiveSkillSandbox(
    context: Context,
    private val timeoutMs: Long = ACTIVE_SKILL_TIMEOUT_MS,
) {
    private val appContext = context.applicationContext

    suspend fun run(
        bundle: ActiveSkillBundle,
        declaration: ActiveSkillDeclaration,
        requestJson: String,
    ): ActiveSkillOutcome = runWithReport(bundle, declaration, requestJson).outcome

    suspend fun runWithReport(
        bundle: ActiveSkillBundle,
        declaration: ActiveSkillDeclaration,
        requestJson: String,
    ): ActiveSkillRunReport {
        if (declaration.networkOrigins.isNotEmpty()) {
            return ActiveSkillRunReport(ActiveSkillOutcome.Error("Skills with network access cannot run yet."), null, null)
        }
        if (bundle.files.values.sumOf { it.size.toLong() } > ACTIVE_SKILL_MAX_BUNDLE_BYTES) {
            return ActiveSkillRunReport(ActiveSkillOutcome.Error("The skill is too large to run."), null, null)
        }
        val directory = File(appContext.noBackupFilesDir, "active-skill-runs/${UUID.randomUUID()}")
        try {
            withContext(Dispatchers.IO) { writeBundle(directory, bundle) }
            val reply = withTimeoutOrNull(timeoutMs + SERVICE_GRACE_MS) {
                callService(directory, bundle, requestJson)
            } ?: return ActiveSkillRunReport(
                ActiveSkillOutcome.Error("The skill did not finish within ${timeoutMs / 1000} s."),
                null,
                null,
            )
            val outcome = reply.getString(KEY_OUTPUT)?.let { parseActiveSkillOutput(it, declaration) }
                ?: ActiveSkillOutcome.Error(reply.getString(KEY_ERROR) ?: "The skill failed.")
            return ActiveSkillRunReport(
                outcome,
                reply.getInt(KEY_PID).takeIf { reply.containsKey(KEY_PID) },
                reply.getBoolean(KEY_COOKIES_ACCEPTED).takeIf { reply.containsKey(KEY_COOKIES_ACCEPTED) },
            )
        } finally {
            withContext(Dispatchers.IO) { directory.deleteRecursively() }
        }
    }

    private fun writeBundle(directory: File, bundle: ActiveSkillBundle) {
        val root = directory.canonicalFile
        bundle.files.forEach { (path, bytes) ->
            val file = File(root, path).canonicalFile
            require(file.path.startsWith(root.path + File.separator)) { "Bundle path escapes its directory" }
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }

    private suspend fun callService(directory: File, bundle: ActiveSkillBundle, requestJson: String): Bundle {
        val reply = CompletableDeferred<Bundle>()
        val connected = CompletableDeferred<Messenger>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.complete(Messenger(service))
            }

            override fun onServiceDisconnected(name: ComponentName) {
                reply.complete(Bundle().apply { putString(KEY_ERROR, "The skill stopped unexpectedly.") })
            }

            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        }
        val bound = appContext.bindService(
            Intent(appContext, ActiveSkillService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) return Bundle().apply { putString(KEY_ERROR, "The skills process could not start.") }
        try {
            val service = connected.await()
            val replyTo = Messenger(Handler(Looper.getMainLooper()) { message ->
                if (message.what == MSG_SKILL_RESULT) reply.complete(Bundle(message.data))
                true
            })
            val request = Message.obtain(null, MSG_RUN_SKILL).apply {
                this.replyTo = replyTo
                data = Bundle().apply {
                    putString(KEY_BUNDLE_DIR, directory.path)
                    putString(KEY_SKILL_NAME, bundle.skillName)
                    putString(KEY_DIGEST, bundle.digest)
                    putString(KEY_REQUEST, requestJson)
                    putLong(KEY_TIMEOUT_MS, timeoutMs)
                }
            }
            try {
                service.send(request)
            } catch (_: RemoteException) {
                return Bundle().apply { putString(KEY_ERROR, "The skill stopped unexpectedly.") }
            }
            return reply.await()
        } finally {
            appContext.unbindService(connection)
        }
    }

    private companion object {
        const val SERVICE_GRACE_MS = 5_000L
    }
}
