package com.youngsu.fieldshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Normalizes app-owned image files before registration uploads. */
object ImageOptimizer {
    private const val MaxDimension = 2_000
    private const val LogTag = "FieldShareImage"

    /**
     * Reads an app-owned cached image only through [FileInputStream]. The input is opened anew for
     * bounds, EXIF, and decoding so callers never depend on an externally-owned content URI.
     */
    fun optimize(context: Context, sourceFile: File): Uri {
        try {
            validateImageSourceFile(sourceFile)
        } catch (error: ImageOptimizationException) {
            logError("파일 검증", error)
            throw error
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            FileInputStream(sourceFile).use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (error: Throwable) {
            val failure = ImageOptimizationException("이미지 크기 확인 중 오류가 발생했습니다.", error)
            logError("크기 확인", failure)
            throw failure
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            val failure = ImageOptimizationException("이미지 크기를 읽을 수 없습니다. (${bounds.outWidth}x${bounds.outHeight})")
            logError("크기 확인", failure)
            throw failure
        }
        val orientation = readExifOrientationOrNormal(
            readOrientation = {
                FileInputStream(sourceFile).use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            }
        )

        val decoded = try {
            FileInputStream(sourceFile).use { input ->
                BitmapFactory.decodeStream(
                    input,
                    null,
                    BitmapFactory.Options().apply {
                        inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            }
        } catch (error: Throwable) {
            val failure = ImageOptimizationException("이미지 디코딩 중 오류가 발생했습니다.", error)
            logError("디코딩", failure)
            throw failure
        } ?: run {
            val failure = ImageOptimizationException("이미지 디코딩에 실패했습니다.")
            logError("디코딩", failure)
            throw failure
        }

        var bitmapToRecycle: Bitmap = decoded
        return try {
            val oriented = try {
                applyExifOrientation(decoded, orientation)
            } catch (error: Throwable) {
                val failure = ImageOptimizationException("이미지 방향 변환에 실패했습니다.", error)
                logError("방향 변환", failure)
                throw failure
            }
            if (oriented !== decoded) {
                decoded.recycle()
                bitmapToRecycle = oriented
            }
            val resized = try {
                resizeToMaximum(oriented)
            } catch (error: Throwable) {
                val failure = ImageOptimizationException("이미지 크기 조정에 실패했습니다.", error)
                logError("리사이즈", failure)
                throw failure
            }
            if (resized !== oriented) {
                oriented.recycle()
                bitmapToRecycle = resized
            }
            val outputDirectory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?.resolve("fieldshare")
                ?: File(context.filesDir, "images")
            if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                val failure = ImageOptimizationException("최적화 이미지 저장 폴더를 만들 수 없습니다.")
                logError("출력 폴더 생성", failure)
                throw failure
            }

            val outputFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    writeBitmap(
                        bitmap = resized,
                        directory = outputDirectory,
                        extension = ".webp",
                        format = Bitmap.CompressFormat.WEBP_LOSSY,
                        quality = 92
                    )
                } catch (webpError: Throwable) {
                    Log.w(LogTag, "WebP 압축에 실패해 JPEG로 한 번 대체 저장합니다.")
                    try {
                        writeBitmap(
                            bitmap = resized,
                            directory = outputDirectory,
                            extension = ".jpg",
                            format = Bitmap.CompressFormat.JPEG,
                            quality = 95
                        )
                    } catch (jpegError: Throwable) {
                        logError("JPEG 압축", jpegError)
                        throw jpegError
                    }
                }
            } else {
                try {
                    writeBitmap(
                        bitmap = resized,
                        directory = outputDirectory,
                        extension = ".jpg",
                        format = Bitmap.CompressFormat.JPEG,
                        quality = 95
                    )
                } catch (error: Throwable) {
                    logError("JPEG 압축", error)
                    throw error
                }
            }
            try {
                FileProvider.getUriForFile(context.applicationContext, "${context.packageName}.fileprovider", outputFile)
            } catch (error: Throwable) {
                logError("출력 URI 생성", error)
                throw error
            }
        } finally {
            bitmapToRecycle.recycle()
        }
    }

    internal fun calculateSampleSize(width: Int, height: Int): Int {
        val longestSide = max(width, height)
        var sampleSize = 1
        while (
            (longestSide.toLong() + sampleSize - 1L) / sampleSize > MaxDimension &&
                sampleSize <= Int.MAX_VALUE / 2
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
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
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun resizeToMaximum(bitmap: Bitmap): Bitmap {
        val (targetWidth, targetHeight) = constrainedImageDimensions(bitmap.width, bitmap.height)
        if (targetWidth == bitmap.width && targetHeight == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun writeBitmap(
        bitmap: Bitmap,
        directory: File,
        extension: String,
        format: Bitmap.CompressFormat,
        quality: Int
    ): File {
        var sequence = 0
        val timestamp = System.currentTimeMillis()
        var outputFile: File
        do {
            val suffix = if (sequence == 0) "" else "_$sequence"
            outputFile = File(directory, "fieldshare_optimized_${timestamp}$suffix$extension")
            sequence++
        } while (outputFile.exists())

        return try {
            FileOutputStream(outputFile).use { output ->
                check(bitmap.compress(format, quality, output)) { "이미지 압축에 실패했습니다." }
            }
            outputFile
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        }
    }
}

private fun logError(stage: String, error: Throwable) {
    Log.e("FieldShareImage", "$stage 실패: ${error.javaClass.simpleName}")
}

internal fun validateImageSourceFile(sourceFile: File) {
    when {
        !sourceFile.exists() -> throw ImageOptimizationException("이미지 파일을 찾을 수 없습니다.")
        !sourceFile.isFile -> throw ImageOptimizationException("이미지 파일이 올바르지 않습니다.")
        !sourceFile.canRead() -> throw ImageOptimizationException("이미지 파일을 읽을 수 없습니다.")
        sourceFile.length() <= 0L -> throw ImageOptimizationException("이미지 파일이 비어 있습니다.")
    }
}

internal fun readExifOrientationOrNormal(
    readOrientation: () -> Int,
    logFailure: (Throwable) -> Unit = { error ->
        Log.w("FieldShareImage", "EXIF 방향 정보를 읽지 못해 기본 방향으로 처리합니다.")
    }
): Int = try {
    readOrientation()
} catch (error: Throwable) {
    logFailure(error)
    ExifInterface.ORIENTATION_NORMAL
}

internal fun constrainedImageDimensions(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "이미지 크기는 양수여야 합니다." }
    val longestSide = max(width, height)
    if (longestSide <= 2_000) return width to height

    val scale = 2_000.toFloat() / longestSide
    return (width * scale).roundToInt().coerceIn(1, 2_000) to
        (height * scale).roundToInt().coerceIn(1, 2_000)
}

class ImageOptimizationException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
