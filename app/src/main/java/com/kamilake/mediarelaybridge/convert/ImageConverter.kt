package com.kamilake.mediarelaybridge.convert

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * HEIC/HEIF/AVIF still images ??PNG (if alpha) or JPEG-95.
 * ?úÏä§??ImageDecoderÎ•?1Ï∞®Î°ú ?¨Ïö©?òÍ≥†, ?§Ìå® ??BitmapFactoryÎ°??¥Î∞±.
 */
class ImageConverter(private val context: Context) {

    suspend fun convert(sourceUri: Uri): ConversionResult = withContext(Dispatchers.IO) {
        val resolver: ContentResolver = context.contentResolver

        val (bitmap, decodePath) = decode(resolver, sourceUri)
            ?: return@withContext ConversionResult.Failed("all decoders failed")

        Log.d(TAG, "decoded via $decodePath: ${bitmap.width}x${bitmap.height} ${bitmap.config}")

        val hasAlpha = bitmap.hasAlpha() && containsTransparentPixel(bitmap)
        val (ext, mime) = if (hasAlpha) "png" to "image/png" else "jpg" to "image/jpeg"

        val outDir = File(context.cacheDir, "converted").apply { mkdirs() }
        val outFile = File(outDir, "img_${UUID.randomUUID()}.$ext")

        try {
            FileOutputStream(outFile).use { out ->
                if (hasAlpha) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                out.fd.sync()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "encode failed", t)
            outFile.delete()
            return@withContext ConversionResult.Failed("encode: ${t.javaClass.simpleName}: ${t.message}", t)
        } finally {
            bitmap.recycle()
        }

        val sharedUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        ConversionResult.Success(sharedUri, mime)
    }

    private fun decode(resolver: ContentResolver, uri: Uri): Pair<Bitmap, String>? {
        // 1) ImageDecoder (default allocator)
        try {
            val source = ImageDecoder.createSource(resolver, uri)
            val bmp = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = false
            }
            return bmp to "ImageDecoder"
        } catch (t: Throwable) {
            Log.w(TAG, "ImageDecoder failed for $uri: ${t.javaClass.simpleName}: ${t.message}")
        }

        // 2) ImageDecoder with software allocator
        try {
            val source = ImageDecoder.createSource(resolver, uri)
            val bmp = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
            return bmp to "ImageDecoder(software)"
        } catch (t: Throwable) {
            Log.w(TAG, "ImageDecoder(software) failed: ${t.javaClass.simpleName}: ${t.message}")
        }

        // 3) Direct HEIF parsing + MediaCodec.
        //    Handles HEIC files (notably 8K single-tile hvc1) that AOSP HeifDecoderImpl rejects.
        try {
            val item = HeifParser.parse(resolver, uri)
            when (item) {
                is HeifParser.PrimaryItem.HevcStill -> {
                    val bmp = HevcStillDecoder.decode(item.hvcC, item.payload, item.width, item.height)
                    if (bmp != null) return bmp to "HevcStillDecoder"
                    Log.w(TAG, "HevcStillDecoder returned null")
                }
                is HeifParser.PrimaryItem.HevcGrid -> {
                    Log.w(TAG, "HEIC grid not yet supported in direct path (${item.cols}x${item.rows} tiles)")
                }
                is HeifParser.PrimaryItem.Unsupported -> {
                    Log.w(TAG, "HeifParser unsupported: ${item.reason}")
                }
                null -> Log.w(TAG, "HeifParser returned null")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Direct HEIF path failed: ${t.javaClass.simpleName}: ${t.message}")
        }

        // 4) BitmapFactory fallback (handles plain JPEG/PNG that arrive on this path)
        try {
            resolver.openInputStream(uri)?.use { input ->
                val bmp = BitmapFactory.decodeStream(input)
                if (bmp != null) return bmp to "BitmapFactory"
            }
            Log.w(TAG, "BitmapFactory returned null")
        } catch (t: Throwable) {
            Log.w(TAG, "BitmapFactory failed: ${t.javaClass.simpleName}: ${t.message}")
        }

        return null
    }

    /**
     * hasAlpha()??ALPHA Ï±ÑÎÑê Ï°¥Ïû¨Îß??ïÏù∏. ?§Ï†úÎ°??¨Î™Ö ?ΩÏ????àÎäîÏßÄ Í∞ÄÎ≥çÍ≤å ?òÌîåÎß?
     */
    private fun containsTransparentPixel(bitmap: Bitmap): Boolean {
        if (bitmap.config != Bitmap.Config.ARGB_8888) return false
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return false
        val stepsX = 16
        val stepsY = 16
        for (iy in 0 until stepsY) {
            val y = (h - 1) * iy / (stepsY - 1).coerceAtLeast(1)
            for (ix in 0 until stepsX) {
                val x = (w - 1) * ix / (stepsX - 1).coerceAtLeast(1)
                val pixel = bitmap.getPixel(x, y)
                if ((pixel ushr 24) and 0xFF != 0xFF) return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "ImageConverter"
    }
}