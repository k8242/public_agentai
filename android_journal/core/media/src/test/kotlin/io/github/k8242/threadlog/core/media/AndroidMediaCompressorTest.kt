// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.contract.MediaCompressor
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidMediaCompressorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun compressAndStore_normalPngAndJpeg_storesValidAttachmentWithinBudget() = runTest {
        val baseDir = tempFolder.newFolder("compressor-normal")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = FileMediaStorage(baseDir, dispatcher)
        val counter = AtomicInteger(0)
        val compressor = AndroidMediaCompressor(
            mediaStorage = storage,
            ioDispatcher = dispatcher,
            uuidGenerator = { "img-${counter.incrementAndGet()}" },
        )

        val pngBytes = encodeSolidBitmap(320, 180, Color.rgb(30, 160, 90), Bitmap.CompressFormat.PNG)
        val jpegBytes = encodeSolidBitmap(400, 200, Color.rgb(200, 80, 40), Bitmap.CompressFormat.JPEG)

        val pngResult = compressor.compressAndStore(pngBytes)
        val jpegResult = compressor.compressAndStore(jpegBytes)

        assertThat(pngResult.isSuccess).isTrue()
        assertThat(jpegResult.isSuccess).isTrue()

        val pngAttachment = pngResult.getOrThrow()
        assertThat(pngAttachment.byteSize).isAtLeast(1L)
        assertThat(pngAttachment.byteSize).isAtMost(MediaCompressor.MAX_ATTACHMENT_BYTES)
        assertThat(pngAttachment.mimeType).isAnyOf("image/webp", "image/jpeg")
        assertThat(pngAttachment.width).isEqualTo(320)
        assertThat(pngAttachment.height).isEqualTo(180)
        assertThat(pngAttachment.localFilename).startsWith("media/img-1.")
        assertThat(pngAttachment.sha256).matches("[0-9a-f]{64}")

        val storedBytes = storage.readBytes(pngAttachment.localFilename)
        assertThat(storedBytes).isNotNull()
        val decoded = BitmapFactory.decodeByteArray(storedBytes, 0, storedBytes!!.size)
        assertThat(decoded).isNotNull()
        try {
            assertThat(decoded.width).isEqualTo(320)
            assertThat(decoded.height).isEqualTo(180)
        } finally {
            decoded.recycle()
        }

        val jpegAttachment = jpegResult.getOrThrow()
        assertThat(jpegAttachment.byteSize).isAtMost(MediaCompressor.MAX_ATTACHMENT_BYTES)
        assertThat(jpegAttachment.width).isEqualTo(400)
        assertThat(jpegAttachment.height).isEqualTo(200)
    }

    @Test
    fun compressAndStore_largeHighEntropyNoisyBitmap_strictlyEnforces640KiBCeiling() = runTest {
        val baseDir = tempFolder.newFolder("compressor-noisy")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = FileMediaStorage(baseDir, dispatcher)
        val compressor = AndroidMediaCompressor(
            mediaStorage = storage,
            ioDispatcher = dispatcher,
            uuidGenerator = { "noisy-1" },
        )

        val width = 2048
        val height = 1536
        val noisyBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val random = Random(1337L)
        val pixels = IntArray(width * height) {
            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        noisyBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val rawHighQualityJpeg = ByteArrayOutputStream().use { out ->
            try {
                assertThat(noisyBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)).isTrue()
                out.toByteArray()
            } finally {
                noisyBitmap.recycle()
            }
        }
        // Confirm the input truly exceeds the 640 KiB ceiling before compression
        assertThat(rawHighQualityJpeg.size.toLong()).isGreaterThan(MediaCompressor.MAX_ATTACHMENT_BYTES)

        val result = compressor.compressAndStore(rawHighQualityJpeg)
        assertThat(result.isSuccess).isTrue()

        val attachment = result.getOrThrow()
        assertThat(attachment.byteSize).isGreaterThan(0L)
        assertThat(attachment.byteSize).isAtMost(MediaCompressor.MAX_ATTACHMENT_BYTES)
        assertThat(attachment.width).isAtLeast(1)
        assertThat(attachment.height).isAtLeast(1)
        assertThat(maxOf(attachment.width, attachment.height))
            .isAtMost(AndroidMediaCompressor.MAX_EDGE_PX)

        val storedBytes = storage.readBytes(attachment.localFilename)
        assertThat(storedBytes).isNotNull()
        assertThat(storedBytes!!.size.toLong()).isEqualTo(attachment.byteSize)
        val decoded = BitmapFactory.decodeByteArray(storedBytes, 0, storedBytes.size)
        assertThat(decoded).isNotNull()
        try {
            assertThat(decoded.width).isEqualTo(attachment.width)
            assertThat(decoded.height).isEqualTo(attachment.height)
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun compressAndStore_exifRotate90_rotatesDimensionsAndPixels() = runTest {
        val baseDir = tempFolder.newFolder("compressor-exif-90")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = FileMediaStorage(baseDir, dispatcher)
        val compressor = AndroidMediaCompressor(
            mediaStorage = storage,
            ioDispatcher = dispatcher,
            uuidGenerator = { "exif-90" },
        )

        val jpegWithExif = createQuadrantJpegWithExif(
            width = 240,
            height = 120,
            orientation = ExifInterface.ORIENTATION_ROTATE_90,
        )

        val result = compressor.compressAndStore(jpegWithExif)
        assertThat(result.isSuccess).isTrue()

        val attachment = result.getOrThrow()
        assertThat(attachment.width).isEqualTo(120)
        assertThat(attachment.height).isEqualTo(240)

        val decoded = decodeStoredBitmap(storage, attachment.localFilename)
        try {
            // Original top-left quadrant (RED) rotates 90 deg CW -> top-right quadrant
            val topRightColor = decoded.getPixel(decoded.width - 15, 15)
            assertPredominantlyRed(topRightColor)
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun compressAndStore_exifRotate180_rotatesPixelsAndPreservesDimensions() = runTest {
        val baseDir = tempFolder.newFolder("compressor-exif-180")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = FileMediaStorage(baseDir, dispatcher)
        val compressor = AndroidMediaCompressor(
            mediaStorage = storage,
            ioDispatcher = dispatcher,
            uuidGenerator = { "exif-180" },
        )

        val jpegWithExif = createQuadrantJpegWithExif(
            width = 240,
            height = 120,
            orientation = ExifInterface.ORIENTATION_ROTATE_180,
        )

        val result = compressor.compressAndStore(jpegWithExif)
        assertThat(result.isSuccess).isTrue()

        val attachment = result.getOrThrow()
        assertThat(attachment.width).isEqualTo(240)
        assertThat(attachment.height).isEqualTo(120)

        val decoded = decodeStoredBitmap(storage, attachment.localFilename)
        try {
            // Original top-left quadrant (RED) rotates 180 deg -> bottom-right quadrant
            val bottomRightColor = decoded.getPixel(decoded.width - 15, decoded.height - 15)
            assertPredominantlyRed(bottomRightColor)
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun compressAndStore_exifRotate270_rotatesDimensionsAndPixels() = runTest {
        val baseDir = tempFolder.newFolder("compressor-exif-270")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = FileMediaStorage(baseDir, dispatcher)
        val compressor = AndroidMediaCompressor(
            mediaStorage = storage,
            ioDispatcher = dispatcher,
            uuidGenerator = { "exif-270" },
        )

        val jpegWithExif = createQuadrantJpegWithExif(
            width = 240,
            height = 120,
            orientation = ExifInterface.ORIENTATION_ROTATE_270,
        )

        val result = compressor.compressAndStore(jpegWithExif)
        assertThat(result.isSuccess).isTrue()

        val attachment = result.getOrThrow()
        assertThat(attachment.width).isEqualTo(120)
        assertThat(attachment.height).isEqualTo(240)

        val decoded = decodeStoredBitmap(storage, attachment.localFilename)
        try {
            // Original top-left quadrant (RED) rotates 270 deg CW -> bottom-left quadrant
            val bottomLeftColor = decoded.getPixel(15, decoded.height - 15)
            assertPredominantlyRed(bottomLeftColor)
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun compressAndStore_identicalInputDeduplicatesEndToEnd() = runTest {
        val baseDir = tempFolder.newFolder("compressor-dedup")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = FileMediaStorage(baseDir, dispatcher)
        val counter = AtomicInteger(0)
        val compressor = AndroidMediaCompressor(
            mediaStorage = storage,
            ioDispatcher = dispatcher,
            uuidGenerator = { "dedup-${counter.incrementAndGet()}" },
        )

        val rawBytes = encodeSolidBitmap(160, 120, Color.rgb(64, 96, 192), Bitmap.CompressFormat.PNG)

        val first = compressor.compressAndStore(rawBytes).getOrThrow()
        val second = compressor.compressAndStore(rawBytes.copyOf()).getOrThrow()

        assertThat(second.localFilename).isEqualTo(first.localFilename)
        assertThat(second.sha256).isEqualTo(first.sha256)
        assertThat(File(baseDir, "media").listFiles()?.toList()).hasSize(1)
    }

    @Test
    fun compressAndStore_emptyOrCorruptBytesReturnsFailure() = runTest {
        val baseDir = tempFolder.newFolder("compressor-invalid")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = FileMediaStorage(baseDir, dispatcher)
        val compressor = AndroidMediaCompressor(
            mediaStorage = storage,
            ioDispatcher = dispatcher,
            uuidGenerator = { "invalid-1" },
        )

        assertThat(compressor.compressAndStore(ByteArray(0)).isFailure).isTrue()
        assertThat(compressor.compressAndStore("not-valid-image-bytes".toByteArray()).isFailure).isTrue()
    }

    @Test
    fun compressAndStore_capsOversizedDimensionsAt2048px() = runTest {
        val baseDir = tempFolder.newFolder("compressor-cap")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = FileMediaStorage(baseDir, dispatcher)
        val compressor = AndroidMediaCompressor(
            mediaStorage = storage,
            ioDispatcher = dispatcher,
            uuidGenerator = { "capped-1" },
        )

        val oversizedPng = encodeSolidBitmap(3200, 1600, Color.rgb(120, 140, 160), Bitmap.CompressFormat.PNG)
        val attachment = compressor.compressAndStore(oversizedPng).getOrThrow()

        assertThat(attachment.width).isEqualTo(2048)
        assertThat(attachment.height).isEqualTo(1024)
        assertThat(attachment.byteSize).isAtMost(MediaCompressor.MAX_ATTACHMENT_BYTES)
    }

    private fun createQuadrantJpegWithExif(
        width: Int,
        height: Int,
        orientation: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val halfW = width / 2
        val halfH = height / 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = when {
                    x < halfW && y < halfH -> Color.RED
                    x >= halfW && y < halfH -> Color.GREEN
                    x < halfW && y >= halfH -> Color.BLUE
                    else -> Color.YELLOW
                }
                bitmap.setPixel(x, y, color)
            }
        }

        val jpegFile = tempFolder.newFile("exif-src-$orientation-${System.nanoTime()}.jpg")
        try {
            jpegFile.outputStream().use { out ->
                assertThat(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)).isTrue()
            }
        } finally {
            bitmap.recycle()
        }

        val exif = ExifInterface(jpegFile.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.saveAttributes()

        return jpegFile.readBytes()
    }

    private fun encodeSolidBitmap(
        width: Int,
        height: Int,
        color: Int,
        format: Bitmap.CompressFormat,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { out ->
            try {
                assertThat(bitmap.compress(format, 95, out)).isTrue()
                out.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun decodeStoredBitmap(storage: FileMediaStorage, localFilename: String): Bitmap {
        val bytes = storage.readBytes(localFilename)
        assertThat(bytes).isNotNull()
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size)
        assertThat(decoded).isNotNull()
        return decoded
    }

    private fun assertPredominantlyRed(color: Int) {
        assertThat(Color.red(color)).isGreaterThan(180)
        assertThat(Color.green(color)).isLessThan(80)
        assertThat(Color.blue(color)).isLessThan(80)
    }
}
