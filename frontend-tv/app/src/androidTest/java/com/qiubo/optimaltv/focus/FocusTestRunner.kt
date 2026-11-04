package com.qiubo.optimaltv.focus

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * 焦点仪器测试专用 Runner：把 Application 换成空壳 FocusTestApp，
 * 绕开 App.kt 的 Chaquopy 后端拉起 / 许可证检查 / 启动门闸——
 * 引擎测试只需要 Compose + OtvNav 本体，不依赖任何业务初始化。
 */
class FocusTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, name: String, context: Context): Application =
        super.newApplication(cl, FocusTestApp::class.java.name, context)
}

class FocusTestApp : Application()
