package com.qiubo.optimaltv.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RememberedLoginDeviceTest {
    @Test
    fun rememberedLoginRoundTripsWithoutPlaintextOnDisk() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = DongpianAuthClient.rememberedLogin(context)
        val account = "test.user@example.com"
        val password = "example-password-123"
        try {
            assertTrue(DongpianAuthClient.rememberLogin(context, account, password))
            assertEquals(
                DongpianRememberedLogin(account, password),
                DongpianAuthClient.rememberedLogin(context),
            )

            val preferencesFile =
                File(context.applicationInfo.dataDir, "shared_prefs/video_pro_remembered_login.xml")
            assertTrue(preferencesFile.isFile)
            val encryptedText = preferencesFile.readText()
            assertFalse(encryptedText.contains(account))
            assertFalse(encryptedText.contains(password))

            DongpianAuthClient.forgetRememberedLogin(context)
            assertNull(DongpianAuthClient.rememberedLogin(context))
        } finally {
            if (original == null) {
                DongpianAuthClient.forgetRememberedLogin(context)
            } else {
                DongpianAuthClient.rememberLogin(context, original.account, original.password)
            }
        }
    }
}
