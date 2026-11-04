package com.qiubo.optimaltv.playback

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackHttpFailureTest {
    @Test fun responseCodeSurvivesWrappedMediaFailure() {
        val failure = HttpDataSource.InvalidResponseCodeException(
            403, "Forbidden", null, emptyMap(),
            DataSpec(Uri.parse("https://example.invalid/video.m3u8")), byteArrayOf(),
        )
        assertEquals(403, PlaybackHttpFailure.statusCode(IllegalStateException("Source error", failure)))
        assertNull(PlaybackHttpFailure.statusCode(IllegalStateException("Network timeout")))
    }
}
