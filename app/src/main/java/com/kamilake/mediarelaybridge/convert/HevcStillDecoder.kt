package com.kamilake.mediarelaybridge.convert

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodes a single still HEVC bitstream (extracted from a HEIF `hvc1` item) into a Bitmap.
 *
 * We bypass the system HEIF path entirely: build the codec-specific data from the `hvcC` box,
 * convert the length-prefixed NAL units into Annex-B start-code form, then feed them to a
 * MediaCodec instance whose surface is a YUV ImageReader. After we get one decoded frame,
 * we convert it to an ARGB_8888 Bitmap.
 *
 * Why a YUV ImageReader instead of a SurfaceTexture/GL path:
 *  - We can read the raw planes back as bytes and convert them losslessly with a known matrix.
 *  - Avoids the GPU/EGL setup boilerplate.
 *  - Works for arbitrary resolutions including 8K, as long as the chosen decoder supports them.
 */
object HevcStillDecoder {

    private const val TAG = "HevcStill"
    private const val MIME = "video/hevc"
    private const val IO_TIMEOUT_US = 1_000_000L

    /**
     * @param hvcC the raw `hvcC` box payload (starts with configurationVersion=1).
     * @param payload the item bytes (length-prefixed NAL units).
     * @param width output picture width in pixels.
     * @param height output picture height in pixels.
     * @return a freshly allocated ARGB_8888 [Bitmap], or null on failure.
     */
    fun decode(hvcC: ByteArray, payload: ByteArray, width: Int, height: Int): Bitmap? {
        val annexB = convertHvcCAndPayloadToAnnexB(hvcC, payload) ?: run {
            Log.w(TAG, "failed to build Annex-B bitstream")
            return null
        }
        val csd = extractCsd(hvcC) ?: run {
            Log.w(TAG, "failed to build csd-0")
            return null
        }
        val candidates = pickDecoders(width, height)
        if (candidates.isEmpty()) {
            Log.w(TAG, "no decoder claims support for ${width}x$height HEVC")
            return null
        }
        for (codecName in candidates) {
            Log.i(TAG, "decoding ${width}x$height via $codecName (annexB=${annexB.size}B csd=${csd.size}B)")
            val bmp = try {
                decodeWith(codecName, csd, annexB, width, height)
            } catch (t: Throwable) {
                Log.w(TAG, "decode failed on $codecName: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            if (bmp != null) return bmp
        }
        return null
    }

    // ---- decoder selection --------------------------------------------------

    /** Returns hardware decoders first, then software fallbacks (if size fits). */
    private fun pickDecoders(width: Int, height: Int): List<String> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val hw = mutableListOf<String>()
        val sw = mutableListOf<String>()
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(MIME, true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(MIME) }.getOrNull() ?: continue
            val vc = caps.videoCapabilities ?: continue
            if (!vc.isSizeSupported(width, height)) continue
            if (info.isHardwareAccelerated) hw += info.name else sw += info.name
        }
        return hw + sw
    }

    // ---- bitstream conversion -----------------------------------------------

    /**
     * `hvcC` box layout (HEVCDecoderConfigurationRecord):
     *   1 byte configurationVersion
     *  ... 21 bytes of profile/tier/level/etc ...
     *   1 byte numOfArrays
     *  for each array: 1 byte arrayCompleteness/reserved/NAL_unit_type, 2 bytes numNalus,
     *      for each NAL: 2 bytes nalUnitLength, then nalUnit bytes
     *
     * The "lengthSizeMinusOne" field at offset 21, low 2 bits, gives the size (in bytes) of the
     * NAL length prefix used in the item payload.
     */
    private fun extractCsd(hvcC: ByteArray): ByteArray? {
        if (hvcC.size < 23) return null
        if (hvcC[0].toInt() != 1) return null
        var pos = 22
        val numArrays = hvcC[pos].toInt() and 0xFF; pos++
        val out = ByteArrayBuilder(hvcC.size + numArrays * 4)
        for (i in 0 until numArrays) {
            if (pos + 3 > hvcC.size) return null
            pos += 1 // skip arrayCompleteness/NAL_unit_type
            val numNalus = ((hvcC[pos].toInt() and 0xFF) shl 8) or (hvcC[pos + 1].toInt() and 0xFF)
            pos += 2
            for (j in 0 until numNalus) {
                if (pos + 2 > hvcC.size) return null
                val len = ((hvcC[pos].toInt() and 0xFF) shl 8) or (hvcC[pos + 1].toInt() and 0xFF)
                pos += 2
                if (pos + len > hvcC.size) return null
                out.writeStartCode4()
                out.write(hvcC, pos, len)
                pos += len
            }
        }
        return out.toByteArray()
    }

    private fun convertHvcCAndPayloadToAnnexB(hvcC: ByteArray, payload: ByteArray): ByteArray? {
        if (hvcC.size < 23) return null
        val lengthSize = (hvcC[21].toInt() and 0x3) + 1
        val out = ByteArrayBuilder(payload.size + 64)
        var p = 0
        while (p + lengthSize <= payload.size) {
            var nalLen = 0L
            for (i in 0 until lengthSize) {
                nalLen = (nalLen shl 8) or (payload[p + i].toLong() and 0xFFL)
            }
            p += lengthSize
            if (nalLen <= 0L || p + nalLen > payload.size) return null
            out.writeStartCode4()
            out.write(payload, p, nalLen.toInt())
            p += nalLen.toInt()
        }
        return out.toByteArray()
    }

