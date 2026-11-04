package com.dongpiandi.voice.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorKeyRoutingPolicyTest {
    @Test
    fun routesOnlyForAFocusedVisibleEditor() {
        assertTrue(
            VendorKeyRoutingPolicy.dictationOnly(
                editorAttached = true,
                inputMethodVisible = true,
                microphoneGranted = true,
            ),
        )
        assertFalse(
            VendorKeyRoutingPolicy.dictationOnly(
                editorAttached = false,
                inputMethodVisible = true,
                microphoneGranted = true,
            ),
        )
        assertFalse(
            VendorKeyRoutingPolicy.dictationOnly(
                editorAttached = true,
                inputMethodVisible = false,
                microphoneGranted = true,
            ),
        )
    }

    @Test
    fun neverRoutesWithoutMicrophonePermission() {
        assertFalse(
            VendorKeyRoutingPolicy.dictationOnly(
                editorAttached = true,
                inputMethodVisible = true,
                microphoneGranted = false,
            ),
        )
    }
}
