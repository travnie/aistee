package ais.tee.data.appfunctions

import android.content.Context
import android.os.Build
import androidx.appfunctions.AppFunctionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No platform references loaded below Android 16. No startup hook. */
internal object SystemAssistantAccess {
    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 36 && AppFunctionManager.getInstance(context) != null

    suspend fun setEnabled(context: Context, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val consent = SystemAssistantConsentStore(context)
        // Deny locally before changing OS state; stale indexing never restores consent.
        consent.setEnabled(false)
        if (Build.VERSION.SDK_INT < 36) return@withContext false
        val manager = AppFunctionManager.getInstance(context) ?: return@withContext false
        try {
            manager.setAppFunctionEnabled(
                FUNCTION_ID,
                if (enabled) AppFunctionManager.APP_FUNCTION_STATE_ENABLED
                else AppFunctionManager.APP_FUNCTION_STATE_DISABLED,
            )
            consent.setEnabled(enabled)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Indexing may lag installation. Keep local denial; user may retry explicitly.
            false
        }
    }

    internal const val FUNCTION_ID = "ais.tee.data.appfunctions.BaseAisteeAppFunctionService#countTokens"
}
