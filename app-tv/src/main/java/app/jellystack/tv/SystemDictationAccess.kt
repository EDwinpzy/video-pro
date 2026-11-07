package app.jellystack.tv

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

internal object SystemDictationAccess {
    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { enabled ->
                val service = enabled.resolveInfo.serviceInfo
                service.packageName == context.packageName &&
                    service.name == SystemDictationService::class.java.name
            }
    }

    fun openSettings(context: Context): Boolean =
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            true
        }.getOrDefault(false)
}
