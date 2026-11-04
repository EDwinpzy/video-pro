package com.qiubo.optimaltv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qiubo.optimaltv.ui.search.SearchDebugBus

/**
 * 调试广播（仅 debug 构建注册，见 src/debug/AndroidManifest.xml）：
 * adb shell am broadcast -a com.qiubo.optimaltv.DEBUG_QUERY --es q "爱情公寓"
 * → 注入搜索页输入框（MuMu 模拟器 input 注键丢键严重，遥控键盘驱动不可靠）。
 */
class DebugQueryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("q")?.let { SearchDebugBus.pendingQuery = it }
    }
}
