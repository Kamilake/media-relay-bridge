package com.kamilake.mediarelaybridge.convert

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log

/**
 * Dumps all HEVC decoder capabilities reported by the device.
 * Helps diagnose why the system HEIF path fails on specific HEVC streams
 * (e.g. 8K single-frame stills with unusual profile/level/tier combinations).
 */
object CodecDiagnostics {
    private const val TAG = "CodecDiag"

    fun logHevcDecoders() {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            val types = info.supportedTypes
            if (types.none { it.equals("video/hevc", ignoreCase = true) }) continue

            val isSoftware = try { info.isSoftwareOnly } catch (_: Throwable) { false }
            val isHardware = try { info.isHardwareAccelerated } catch (_: Throwable) { false }
            val isVendor = try { info.isVendor } catch (_: Throwable) { false }

            Log.i(TAG, "decoder name=${info.name} sw=$isSoftware hw=$isHardware vendor=$isVendor")

            val caps = runCatching { info.getCapabilitiesForType("video/hevc") }.getOrNull() ?: continue
            val vc = caps.videoCapabilities
            if (vc != null) {
                Log.i(
                    TAG,
                    "  width=${vc.supportedWidths} height=${vc.supportedHeights} " +
                        "blockW=${vc.widthAlignment} blockH=${vc.heightAlignment}"
                )
                runCatching {
                    val w7680 = vc.getSupportedHeightsFor(7680)
                    Log.i(TAG, "  heights@7680=$w7680")
                }
                runCatching {
                    val h4320 = vc.getSupportedWidthsFor(4320)
                    Log.i(TAG, "  widths@4320=$h4320")
                }
                Log.i(TAG, "  bitrate=${vc.bitrateRange}")
            }

            for (pl in caps.profileLevels) {
                Log.i(TAG, "  profile=${profileName(pl.profile)}(${pl.profile}) level=${levelName(pl.level)}(${pl.level})")
            }

            val features = caps.colorFormats.joinToString(",")
            Log.i(TAG, "  colorFormats=$features")
        }
    }

    private fun profileName(p: Int): String = when (p) {
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "Main10HDR10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "Main10HDR10+"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "MainStill"
        else -> "?"
    }

    private fun levelName(l: Int): String = when (l) {
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1 -> "MT1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2 -> "MT2"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21 -> "MT2.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3 -> "MT3"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31 -> "MT3.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4 -> "MT4"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41 -> "MT4.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5 -> "MT5"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51 -> "MT5.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52 -> "MT5.2"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6 -> "MT6"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61 -> "MT6.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62 -> "MT6.2"
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4 -> "HT4"
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel41 -> "HT4.1"
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5 -> "HT5"
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51 -> "HT5.1"
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52 -> "HT5.2"
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel6 -> "HT6"
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61 -> "HT6.1"
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel62 -> "HT6.2"
        else -> "?"
    }
}