package com.dongpiandi.voicehelper.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicReference

/**
 * Collects ASR text only from accessibility roots owned by the JMGO voice package.
 *
 * Accessibility calls stay on the main thread. [latest] and [clear] are safe from any thread;
 * [refreshAndGet] returns the cached value instead of touching nodes when called off-main.
 */
class VendorUiTranscriptCollector(
    private val service: AccessibilityService,
) {
    private val latestValue = AtomicReference<String?>(null)

    fun onAccessibilityEvent(event: AccessibilityEvent?): Boolean {
        if (event == null || !isMainThread() || !isRelevant(event.eventType)) return false
        if (event.packageName?.toString() != VENDOR_PACKAGE) return false

        val eventText = selectEventText(event)
        if (eventText != null) {
            latestValue.set(eventText)
            return true
        }

        val sourceText = collectEventSource(event)
        if (sourceText != null) {
            latestValue.set(sourceText)
            return true
        }

        refreshAndGet()
        return true
    }

    fun latest(): String? = latestValue.get()

    fun refreshAndGet(): String? {
        if (!isMainThread()) return latestValue.get()
        transcriptFromWindows()?.let(latestValue::set)
        return latestValue.get()
    }

    fun clear() {
        latestValue.set(null)
    }

    private fun selectEventText(event: AccessibilityEvent): String? {
        val eventTexts =
            buildList<CharSequence?> {
                addAll(event.text)
                event.contentDescription?.let(::add)
            }
        return VendorTranscriptPolicy.select(eventTexts)
    }

    private fun collectEventSource(event: AccessibilityEvent): String? {
        val source = runCatching { event.source }.getOrNull() ?: return null
        return try {
            if (!isVendorNode(source)) return null
            transcriptFromRoot(source)
        } finally {
            recycleNodeCompat(source)
        }
    }

    private fun transcriptFromWindows(): String? {
        val candidates = ArrayList<String>()
        val budget = WalkBudget()
        var scannedVendorRoot = false
        val windows = runCatching { service.windows }.getOrNull().orEmpty()

        for (window in windows) {
            try {
                val root = runCatching { window.root }.getOrNull() ?: continue
                try {
                    if (isVendorNode(root)) {
                        scannedVendorRoot = true
                        collectFromNode(root, 0, budget, candidates)
                    }
                } finally {
                    recycleNodeCompat(root)
                }
            } finally {
                recycleWindowCompat(window)
            }
        }

        if (!scannedVendorRoot) {
            val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
            if (activeRoot != null) {
                try {
                    if (isVendorNode(activeRoot)) {
                        collectFromNode(activeRoot, 0, budget, candidates)
                    }
                } finally {
                    recycleNodeCompat(activeRoot)
                }
            }
        }

        return VendorTranscriptPolicy.select(candidates)
    }

    private fun transcriptFromRoot(root: AccessibilityNodeInfo): String? {
        val candidates = ArrayList<String>()
        collectFromNode(root, 0, WalkBudget(), candidates)
        return VendorTranscriptPolicy.select(candidates)
    }

    private fun collectFromNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        budget: WalkBudget,
        candidates: MutableList<String>,
    ) {
        if (depth > MAX_DEPTH || budget.visited >= MAX_NODES) return
        budget.visited += 1
        if (!isVendorNode(node)) return
        if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) return

        addNodeText(node.text, candidates)
        addNodeText(node.contentDescription, candidates)

        if (depth == MAX_DEPTH || budget.visited >= MAX_NODES) return
        val childCount =
            runCatching { node.childCount }
                .getOrDefault(0)
                .coerceAtMost(MAX_CHILDREN_PER_NODE)
        for (index in 0 until childCount) {
            if (budget.visited >= MAX_NODES) return
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                collectFromNode(child, depth + 1, budget, candidates)
            } finally {
                recycleNodeCompat(child)
            }
        }
    }

    private fun addNodeText(
        text: CharSequence?,
        candidates: MutableList<String>,
    ) {
        val value = text?.toString()?.takeIf { it.isNotBlank() } ?: return
        candidates += value
    }

    private fun isVendorNode(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.packageName?.toString() == VENDOR_PACKAGE }.getOrDefault(false)

    private fun isRelevant(eventType: Int): Boolean =
        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> true
            else -> false
        }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    @Suppress("DEPRECATION")
    private fun recycleNodeCompat(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
    }

    @Suppress("DEPRECATION")
    private fun recycleWindowCompat(window: AccessibilityWindowInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) window.recycle()
    }

    private class WalkBudget(
        var visited: Int = 0,
    )

    private companion object {
        const val VENDOR_PACKAGE = "com.jmgo.ai.voice"
        const val MAX_DEPTH = 14
        const val MAX_NODES = 192
        const val MAX_CHILDREN_PER_NODE = 24
    }
}
