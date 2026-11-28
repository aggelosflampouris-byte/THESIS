package com.example.thesis.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.thesis.util.CryptoUtils
import com.example.thesis.xmtp.MediaAttachment
import com.example.thesis.xmtp.MediaAttachmentCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Procesa, comprime (sin metadatos EXIF) y comparte adjuntos multimedia dentro del canal cifrado MLS.
 */
internal object MediaAttachmentProcessor {
    const val MEDIA_CACHE_DIR: String = "media_cache"
    private const val MAX_IMAGE_EDGE_PX: Int = 1440

    fun createTempCameraCaptureFile(context: Context): Pair<File, Uri> {
        val dir = context.cacheDir.resolve(MEDIA_CACHE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val captureFile = dir.resolve("capture_${System.currentTimeMillis()}.jpg")
        if (!captureFile.exists()) {
            captureFile.createNewFile()
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            captureFile
        )
        return captureFile to uri
    }

    fun purgeMediaCache(cacheDir: File) {
        runCatching {
            val dir = cacheDir.resolve(MEDIA_CACHE_DIR)
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
                dir.delete()
            }
        }
    }

    fun prepareFromUri(
        context: Context,
        uri: Uri,
        caption: String = "",
        maxViews: Int = MediaViewLimitPolicy.UNLIMITED_VIEWS
    ): Result<MediaAttachment> {
        return runCatching {
            val resolver = context.contentResolver
            val (rawDisplayName, reportedSize) = queryDisplayMetadata(context, uri)
            val resolvedMime = resolveMimeType(context, uri, rawDisplayName)
            val cleanCaption = caption.trim().take(MediaAttachmentCodec.MAX_CAPTION_CHARS)
            val safeMaxViews = MediaViewLimitPolicy.sanitizeMaxViews(maxViews)

            if (MediaAttachmentCodec.isImageMimeType(resolvedMime)) {
                prepareCompressedImage(
                    context = context,
                    uri = uri,
                    rawDisplayName = rawDisplayName,
                    caption = cleanCaption,
                    maxViews = safeMaxViews
                )
            } else {
                if (reportedSize > MediaAttachmentCodec.MAX_MEDIA_BYTES.toLong()) {
                    throw IllegalArgumentException(
                        "Το αρχείο υπερβαίνει το όριο ${MediaAttachmentCodec.formatByteSize(MediaAttachmentCodec.MAX_MEDIA_BYTES)} του καναλιού MLS E2EE."
                    )
                }
                val bytes = readBoundedBytes(resolver, uri, MediaAttachmentCodec.MAX_MEDIA_BYTES)
                val safeName = MediaAttachmentCodec.sanitizeFileName(rawDisplayName, "document.bin")
                MediaAttachment(
                    fileName = safeName,
                    mimeType = resolvedMime.ifBlank { "application/octet-stream" },
                    widthPx = 0,
                    heightPx = 0,
                    maxViews = safeMaxViews,
                    caption = cleanCaption,
                    sha256Hex = CryptoUtils.sha256Hex(bytes),
                    dataBytes = bytes
                )
            }
        }
    }

