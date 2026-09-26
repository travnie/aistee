package ais.tee

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import ais.tee.notifications.NativeChatDirectReply
import ais.tee.notifications.nativeChatQueuedSendTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeChatQueuedSendWorkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val workManager = WorkManager.getInstance(context)

    private fun queuedWork(replyId: String): WorkInfo =
        workManager.getWorkInfosByTag(nativeChatQueuedSendTag(replyId)).get().single()

    @Test
    fun queuedSendWaitsForTheNetworkAndCanBeCancelled() {
        assertTrue(NativeChatDirectReply.enqueueQueuedSend(context, "queue-test-chat", "reply-wait", "hello", 0L))
        assertEquals(WorkInfo.State.ENQUEUED, queuedWork("reply-wait").state)

        NativeChatDirectReply.cancelQueuedSend(context, "reply-wait")

        assertEquals(WorkInfo.State.CANCELLED, queuedWork("reply-wait").state)
    }

    @Test
    fun networkArrivalRunsTheQueuedSend() {
        assertTrue(NativeChatDirectReply.enqueueQueuedSend(context, "queue-test-missing-chat", "reply-run", "hello", 0L))
        val work = queuedWork("reply-run")
        assertEquals(WorkInfo.State.ENQUEUED, work.state)

        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work.id)

        // The conversation does not exist, so the worker drops the turn and finishes.
        assertEquals(WorkInfo.State.SUCCEEDED, queuedWork("reply-run").state)
    }
}