    private class ByteArrayBuilder(capacity: Int) {
        private var buf = ByteArray(capacity.coerceAtLeast(16))
        private var size = 0
        fun writeStartCode4() {
            ensure(4)
            buf[size] = 0; buf[size + 1] = 0; buf[size + 2] = 0; buf[size + 3] = 1
            size += 4
        }
        fun write(src: ByteArray, off: Int, len: Int) {
            ensure(len)
            System.arraycopy(src, off, buf, size, len)
            size += len
        }
        private fun ensure(more: Int) {
            if (size + more > buf.size) {
                var n = buf.size
                while (n < size + more) n = n * 2
                buf = buf.copyOf(n)
            }
        }
        fun toByteArray(): ByteArray = buf.copyOf(size)
    }

    // ---- actual decode ------------------------------------------------------

    private fun decodeWith(
        codecName: String,
        csd: ByteArray,
        annexB: ByteArray,
        width: Int,
        height: Int
    ): Bitmap? {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
        }

        // ImageReader as the codec's output target. YUV_420_888 is mandatory format.
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
        val frameQueue = LinkedBlockingQueue<Image>(1)
        val readerThread = HandlerThread("HevcStill-reader").also { it.start() }
        val readerHandler = Handler(readerThread.looper)
        reader.setOnImageAvailableListener({ ir ->
            // We only need the first decoded frame.
            val img = runCatching { ir.acquireLatestImage() }.getOrNull()
            if (img != null && !frameQueue.offer(img)) img.close()
        }, readerHandler)

        val codec = MediaCodec.createByCodecName(codecName)
        var bitmap: Bitmap? = null
        try {
            codec.configure(format, reader.surface, null, 0)
            codec.start()

            // Feed the entire Annex-B blob in one BUFFER_FLAG_END_OF_STREAM submission.
            val inIdx = codec.dequeueInputBuffer(IO_TIMEOUT_US)
            if (inIdx < 0) {
                Log.w(TAG, "no input buffer available")
                return null
            }
            val inBuf = codec.getInputBuffer(inIdx) ?: return null
            if (inBuf.capacity() < annexB.size) {
                Log.w(TAG, "input buffer too small (${inBuf.capacity()} < ${annexB.size})")
                return null
            }
            inBuf.clear()
            inBuf.put(annexB)
            codec.queueInputBuffer(
                inIdx, 0, annexB.size, 0,
                MediaCodec.BUFFER_FLAG_KEY_FRAME or MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )

            // Drain output until we either get a frame or hit EOS.
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                val outIdx = codec.dequeueOutputBuffer(info, IO_TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        val produced = info.size > 0
                        codec.releaseOutputBuffer(outIdx, produced)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        if (produced) {
                            val img = frameQueue.poll(2, TimeUnit.SECONDS)
                            if (img != null) {
                                bitmap = imageToBitmap(img, width, height)
                                img.close()
                                break
                            }
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "output format: ${codec.outputFormat}")
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* keep looping */ }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { reader.close() }
            readerThread.quitSafely()
            // Drain any leftover image
            frameQueue.poll()?.close()
        }
        return bitmap
    }

    // ---- YUV ??Bitmap -------------------------------------------------------

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        // Convert YUV_420_888 ??ARGB int array using BT.601 limited range matrix.
        // (HEIC color information would refine this; for now we use the safe default.)
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yRow = yPlane.rowStride
        val uRow = uPlane.rowStride
        val vRow = vPlane.rowStride
        val uPix = uPlane.pixelStride
        val vPix = vPlane.pixelStride

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val pixels = IntArray(width * height)
        val yLine = ByteArray(yRow)
        val uLine = ByteArray(uRow)
        val vLine = ByteArray(vRow)
        var lastUvY = -1

        for (j in 0 until height) {
            yBuf.position(j * yRow)
            yBuf.get(yLine, 0, minOf(yRow, yBuf.remaining()))
            val uvY = j shr 1
            if (uvY != lastUvY) {
                uBuf.position(uvY * uRow)
                uBuf.get(uLine, 0, minOf(uRow, uBuf.remaining()))
                vBuf.position(uvY * vRow)
                vBuf.get(vLine, 0, minOf(vRow, vBuf.remaining()))
                lastUvY = uvY
            }
            val rowBase = j * width
            for (i in 0 until width) {
                val y = yLine[i].toInt() and 0xFF
                val uvI = (i shr 1)
                val u = uLine[uvI * uPix].toInt() and 0xFF
                val v = vLine[uvI * vPix].toInt() and 0xFF
                pixels[rowBase + i] = yuvToArgb(y, u, v)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** BT.601 limited-range YUV ??ARGB. Integer math, clamped. */
    private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val c = y - 16
        val d = u - 128
        val e = v - 128
        var r = (298 * c + 409 * e + 128) shr 8
        var g = (298 * c - 100 * d - 208 * e + 128) shr 8
        var b = (298 * c + 516 * d + 128) shr 8
        if (r < 0) r = 0 else if (r > 255) r = 255
        if (g < 0) g = 0 else if (g > 255) g = 255
        if (b < 0) b = 0 else if (b > 255) b = 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}