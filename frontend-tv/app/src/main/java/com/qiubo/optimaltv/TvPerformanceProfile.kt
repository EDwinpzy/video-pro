package com.qiubo.optimaltv

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings

internal enum class TvPerformanceTier {
    BALANCED,
    CONSTRAINED,
}

internal object TvPerformancePolicy {
    private const val CONSTRAINED_HEAP_MB = 192
    private const val CONSTRAINED_CPU_COUNT = 2

    fun tier(
        maxHeapMb: Int,
        cpuCount: Int,
        lowRamDevice: Boolean,
    ): TvPerformanceTier =
        if (lowRamDevice || maxHeapMb <= CONSTRAINED_HEAP_MB || cpuCount <= CONSTRAINED_CPU_COUNT) {
            TvPerformanceTier.CONSTRAINED
        } else {
            TvPerformanceTier.BALANCED
        }

    fun coverPreloadLimit(tier: TvPerformanceTier): Int =
        if (tier == TvPerformanceTier.CONSTRAINED) 8 else 64

    fun coverPreloadConcurrency(tier: TvPerformanceTier): Int =
        if (tier == TvPerformanceTier.CONSTRAINED) 2 else 8

    fun posterDecodeScale(tier: TvPerformanceTier): Float =
        if (tier == TvPerformanceTier.CONSTRAINED) 1f else 2f

    fun focusScaleMotionEnabled(tier: TvPerformanceTier): Boolean =
        tier == TvPerformanceTier.BALANCED

    fun navigationMotionEnabled(tier: TvPerformanceTier): Boolean =
        tier == TvPerformanceTier.BALANCED
}

/**
 * Runtime performance profile for TV hardware.
 *
 * Projectors and low-cost boxes often expose only a 128-192 MB application heap even
 * when the device has 2 GB of RAM. Keep expensive full-screen motion off on those
 * devices and leave the inexpensive focus ring/background as the primary focus cue.
 */
internal object TvPerformanceProfile {
    private const val BYTES_PER_MB = 1024L * 1024L
    private const val VENDOR_ACCESSIBILITY_PREFIX = "com.jmgo."

    private val tier: TvPerformanceTier by lazy {
        val lowRamDevice = runCatching {
            (Graph.appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .isLowRamDevice
        }.getOrDefault(false)
        val maxHeapMb = (Runtime.getRuntime().maxMemory() / BYTES_PER_MB).toInt()
        val cpuCount = Runtime.getRuntime().availableProcessors()
        TvPerformancePolicy.tier(
            maxHeapMb = maxHeapMb,
            cpuCount = cpuCount,
            lowRamDevice = lowRamDevice,
        ).also { selected ->
            OtvLog.i(
                "performance tier=${selected.name} maxHeapMb=$maxHeapMb " +
                    "cpuCount=$cpuCount lowRamDevice=$lowRamDevice " +
                    "focusScale=${TvPerformancePolicy.focusScaleMotionEnabled(selected)}",
            )
        }
    }

    val isConstrained: Boolean
        get() = tier == TvPerformanceTier.CONSTRAINED

    val focusScaleMotionEnabled: Boolean
        get() = TvPerformancePolicy.focusScaleMotionEnabled(tier)

    val navigationMotionEnabled: Boolean
        get() = TvPerformancePolicy.navigationMotionEnabled(tier)

    /**
     * Some projector firmware ships an always-on accessibility service that rebuilds
     * the semantic tree on every focus change. That service is a key injector, not a
     * screen reader. On constrained devices we keep native focus/click handling but
     * stop exporting per-focus semantics unless a real screen reader is enabled.
     */
    private val suppressFocusSemanticsOnVendorScanner: Boolean by lazy {
        if (!isConstrained) {
            false
        } else {
            runCatching {
                val enabled =
                    Settings.Secure.getString(
                        Graph.appContext.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    ).orEmpty()
                val packages =
                    enabled.split(':')
                        .map { it.substringBefore('/').trim() }
                        .filter { it.isNotEmpty() }
                val vendorScanner = packages.any { it.startsWith(VENDOR_ACCESSIBILITY_PREFIX) }
                val screenReader =
                    packages.any {
                        it == "com.google.android.marvin.talkback" ||
                            it == "com.android.talkback" ||
                            it.contains("screenreader", ignoreCase = true)
                    }
                vendorScanner && !screenReader
            }.getOrDefault(false)
        }
    }

    val suppressFocusSemantics: Boolean
        get() = suppressFocusSemanticsOnVendorScanner

    val coverPreloadLimit: Int
        get() = TvPerformancePolicy.coverPreloadLimit(tier)

    val coverPreloadConcurrency: Int
        get() = TvPerformancePolicy.coverPreloadConcurrency(tier)

    val posterDecodeScale: Float
        get() = TvPerformancePolicy.posterDecodeScale(tier)

    val backgroundWarmupDelayMs: Long
        get() = if (isConstrained) 1_500L else 0L

    val startupFirstPaintBudgetMs: Long
        get() = if (isConstrained) 3_000L else 4_500L
}
