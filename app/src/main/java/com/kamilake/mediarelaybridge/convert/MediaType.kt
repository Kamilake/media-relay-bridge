package com.kamilake.mediarelaybridge.convert

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import java.util.Locale

enum class MediaKind { IMAGE, VIDEO, UNKNOWN }

/**
 * MIME ? ë¢°ê°€ ?´ë ¤??ê²½ìš°(?¹ížˆ HEICê°€ image/jpegë¡??„ìž¥)ë¥??€ë¹„í•´
 * (1) ContentResolver MIME ??(2) ?•ìž¥????(3) magic bytes ?œìœ¼ë¡??ì •.
 */
object MediaType {

    private val IMAGE_MIME = setOf(
        "image/heic", "image/heif",
        "image/heic-sequence", "image/heif-sequence",
        "image/avif", "image/avif-sequence"
    )
    private val VIDEO_MIME = setOf(
        "video/heic", "video/heif", "video/avif",
        "video/hevc", "video/h265", "video/x-matroska", "video/mp4"
    )
    private val IMAGE_EXT = setOf("heic", "heif", "avif", "heics", "heifs")
    private val VIDEO_EXT = setOf("mov", "mp4", "mkv", "hevc", "h265")

    fun detect(resolver: ContentResolver, uri: Uri): Detection {
        val mime = resolver.type(uri)?.lowercase(Locale.US)
        val ext = extensionOf(uri)?.lowercase(Locale.US)
        val byMime = mime?.let(::classify) ?: MediaKind.UNKNOWN
        val byExt = ext?.let(::classifyByExt) ?: MediaKind.UNKNOWN

        var kind = byMime.takeUnless { it == MediaKind.UNKNOWN } ?: byExt
        var ftyp: String? = null

        if (kind == MediaKind.UNKNOWN || mime == null || mime == "application/octet-stream") {
            ftyp = readFtypBrand(resolver, uri)
            if (ftyp != null) {
                kind = when (ftyp) {
                    in HEIF_IMAGE_BRANDS, in AVIF_IMAGE_BRANDS -> MediaKind.IMAGE
                    in HEIF_VIDEO_BRANDS, in AVIF_VIDEO_BRANDS -> MediaKind.VIDEO
                    else -> kind
                }
            }
        }
        return Detection(kind = kind, mimeType = mime, extension = ext, ftypBrand = ftyp)
    }

    private fun ContentResolver.type(uri: Uri): String? = runCatching { getType(uri) }.getOrNull()

    private fun extensionOf(uri: Uri): String? {
        val lastSeg = uri.lastPathSegment
        if (lastSeg != null) {
            val dot = lastSeg.lastIndexOf('.')
            if (dot >= 0 && dot < lastSeg.length - 1) return lastSeg.substring(dot + 1)
        }
        return MimeTypeMap.getFileExtensionFromUrl(uri.toString()).takeIf { it.isNotEmpty() }
    }

    private fun classify(mime: String): MediaKind = when {
        mime in IMAGE_MIME -> MediaKind.IMAGE
        mime in VIDEO_MIME -> MediaKind.VIDEO
        mime.startsWith("image/") -> MediaKind.IMAGE
        mime.startsWith("video/") -> MediaKind.VIDEO
        else -> MediaKind.UNKNOWN
    }

    private fun classifyByExt(ext: String): MediaKind = when (ext) {
        in IMAGE_EXT -> MediaKind.IMAGE
        in VIDEO_EXT -> MediaKind.VIDEO
        else -> MediaKind.UNKNOWN
    }

    // ISO BMFF: bytes 4..8 = "ftyp", bytes 8..12 = major brand
    private val HEIF_IMAGE_BRANDS = setOf("heic", "heix", "mif1", "msf1", "heim", "heis")
    private val HEIF_VIDEO_BRANDS = setOf("hevc", "hevx", "hevm", "hevs")
    private val AVIF_IMAGE_BRANDS = setOf("avif", "avis")
    private val AVIF_VIDEO_BRANDS = setOf("avio")

    private fun readFtypBrand(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(12)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n <= 0) return@use null
                read += n
            }
            if (header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
            ) {
                String(header, 8, 4, Charsets.US_ASCII).lowercase(Locale.US)
            } else null
        }
    }.getOrNull()

    data class Detection(
        val kind: MediaKind,
        val mimeType: String?,
        val extension: String?,
        val ftypBrand: String?
    )
}