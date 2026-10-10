package com.tcgninja.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

internal class BackupImages(private val context: Context) {
    fun export(inventory: Inventory): BackupArchive {
        val images = linkedMapOf<String, ByteArray>()
        val references = mutableMapOf<String, String>()
        var total = 0L
        val portable = inventory.mapImages { path ->
            if (path.isEmpty() || isRemoteImageReference(path)) path else references.getOrPut(path) {
                val uri = Uri.parse(path)
                val input = when (uri.scheme) {
                    "content" -> requireNotNull(context.contentResolver.openInputStream(uri)) { "Image cannot be opened" }
                    null, "file" -> {
                        val file = File(if (uri.scheme == "file") requireNotNull(uri.path) else path).canonicalFile
                        val roots = listOf(context.filesDir.canonicalFile, context.cacheDir.canonicalFile)
                        require(roots.any { file.path.startsWith(it.path + File.separator) }) { "Images must be in app-private storage or a granted content URI" }
                        file.inputStream()
                    }
                    else -> throw IllegalArgumentException("Image must be local; remote image URLs are not fetched by backup")
                }
                val bytes = input.use { stream ->
                    val buffer = ByteArray(8192)
                    val output = ByteArrayOutputStream()
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size().toLong() + count <= BackupCodec.MAX_IMAGE_BYTES) { "Image exceeds 8 MiB" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                checkDimensions(bytes)
                val id = BackupCodec.digest(bytes)
                if (id !in images) {
                    total += bytes.size
                    require(total <= BackupCodec.MAX_TOTAL_IMAGE_BYTES) { "Attached images exceed 32 MiB" }
                    images[id] = bytes
                }
                "image:$id"
            }
        }
        return BackupArchive(portable, images)
    }

    /** Only new files are tracked; rollback never deletes existing owned images. */
    fun restore(archive: BackupArchive, created: MutableList<File>): Inventory {
        archive.images.values.forEach(::checkDimensions)
        val directory = File(context.filesDir, "restored-images")
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create image directory" }
        require(directory.canonicalFile.parentFile == context.filesDir.canonicalFile) { "Invalid private restore directory" }
        val paths = mutableMapOf<String, String>()
        archive.images.forEach { (id, bytes) ->
            val target = File(directory, "$id.img")
            require(target.canonicalFile.parentFile == directory.canonicalFile) { "Invalid restored image path" }
            if (target.exists()) {
                require(target.length() == bytes.size.toLong() && BackupCodec.digest(target.readBytes()) == id) { "Existing restored image is corrupt" }
            } else {
                val staging = File.createTempFile("restore-", ".tmp", directory)
                try {
                    staging.outputStream().use { it.write(bytes); it.fd.sync() }
                    check(staging.renameTo(target)) { "Unable to restore image" }
                    created += target
                } finally {
                    staging.delete()
                }
            }
            paths["image:$id"] = target.absolutePath
        }
        return archive.inventory.mapImages { if (it.isEmpty() || isRemoteImageReference(it)) it else paths.getValue(it) }
    }

    private fun checkDimensions(bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..16_384 && bounds.outHeight in 1..16_384 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000) { "Invalid image or image exceeds 64 megapixels" }
    }
}
