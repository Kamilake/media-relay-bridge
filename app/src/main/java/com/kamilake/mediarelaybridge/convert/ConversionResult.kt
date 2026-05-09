package com.kamilake.mediarelaybridge.convert

import android.net.Uri

sealed interface ConversionResult {
    data class Success(val uri: Uri, val mimeType: String) : ConversionResult
    data class Skipped(val reason: String) : ConversionResult
    data class Failed(val reason: String, val cause: Throwable? = null) : ConversionResult
}
