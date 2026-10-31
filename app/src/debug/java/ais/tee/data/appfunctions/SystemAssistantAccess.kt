package ais.tee.data.appfunctions

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** This preview requires Android 17's full-schema indexer. No startup hook. */
internal object SystemAssistantAccess {
    private val updates = Mutex()

    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 37 && AppFunctionManager.getInstance(context) != null

    suspend fun setEnabled(
        context: Context,
        enabled: Boolean,
        expectedGeneration: Long = SystemAssistantConsentStore.beginUpdate(),
    ): Boolean = withContext(Dispatchers.IO) {
        updates.withLock {
            val consent = SystemAssistantConsentStore
            if (!consent.isCurrent(expectedGeneration)) return@withLock false
            if (Build.VERSION.SDK_INT < 37) return@withLock false
            val manager = AppFunctionManager.getInstance(context) ?: return@withLock false
            try {
                manager.setAppFunctionEnabled(
                    functionId,
                    if (enabled) AppFunctionManager.APP_FUNCTION_STATE_ENABLED
                    else AppFunctionManager.APP_FUNCTION_STATE_DISABLED,
                )
                consent.completeUpdate(expectedGeneration, enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Indexing may lag installation. Keep local denial; user may retry explicitly.
                false
            }
        }
    }

    @get:RequiresApi(36)
    internal val functionId: String
        get() = AisteeAppFunctionService.FUNCTION_ID_COUNT_TOKENS
}
