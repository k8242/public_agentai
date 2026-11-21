package io.github.k8242.threadlog.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.contract.MediaCompressor
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
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
class FileMediaStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun writeVerifiedBytes_webp_writesAtomicallyAndReadsBackIdenticalBytes() = runTest {
        val baseDir = tempFolder.newFolder("storage-webp")
        val storage = FileMediaStorage(baseDir, UnconfinedTestDispatcher(testScheduler))
        val imageBytes = encodeTestBitmap(width = 64, height = 48, format = webpFormat())

        val attachment = storage.writeVerifiedBytes(
            uuid = "550e8400-e29b-41d4-a716-446655440000",
            mimeType = "image/webp",
            bytes = imageBytes,
        )

        assertThat(attachment.localFilename).isEqualTo("media/550e8400-e29b-41d4-a716-446655440000.webp")
        assertThat(attachment.mimeType).isEqualTo("image/webp")
        assertThat(attachment.byteSize).isEqualTo(imageBytes.size.toLong())
        assertThat(attachment.width).isEqualTo(64)
        assertThat(attachment.height).isEqualTo(48)
        assertThat(attachment.sha256).isEqualTo(expectedSha256(imageBytes))
        assertThat(attachment.sha256).matches("[0-9a-f]{64}")

        val readBack = storage.readBytes(attachment.localFilename)
        assertThat(readBack).isEqualTo(imageBytes)

        val resolvedPath = storage.resolveFilePath(attachment.localFilename)
        assertThat(File(resolvedPath).exists()).isTrue()

