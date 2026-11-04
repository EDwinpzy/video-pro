package com.dongpiandi.voicehelper

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

internal object VoiceHelperAccess {
    enum class EnableRequest {
        ALREADY_ENABLED,
        VENDOR_REQUESTED,
        SETTINGS_OPENED,
        UNAVAILABLE,
    }

    fun canUseVendorEngine(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            !Process.is64Bit() &&
            Build.SUPPORTED_32_BIT_ABIS.contains("armeabi-v7a") &&
            runCatching {
                context.packageManager.getApplicationInfo(VENDOR_PACKAGE, 0)
                true
            }.getOrDefault(false)

    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { enabled ->
                val service = enabled.resolveInfo.serviceInfo
                service.packageName == context.packageName &&
                    service.name == VoiceGatewayService::class.java.name
            }
    }

    fun conflictingDictationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { enabled ->
                val service = enabled.resolveInfo.serviceInfo
                service.packageName == VIDEO_PRO_PACKAGE &&
                    service.name == VIDEO_PRO_DICTATION_SERVICE
            }
    }

    fun requestEnable(context: Context): EnableRequest {
        if (isEnabled(context)) return EnableRequest.ALREADY_ENABLED
        if (requestVendorEnable(context)) return EnableRequest.VENDOR_REQUESTED
        return if (openSettings(context)) {
            EnableRequest.SETTINGS_OPENED
        } else {
            EnableRequest.UNAVAILABLE
        }
    }

    fun openSettings(context: Context): Boolean =
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)

    private fun requestVendorEnable(context: Context): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(VENDOR_ACCESSIBILITY_CONTROLLER, 0)
            val component = ComponentName(context, VoiceGatewayService::class.java).flattenToString()
            context.sendBroadcast(
                Intent(ACTION_REQUEST_VENDOR_ACCESSIBILITY)
                    .setPackage(VENDOR_ACCESSIBILITY_CONTROLLER)
                    .putExtra(EXTRA_COMPONENT, component)
                    .putExtra(EXTRA_ENABLED, true),
            )
            true
        }.getOrDefault(false)

    private const val VENDOR_PACKAGE = "com.jmgo.ai.voice"
    private const val VENDOR_ACCESSIBILITY_CONTROLLER = "com.jmgo.hippo"
    private const val ACTION_REQUEST_VENDOR_ACCESSIBILITY =
        "action.jmgo.request.accessibility.service"
    private const val EXTRA_COMPONENT = "compontentNameStr"
    private const val EXTRA_ENABLED = "enabled"
    private const val VIDEO_PRO_PACKAGE = "com.dongpiandi.videopro"
    private const val VIDEO_PRO_DICTATION_SERVICE =
        "com.qiubo.optimaltv.dictation.SystemDictationService"
}
