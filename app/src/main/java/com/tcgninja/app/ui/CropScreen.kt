package com.tcgninja.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Edits an upright, normalized private photo. A successful save always creates a new JPEG. */
@Composable
fun CropScreen(path: String, onCropped: (String) -> Unit, onClose: () -> Unit) {
    // Changing source cancels work and resets state even if the navigation host reuses this screen.
    key(path) { CropContent(path, onCropped, onClose) }
}

private const val MIN_CROP_EXTENT = .03f
private const val MAX_CROP_EDGE = 2200
private const val MAX_CROP_BYTES = 64L * 1024 * 1024

private data class CropEdges(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
)

@Composable
private fun CropContent(path: String, onCropped: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val croppedCallback by rememberUpdatedState(onCropped)
    val closeCallback by rememberUpdatedState(onClose)
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var left by rememberSaveable { mutableStateOf(0f) }
    var top by rememberSaveable { mutableStateOf(0f) }
    var right by rememberSaveable { mutableStateOf(1f) }
    var bottom by rememberSaveable { mutableStateOf(1f) }
    var saving by remember { mutableStateOf(false) }
    var delivered by remember { mutableStateOf(false) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    val edges = CropEdges(left, top, right, bottom)
    val close: () -> Unit = {
        saveJob?.cancel()
        if (!delivered) closeCallback()
    }
    BackHandler(onBack = close)

    LaunchedEffect(retry) {
        loading = true
        loadError = null
        try {
            preview = withContext(Dispatchers.IO) { decodeCropSource(context, path, preview = true) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = cropErrorMessage(error)
        } finally {
            loading = false
        }
    }

    val controls: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Move the edges to keep just the card or binder area you want.",
                style = MaterialTheme.typography.bodyMedium)
            CropEdgeSlider("Left", left, !saving && !delivered) {
                left = it.coerceIn(0f, right - MIN_CROP_EXTENT)
            }
            CropEdgeSlider("Top", top, !saving && !delivered) {
                top = it.coerceIn(0f, bottom - MIN_CROP_EXTENT)
            }
            CropEdgeSlider("Right", right, !saving && !delivered) {
                right = it.coerceIn(left + MIN_CROP_EXTENT, 1f)
            }
            CropEdgeSlider("Bottom", bottom, !saving && !delivered) {
                bottom = it.coerceIn(top + MIN_CROP_EXTENT, 1f)
            }
            saveError?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (saving) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Saving crop…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !saving && !delivered, onClick = {
                    left = 0f; top = 0f; right = 1f; bottom = 1f
                    saveError = null
                }) { Text("Reset") }
                Button(enabled = !saving && !delivered, onClick = {
                    saving = true
                    saveError = null
                    saveJob = scope.launch {
                        val result = try {
                            savePrivateCrop(context, path, edges)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            saveError = cropErrorMessage(error)
                            null
                        } finally {
                            saving = false
                        }
                        if (result != null) {
                            delivered = true
                            croppedCallback(result)
                        }
                    }
                }) { Text("Save crop") }
            }
            Text("A new photo is saved. Your original stays unchanged.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close, enabled = !delivered) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, if (saving) "Cancel save and close" else "Close crop")
                }
                Text("Crop photo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (saving) TextButton(onClick = close) { Text("Cancel") }
            }
            val bitmap = preview
            when {
                loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading photo" })
                }
                loadError != null || bitmap == null -> Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(loadError ?: "The photo is unavailable.",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = { retry++ }) { Text("Retry") }
                }
                else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val controlsWidth = minOf(340.dp, maxWidth * .48f)
                    if (maxWidth > maxHeight) {
                        Row(Modifier.fillMaxSize()) {
                            CropPreview(bitmap, edges, Modifier.weight(1f).fillMaxSize())
                            Box(Modifier.width(controlsWidth)) { controls() }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            CropPreview(bitmap, edges, Modifier.weight(1f).fillMaxWidth())
                            // Scrolling controls remain usable with large fonts or short screens.
                            Box(Modifier.weight(1f).fillMaxWidth()) { controls() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CropEdgeSlider(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ${(value * 100).roundToInt()}%", Modifier.width(88.dp),
            style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, enabled = enabled, valueRange = 0f..1f,
            modifier = Modifier.weight(1f).semantics { contentDescription = "$label crop edge" })
    }
}

@Composable
private fun CropPreview(bitmap: Bitmap, edges: CropEdges, modifier: Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Image(image, "Photo with crop selection", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(Modifier.fillMaxSize()) {
            // Match Image's centered ContentScale.Fit exactly, excluding letterboxed space.
            val scale = minOf(size.width / image.width, size.height / image.height)
            val width = image.width * scale
            val height = image.height * scale
            val origin = Offset((size.width - width) / 2, (size.height - height) / 2)
            val x1 = origin.x + edges.left * width
            val y1 = origin.y + edges.top * height
            val x2 = origin.x + edges.right * width
            val y2 = origin.y + edges.bottom * height
            val shade = Color.Black.copy(alpha = .58f)
            drawRect(shade, origin, Size(width, y1 - origin.y))
            drawRect(shade, Offset(origin.x, y2), Size(width, origin.y + height - y2))
            drawRect(shade, Offset(origin.x, y1), Size(x1 - origin.x, y2 - y1))
            drawRect(shade, Offset(x2, y1), Size(origin.x + width - x2, y2 - y1))
            drawRect(Color.White, Offset(x1, y1), Size(x2 - x1, y2 - y1), style = Stroke(2.dp.toPx()))
        }
    }
    // Do not recycle a displayed bitmap: Compose's renderer may still reference it after disposal.
}

/** Validate canonical containment before opening; reject arbitrary URIs, traversal and symlinks out. */
private fun privateCropSource(context: Context, path: String): File {
    val requested = File(path)
    val root = context.filesDir.canonicalFile
    val source = requested.canonicalFile
    if (!requested.isAbsolute || !source.toPath().startsWith(root.toPath()) || source == root ||
        !source.isFile || source.length() !in 1..MAX_CROP_BYTES
    ) {
        throw IOException("Choose a photo already saved privately in TCG Ninja.")
    }
    return source
}

/** Only normalized, upright sources are accepted; never allocate an unbounded source bitmap. */
private suspend fun decodeCropSource(context: Context, path: String, preview: Boolean): Bitmap {
    currentCoroutineContext().ensureActive()
    try {
        val source = privateCropSource(context, path)
        // Inspect and decode through the same descriptor so file replacement cannot bypass bounds.
        return FileInputStream(source).use { input ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(input.fd, null, bounds)
            if (bounds.outWidth !in 1..MAX_CROP_EDGE || bounds.outHeight !in 1..MAX_CROP_EDGE) {
                throw IOException("This photo is not normalized. Import it again before cropping.")
            }
            currentCoroutineContext().ensureActive()
            input.channel.position(0)
            val options = BitmapFactory.Options().apply {
                inSampleSize = if (preview && maxOf(bounds.outWidth, bounds.outHeight) > 1100) 2 else 1
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            BitmapFactory.decodeFileDescriptor(input.fd, null, options)
                ?: throw IOException("This photo could not be read. Try importing it again.")
        }
    } catch (error: OutOfMemoryError) {
        throw IOException("Not enough memory to open this photo. Close other apps and retry.", error)
    }
}

private suspend fun savePrivateCrop(context: Context, path: String, edges: CropEdges): String {
    var created: File? = null
    var handedOff = false
    try {
        val result = withContext(Dispatchers.IO) {
            var source: Bitmap? = null
            var cropped: Bitmap? = null
            try {
                require(listOf(edges.left, edges.top, edges.right, edges.bottom).all { it.isFinite() && it in 0f..1f })
                require(edges.right > edges.left && edges.bottom > edges.top)
                source = decodeCropSource(context, path, preview = false)
                val x = floor(edges.left * source.width).toInt().coerceIn(0, source.width - 1)
                val y = floor(edges.top * source.height).toInt().coerceIn(0, source.height - 1)
                val endX = ceil(edges.right * source.width).toInt().coerceIn(x + 1, source.width)
                val endY = ceil(edges.bottom * source.height).toInt().coerceIn(y + 1, source.height)
                currentCoroutineContext().ensureActive()
                cropped = Bitmap.createBitmap(source, x, y, endX - x, endY - y)
                val root = context.filesDir.canonicalFile
                val directory = File(root, "images").canonicalFile
                if (directory.parentFile != root || (!directory.isDirectory && !directory.mkdirs())) {
                    throw IOException("Private photo storage is unavailable. Check available space.")
                }
                // createTempFile reserves a unique name; no existing image can be overwritten.
                val output = File.createTempFile("crop-", ".jpg", directory)
                created = output
                output.outputStream().use { stream ->
                    if (!cropped.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
                        throw IOException("The crop could not be saved. Please retry.")
                    }
                    stream.fd.sync()
                }
                currentCoroutineContext().ensureActive()
                output.absolutePath
            } catch (error: OutOfMemoryError) {
                throw IOException("Not enough memory to crop this photo. Try a smaller crop.", error)
            } finally {
                cropped?.recycle()
                source?.takeIf { !it.isRecycled }?.recycle()
            }
        }
        handedOff = true
        return result
    } finally {
        // Also handles cancellation after IO completes but before the result reaches the UI.
        if (!handedOff) withContext(NonCancellable + Dispatchers.IO) { created?.delete() }
    }
}

private fun cropErrorMessage(error: Exception): String = when (error) {
    is IOException -> error.message ?: "The photo could not be saved. Check available storage and retry."
    is SecurityException -> "This photo is no longer accessible. Import it again."
    else -> "The crop could not be prepared. Please try again."
}
