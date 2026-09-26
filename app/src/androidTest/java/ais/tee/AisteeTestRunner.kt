package ais.tee

import android.app.Application
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper

/** Installs a test WorkManager (with TestDriver) before any app code can initialize the real one. */
class AisteeTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        super.callApplicationOnCreate(app)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
    }
}
