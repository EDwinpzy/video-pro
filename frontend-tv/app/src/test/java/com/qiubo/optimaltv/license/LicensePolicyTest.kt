package com.qiubo.optimaltv.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicensePolicyTest {
    @Test
    fun deviceIdUsesTheFullStableSha256() {
        val id = LicensePolicy.deviceId(
            androidId = "android-id",
            packageName = "com.qiubo.optimaltv.public",
            model = "M2012K11AC",
        )

        assertEquals(64, id.length)
        assertEquals(
            "e4aa48f94a0ac5eec0682b8e90336839d7dda3d4609ab0e502531430b773a8c0",
            id,
        )
    }

    @Test
    fun deviceIdSeparatesTvAndMobilePackages() {
        val tv = LicensePolicy.deviceId("same-android", "com.qiubo.optimaltv.public", "same-model")
        val mobile = LicensePolicy.deviceId("same-android", "com.qiubo.optimaltv.mobile", "same-model")

        assertFalse(tv == mobile)
    }

    @Test
    fun transportFailureKeepsAccessForLessThanTwentyFourHours() {
        val lastSuccess = 1_000L

        assertTrue(LicensePolicy.withinOfflineGrace(lastSuccess, lastSuccess + LicensePolicy.OFFLINE_GRACE_MS - 1))
        assertFalse(LicensePolicy.withinOfflineGrace(lastSuccess, lastSuccess + LicensePolicy.OFFLINE_GRACE_MS))
        assertFalse(LicensePolicy.withinOfflineGrace(0L, lastSuccess))
    }

    @Test
    fun expiryStillStopsAccessEvenInsideOfflineGrace() {
        val lastSuccess = 10_000L
        assertTrue(LicensePolicy.accessAllowed(20_000L, lastSuccess, 19_999L))
        assertFalse(LicensePolicy.accessAllowed(20_000L, lastSuccess, 20_000L))
        assertTrue(LicensePolicy.accessAllowed(null, lastSuccess, 19_999L))
    }
}
