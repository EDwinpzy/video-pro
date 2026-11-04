package com.qiubo.optimaltv

import android.app.Activity
import android.os.SystemClock
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Local timing at the actual focus event; UIAutomator polling latency is excluded. */
internal object StartupReadiness {
    private var activity = WeakReference<Activity>(null)
    private var startedAt = 0L
    private var reported = false
    private val homeFocused = MutableStateFlow(false)

    suspend fun awaitHomeFocus() {
        // Login/no-network paths may never mount the film home; never hold warmup indefinitely.
        withTimeoutOrNull(15_000L) { homeFocused.first { it } }
    }

    fun bind(host: Activity) {
        activity = WeakReference(host)
        startedAt = SystemClock.elapsedRealtime()
        reported = false
        homeFocused.value = false
    }

    fun onInitialFocus(tag: String) {
        if (reported || tag != "vod-tab") return
        val host = activity.get() ?: return
        reported = true
        homeFocused.value = true
        OtvLog.i("startup home_focus_ready activityElapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
        // reportFullyDrawn is a readiness signal for the usable screen, not the network artwork.
        host.window.decorView.postOnAnimation {
            if (!host.isFinishing && !host.isDestroyed) host.reportFullyDrawn()
        }
    }
}
