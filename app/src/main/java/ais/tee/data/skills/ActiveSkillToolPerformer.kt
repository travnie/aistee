package ais.tee.data.skills

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ais.tee.R
import ais.tee.notifications.NativeChatNotificationPublisher
import ais.tee.share.copyPlainTextToClipboard
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Performs a skill's native action only after the user confirmed it in Aistee. Calendar and
 * email open the system insert/compose screen, where the user confirms again; nothing is created
 * or sent silently. Returns a short outcome message.
 */
internal object ActiveSkillToolPerformer {
    fun perform(context: Context, action: ActiveSkillToolAction): String = when (action) {
        ActiveSkillToolAction.CurrentDateTime -> "Current time: ${Instant.now()}"
        is ActiveSkillToolAction.CalendarEvent -> startOrExplain(
            context,
            Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, action.title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, action.startEpochMs)
                .apply {
                    action.endEpochMs?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
                    action.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                    action.description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
                },
            opened = "Opened the calendar to review the event.",
            missing = "No calendar app is available.",
        )
        is ActiveSkillToolAction.Email -> startOrExplain(
            context,
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .putExtra(Intent.EXTRA_EMAIL, action.to.toTypedArray())
                .putExtra(Intent.EXTRA_SUBJECT, action.subject)
                .putExtra(Intent.EXTRA_TEXT, action.body),
            opened = "Opened the email composer; nothing was sent.",
            missing = "No email app is available.",
        )
        is ActiveSkillToolAction.Notification -> scheduleReminder(context, action)
        is ActiveSkillToolAction.Clipboard ->
            if (copyPlainTextToClipboard(context, "Skill output", action.text, sensitive = false)) {
                "Copied to the clipboard."
            } else {
                "Could not copy to the clipboard."
            }
    }

    private fun startOrExplain(context: Context, intent: Intent, opened: String, missing: String): String =
        try {
            context.startActivity(intent)
            opened
        } catch (_: ActivityNotFoundException) {
            missing
        }

    private fun scheduleReminder(context: Context, action: ActiveSkillToolAction.Notification): String {
        if (!NativeChatNotificationPublisher.systemNotificationsAllowed(context)) {
            return "Notifications are turned off for Aistee."
        }
        val delay = (action.atEpochMs - System.currentTimeMillis()).coerceAtLeast(0)
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ActiveSkillReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_TITLE to action.title, KEY_TEXT to action.text))
                .build()
        )
        return "Reminder scheduled for ${Instant.ofEpochMilli(action.atEpochMs)}."
    }

    internal const val KEY_TITLE = "title"
    internal const val KEY_TEXT = "text"
}

/** Posts one reminder the user approved. Its text is hidden on the lock screen. */
class ActiveSkillReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!NativeChatNotificationPublisher.systemNotificationsAllowed(context)) return Result.success()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Skill reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_settings)
            .setContentTitle("Skill reminder")
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_settings)
            .setContentTitle(inputData.getString(ActiveSkillToolPerformer.KEY_TITLE).orEmpty())
            .setContentText(inputData.getString(ActiveSkillToolPerformer.KEY_TEXT).orEmpty())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission was revoked after scheduling.
        }
        return Result.success()
    }

    private companion object {
        const val CHANNEL_ID = "active_skill_reminders"
    }
}
