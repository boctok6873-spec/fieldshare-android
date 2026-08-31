package com.youngsu.fieldshare

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrExtractionResult(
    val searchableText: String,
    val topTextLinesByImage: List<List<String>>
)

/** Extracts Korean and Latin text only for the private search index; never log this text. */
object OcrTextExtractor {
    suspend fun extract(context: Context, imageUris: List<Uri>): OcrExtractionResult {
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            val topTextLinesByImage = mutableListOf<List<String>>()
            val searchableText = imageUris.mapNotNull { uri ->
                val image = InputImage.fromFilePath(context, uri)
                val recognized = recognizer.process(image).awaitResult()
                topTextLinesByImage += recognized.textBlocks
                    .sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
                    .flatMap { block ->
                        block.lines
                            .sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
                            .map { it.text }
                            .ifEmpty { listOf(block.text) }
                    }
                recognized.text.takeIf { it.isNotBlank() }
            }.joinToString(separator = "\n")
            OcrExtractionResult(searchableText, topTextLinesByImage)
        } finally {
            recognizer.close()
        }
    }
}

private const val MaxAutoTitleLength = 48

internal fun selectOcrTitleCandidate(topTextLinesByImage: List<List<String>>): String? {
    val candidate = topTextLinesByImage.firstOrNull()
        .orEmpty()
        .asSequence()
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .firstOrNull { it.length >= 2 }
        ?: return null
    if (candidate.length <= MaxAutoTitleLength) return candidate

    val clipped = candidate.take(MaxAutoTitleLength).trimEnd()
    val naturalBreak = clipped.lastIndexOf(' ').takeIf { it >= MaxAutoTitleLength / 2 }
    return (naturalBreak?.let { clipped.take(it) } ?: clipped).trimEnd() + "…"
}

internal fun autoTitleIfEligible(
    currentTitle: String,
    titleEditedByUser: Boolean,
    candidate: String?
): String? = candidate?.takeIf { currentTitle.isBlank() && !titleEditedByUser }

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        if (continuation.isActive) continuation.resume(result)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
}
