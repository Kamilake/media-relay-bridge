package com.kamilake.mediarelaybridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.kamilake.mediarelaybridge.convert.ConversionResult
import com.kamilake.mediarelaybridge.convert.ImageConverter
import com.kamilake.mediarelaybridge.convert.MediaKind
import com.kamilake.mediarelaybridge.convert.MediaType
import com.kamilake.mediarelaybridge.convert.VideoConverter
import com.kamilake.mediarelaybridge.convert.CodecDiagnostics
import com.kamilake.mediarelaybridge.ui.theme.MediaRelayBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class ShareReceiverActivity : ComponentActivity() {

    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val inputs = extractIncomingUris(intent)
        if (inputs.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_input, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val state = ConversionUiState()

        setContent {
            MediaRelayBridgeTheme {
                ProgressDialog(
                    state = state,
                    onCancel = {
                        job?.cancel()
                        finish()
                    }
                )
            }
        }

        job = lifecycleScope.launch {
            runConversions(inputs, state)
        }
    }

    private suspend fun runConversions(
        inputs: List<Uri>,
        state: ConversionUiState
    ) {
        state.total = inputs.size
        if (BuildConfig.DEBUG) {
            CodecDiagnostics.logHevcDecoders()
        }
        val imageConv = ImageConverter(this)
        val videoConv = VideoConverter(this)

        // Concurrency: keep this conservative for now.
        // - HW HEVC pool is small and an 8K Level-6.2 decoder instance is heavy.
        // - 8K ARGB_8888 = ~132MB per bitmap; even with largeHeap, holding several
        //   simultaneously is risky on a 16GB device because the Java heap cap is
        //   independent of physical RAM.
        // We start at 2 and expose room to bump later once we add a streaming encoder.
        val cpus = Runtime.getRuntime().availableProcessors()
        val concurrency = inputs.size.coerceAtMost(maxOf(1, minOf(2, cpus / 2)))
        val gate = Semaphore(concurrency)
        val completed = AtomicInteger(0)
        Log.i(TAG, "converting ${inputs.size} item(s) with concurrency=$concurrency")

        val results = coroutineScope {
            inputs.mapIndexed { index, uri ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        // Isolate per-item failures so one crash doesn't cancel siblings.
                        val result = try {
                            val detection = MediaType.detect(contentResolver, uri)
                            Log.i(
                                TAG,
                                "[$index] uri=$uri kind=${detection.kind} mime=${detection.mimeType} " +
                                    "ext=${detection.extension} ftyp=${detection.ftypBrand}"
                            )
                            when (detection.kind) {
                                MediaKind.IMAGE -> imageConv.convert(uri)
                                MediaKind.VIDEO -> videoConv.convert(uri) { /* parallel: per-item video progress dropped */ }
                                MediaKind.UNKNOWN -> ConversionResult.Skipped(
                                    detection.mimeType ?: detection.extension ?: "unknown"
                                )
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "[$index] uncaught failure", t)
                            ConversionResult.Failed("${t.javaClass.simpleName}: ${t.message}", t)
                        }
                        Log.i(TAG, "[$index] result=$result")
                        val done = completed.incrementAndGet()
                        withContext(Dispatchers.Main) {
                            state.current = done
                        }
                        index to result
                    }
                }
            }.awaitAll()
        }.sortedBy { it.first }.map { it.second }

        val successes = ArrayList<ConversionResult.Success>()
        val mimes = LinkedHashSet<String>()
        for (result in results) {
            when (result) {
                is ConversionResult.Success -> {
                    successes += result
                    mimes += result.mimeType
                }
                is ConversionResult.Failed -> {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_failed, result.reason),
                        Toast.LENGTH_LONG
                    ).show()
                }
                is ConversionResult.Skipped -> {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_unsupported, result.reason),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        if (successes.isNotEmpty()) {
            launchShare(successes, mimes)
        }
        finish()
    }

    private fun launchShare(
        results: List<ConversionResult.Success>,
        mimes: Set<String>
    ) {
        val commonMime = when {
            mimes.size == 1 -> mimes.first()
            mimes.all { it.startsWith("image/") } -> "image/*"
            mimes.all { it.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }

        val sendIntent = if (results.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = results.first().mimeType
                putExtra(Intent.EXTRA_STREAM, results.first().uri)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = commonMime
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList(results.map { it.uri })
                )
            }
        }.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(sendIntent, getString(R.string.share_chooser_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(chooser)
    }

    private fun extractIncomingUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
    }
}

private const val TAG = "ShareReceiver"

private class ConversionUiState {
    var total by mutableIntStateOf(0)
    var current by mutableIntStateOf(0)
}

@Composable
private fun ProgressDialog(
    state: ConversionUiState,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* not dismissable by tap-out */ },
        title = { Text(stringResource(R.string.dialog_converting)) },
        text = {
            val total = state.total.coerceAtLeast(1)
            val done = state.current.coerceIn(0, total)
            val overall = done.toFloat() / total
            Column {
                Text(
                    text = stringResource(R.string.dialog_progress_format, done, total)
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { overall.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}