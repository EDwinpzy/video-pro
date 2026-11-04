package com.qiubo.optimaltv.lifecycle

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

enum class ForegroundDecision { NONE, REFRESH, REFRESH_AND_RENEW_LIVE }

object AppLifecyclePolicy {
    const val LIVE_RENEW_BACKGROUND_MS = 30_000L

    fun onForeground(backgroundedAtMs: Long, nowMs: Long, isLive: Boolean): ForegroundDecision {
        if (backgroundedAtMs <= 0L || nowMs < backgroundedAtMs) return ForegroundDecision.NONE
        return if (isLive && nowMs - backgroundedAtMs >= LIVE_RENEW_BACKGROUND_MS) {
            ForegroundDecision.REFRESH_AND_RENEW_LIVE
        } else {
            ForegroundDecision.REFRESH
        }
    }

    fun isExplicitExit(marker: Boolean): Boolean = marker
}

/** One process-wide refresh signal for foreground returns and network recovery. */
object AppLifecycleManager {
    private val initialized = AtomicBoolean(false)
    private val _refreshEpoch = MutableStateFlow(0L)
    val refreshEpoch: StateFlow<Long> = _refreshEpoch.asStateFlow()

    @Volatile private var backgroundedAtMs = 0L
    private var connectivity: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        connectivity = cm
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = signalRefresh()
        }.also { runCatching { cm.registerDefaultNetworkCallback(it) } }
    }

    fun onBackground() {
        if (backgroundedAtMs == 0L) backgroundedAtMs = SystemClock.elapsedRealtime()
    }

    fun onForeground() {
        if (backgroundedAtMs > 0L) {
            backgroundedAtMs = 0L
            signalRefresh()
        }
    }

    fun signalRefresh() {
        _refreshEpoch.value = _refreshEpoch.value + 1L
    }

    fun shutdown() {
        callback?.let { cb -> runCatching { connectivity?.unregisterNetworkCallback(cb) } }
        callback = null
        connectivity = null
        backgroundedAtMs = 0L
        initialized.set(false)
    }
}
