package app.spektralab.data

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IncomingShareTest {
    private val photo = Uri.parse("content://gallery/photo.jpg")
    private val video = Uri.parse("content://gallery/movie.mp4")

    @Test fun receivesSingleStream() {
        assertEquals(listOf(photo), IncomingShare.uris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, photo)))
    }

    @Test fun multipleAndClipDataAreDeduplicatedInOrder() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(photo, video))
        intent.clipData = ClipData.newRawUri("photo", photo).apply {
            addItem(ClipData.Item(Uri.parse("content://files/frame.dng")))
        }
        assertEquals(listOf(photo, video, Uri.parse("content://files/frame.dng")), IncomingShare.uris(intent))
    }

    @Test fun acceptsClipOnlySharesButNotTextOrUntrustedSchemes() {
        val intent = Intent(Intent.ACTION_SEND).apply { clipData = ClipData.newRawUri("video", video) }
        assertEquals(listOf(video), IncomingShare.uris(intent))
        for (uri in listOf("https://example.com/photo.jpg", "file:///data/private.jpg")) {
            assertTrue(IncomingShare.uris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))).isEmpty())
        }
        assertTrue(IncomingShare.uris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "hello")).isEmpty())
        assertTrue(IncomingShare.uris(Intent(Intent.ACTION_VIEW).setData(photo)).isEmpty())
    }

    @Test fun copiesOriginalOutsideCacheAndKeepsExtension() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        shadowOf(context.contentResolver).registerInputStream(photo, ByteArrayInputStream(bytes))
        val file = IncomingShare.copyOriginal(context, photo)
        try {
            assertEquals("jpg", file.extension)
            assertEquals(context.filesDir.resolve("shared-originals"), file.parentFile)
            assertArrayEquals(bytes, file.readBytes())
        } finally { file.delete() }
    }

    @Test
    // Use Robolectric's supported legacy decoder; native FD decoding needs Android.
    @Config(sdk = [29], shadows = [FileImageDecoder::class])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
    fun importedPhotoRetainsAnEditableOwnedOriginalAndDisplayName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = android.graphics.Bitmap.createBitmap(8, 6, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().apply {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        bitmap.recycle()
        val source = java.io.File(context.cacheDir, "source.png").apply { writeBytes(bytes) }
        try {
            Media.loadSource(context, Uri.fromFile(source), 512, false).use { assertEquals(8, it.width) }
        } finally { source.delete() }
        shadowOf(context.contentResolver).registerInputStream(photo, ByteArrayInputStream(bytes))
        val item = IncomingShare.importItem(context, photo, 42)
        assertNotNull(item)
        val original = java.io.File(Uri.parse(item!!.uri).path!!)
        try {
            assertEquals(42L, item.id)
            assertEquals("photo.jpg", item.name)
            assertFalse(item.isVideo)
            assertArrayEquals(bytes, original.readBytes())
            Media.loadSource(context, Uri.parse(item.uri), 512, false).use {
                assertEquals(8, it.width)
                assertEquals(6, it.height)
            }
        } finally { original.delete() }
    }

    @Test fun unsupportedDocumentIsSkippedWithoutRetainingACopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = context.filesDir.resolve("shared-originals")
        val before = directory.listFiles()?.toSet().orEmpty()
        val document = Uri.parse("content://files/document.pdf")
        shadowOf(context.contentResolver).registerInputStream(document, ByteArrayInputStream("%PDF-invalid".toByteArray()))
        assertNull(IncomingShare.importItem(context, document, 43))
        assertEquals(before, directory.listFiles()?.toSet().orEmpty())
    }

    @Test fun failedCopyLeavesNoPartialOriginal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = context.filesDir.resolve("shared-originals")
        val before = directory.listFiles()?.toSet().orEmpty()
        shadowOf(context.contentResolver).registerInputStream(photo, object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("provider unavailable")
        })
        assertTrue(runCatching { IncomingShare.copyOriginal(context, photo) }.isFailure)
        assertEquals(before, directory.listFiles()?.toSet().orEmpty())
    }
}
