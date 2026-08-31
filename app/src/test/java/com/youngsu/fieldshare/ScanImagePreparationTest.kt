package com.youngsu.fieldshare

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class ScanImagePreparationTest {
    @Test
    fun scanPreparationFailureMessage_identifiesEveryFailedPageWithoutLeakingPaths() {
        assertEquals(
            "스캔 이미지 1, 3페이지를 처리하지 못했습니다. 다시 스캔하거나 재시도해 주세요.",
            scanPreparationFailureMessage(listOf(1, 3))
        )
    }

    @Test
    fun constrainedImageDimensions_keepsGalleryAndScanImagesWithinTwoThousandPixels() {
        assertEquals(2_000 to 1_125, constrainedImageDimensions(4_000, 2_250))
        assertEquals(1_500 to 2_000, constrainedImageDimensions(3_000, 4_000))
    }

    @Test
    fun validateImageSourceFile_rejectsMissingAndEmptyCacheFiles() {
        val missingFile = File("build/nonexistent-scan-cache-page.jpg")
        assertThrows(ImageOptimizationException::class.java) {
            validateImageSourceFile(missingFile)
        }

        val emptyFile = File.createTempFile("fieldshare-empty-scan-", ".jpg")
        try {
            assertThrows(ImageOptimizationException::class.java) {
                validateImageSourceFile(emptyFile)
            }
        } finally {
            emptyFile.delete()
        }
    }

    @Test
    fun readExifOrientationOrNormal_usesNormalOrientationWhenExifReadingFails() {
        assertEquals(
            ExifInterface.ORIENTATION_NORMAL,
            readExifOrientationOrNormal(
                readOrientation = { throw IllegalArgumentException("invalid EXIF") },
                logFailure = {}
            )
        )
    }

    @Test
    fun optimizedUrisOrNull_neverReturnsOriginalUriWhenAnyOptimizationFails() {
        val originalUri = "content://gallery/original.jpg"
        val optimizedUri = "content://com.youngsu.fieldshare.fileprovider/fieldshare_optimized_ok.webp"

        assertEquals(
            null,
            optimizedValuesOrNull(listOf(Result.success(optimizedUri), Result.failure(IllegalStateException("decode failed"))))
        )
        assertEquals(originalUri, "content://gallery/original.jpg")
    }

    @Test
    fun optimizedImageUploadValidation_acceptsOnlyOptimizerOutputAndSupportedMimeTypes() {
        val optimizedPath = "/fieldshare_images/fieldshare_optimized_1.webp"

        assertEquals(true, isOptimizedImageLocation("com.youngsu.fieldshare.fileprovider", optimizedPath, "com.youngsu.fieldshare"))
        assertEquals(false, isOptimizedImageLocation("gallery", "/original.jpg", "com.youngsu.fieldshare"))
        assertEquals("image/webp", optimizedImageMimeType(optimizedPath, null))
        assertEquals("image/jpeg", optimizedImageMimeType("/fieldshare_optimized_1.jpg", null))
        assertEquals(true, isValidOptimizedImageSize(1L))
        assertEquals(true, isValidOptimizedImageSize(MaxOptimizedImageUploadBytes))
        assertEquals(false, isValidOptimizedImageSize(0L))
        assertEquals(false, isValidOptimizedImageSize(MaxOptimizedImageUploadBytes + 1L))
    }
}
