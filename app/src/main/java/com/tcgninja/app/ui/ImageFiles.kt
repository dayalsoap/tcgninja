package com.tcgninja.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** App-owned JPEGs. Successful results belong to the scan/collection, not the camera screen. */
object ImageFiles {
    private const val MAX_EDGE = 2200
    private const val MAX_INPUT_BYTES = 64L * 1024 * 1024
    private const val MAX_INPUT_PIXELS = 200_000_000L
    private const val MAX_INPUT_EDGE = 65_535
    // Serialize decoding so simultaneous callers do not multiply the bitmap memory budget.
    private val decodeLock = Mutex()

    /** Copies a picker grant immediately; no persistent or broad storage permission is needed. */
    suspend fun importUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val temporary = File.createTempFile("photo-import-", ".tmp", app.cacheDir)
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_INPUT_BYTES) {
                            throw IOException("This photo is too large. Choose one smaller than 64 MB.")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("This photo could not be opened. Please select it again.")
            normalizeFile(app, temporary)
        } finally {
            // Only the temporary file created by this invocation is removed, never the picked URI.
            temporary.delete()
        }
    }

    /**
     * Reads a file without modifying or deleting it, returning a new absolute filesDir/images path.
     * The capture caller owns cleanup of its input temporary file. Metadata (including GPS) is
     * intentionally not copied: pixels are rotated/mirrored and encoded as an upright JPEG.
     */
    suspend fun normalizeFile(context: Context, file: File): String = withContext(Dispatchers.IO) {
        decodeLock.withLock {
            var bitmap: Bitmap? = null
            var transformed: Bitmap? = null
            var opaque: Bitmap? = null
            var pending: File? = null
            try {
                currentCoroutineContext().ensureActive()
                if (!file.isFile || file.length() !in 1..MAX_INPUT_BYTES) {
                    throw IOException("The photo is empty, unavailable, or larger than 64 MB.")
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val width = bounds.outWidth
                val height = bounds.outHeight
                if (width <= 0 || height <= 0) {
                    throw IOException("This file is not a supported photo. Try a JPEG or PNG.")
                }
                if (width > MAX_INPUT_EDGE || height > MAX_INPUT_EDGE ||
                    width.toLong() * height > MAX_INPUT_PIXELS
                ) {
                    throw IOException("This photo has unusually large dimensions. Choose a smaller copy.")
                }
                val orientation = try {
                    ExifInterface(file).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                    )
                } catch (_: IOException) {
                    // Valid image formats without EXIF are already in their displayed orientation.
                    ExifInterface.ORIENTATION_NORMAL
                }
                var sample = 1
                while ((maxOf(width, height).toLong() + sample - 1) / sample > MAX_EDGE) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
                bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                    ?: throw IOException("This photo could not be decoded. Try another photo.")
                currentCoroutineContext().ensureActive()
                val decoded = bitmap
                // Account for formats whose decoder rounds sampled dimensions differently.
                val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
                val matrix = orientationMatrix(orientation).apply { postScale(scale, scale) }
                transformed = Bitmap.createBitmap(
                    decoded, 0, 0, decoded.width, decoded.height, matrix, true,
                )
                if (transformed !== decoded) {
                    decoded.recycle()
                    bitmap = null
                }
                val upright = transformed
                val jpegBitmap = if (upright.hasAlpha()) {
                    Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888)
                        .also { result ->
                            opaque = result
                            Canvas(result).apply {
                                drawColor(Color.WHITE)
                                drawBitmap(upright, 0f, 0f, null)
                            }
                        }
                } else {
                    upright
                }
                val directory = File(context.applicationContext.filesDir, "images")
                if (!directory.isDirectory && !directory.mkdirs()) {
                    throw IOException("There is not enough storage to save this photo.")
                }
                // Defend the private destination boundary even if a caller created a symlink.
                if (directory.canonicalFile.parentFile != context.applicationContext.filesDir.canonicalFile) {
                    throw IOException("The private image directory is unavailable.")
                }
                val destination = File(directory, "${UUID.randomUUID()}.jpg")
                pending = File.createTempFile("normalizing-", ".tmp", directory)
                pending.outputStream().buffered().use { output ->
                    if (!jpegBitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                        throw IOException("This photo could not be saved. Please try again.")
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!pending.renameTo(destination)) {
                    throw IOException("This photo could not be saved. Check available storage.")
                }
                destination.absolutePath
            } catch (error: OutOfMemoryError) {
                throw IOException("Not enough memory to process this photo. Try a smaller image.", error)
            } finally {
                opaque?.recycle()
                transformed?.takeIf { !it.isRecycled }?.recycle()
                bitmap?.takeIf { !it.isRecycled }?.recycle()
                pending?.delete()
            }
        }
    }

    private fun orientationMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
}
