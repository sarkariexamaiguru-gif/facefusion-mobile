package com.facefusion.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.facefusion.mobile.ContentGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

private enum class ExpectedLabel(val display: String) {
    UNLABELLED("Not labelled"),
    SFW("SFW — should pass"),
    FLAGGED("Should be flagged"),
}

private data class QueuedMedia(
    val id: Int,
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
)

private data class GateSample(
    val id: Int,
    val name: String,
    val timestampMs: Long,
    val mediaType: String,
    /** The image score or the worst sampled-frame score for a video. */
    val score: Float,
    val expected: ExpectedLabel,
    val sampledFrames: Int = 1,
    val flaggedFrames: Int = 0,
    val nativeVerdict: String = "UNKNOWN",
    val summary: String = "",
    val error: String = "",
    /** Used only to save a copy of the user's original test file. Never exported in the report. */
    val sourceUri: Uri? = null,
)

/** Logistic calibration (Platt-style scaling) over the current model's scalar score. */
private data class Calibration(
    val slope: Double = 4.0,
    val intercept: Double = -1.0,
    val fitted: Boolean = false,
    val fitSamples: Int = 0,
)

private data class CalibrationFit(
    val calibration: Calibration?,
    val message: String,
)

/**
 * The outcome of running a REAL swap in the lab and scoring its swapped output.
 *
 * [gate] is the production content-gate verdict over the SWAPPED result, not the raw
 * target. [swappedPreview] carries the swapped still for on-screen inspection (null for
 * video, which is written to a file and sampled instead). [faces] is how many faces the
 * swap processed, or -1 when unknown.
 */
data class LabSwapResult(
    val gate: ContentGate.Result,
    val swappedPreview: Bitmap? = null,
    val faces: Int = -1,
)

/**
 * Developer-only calibration and test-set workbench for the existing ContentGate scorer.
 *
 * This screen never changes production policy. Its sliders, fitted calibration and
 * observe-only mode are local diagnostics; production swap/live/video/API code continues
 * to use the production policy unchanged.
 */
