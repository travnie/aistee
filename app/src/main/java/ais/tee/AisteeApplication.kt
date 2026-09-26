package ais.tee

import android.app.Application
import androidx.work.Configuration
import ais.tee.security.AppLock
import ais.tee.security.ScreenPrivacyActivityCallbacks

class AisteeApplication : Application(), Configuration.Provider {
    // On-demand WorkManager initialization: nothing runs at startup, and instrumentation tests can
    // install a test WorkManager before first use.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(ScreenPrivacyActivityCallbacks)
        registerActivityLifecycleCallbacks(AppLock)
    }
}
