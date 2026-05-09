package com.kamilake.mediarelaybridge.convert

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * HEVC/HEIC/AVIF video containers ??H.264 + AAC inside MP4.
 * otaliastudios Transcoder가 MediaCodec ?�이?�라?�을 처리.
 */
class VideoConverter(private val context: Context) {

    suspend fun convert(
        sourceUri: Uri,
        onProgress: (Float) -> Unit = {}
    ): ConversionResult {
        val outDir = File(context.cacheDir, "converted").apply { mkdirs() }
        val outFile = File(outDir, "vid_${UUID.randomUUID()}.mp4")

        return suspendCancellableCoroutine { cont ->
            val future = Transcoder.into(outFile.absolutePath)
                .addDataSource(context, sourceUri)
                .setVideoTrackStrategy(
                    DefaultVideoStrategy.Builder()
                        // legacy-friendly: H.264 baseline/main, 720p cap, ~6 Mbps
                        .addResizer(com.otaliastudios.transcoder.resize.AtMostResizer(1280, 720))
                        .frameRate(30)
                        .keyFrameInterval(3f)
                        .bitRate(6_000_000L)
                        .build()
                )
                .setAudioTrackStrategy(
                    DefaultAudioStrategy.builder()
                        .channels(2)
                        .sampleRate(44_100)
                        .bitRate(128_000L)
                        .build()
                )
                .setListener(object : TranscoderListener {
                    override fun onTranscodeProgress(progress: Double) {
                        onProgress(progress.toFloat().coerceIn(0f, 1f))
                    }

                    override fun onTranscodeCompleted(successCode: Int) {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            outFile
                        )
                        cont.resume(ConversionResult.Success(uri, "video/mp4"))
                    }

                    override fun onTranscodeCanceled() {
                        outFile.delete()
                        cont.resume(ConversionResult.Failed("canceled"))
                    }

                    override fun onTranscodeFailed(exception: Throwable) {
                        outFile.delete()
                        cont.resume(ConversionResult.Failed(exception.message ?: "transcode failed", exception))
                    }
                })
                .transcode()

            cont.invokeOnCancellation {
                future.cancel(true)
                outFile.delete()
            }
        }
    }
}
