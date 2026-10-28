package com.youngsu.fieldshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File

/** A list card can have a preview only for media; text and PDFs deliberately keep their icon. */
internal fun PrivateAttachment.isListPreviewable(): Boolean = mime.startsWith("image/") || mime.startsWith("video/")

internal fun firstListPreviewAttachment(attachments: List<PrivateAttachment>): PrivateAttachment? =
    attachments.firstOrNull(PrivateAttachment::isListPreviewable)

/** Creates the same bounded JPEG for a local image or the representative frame of a local video. */
internal fun createPrivateListThumbnail(source: File, mime: String): ByteArray? {
    val bitmap = when {
        mime.startsWith("image/") -> runCatching { BitmapFactory.decodeFile(source.path) }.getOrNull()
        mime.startsWith("video/") -> runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(source.path)
                retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        }.getOrNull()
        else -> null
    } ?: return null
    val scale = minOf(320f / bitmap.width, 320f / bitmap.height, 1f)
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
    return try {
        ByteArrayOutputStream().use { output ->
            if (scaled.compress(Bitmap.CompressFormat.JPEG, 76, output)) output.toByteArray() else null
        }
    } finally {
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
    }
}

/** Disk cache avoids decoding images or extracting video frames again while a list is scrolled. */
internal fun cachedPrivateListThumbnail(directory: File, attachment: PrivateAttachment, source: File): File? {
    if (!attachment.isListPreviewable()) return null
    val target = File(directory, "list-thumbnails/${accountCacheKey(attachment.id)}.jpg")
    if (target.isFile) {
        target.setLastModified(System.currentTimeMillis())
        return target
    }
    val bytes = createPrivateListThumbnail(source, attachment.mime) ?: return null
    target.parentFile!!.mkdirs()
    val atomic = AtomicFile(target)
    val output = atomic.startWrite()
    try {
        output.write(bytes)
        atomic.finishWrite(output)
        target.setLastModified(System.currentTimeMillis())
        return target
    } catch (failure: Exception) {
        atomic.failWrite(output)
        throw failure
    }
}
