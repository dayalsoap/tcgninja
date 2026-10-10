package com.tcgninja.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Captures a whole image; the guide is framing help, not a destructive crop. */
@Composable
fun CameraScreen(binder: Boolean, onCaptured: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val capturedCallback by rememberUpdatedState(onCaptured)
    val closeCallback by rememberUpdatedState(onClose)
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    var permissionGranted by remember { mutableStateOf(context.hasCameraPermission()) }
    var requestedPermission by rememberSaveable { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var busy by remember { mutableStateOf(false) }
    var choosingPhoto by rememberSaveable { mutableStateOf(false) }
    var delivered by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var flashOn by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it
        requestedPermission = true
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        choosingPhoto = false
        if (uri != null && !busy && !delivered) {
            busy = true
            imageError = null
            scope.launch {
                val path = try {
                    ImageFiles.importUri(app, uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    imageError = error.photoMessage()
                    null
                } finally {
                    busy = false
                }
                if (path != null) {
                    delivered = true
                    capturedCallback(path)
                }
            }
        }
    }
    val locked = busy || choosingPhoto || delivered
    BackHandler { if (!locked) closeCallback() }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_RESUME) permissionGranted = context.hasCameraPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(permissionGranted, lifecycleOwner, previewView, retry) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var ownedPreview: Preview? = null
        var ownedCapture: ImageCapture? = null
        var cameraState: androidx.lifecycle.LiveData<CameraState>? = null
        var stateObserver: Observer<CameraState>? = null
        var orientationListener: OrientationEventListener? = null
        cameraReady = false
        cameraError = null
        hasFlash = false
        capture = null
        if (permissionGranted) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed) {
                    try {
                        val cameraProvider = future.get()
                        provider = cameraProvider
                        if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                            throw IOException("No rear camera is available. Choose a device photo instead.")
                        }
                        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                        val preview = Preview.Builder().setTargetRotation(rotation).build()
                        val imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setTargetRotation(rotation)
                            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                            .build()
                        ownedPreview = preview
                        ownedCapture = imageCapture
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture,
                        )
                        hasFlash = camera.cameraInfo.hasFlashUnit()
                        capture = imageCapture
                        val observer = Observer<CameraState> { state ->
                            if (!disposed) {
                                cameraReady = state.type == CameraState.Type.OPEN && state.error == null
                                cameraError = if (state.error != null) {
                                    "Camera unavailable. Close other camera apps, then retry or choose a photo."
                                } else null
                            }
                        }
                        cameraState = camera.cameraInfo.cameraState
                        stateObserver = observer
                        camera.cameraInfo.cameraState.observe(lifecycleOwner, observer)
                        orientationListener = object : OrientationEventListener(context) {
                            override fun onOrientationChanged(orientation: Int) {
                                if (orientation == ORIENTATION_UNKNOWN || disposed) return
                                imageCapture.targetRotation = when (orientation) {
                                    in 45..134 -> Surface.ROTATION_270
                                    in 135..224 -> Surface.ROTATION_180
                                    in 225..314 -> Surface.ROTATION_90
                                    else -> Surface.ROTATION_0
                                }
                            }
                        }.also { if (it.canDetectOrientation()) it.enable() }
                    } catch (_: SecurityException) {
                        permissionGranted = false
                        cameraError = "Camera permission is needed. You can also choose a photo."
                    } catch (error: Exception) {
                        cameraError = if (error is IOException) error.message else
                            "The camera could not start. Retry or choose a photo."
                    }
                }
            }, executor)
        }
        onDispose {
            disposed = true
            orientationListener?.disable()
            stateObserver?.let { cameraState?.removeObserver(it) }
            // Unbind only this screen's use cases, leaving other owners untouched.
            val useCases = listOfNotNull(ownedPreview, ownedCapture).toTypedArray()
            if (useCases.isNotEmpty()) provider?.unbind(*useCases)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { closeCallback() }, enabled = !locked) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close camera")
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(if (binder) "Scan a binder page" else "Scan a card", style = MaterialTheme.typography.titleLarge)
                    Text(if (binder) "Keep every pocket in view" else "Front of card", style = MaterialTheme.typography.bodyMedium)
                }
                if (hasFlash && permissionGranted) {
                    IconButton(onClick = { flashOn = !flashOn }, enabled = !locked && cameraReady) {
                        Icon(
                            if (flashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            if (flashOn) "Flash on. Turn flash off" else "Flash off. Turn flash on",
                        )
                    }
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (permissionGranted) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    if (cameraReady && cameraError == null) FramingGuide(binder)
                }
                when {
                    !permissionGranted -> {
                        val activity = context.findActivity()
                        val needsSettings = requestedPermission && activity != null &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
                        CameraNotice(
                            title = "A photo is the first step",
                            message = "Allow camera access to photograph ${if (binder) "a binder page" else "your card"}. " +
                                "Photos are saved privately in TCG Ninja. You can also choose an existing photo below.",
                            action = if (needsSettings) "Open settings" else if (requestedPermission) "Try permission again" else "Allow camera",
                            onAction = {
                                if (needsSettings) {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    } catch (_: Exception) {
                                        imageError = "Settings could not open. Enable Camera in your device's app settings."
                                    }
                                } else {
                                    requestedPermission = true
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            enabled = !locked,
                        )
                    }
                    cameraError != null -> CameraNotice(
                        "Camera unavailable", cameraError!!, "Retry camera", { retry++ }, !locked,
                    )
                    !cameraReady -> CircularProgressIndicator(color = Color.White,
                        modifier = Modifier.semantics { contentDescription = "Starting camera" })
                }
                if (busy) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .7f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator(color = Color.White)
                            Text("Preparing your photo…", color = Color.White,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                imageError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = { imageError = null }) { Text("Dismiss") }
                }
                Text(
                    if (binder) "Lay the page flat. Avoid glare and include all card edges."
                    else "Keep all four corners visible. Tilt slightly to avoid glare.",
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                )
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        enabled = !locked,
                        onClick = {
                            imageError = null
                            choosingPhoto = true
                            try { photoLauncher.launch("image/*") } catch (_: Exception) {
                                choosingPhoto = false
                                imageError = "No photo picker is available on this device."
                            }
                        },
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null, Modifier.size(20.dp))
                        Text("Choose photo", Modifier.padding(start = 8.dp))
                    }
                    Surface(shape = CircleShape, border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) {
                        FilledIconButton(
                            modifier = Modifier.padding(5.dp).size(64.dp),
                            enabled = !locked && cameraReady && resumed && permissionGranted,
                            onClick = {
                                val imageCapture = capture ?: return@FilledIconButton
                                busy = true
                                imageError = null
                                imageCapture.flashMode = if (hasFlash && flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                                scope.launch {
                                    val path = try {
                                        capturePrivatePhoto(app, imageCapture, executor)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        imageError = error.photoMessage()
                                        null
                                    } finally {
                                        busy = false
                                    }
                                    if (path != null) {
                                        delivered = true
                                        capturedCallback(path)
                                    }
                                }
                            },
                        ) {
                            Icon(Icons.Default.CameraAlt, if (binder) "Capture binder page" else "Capture card front", Modifier.size(30.dp))
                        }
                    }
                }
                Text("The entire photo is saved", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CameraNotice(title: String, message: String, action: String, onAction: () -> Unit, enabled: Boolean) {
    Surface(Modifier.padding(24.dp).widthIn(max = 420.dp), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.CameraAlt, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Button(onClick = onAction, enabled = enabled) { Text(action) }
        }
    }
}