    fun shareMediaAttachment(context: Context, attachment: MediaAttachment): Result<File> {
        return runCatching {
            val dir = context.cacheDir.resolve(MEDIA_CACHE_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            // Limpiamos copias anteriores para conservar solo el adjunto actual en cache compartida.
            dir.listFiles()?.forEach { existing ->
                if (existing.isFile && !existing.name.startsWith("capture_")) {
                    existing.delete()
                }
            }
            val safeName = MediaAttachmentCodec.sanitizeFileName(attachment.fileName)
            val outFile = dir.resolve(safeName)
            FileOutputStream(outFile).use { fos ->
                fos.write(attachment.dataBytes)
                fos.fd.sync()
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = attachment.mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, safeName).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            outFile
        }
    }

    fun saveMediaAttachmentToUri(context: Context, targetUri: Uri, attachment: MediaAttachment): Result<Unit> {
        return runCatching {
            val out = context.contentResolver.openOutputStream(targetUri)
                ?: throw IllegalStateException("Αδύνατο το άνοιγμα προορισμού αποθήκευσης.")
            out.use { stream ->
                stream.write(attachment.dataBytes)
                stream.flush()
            }
        }
    }

    private fun prepareCompressedImage(
        context: Context,
        uri: Uri,
        rawDisplayName: String?,
        caption: String,
        maxViews: Int = MediaViewLimitPolicy.UNLIMITED_VIEWS
    ): MediaAttachment {
        val resolver = context.contentResolver
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val (srcWidth, srcHeight) = readImageBounds(
            openStream = { resolver.openInputStream(uri) },
            decodeBounds = { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                // decodeStream devuelve null con inJustDecodeBounds=true; leemos solo outWidth/outHeight.
                BitmapFactory.decodeStream(stream, null, bounds)
                bounds.outWidth to bounds.outHeight
            }
        )

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(srcWidth, srcHeight, MAX_IMAGE_EDGE_PX)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decodeStream = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Αδύνατη η ανάγνωση της επιλεγμένης εικόνας.")
        val decoded = decodeStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: throw IllegalStateException("Αποτυχία αποκωδικοποίησης εικόνας.")

        var workingBitmap = applyExifOrientationAndCap(decoded, orientation, MAX_IMAGE_EDGE_PX)
        if (workingBitmap !== decoded) {
            decoded.recycle()
        }

        try {
            // Recomprimimos a JPEG para eliminar todos los metadatos EXIF (GPS/dispositivo) y ajustar al limite MLS.
            var encodedBytes = compressAdaptiveJpeg(workingBitmap)
            var shrinkAttempt = 0
            while (encodedBytes.size > MediaAttachmentCodec.MAX_MEDIA_BYTES && shrinkAttempt < 3) {
                val smallerW = (workingBitmap.width * 0.75f).roundToInt().coerceAtLeast(1)
                val smallerH = (workingBitmap.height * 0.75f).roundToInt().coerceAtLeast(1)
                val resized = Bitmap.createScaledBitmap(workingBitmap, smallerW, smallerH, true)
                if (resized !== workingBitmap) {
                    workingBitmap.recycle()
                    workingBitmap = resized
                }
                encodedBytes = compressAdaptiveJpeg(workingBitmap)
                shrinkAttempt++
            }

            if (encodedBytes.size > MediaAttachmentCodec.MAX_MEDIA_BYTES) {
                throw IllegalArgumentException(
                    "Η εικόνα παραμένει μεγαλύτερη από ${MediaAttachmentCodec.formatByteSize(MediaAttachmentCodec.MAX_MEDIA_BYTES)} μετά τη συμπίεση."
                )
            }

            val baseName = MediaAttachmentCodec.sanitizeFileName(rawDisplayName, "photo.jpg")
            val jpegName = replaceExtensionWithJpg(baseName)
            return MediaAttachment(
                fileName = jpegName,
                mimeType = "image/jpeg",
                widthPx = workingBitmap.width,
                heightPx = workingBitmap.height,
                maxViews = maxViews,
                caption = caption,
                sha256Hex = CryptoUtils.sha256Hex(encodedBytes),
                dataBytes = encodedBytes
            )
        } finally {
            if (!workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
        }
    }

    private fun compressAdaptiveJpeg(bitmap: Bitmap): ByteArray {
        val qualities = intArrayOf(82, 70, 58, 46)
        var lastBytes = ByteArray(0)
        for (q in qualities) {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, q, baos)
            lastBytes = baos.toByteArray()
            if (lastBytes.size <= MediaAttachmentCodec.MAX_MEDIA_BYTES) {
                return lastBytes
            }
        }
        return lastBytes
    }

    internal fun readImageBounds(
        openStream: () -> InputStream?,
        decodeBounds: (InputStream) -> Pair<Int, Int>
    ): Pair<Int, Int> {
        val stream = openStream()
            ?: throw IllegalStateException("Αδύνατη η ανάγνωση της επιλεγμένης εικόνας.")
        val (width, height) = stream.use(decodeBounds)
        require(width > 0 && height > 0) {
            "Μη έγκυρη ή κατεστραμμένη μορφή εικόνας."
        }
        return width to height
    }

    internal fun computeInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var inSampleSize = 1
        var longest = max(width, height)
        while (longest / 2 >= maxEdge) {
            longest /= 2
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun applyExifOrientationAndCap(bitmap: Bitmap, orientation: Int, maxEdge: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }

        val oriented = if (!matrix.isIdentity) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val longest = max(oriented.width, oriented.height)
        if (longest <= maxEdge) return oriented

        val scale = maxEdge.toFloat() / longest.toFloat()
        val targetW = (oriented.width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (oriented.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
        if (oriented !== bitmap && scaled !== oriented) {
            oriented.recycle()
        }
        return scaled
    }

    private fun replaceExtensionWithJpg(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        return "$stem.jpg"
    }

    private fun queryDisplayMetadata(context: Context, uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        name = cursor.getString(nameIdx)
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment
        }
        return name to size
    }

    private fun resolveMimeType(context: Context, uri: Uri, displayName: String?): String {
        val fromResolver = context.contentResolver.getType(uri)?.trim()
        if (!fromResolver.isNullOrBlank() && fromResolver != "application/octet-stream") {
            return fromResolver
        }
        val ext = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        if (ext.isNotEmpty()) {
            val mapped = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (!mapped.isNullOrBlank()) return mapped
        }
        return fromResolver ?: "application/octet-stream"
    }

    private fun readBoundedBytes(
        resolver: android.content.ContentResolver,
        uri: Uri,
        maxBytes: Int
    ): ByteArray {
        val input = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Αδύνατη η ανάγνωση του επιλεγμένου αρχείου.")
        return input.use { stream ->
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    throw IllegalArgumentException(
                        "Το αρχείο υπερβαίνει το όριο ${MediaAttachmentCodec.formatByteSize(maxBytes)} του καναλιού MLS E2EE."
                    )
                }
                baos.write(buf, 0, n)
            }
            val result = baos.toByteArray()
            if (result.isEmpty()) {
                throw IllegalArgumentException("Το επιλεγμένο αρχείο είναι κενό.")
            }
            result
        }
    }
}

