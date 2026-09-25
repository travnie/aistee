package ais.tee

import android.app.Application
import ais.tee.security.AppLock
import ais.tee.security.ScreenPrivacyActivityCallbacks

class AisteeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(ScreenPrivacyActivityCallbacks)
        registerActivityLifecycleCallbacks(AppLock)
    }
}