@Composable
private fun FramingGuide(binder: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val aspect = if (binder) .78f else 63f / 88f
        val width = minOf(size.width * .84f, size.height * .86f * aspect)
        val height = width / aspect
        drawRoundRect(
            color = Color.White.copy(alpha = .85f),
            topLeft = Offset((size.width - width) / 2, (size.height - height) / 2),
            size = Size(width, height), cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
    else -> null
}

private fun Exception.photoMessage(): String = when (this) {
    is SecurityException -> "Access to this photo expired. Please choose it again."
    is IOException -> message ?: "The photo could not be saved. Check available storage and retry."
    else -> "The photo could not be prepared. Try again or choose another photo."
}

private suspend fun capturePrivatePhoto(context: Context, capture: ImageCapture, executor: Executor): String {
    val temporary = withContext(Dispatchers.IO) {
        File.createTempFile("camera-capture-", ".jpg", context.cacheDir)
    }
    try {
        try {
            withTimeout(30_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    capture.takePicture(ImageCapture.OutputFileOptions.Builder(temporary).build(), executor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                if (continuation.isActive) continuation.resume(Unit) else temporary.delete()
                            }
                            override fun onError(exception: ImageCaptureException) {
                                temporary.delete()
                                if (continuation.isActive) continuation.resumeWithException(
                                    IOException("Capture failed. Keep the camera open and try again.", exception),
                                )
                            }
                        })
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw IOException("The camera took too long. Please try again.", error)
        }
        return ImageFiles.normalizeFile(context, temporary)
    } finally {
        // A cancelled CameraX capture may finish later; its callback also removes this same file.
        temporary.delete()
    }
}