@Composable
fun ContentGateLabScreen(
    modifier: Modifier = Modifier,
    onInspectStill: suspend (Bitmap) -> ContentGate.Result,
    onInspectClip: suspend (File) -> ContentGate.Result,
    onSwapThenInspectStill: suspend (Bitmap, Bitmap) -> LabSwapResult,
    onSwapThenInspectClip: suspend (Bitmap, File) -> LabSwapResult,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var nextQueueId by remember { mutableIntStateOf(1) }
    val mediaQueue = remember { mutableStateListOf<QueuedMedia>() }
    var pendingMediaUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMediaName by remember { mutableStateOf("media-copy") }
    var mediaUrl by rememberSaveable { mutableStateOf("") }
    var checkerEnabled by rememberSaveable { mutableStateOf(true) }
    var rawScoreThresholdText by rememberSaveable { mutableStateOf("0.25") }
    var probabilityThreshold by rememberSaveable { mutableStateOf(0.50f) }
    // 0 = diagnostics only / no blocking response; 1 = apply the lab's probability rule.
    var responseStrength by rememberSaveable { mutableStateOf(1f) }
    var calibration by remember { mutableStateOf(Calibration()) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready. Each diagnostic initializes the native checker pipeline when required models are available.") }
    var fitStatus by remember { mutableStateOf("No data-fit calibration yet. The displayed formula is provisional.") }
    var pendingExport by remember { mutableStateOf("") }
    var nextId by remember { mutableIntStateOf(1) }
    val history = remember { mutableStateListOf<GateSample>() }

    // Source face for the real-swap path. When set, each target is swapped with this face
    // and the SWAPPED output is scored; when null, targets are scored directly as before.
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf("") }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lastSwapPreview by remember { mutableStateOf<Bitmap?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            var added = 0
            uris.forEach { uri ->
                runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                if (mediaQueue.none { it.uri == uri }) {
                    mediaQueue.add(QueuedMedia(nextQueueId++, uri, displayName(context, uri), isVideo = false))
                    added++
                }
            }
            status = "Added $added image(s) to the test queue. Run each item to score it."
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            var added = 0
            uris.forEach { uri ->
                runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                if (mediaQueue.none { it.uri == uri }) {
                    mediaQueue.add(QueuedMedia(nextQueueId++, uri, displayName(context, uri), isVideo = true))
                    added++
                }
            }
            status = "Added $added video(s) to the test queue. Videos are sampled using ContentGate.inspectClip."
        }
    }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            sourceUri = uri
            sourceName = displayName(context, uri)
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { runCatching { decodeBitmap(context, uri) }.getOrNull() }
                sourceBitmap = bmp
                status = if (bmp != null)
                    "Source face loaded: $sourceName. Each target is now swapped with this face and the SWAPPED output is scored."
                else "Could not decode that source image."
            }
        }
    }
    val mediaSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { outputUri ->
        val sourceUri = pendingMediaUri
        if (outputUri != null && sourceUri != null) {
            saveMediaCopy(context, sourceUri, outputUri, scope) { status = it }
        }
    }

    val csvSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) saveText(context, uri, pendingExport, scope) { status = it }
    }
    val jsonSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) saveText(context, uri, pendingExport, scope) { status = it }
    }

    val finiteSamples = history.filter { it.score.isFinite() && it.error.isBlank() }
    val labelled = finiteSamples.filter { it.expected != ExpectedLabel.UNLABELLED }
    val rawScoreThreshold = rawScoreThresholdText.toFloatOrNull()
        ?.takeIf { it.isFinite() } ?: ContentGate.THRESHOLD
    val predictions = finiteSamples.associate { it.id to probabilityForScore(it.score.toDouble(), calibration) }
    val falsePositives = labelled.count {
        it.expected == ExpectedLabel.SFW && isFlagged(predictions[it.id] ?: 0.0, probabilityThreshold.toDouble())
    }
    val falseNegatives = labelled.count {
        it.expected == ExpectedLabel.FLAGGED && !isFlagged(predictions[it.id] ?: 0.0, probabilityThreshold.toDouble())
    }
    val truePositives = labelled.count {
        it.expected == ExpectedLabel.FLAGGED && isFlagged(predictions[it.id] ?: 0.0, probabilityThreshold.toDouble())
    }
    val trueNegatives = labelled.count {
        it.expected == ExpectedLabel.SFW && !isFlagged(predictions[it.id] ?: 0.0, probabilityThreshold.toDouble())
    }
    val rawFalsePositives = labelled.count { it.expected == ExpectedLabel.SFW && it.score > rawScoreThreshold }
    val rawFalseNegatives = labelled.count { it.expected == ExpectedLabel.FLAGGED && it.score <= rawScoreThreshold }
    val rawTruePositives = labelled.count { it.expected == ExpectedLabel.FLAGGED && it.score > rawScoreThreshold }
    val rawTrueNegatives = labelled.count { it.expected == ExpectedLabel.SFW && it.score <= rawScoreThreshold }
    val rawAccuracy = if (labelled.isNotEmpty()) (rawTruePositives + rawTrueNegatives).toDouble() / labelled.size else Double.NaN
    val errors = history.count { !it.score.isFinite() || it.error.isNotBlank() }
    val positiveCount = labelled.count { it.expected == ExpectedLabel.FLAGGED }
    val negativeCount = labelled.count { it.expected == ExpectedLabel.SFW }
    val accuracy = if (labelled.isNotEmpty()) (truePositives + trueNegatives).toDouble() / labelled.size else Double.NaN
    val precision = if (truePositives + falsePositives > 0) truePositives.toDouble() / (truePositives + falsePositives) else Double.NaN
    val recall = if (truePositives + falseNegatives > 0) truePositives.toDouble() / (truePositives + falseNegatives) else Double.NaN
    val brierScore = if (labelled.isNotEmpty()) labelled.map { sample ->
        val p = predictions[sample.id] ?: Double.NaN
        val actual = if (sample.expected == ExpectedLabel.FLAGGED) 1.0 else 0.0
        (p - actual) * (p - actual)
    }.filter { it.isFinite() }.average() else Double.NaN
    val effectiveThreshold = effectiveThreshold(probabilityThreshold.toDouble(), responseStrength.toDouble())
    val minScore = finiteSamples.minOfOrNull { it.score.toDouble() }
    val maxScore = finiteSamples.maxOfOrNull { it.score.toDouble() }

    val runMedia: (QueuedMedia) -> Unit = { item ->
        if (!checkerEnabled) {
            status = "Lab inference is OFF. No media was scored; production checks remain unchanged."
        } else if (!running) {
            running = true
            val src = sourceBitmap
            val postSwap = src != null
            val tag = if (postSwap) "[swap] " else ""
            status = if (postSwap)
                "Swapping ${sourceName.ifBlank { "the source face" }} onto ${item.name}, then scoring the swapped output…"
            else "Scoring ${item.name}…"
            scope.launch {
                val sampleId = nextId++
                try {
                    val outcome = if (item.isVideo) {
                        val temp = withContext(Dispatchers.IO) { copyUriToCache(context, item.uri, item.name) }
                        try {
                            if (postSwap) onSwapThenInspectClip(src!!, temp)
                            else LabSwapResult(onInspectClip(temp))
                        } finally { temp.delete() }
                    } else {
                        val bitmap = withContext(Dispatchers.IO) { decodeBitmap(context, item.uri) }
                        if (postSwap) onSwapThenInspectStill(src!!, bitmap)
                        else LabSwapResult(onInspectStill(bitmap))
                    }
                    val result = outcome.gate
                    lastSwapPreview = outcome.swappedPreview
                    val failed = result.verdict == ContentGate.Verdict.ERROR
                    val sample = GateSample(
                        id = sampleId,
                        name = tag + item.name,
                        timestampMs = System.currentTimeMillis(),
                        mediaType = when {
                            postSwap && item.isVideo -> "swapped-video"
                            postSwap -> "swapped-image"
                            item.isVideo -> "video"
                            else -> "image"
                        },
                        score = result.score,
                        expected = ExpectedLabel.UNLABELLED,
                        sampledFrames = result.sampled.coerceAtLeast(1),
                        flaggedFrames = if (item.isVideo) result.flagged else if (result.refused) 1 else 0,
                        nativeVerdict = result.verdict.name,
                        summary = if (!failed) result.detail else "",
                        error = if (failed) result.detail.ifBlank { "The content model did not return a verdict." } else "",
                        sourceUri = item.uri,
                    )
                    history.add(0, sample)
                    if (!failed) {
                        val p = probabilityForScore(sample.score.toDouble(), calibration)
                        val wouldFlag = isFlagged(p, probabilityThreshold.toDouble())
                        val labAction = if (responseStrength <= 0f) "OBSERVE ONLY" else
                            if (isFlagged(p, effectiveThreshold(probabilityThreshold.toDouble(), responseStrength.toDouble()))) "FLAGGED" else "PASS"
                        status = (if (postSwap) "SWAPPED ${item.name}" else item.name) +
                            ": native=${sample.nativeVerdict} · raw=${formatScore(sample.score)} · " +
                            "p=${formatProbability(p)} · would flag=${if (wouldFlag) "YES" else "NO"} · lab action=$labAction" +
                            if (item.isVideo) " · ${sample.sampledFrames} samples, ${sample.flaggedFrames} flagged" else ""
                    } else status = "ERROR for ${item.name}: ${sample.error}"
                } catch (t: Throwable) {
                    val failedSample = GateSample(
                        id = sampleId,
                        name = tag + item.name,
                        timestampMs = System.currentTimeMillis(),
                        mediaType = when {
                            postSwap && item.isVideo -> "swapped-video"
                            postSwap -> "swapped-image"
                            item.isVideo -> "video"
                            else -> "image"
                        },
                        score = Float.NaN,
                        expected = ExpectedLabel.UNLABELLED,
                        nativeVerdict = "ERROR",
                        error = t.message ?: t.javaClass.simpleName,
                        sourceUri = item.uri,
                    )
                    history.add(0, failedSample)
                    status = "ERROR for ${item.name}: ${failedSample.error}"
                } finally {
                    running = false
                }
            }
        }
    }

    val downloadFromUrl: () -> Unit = {
        val candidate = mediaUrl.trim()
        if (candidate.isBlank()) {
            status = "Enter a direct image or video URL first."
        } else if (!running) {
            running = true
            status = "Downloading media into the lab's private cache…"
            scope.launch {
                try {
                    val downloaded = withContext(Dispatchers.IO) { downloadMediaToCache(context, candidate) }
                    val uri = Uri.fromFile(downloaded.file)
                    mediaQueue.add(QueuedMedia(nextQueueId++, uri, downloaded.file.name, downloaded.isVideo))
                    mediaUrl = ""
                    status = "Downloaded ${downloaded.file.name}; added it to the ${if (downloaded.isVideo) "video" else "image"} queue."
                } catch (t: Throwable) {
                    status = "Download failed: ${t.message ?: t.javaClass.simpleName}"
                } finally {
                    running = false
                }
            }
        }
    }

    val saveCopy: (Uri?, String) -> Unit = { uri, name ->
        if (uri != null) {
            pendingMediaUri = uri
            pendingMediaName = name.ifBlank { "media-copy" }
            mediaSaver.launch(pendingMediaName)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Content Gate Lab", style = MaterialTheme.typography.headlineSmall)
        Text(
            "A model-score calibration workbench. Score images and videos, label expected results, fit a transparent formula, " +
                "save copies of test media, and export the dataset and rule parameters. The fit is an experiment—not a replacement vision model.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Inference control", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Run existing model in this lab")
                        Text(if (checkerEnabled) "ON — inference enabled" else "OFF — no media inference will run",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = checkerEnabled, onCheckedChange = { checkerEnabled = it })
                }
                Text(
                    "Lab-only control: OFF means no inference in this panel. It does not change production " +
                        "swap/live/video/API checks or the shipped model files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Raw model-score cutoff", style = MaterialTheme.typography.titleMedium)
                Text("The original model score is s = logit[0] − logit[1], not a probability and not limited to 0…1. The production baseline is 0.25; this editable cutoff only changes lab comparisons.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = rawScoreThresholdText,
                    onValueChange = { rawScoreThresholdText = it.filter { ch -> ch.isDigit() || ch in ".-+eE" }.take(24) },
                    label = { Text("Raw cutoff s") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = rawScoreThresholdText.toFloatOrNull()?.isFinite() != true,
                )
                Text("Quick range: −3.00 to +3.00 (type a value outside this range above if your measured scores need it).")
                Slider(
                    value = rawScoreThreshold.coerceIn(-3f, 3f),
                    onValueChange = { rawScoreThresholdText = String.format(Locale.US, "%.2f", it) },
                    valueRange = -3f..3f,
                    steps = 119,
                )
                Text("Raw rule: flag when score > ${formatScore(rawScoreThreshold)}. This result is compared separately from the calibrated probability rule.",
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("Probability cutoff", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${formatProbability(probabilityThreshold.toDouble())}", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.weight(1f))
                    Text("Flag if p > cutoff", style = MaterialTheme.typography.labelMedium)
                }
                Slider(value = probabilityThreshold, onValueChange = { probabilityThreshold = it.coerceIn(0f, 1f) },
                    valueRange = 0f..1f, steps = 99)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0.00 · almost everything flags", style = MaterialTheme.typography.labelSmall)
                    Text("1.00 · almost nothing flags", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "This is a probability cutoff—not the raw model score. The rule is flag when calibrated " +
                        "p(flagged | score) > cutoff. At exactly 0.00, any positive probability is flagged; " +
                        "at exactly 1.00, the strict 'greater than' rule flags nothing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text("Lab response strength", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatProbability(responseStrength.toDouble()), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    Text(if (responseStrength <= 0f) "Observe only" else if (responseStrength >= 1f) "Full lab rule" else "Reduced lab response")
                }
                Slider(value = responseStrength, onValueChange = { responseStrength = it.coerceIn(0f, 1f) },
                    valueRange = 0f..1f, steps = 19)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0 · no block response", style = MaterialTheme.typography.labelSmall)
                    Text("1 · apply lab rule", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "At 0, this lab only reports 'would flag' and never returns a blocking response. Between 0 and 1, " +
                        "the effective cutoff moves from 1.00 toward your chosen cutoff. At 1, the selected cutoff is used. " +
                        "This response-strength slider is not connected to real swaps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Formula and value ranges", style = MaterialTheme.typography.titleMedium)
                Text("Raw input score", style = MaterialTheme.typography.labelLarge)
                Text("s = output[0] − output[1] (raw margin; not bounded to 0…1)")
                Text("Calibrated probability", style = MaterialTheme.typography.labelLarge)
                Text("p = 1 / (1 + exp(−(a × s + b)))")
                Text("a = ${formatCoefficient(calibration.slope)}    b = ${formatCoefficient(calibration.intercept)}")
                Text("Probability output range: 0.0000…1.0000")
                Text(
                    if (minScore != null && maxScore != null)
                        "Observed raw-score range in this session: ${formatScore(minScore.toFloat())} to ${formatScore(maxScore.toFloat())}"
                    else "Observed raw-score range: no successful inference samples yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (calibration.fitted) "Calibration status: fitted from ${calibration.fitSamples} labelled samples."
                    else "Calibration status: provisional mapping centred on the legacy raw threshold 0.25; it is NOT a measured probability.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        val fit = fitLogisticCalibration(labelled)
                        if (fit.calibration != null) {
                            calibration = fit.calibration
                            fitStatus = fit.message
                            status = fit.message
                        } else {
                            fitStatus = fit.message
                            status = fit.message
                        }
                    },
                    enabled = positiveCount >= 4 && negativeCount >= 4,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Fit formula from labelled samples") }
                Text("SFW labels: $negativeCount · Should-flag labels: $positiveCount", style = MaterialTheme.typography.bodySmall)
                Text(fitStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Minimum for fitting: 4 examples per class (more is strongly recommended). This fits a one-dimensional " +
                        "logistic calibration over the existing model's score; it does not train a vision model. Validate the " +
                        "formula on a separate held-out set before relying on its probabilities.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Source face", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pick the face to swap in. With a source set, each target below is run through the real swap and the " +
                        "SWAPPED output is scored by the content gate — so the score is of what a swap would actually " +
                        "produce, not of the raw target. Without a source, targets are scored as-is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val sb = sourceBitmap
                    if (sb != null) {
                        Image(bitmap = sb.asImageBitmap(), contentDescription = "Selected source face",
                            modifier = Modifier.size(56.dp), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (sb != null) "Source: ${sourceName.ifBlank { "selected image" }}" else "No source face selected",
                            style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (sb != null) "Runs will swap this face, then score the swapped output."
                            else "Targets will be scored directly, without a swap.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { sourcePicker.launch(arrayOf("image/*")) }, enabled = !running,
                        modifier = Modifier.weight(1f)) {
                        Text(if (sourceBitmap == null) "Choose source face" else "Change source face")
                    }
                    if (sourceBitmap != null || sourceUri != null) {
                        OutlinedButton(onClick = {
                            sourceUri = null; sourceBitmap = null; sourceName = ""; lastSwapPreview = null
                            status = "Source face cleared. Targets are scored directly again."
                        }, enabled = !running) { Text("Clear") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add test images and videos", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Select one or several local media files. Images use inspectStill(); videos are copied to a temporary cache file and scored with inspectClip(), including the configured frame-sampling rule.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }, enabled = !running,
                        modifier = Modifier.weight(1f)) { Text("Add images") }
                    OutlinedButton(onClick = { videoPicker.launch(arrayOf("video/*")) }, enabled = !running,
                        modifier = Modifier.weight(1f)) { Text("Add videos") }
                }
                OutlinedTextField(
                    value = mediaUrl,
                    onValueChange = { mediaUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Direct image/video URL") },
                    placeholder = { Text("https://example.com/sample.mp4") },
                    singleLine = true,
                    enabled = !running,
                )
                Button(onClick = downloadFromUrl, enabled = !running && mediaUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) { Text(if (running) "Working…" else "Download URL into lab") }
                Text("Direct HTTP(S) media links only. Files are downloaded to app-private cache for testing; use Download copy to save them elsewhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (mediaQueue.isEmpty()) {
                    Text("Queue is empty. Add images or videos from your device.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Queued media (${mediaQueue.size})", style = MaterialTheme.typography.titleSmall)
                    mediaQueue.toList().forEach { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(if (item.isVideo) "VIDEO · ${item.name}" else "IMAGE · ${item.name}",
                                    style = MaterialTheme.typography.bodyMedium)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { runMedia(item) }, enabled = !running && checkerEnabled,
                                        modifier = Modifier.weight(1f)) { Text(if (running) "Working…" else "Run diagnostic") }
                                    OutlinedButton(onClick = { saveCopy(item.uri, item.name) }, enabled = !running) {
                                        Text("Download copy")
                                    }
                                    OutlinedButton(onClick = { mediaQueue.removeAll { it.id == item.id } }, enabled = !running) {
                                        Text("Remove")
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { mediaQueue.clear() }, enabled = !running,
                        modifier = Modifier.fillMaxWidth()) { Text("Clear queue") }
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Download copy saves the selected original media bytes to a location you choose with Android's document picker. It does not re-encode or upload the media.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val swapPreview = lastSwapPreview
        if (swapPreview != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Last swapped output", style = MaterialTheme.typography.titleMedium)
                    Image(bitmap = swapPreview.asImageBitmap(), contentDescription = "Swapped output",
                        modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    Text(
                        "The frame the content gate scored above — the result of swapping the source face onto the " +
                            "most recent still target, before any gate decision.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dataset quality at selected probability cutoff", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Samples", history.size.toString())
                    Metric("False +", falsePositives.toString())
                    Metric("False −", falseNegatives.toString())
                    Metric("Errors", errors.toString())
                }
                Text("True positives: $truePositives · True negatives: $trueNegatives · Labelled total: ${labelled.size}")
                Text("Probability-rule accuracy: ${formatProbability(accuracy)} · Precision: ${formatProbability(precision)} · Recall: ${formatProbability(recall)} · Brier score: ${formatProbability(brierScore)}")
                Text("Raw-cutoff metrics @ ${formatScore(rawScoreThreshold)}: FP $rawFalsePositives · FN $rawFalseNegatives · TP $rawTruePositives · TN $rawTrueNegatives · accuracy ${formatProbability(rawAccuracy)}")
                Text(
                    "Accuracy/precision/recall use the selected cutoff. Brier score measures probability error (lower is better). " +
                        "These are in-sample if you fitted the formula on these same labels; evaluate on held-out samples too. " +
                    "Counts use the selected probability cutoff and fitted formula. They are independent of lab response strength, " +
                        "so turning the lab response to 0 does not make the model metrics look artificially perfect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        pendingExport = buildCsv(history.toList(), calibration, rawScoreThreshold.toDouble(), probabilityThreshold.toDouble(), responseStrength.toDouble())
                        csvSaver.launch("content_gate_dataset.csv")
                    }, enabled = history.isNotEmpty()) { Text("Export CSV") }
                    OutlinedButton(onClick = {
                        pendingExport = buildRuleSetJson(history.toList(), calibration,
                            rawScoreThreshold.toDouble(), probabilityThreshold.toDouble(), responseStrength.toDouble(),
                            rawFalsePositives, rawFalseNegatives, rawTruePositives, rawTrueNegatives, rawAccuracy,
                            falsePositives, falseNegatives, truePositives, trueNegatives, accuracy, precision, recall, brierScore)
                        jsonSaver.launch("content_gate_rule_set.json")
                    }, enabled = history.isNotEmpty()) { Text("Export rule set JSON") }
                }
                OutlinedButton(onClick = {
                    history.clear()
                    nextId = 1
                    fitStatus = "No data-fit calibration yet. The displayed formula is provisional."
                    status = "Session history cleared. Current formula and sliders were retained."
                }, enabled = history.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Clear dataset") }
            }
        }

        Text("Scored dataset", style = MaterialTheme.typography.titleMedium)
        if (history.isEmpty()) {
            Text("No samples yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            history.forEach { sample ->
                val p = if (sample.score.isFinite() && sample.error.isBlank())
                    probabilityForScore(sample.score.toDouble(), calibration) else Double.NaN
                val wouldFlag = p.isFinite() && isFlagged(p, probabilityThreshold.toDouble())
                val labResult = when {
                    !p.isFinite() || sample.error.isNotBlank() -> "ERROR"
                    responseStrength <= 0f -> "OBSERVE ONLY (no block response)"
                    isFlagged(p, effectiveThreshold(probabilityThreshold.toDouble(), responseStrength.toDouble())) -> "FLAGGED"
                    else -> "PASS"
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("${sample.mediaType.uppercase(Locale.US)} · ${sample.name}", style = MaterialTheme.typography.titleSmall)
                        Text("Native verdict: ${sample.nativeVerdict}")
                        Text("Raw score (image / worst video frame): ${formatScore(sample.score)}")
                        Text("Raw cutoff ${formatScore(rawScoreThreshold)}: ${if (!sample.score.isFinite() || sample.error.isNotBlank()) "ERROR" else if (sample.score > rawScoreThreshold) "WOULD FLAG" else "WOULD PASS"}")
                        if (sample.mediaType == "video") {
                            Text("Frames sampled: ${sample.sampledFrames} · flagged frames: ${sample.flaggedFrames}")
                        }
                        if (sample.summary.isNotBlank()) Text("Native summary: ${sample.summary}", style = MaterialTheme.typography.bodySmall)
                        Text("Calibrated p(flagged): ${formatProbability(p)}")
                        Text("Cutoff result: ${if (!p.isFinite()) "ERROR" else if (wouldFlag) "WOULD FLAG" else "WOULD PASS"}")
                        Text("Lab response: $labResult")
                        Text("Expected: ${sample.expected.display}", style = MaterialTheme.typography.bodySmall)
                        if (sample.error.isNotBlank()) Text("Diagnostic error: ${sample.error}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { saveCopy(sample.sourceUri, sample.name) },
                            enabled = !running && sample.sourceUri != null) { Text("Download original copy") }
                        HorizontalDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { setExpected(history, sample.id, ExpectedLabel.SFW) }) { Text("Mark SFW") }
                            OutlinedButton(onClick = { setExpected(history, sample.id, ExpectedLabel.FLAGGED) }) { Text("Should flag") }
                            OutlinedButton(onClick = { setExpected(history, sample.id, ExpectedLabel.UNLABELLED) }) { Text("Clear label") }
                        }
                    }
                }
            }
        }
        Text(
            "Important: video samples use the worst sampled-frame score as their scalar calibration feature, while native clip scoring also reports sampled/flagged frame counts. Evaluate image and video cohorts separately and validate on held-out examples. The sigmoid maps a score into 0…1 but is not a true probability until calibration is validated.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun setExpected(samples: MutableList<GateSample>, id: Int, expected: ExpectedLabel) {
    val index = samples.indexOfFirst { it.id == id }
    if (index >= 0) samples[index] = samples[index].copy(expected = expected)
}

private fun probabilityForScore(score: Double, calibration: Calibration): Double =
    sigmoid(calibration.slope * score + calibration.intercept)

private fun sigmoid(value: Double): Double = when {
    value > 35.0 -> 1.0
    value < -35.0 -> 0.0
    else -> 1.0 / (1.0 + exp(-value))
}

private fun isFlagged(probability: Double, threshold: Double): Boolean = probability > threshold

/** Strength 0 is a special observe-only state; otherwise move the cutoff toward the user's cutoff. */
private fun effectiveThreshold(probabilityThreshold: Double, strength: Double): Double =
    if (strength <= 0.0) 1.0 else 1.0 - strength.coerceIn(0.0, 1.0) * (1.0 - probabilityThreshold.coerceIn(0.0, 1.0))

/** Fit p(label=FLAGGED | score) = sigmoid(a * score + b), with mild L2 regularisation. */
private fun fitLogisticCalibration(samples: List<GateSample>): CalibrationFit {
    val data = samples.mapNotNull { sample ->
        if (!sample.score.isFinite() || sample.error.isNotBlank()) null
        else when (sample.expected) {
            ExpectedLabel.SFW -> sample.score.toDouble() to 0.0
            ExpectedLabel.FLAGGED -> sample.score.toDouble() to 1.0
            ExpectedLabel.UNLABELLED -> null
        }
    }
    val positives = data.count { it.second == 1.0 }
    val negatives = data.count { it.second == 0.0 }
    if (positives < 4 || negatives < 4) {
        return CalibrationFit(null, "Not enough labels: need at least 4 SFW and 4 should-flag examples.")
    }
    var a = 4.0
    var b = -1.0
    val lambda = 0.001
    var converged = false
    for (iteration in 0 until 100) {
        var gradA = lambda * a
        var gradB = lambda * b
        var hAA = lambda
        var hAB = 0.0
        var hBB = lambda
        for ((x, y) in data) {
            val p = sigmoid(a * x + b)
            val variance = max(p * (1.0 - p), 1e-8)
            val error = p - y
            gradA += error * x
            gradB += error
            hAA += variance * x * x
            hAB += variance * x
            hBB += variance
        }
        val determinant = hAA * hBB - hAB * hAB
        if (!determinant.isFinite() || abs(determinant) < 1e-18) break
        val deltaA = (hBB * gradA - hAB * gradB) / determinant
        val deltaB = (-hAB * gradA + hAA * gradB) / determinant
        val nextA = (a - deltaA).coerceIn(-100.0, 100.0)
        val nextB = (b - deltaB).coerceIn(-100.0, 100.0)
        if (!nextA.isFinite() || !nextB.isFinite()) return CalibrationFit(null, "Fit failed: numerical instability in labelled scores.")
        val movement = abs(nextA - a) + abs(nextB - b)
        a = nextA
        b = nextB
        if (movement < 1e-7) {
            converged = true
            break
        }
    }
    val calibration = Calibration(a, b, fitted = true, fitSamples = data.size)
    val note = "Fitted a=${formatCoefficient(a)}, b=${formatCoefficient(b)} on ${data.size} labels " +
        "($negatives SFW / $positives should-flag). ${if (converged) "Converged." else "Stopped at iteration limit; verify on held-out data."}"
    return CalibrationFit(calibration, note)
}

private fun formatScore(score: Float): String =
    if (score.isFinite()) String.format(Locale.US, "%+.5f", score) else "n/a"

private fun formatProbability(value: Double): String =
    if (value.isFinite()) String.format(Locale.US, "%.4f", value.coerceIn(0.0, 1.0)) else "n/a"

private fun formatCoefficient(value: Double): String = String.format(Locale.US, "%.6f", value)

private fun buildCsv(
    samples: List<GateSample>, calibration: Calibration, rawThreshold: Double,
    threshold: Double, strength: Double,
): String {
    val effective = effectiveThreshold(threshold, strength)
    val out = StringBuilder("sample_id,timestamp_ms,media_type,file_name,raw_score,raw_score_threshold,raw_cutoff_result,calibrated_probability,sampled_frames,flagged_frames,native_verdict,native_summary,probability_threshold,effective_lab_threshold,response_strength,lab_response,expected,slope,intercept,calibration_fitted,error\n")
    for (sample in samples) {
        val valid = sample.score.isFinite() && sample.error.isBlank()
        val probability = if (valid) probabilityForScore(sample.score.toDouble(), calibration) else Double.NaN
        val labResponse = when {
            !valid -> "ERROR"
            strength <= 0.0 -> "OBSERVE_ONLY"
            isFlagged(probability, effective) -> "FLAGGED"
            else -> "PASS"
        }
        val fields = listOf(
            sample.id.toString(), sample.timestampMs.toString(), csv(sample.mediaType), csv(sample.name),
            if (sample.score.isFinite()) String.format(Locale.US, "%.7f", sample.score) else "",
            String.format(Locale.US, "%.7f", rawThreshold),
            if (!valid) "ERROR" else if (sample.score > rawThreshold) "WOULD_FLAG" else "WOULD_PASS",
            if (probability.isFinite()) String.format(Locale.US, "%.7f", probability) else "",
            sample.sampledFrames.toString(), sample.flaggedFrames.toString(), csv(sample.nativeVerdict), csv(sample.summary),
            String.format(Locale.US, "%.7f", threshold), String.format(Locale.US, "%.7f", effective),
            String.format(Locale.US, "%.7f", strength), labResponse, sample.expected.name,
            String.format(Locale.US, "%.9f", calibration.slope), String.format(Locale.US, "%.9f", calibration.intercept),
            calibration.fitted.toString(), csv(sample.error),
        )
        out.append(fields.joinToString(",")).append('\n')
    }
    return out.toString()
}

private fun buildRuleSetJson(
    samples: List<GateSample>, calibration: Calibration, rawThreshold: Double,
    threshold: Double, strength: Double,
    rawFalsePositives: Int, rawFalseNegatives: Int, rawTruePositives: Int, rawTrueNegatives: Int, rawAccuracy: Double,
    falsePositives: Int, falseNegatives: Int, truePositives: Int, trueNegatives: Int,
    accuracy: Double, precision: Double, recall: Double, brierScore: Double,
): String {
    val effective = effectiveThreshold(threshold, strength)
    val sampleRows = samples.joinToString(",\n") { sample ->
        val valid = sample.score.isFinite() && sample.error.isBlank()
        val p = if (valid) probabilityForScore(sample.score.toDouble(), calibration) else Double.NaN
        val response = when {
            !valid -> "ERROR"
            strength <= 0.0 -> "OBSERVE_ONLY"
            isFlagged(p, effective) -> "FLAGGED"
            else -> "PASS"
        }
        """    {"id":${sample.id},"timestamp_ms":${sample.timestampMs},"media_type":"${json(sample.mediaType)}","file_name":"${json(sample.name)}","raw_score":${if (sample.score.isFinite()) sample.score.toString() else "null"},"raw_cutoff_result":"${if (!valid) "ERROR" else if (sample.score > rawThreshold) "WOULD_FLAG" else "WOULD_PASS"}","probability":${if (p.isFinite()) p.toString() else "null"},"sampled_frames":${sample.sampledFrames},"flagged_frames":${sample.flaggedFrames},"native_verdict":"${json(sample.nativeVerdict)}","native_summary":"${json(sample.summary)}","expected":"${sample.expected.name}","lab_response":"$response","error":"${json(sample.error)}"}"""
    }
    return """{
  "schema_version": 2,
  "purpose": "content-gate diagnostic calibration for still images and video clips",
  "production_policy_modified": false,
  "score_definition": "s = output[0] - output[1]; raw, not bounded to 0..1",
  "probability_formula": "p = 1 / (1 + exp(-(a * s + b)))",
  "calibration": {"method":"one-dimensional logistic / Platt-style scaling","a":${calibration.slope},"b":${calibration.intercept},"fitted":${calibration.fitted},"fit_samples":${calibration.fitSamples},"status":"${if (calibration.fitted) "fit_on_labelled_samples; validate held-out" else "provisional, not calibrated probability"}"},
  "raw_score_threshold": $rawThreshold,
  "raw_score_decision_rule": "flag if score > raw_score_threshold; lab-only comparison",
  "probability_threshold": $threshold,
  "decision_rule": "flag if p > probability_threshold",
  "lab_response_strength": $strength,
  "effective_lab_threshold": $effective,
  "response_semantics": "strength 0 is observe-only; strength 1 uses probability_threshold; intermediate strength interpolates the threshold",
  "metrics_at_raw_score_threshold": {"false_positives":$rawFalsePositives,"false_negatives":$rawFalseNegatives,"true_positives":$rawTruePositives,"true_negatives":$rawTrueNegatives,"accuracy":${jsonNumber(rawAccuracy)}},
  "metrics_at_probability_threshold": {"false_positives":$falsePositives,"false_negatives":$falseNegatives,"true_positives":$truePositives,"true_negatives":$trueNegatives,"accuracy":${jsonNumber(accuracy)},"precision":${jsonNumber(precision)},"recall":${jsonNumber(recall)},"brier_score":${jsonNumber(brierScore)},"metric_scope":"in-sample unless a held-out dataset was used"},
  "limitations": ["does not retrain or replace the vision model", "thresholds, fitting and response are lab-only", "video raw score is the worst sampled-frame score; sampled/flagged counts preserve the native clip decision context", "validate against a separate representative held-out dataset"],
  "samples": [
$sampleRows
  ]
}
"""
}

private fun jsonNumber(value: Double): String = if (value.isFinite()) value.toString() else "null"

private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

private fun json(value: String): String = buildString {
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append(String.format(Locale.US, "\\u%04x", ch.code)) else append(ch)
        }
    }
}

private fun saveText(context: Context, uri: Uri, content: String, scope: kotlinx.coroutines.CoroutineScope, onResult: (String) -> Unit) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: throw FileNotFoundException("Could not open export destination")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            }
            onResult("Export saved. Original image/video bytes were not included in the report.")
        } catch (t: Throwable) {
            onResult("Could not save export: ${t.message ?: t.javaClass.simpleName}")
        }
    }
}

private data class DownloadedMedia(val file: File, val isVideo: Boolean)

private fun downloadMediaToCache(context: Context, address: String): DownloadedMedia {
    val parsed = URL(address)
    require(parsed.protocol.equals("http", true) || parsed.protocol.equals("https", true)) {
        "Only HTTP or HTTPS URLs are supported."
    }
    val connection = parsed.openConnection() as? HttpURLConnection
        ?: throw IOException("Could not open an HTTP(S) connection")
    connection.connectTimeout = 15_000
    connection.readTimeout = 30_000
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("User-Agent", "FaceFusion-ContentGateLab/1.0")
    var partialFile: File? = null
    try {
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IOException("Server returned HTTP ${connection.responseCode}")
        }
        val finalUrl = connection.url
        require(finalUrl.protocol.equals("http", true) || finalUrl.protocol.equals("https", true)) {
            "Redirected URL was not HTTP(S)."
        }
        val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
        val pathName = URLDecoder.decode(finalUrl.path.substringAfterLast('/'), Charsets.UTF_8.name())
        val extFromName = pathName.substringAfterLast('.', "").lowercase(Locale.US)
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif")
        val videoExts = setOf("mp4", "mov", "m4v", "3gp", "webm", "mkv", "avi", "mpeg", "mpg")
        val genericMediaTypes = setOf("application/octet-stream", "binary/octet-stream", "application/x-download", "application/download", "application/mp4")
        val isVideo = when {
            contentType.startsWith("video/") -> true
            contentType.startsWith("image/") -> false
            extFromName in videoExts -> true
            extFromName in imageExts -> false
            else -> throw IOException("URL does not appear to be an image/video (type: ${contentType.ifBlank { "unknown" }}). Use a direct media URL.")
        }
        if (contentType.isNotBlank() && !contentType.startsWith("image/") && !contentType.startsWith("video/") &&
            contentType !in genericMediaTypes) {
            throw IOException("Unsupported media type: $contentType. Use a direct media URL rather than a web page.")
        }
        val ext = extFromName.takeIf { if (isVideo) it in videoExts else it in imageExts }
            ?: if (isVideo) "mp4" else when (contentType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/bmp" -> "bmp"
                else -> "jpg"
            }
        val dir = File(context.cacheDir, "content_gate_downloads").apply { mkdirs() }
        val safeName = pathName.substringBeforeLast('.', pathName).replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(48).ifBlank { "download_${System.currentTimeMillis()}" }
        val destination = File(dir, "${safeName}_${System.currentTimeMillis()}.$ext")
        partialFile = destination
        val declaredLength = connection.contentLengthLong
        val maxBytes = 512L * 1024L * 1024L
        if (declaredLength > maxBytes) throw IOException("Media exceeds the 512 MB lab download limit")
        var total = 0L
        connection.inputStream.use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw IOException("Media exceeds the 512 MB lab download limit")
                    output.write(buffer, 0, count)
                }
            }
        }
        if (total == 0L) {
            destination.delete()
            throw IOException("Downloaded media is empty")
        }
        return DownloadedMedia(destination, isVideo)
    } catch (t: Throwable) {
        partialFile?.delete()
        throw t
    } finally {
        connection.disconnect()
    }
}

