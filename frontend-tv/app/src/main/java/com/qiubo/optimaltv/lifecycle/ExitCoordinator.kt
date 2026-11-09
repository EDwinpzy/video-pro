package com.qiubo.optimaltv.lifecycle

import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import com.qiubo.optimaltv.OtvLog

/** Explicit user exit is intentionally stronger than an ordinary background transition. */
object ExitCoordinator {
    private val exitStarted = AtomicBoolean(false)

    fun isExiting(): Boolean = exitStarted.get()

    fun exit(activity: Activity) {
        if (!exitStarted.compareAndSet(false, true)) return
        OtvLog.i("explicit exit: release playback helpers")
        AppLifecycleManager.shutdown()
        // finishAffinity only empties the activities; the task itself remains in Recents.
        // Explicit double-BACK must leave no background task behind.
        activity.finishAndRemoveTask()
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 700L)
    }
}
