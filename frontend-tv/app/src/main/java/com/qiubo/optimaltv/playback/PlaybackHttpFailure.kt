package com.qiubo.optimaltv.playback

import androidx.media3.datasource.HttpDataSource
import java.util.Collections
import java.util.IdentityHashMap

/** Extract the response code without retaining request URLs, headers or credentials in UI state. */
internal object PlaybackHttpFailure {
    fun statusCode(failure: Throwable): Int? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = failure
        while (cause != null && visited.add(cause)) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
            cause = cause.cause
        }
        return null
    }
}
