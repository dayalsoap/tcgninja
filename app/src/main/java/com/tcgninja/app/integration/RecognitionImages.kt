package com.tcgninja.app.integration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

internal object RecognitionImages {
    /** Resizing and re-encoding also strips EXIF/GPS from images sent to the provider. */
    fun load(context: Context, path: String): Bitmap {
        try {
            val file = File(path).canonicalFile
            val roots = listOf(context.filesDir, context.cacheDir).map { it.canonicalPath + File.separator }
            if (!roots.any { file.path.startsWith(it) } || !file.isFile || file.length() !in 1..40_000_000L) imageError()
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, dimensions)
            if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0 ||
                dimensions.outWidth.toLong() * dimensions.outHeight > 100_000_000) imageError()
            var sample = 1
            while (max(dimensions.outWidth, dimensions.outHeight) / sample > 3200) sample *= 2
            var bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: imageError()
            val orientation = ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
            }
            val scale = minOf(1.0, 2000.0 / max(bitmap.width, bitmap.height))
            if (scale < 1) {
                val resized = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()), true)
                if (resized !== bitmap) bitmap.recycle()
                bitmap = resized
            }
            return bitmap
        } catch (e: IntegrationException) { throw e }
        catch (_: Exception) { imageError() }
    }

    fun jpeg(bitmap: Bitmap): ByteArray {
        val bytes = ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) imageError()
            output.toByteArray()
        }
        if (bytes.size > 4_000_000) imageError()
        return bytes
    }

    fun crop(context: Context, bitmap: Bitmap, bounds: CardBounds, id: String): String? {
        val directory = File(context.filesDir, "recognition-crops")
        val file = File(directory, "$id.jpg")
        var cropped: Bitmap? = null
        return try {
            val left = floor(bounds.left * bitmap.width).toInt()
            val top = floor(bounds.top * bitmap.height).toInt()
            val right = ceil(bounds.right * bitmap.width).toInt().coerceAtMost(bitmap.width)
            val bottom = ceil(bounds.bottom * bitmap.height).toInt().coerceAtMost(bitmap.height)
            if (right - left < 32 || bottom - top < 32) return null
            cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            if (!directory.isDirectory && !directory.mkdirs()) return null
            file.outputStream().use { it.write(jpeg(cropped)) }
            file.absolutePath
        } catch (_: Exception) {
            file.delete()
            null
        } finally { if (cropped !== bitmap) cropped?.recycle() }
    }

    private fun imageError(): Nothing = throw IntegrationException(IntegrationException.Kind.IMAGE,
        "Could not read this photo. Capture or import a JPEG, PNG, or WebP image into the app and try again.")
}