private fun displayName(context: Context, uri: Uri): String {
    val queried = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return queried?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "test-media"
}

private fun copyUriToCache(context: Context, uri: Uri, name: String): File {
    val suffix = name.substringAfterLast('.', "mp4").let { ext ->
        if (ext.matches(Regex("[A-Za-z0-9]{1,8}"))) ".${ext.lowercase(Locale.US)}" else ".mp4"
    }
    val temp = File.createTempFile("content-gate-video-", suffix, context.cacheDir)
    try {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Could not open selected video")
        input.use { source -> temp.outputStream().use { target -> source.copyTo(target) } }
        if (temp.length() == 0L) throw IOException("Selected video is empty")
        return temp
    } catch (t: Throwable) {
        temp.delete()
        throw t
    }
}

private fun saveMediaCopy(
    context: Context,
    sourceUri: Uri,
    outputUri: Uri,
    scope: kotlinx.coroutines.CoroutineScope,
    onResult: (String) -> Unit,
) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(sourceUri)
                    ?: throw FileNotFoundException("Could not open original media")
                input.use { src ->
                    val output = context.contentResolver.openOutputStream(outputUri)
                        ?: throw FileNotFoundException("Could not open chosen save location")
                    output.use { dst -> src.copyTo(dst) }
                }
            }
            onResult("Original media copy saved. The lab did not modify or upload the file.")
        } catch (t: Throwable) {
            onResult("Could not save media copy: ${t.message ?: t.javaClass.simpleName}")
        }
    }
}

@Suppress("DEPRECATION")
private fun decodeBitmap(context: Context, uri: Uri): Bitmap {
    // The native content graph itself reduces the image to its small fixed input tensor.
    // Decode with a 2048 px long-edge cap first to avoid allocating a 50–200 MP source
    // bitmap plus full-size IntArray/BGR copies on mobile devices.
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            var sample = 1
            while (maxOf(info.size.width, info.size.height) / sample > 2048) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
    } else {
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            ?: throw FileNotFoundException("Could not decode selected image")
    }
}
