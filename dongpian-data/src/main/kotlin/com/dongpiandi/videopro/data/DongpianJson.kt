package com.dongpiandi.videopro.data

import kotlinx.serialization.json.Json

internal object DongpianJson {
    val value: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }
}