        val mediaFiles = File(baseDir, "media").listFiles()?.map { it.name }.orEmpty()
        assertThat(mediaFiles).containsExactly("550e8400-e29b-41d4-a716-446655440000.webp")
    }

    @Test
    fun writeVerifiedBytes_jpeg_writesJpgExtensionAndDimensions() = runTest {
        val baseDir = tempFolder.newFolder("storage-jpeg")
        val storage = FileMediaStorage(baseDir, UnconfinedTestDispatcher(testScheduler))
        val imageBytes = encodeTestBitmap(width = 80, height = 40, format = Bitmap.CompressFormat.JPEG)

        val attachment = storage.writeVerifiedBytes(
            uuid = "img-jpeg-1",
            mimeType = "image/jpeg",
            bytes = imageBytes,
        )

        assertThat(attachment.localFilename).isEqualTo("media/img-jpeg-1.jpg")
        assertThat(attachment.mimeType).isEqualTo("image/jpeg")
        assertThat(attachment.byteSize).isEqualTo(imageBytes.size.toLong())
        assertThat(attachment.width).isEqualTo(80)
        assertThat(attachment.height).isEqualTo(40)
        assertThat(storage.readBytes(attachment.localFilename)).isEqualTo(imageBytes)
    }

    @Test
    fun readBytes_missingFileReturnsNull() = runTest {
        val baseDir = tempFolder.newFolder("storage-missing")
        val storage = FileMediaStorage(baseDir, UnconfinedTestDispatcher(testScheduler))

        assertThat(storage.readBytes("media/does-not-exist.webp")).isNull()
    }

    @Test
    fun resolveFilePath_rejectsTraversalAbsoluteBlankAndOutsideMedia() {
        val baseDir = tempFolder.newFolder("storage-path-validation")
        val storage = FileMediaStorage(baseDir)

        val invalidPaths = listOf(
            "",
            "   ",
            "../secret",
            "media/../secret",
            "media/sub/../escape.webp",
            "media/./same.webp",
            "/etc/passwd",
            "/media/absolute.webp",
            "other/a.jpg",
            "a.webp",
            "media/",
            "media\\back.webp",
            "media/bad\u0000name.webp",
        )

        for (invalidPath in invalidPaths) {
            assertThrows(
                "Expected IllegalArgumentException for '$invalidPath'",
                IllegalArgumentException::class.java,
            ) {
                storage.resolveFilePath(invalidPath)
            }
        }
    }

    @Test
    fun writeVerifiedBytes_rejectsOversizedEmptyUnsupportedMimeAndInvalidUuid() = runTest {
        val baseDir = tempFolder.newFolder("storage-validation")
        val storage = FileMediaStorage(baseDir, UnconfinedTestDispatcher(testScheduler))
        val validBytes = encodeTestBitmap(width = 16, height = 16, format = Bitmap.CompressFormat.JPEG)

        // Exceeds 640 KiB hard ceiling
        val oversized = ByteArray(MediaCompressor.MAX_ATTACHMENT_BYTES.toInt() + 1) { 1 }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                storage.writeVerifiedBytes("valid-id", "image/webp", oversized)
            }
        }

        // Empty bytes
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                storage.writeVerifiedBytes("valid-id", "image/webp", ByteArray(0))
            }
        }

        // Unsupported mime types
        for (badMime in listOf("image/png", "image/gif", "application/octet-stream", "")) {
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking {
                    storage.writeVerifiedBytes("valid-id", badMime, validBytes)
                }
            }
        }

        // Invalid UUID tokens
        for (badUuid in listOf("", "   ", "../evil", "sub/id", "sub\\id", ".hidden", "has space")) {
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking {
                    storage.writeVerifiedBytes(badUuid, "image/jpeg", validBytes)
                }
            }
        }
    }

    @Test
    fun writeVerifiedBytes_deduplicatesBySha256InMemoryAndAcrossColdRestart() = runTest {
        val baseDir = tempFolder.newFolder("storage-dedup")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val firstStorage = FileMediaStorage(baseDir, dispatcher)
        val imageBytes = encodeTestBitmap(width = 50, height = 30, format = Bitmap.CompressFormat.JPEG)

        val first = firstStorage.writeVerifiedBytes(
            uuid = "first-uuid",
            mimeType = "image/jpeg",
            bytes = imageBytes,
        )
        val second = firstStorage.writeVerifiedBytes(
            uuid = "second-uuid",
            mimeType = "image/jpeg",
            bytes = imageBytes.copyOf(),
        )

        assertThat(second.localFilename).isEqualTo(first.localFilename)
        assertThat(second.sha256).isEqualTo(first.sha256)
        assertThat(second.mimeType).isEqualTo("image/jpeg")
        assertThat(File(baseDir, "media").listFiles()?.toList()).hasSize(1)

        // Brand-new instance simulating cold app restart
        val coldRestartStorage = FileMediaStorage(baseDir, dispatcher)
        assertThat(coldRestartStorage.findBySha256(first.sha256)).isEqualTo(first.localFilename)
        assertThat(coldRestartStorage.findBySha256(first.sha256.uppercase())).isEqualTo(first.localFilename)

        val third = coldRestartStorage.writeVerifiedBytes(
            uuid = "third-uuid",
            mimeType = "image/jpeg",
            bytes = imageBytes.copyOf(),
        )
        assertThat(third.localFilename).isEqualTo(first.localFilename)
        assertThat(File(baseDir, "media").listFiles()?.toList()).hasSize(1)
    }

    @Test
    fun findBySha256_evictsStaleCacheEntryWhenFileDeletedExternally() = runTest {
        val baseDir = tempFolder.newFolder("storage-stale")
        val storage = FileMediaStorage(baseDir, UnconfinedTestDispatcher(testScheduler))
        val imageBytes = encodeTestBitmap(width = 32, height = 32, format = Bitmap.CompressFormat.JPEG)

        val saved = storage.writeVerifiedBytes("ephemeral-id", "image/jpeg", imageBytes)
        assertThat(storage.findBySha256(saved.sha256)).isEqualTo(saved.localFilename)

        assertThat(File(storage.resolveFilePath(saved.localFilename)).delete()).isTrue()
        assertThat(storage.findBySha256(saved.sha256)).isNull()
    }

    @Test
    fun contextConstructor_storesInsideApplicationFilesDir() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = FileMediaStorage(context)
        val imageBytes = encodeTestBitmap(width = 24, height = 24, format = Bitmap.CompressFormat.JPEG)

        val saved = storage.writeVerifiedBytes("ctx-test-id", "image/jpeg", imageBytes)
        val resolved = File(storage.resolveFilePath(saved.localFilename))

        assertThat(resolved.exists()).isTrue()
        assertThat(resolved.canonicalPath)
            .startsWith(File(context.filesDir, "media").canonicalPath + File.separator)
    }

    private fun encodeTestBitmap(
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(42, 128, 210))
        val out = ByteArrayOutputStream()
        try {
            val ok = bitmap.compress(format, 90, out)
            assertThat(ok).isTrue()
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

    private fun expectedSha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
