package com.threadlog.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.threadlog.core.model.contract.MediaCompressor
import com.threadlog.core.model.contract.MediaStorage
import com.threadlog.core.model.contract.PendingAttachment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android [MediaCompressor] implementation that normalizes EXIF orientation, caps dimensions at
 * [MAX_EDGE_PX], compresses strictly to `<=` [MediaCompressor.MAX_ATTACHMENT_BYTES] (640 KiB) using
 * WebP lossy with JPEG fallback, and persists via [MediaStorage].
 */
@Singleton
class AndroidMediaCompressor(
    private val mediaStorage: MediaStorage,
    private val ioDispatcher: CoroutineDispatcher,
    private val uuidGenerator: () -> String = { UUID.randomUUID().toString() },
) : MediaCompressor {

    @Inject
    constructor(mediaStorage: MediaStorage) : this(
        mediaStorage = mediaStorage,
        ioDispatcher = Dispatchers.IO,
        uuidGenerator = { UUID.randomUUID().toString() },
    )

    override suspend fun compressAndStore(rawBytes: ByteArray): Result<PendingAttachment> =
        withContext(ioDispatcher) {
            try {
                if (rawBytes.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Raw image bytes must not be empty"),
                    )
                }

                val exifOrientation = readExifOrientation(rawBytes)
                val decoded = decodeSampledBitmap(rawBytes)
                    ?: return@withContext Result.failure(
                        IllegalArgumentException("Unable to decode image bytes"),
                    )

                val encoded = try {
                    val prepared = prepareUprightBitmap(decoded, exifOrientation)
                    try {
                        compressToByteBudget(prepared)
                    } finally {
                        if (prepared !== decoded && !prepared.isRecycled) {
                            prepared.recycle()
                        }
                    }
                } finally {
                    if (!decoded.isRecycled) {
                        decoded.recycle()
                    }
                }

                val pendingAttachment = mediaStorage.writeVerifiedBytes(
                    uuid = uuidGenerator(),
                    mimeType = encoded.mimeType,
                    bytes = encoded.bytes,
                )
                Result.success(pendingAttachment)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    private fun readExifOrientation(rawBytes: ByteArray): Int {
        return try {
            ExifInterface(ByteArrayInputStream(rawBytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun decodeSampledBitmap(rawBytes: ByteArray): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOptions)

        val srcWidth = boundsOptions.outWidth
        val srcHeight = boundsOptions.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) {
            return null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(srcWidth, srcHeight, MAX_EDGE_PX)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxEdgePx: Int): Int {
        var sampleSize = 1
        var longestEdge = maxOf(width, height)
        while (longestEdge / 2 >= maxEdgePx) {
            sampleSize *= 2
            longestEdge /= 2
        }
        return sampleSize
    }

    private fun prepareUprightBitmap(decoded: Bitmap, exifOrientation: Int): Bitmap {
        val downscaled = downscaleToMaxEdge(decoded, MAX_EDGE_PX)
        return try {
            applyExifOrientation(downscaled, exifOrientation)
        } catch (t: Throwable) {
            if (downscaled !== decoded && !downscaled.isRecycled) {
                downscaled.recycle()
            }
            throw t
        }.also { upright ->
            if (downscaled !== decoded && downscaled !== upright && !downscaled.isRecycled) {
                downscaled.recycle()
            }
        }
    }

    private fun downscaleToMaxEdge(source: Bitmap, maxEdgePx: Int): Bitmap {
        val longestEdge = maxOf(source.width, source.height)
        if (longestEdge <= maxEdgePx) {
            return source
        }
        val scale = maxEdgePx.toFloat() / longestEdge.toFloat()
        val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
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
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun compressToByteBudget(initialBitmap: Bitmap): EncodedImage {
        var current = initialBitmap
        try {
            while (true) {
                val encoded = encodeWithinBudget(current)
                if (encoded != null) {
                    return encoded
                }

                val longestEdge = maxOf(current.width, current.height)
                if (longestEdge <= MIN_EDGE_PX) {
                    throw IllegalStateException(
                        "Unable to compress image within ${MediaCompressor.MAX_ATTACHMENT_BYTES} bytes",
                    )
                }

                val nextWidth = (current.width * DOWNSCALE_FACTOR).roundToInt().coerceAtLeast(1)
                val nextHeight = (current.height * DOWNSCALE_FACTOR).roundToInt().coerceAtLeast(1)
                val clampedScale = if (maxOf(nextWidth, nextHeight) < MIN_EDGE_PX) {
                    MIN_EDGE_PX.toFloat() / longestEdge.toFloat()
                } else {
                    DOWNSCALE_FACTOR
                }
                val targetWidth = (current.width * clampedScale).roundToInt().coerceAtLeast(1)
                val targetHeight = (current.height * clampedScale).roundToInt().coerceAtLeast(1)

                if (targetWidth == current.width && targetHeight == current.height) {
                    throw IllegalStateException(
                        "Unable to downscale image further to fit ${MediaCompressor.MAX_ATTACHMENT_BYTES} bytes",
                    )
                }

                val scaled = Bitmap.createScaledBitmap(current, targetWidth, targetHeight, true)
                if (current !== initialBitmap && current !== scaled && !current.isRecycled) {
                    current.recycle()
                }
                current = scaled
            }
        } finally {
            if (current !== initialBitmap && !current.isRecycled) {
                current.recycle()
            }
        }
    }

    private fun encodeWithinBudget(bitmap: Bitmap): EncodedImage? {
        val webpResult = tryQualityLadder(
            bitmap = bitmap,
            format = webpLossyFormat(),
            mimeType = FileMediaStorage.MIME_WEBP,
        )
        when (webpResult) {
            is LadderOutcome.WithinBudget -> return webpResult.encoded
            is LadderOutcome.ExceededBudget -> return null
            is LadderOutcome.EncoderUnavailable -> Unit
        }

        val jpegResult = tryQualityLadder(
            bitmap = bitmap,
            format = Bitmap.CompressFormat.JPEG,
            mimeType = FileMediaStorage.MIME_JPEG,
        )
        return when (jpegResult) {
            is LadderOutcome.WithinBudget -> jpegResult.encoded
            is LadderOutcome.ExceededBudget -> null
            is LadderOutcome.EncoderUnavailable -> throw IllegalStateException(
                "Both WebP and JPEG bitmap compression failed",
            )
        }
    }

    private fun tryQualityLadder(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        mimeType: String,
    ): LadderOutcome {
        var anyProducedBytes = false
        for (quality in QUALITY_LADDER) {
            val bytes = compressBitmap(bitmap, format, quality) ?: continue
            if (bytes.isEmpty()) continue
            anyProducedBytes = true
            if (bytes.size.toLong() <= MediaCompressor.MAX_ATTACHMENT_BYTES) {
                return LadderOutcome.WithinBudget(EncodedImage(bytes = bytes, mimeType = mimeType))
            }
        }
        return if (anyProducedBytes) {
            LadderOutcome.ExceededBudget
        } else {
            LadderOutcome.EncoderUnavailable
        }
    }

    private fun compressBitmap(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
    ): ByteArray? {
        val output = ByteArrayOutputStream()
        return try {
            val success = bitmap.compress(format, quality, output)
            if (success) output.toByteArray() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun webpLossyFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

    private data class EncodedImage(
        val bytes: ByteArray,
        val mimeType: String,
    )

    private sealed interface LadderOutcome {
        data class WithinBudget(val encoded: EncodedImage) : LadderOutcome
        data object ExceededBudget : LadderOutcome
        data object EncoderUnavailable : LadderOutcome
    }

    companion object {
        const val MAX_EDGE_PX: Int = 2048
        const val MIN_EDGE_PX: Int = 64
        private const val DOWNSCALE_FACTOR: Float = 0.75f
        private val QUALITY_LADDER: IntArray = intArrayOf(85, 75, 65, 55, 45, 35, 25, 15)
    }
}
