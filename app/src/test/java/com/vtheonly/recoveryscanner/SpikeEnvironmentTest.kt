package com.vtheonly.recoveryscanner

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.vtheonly.recoveryscanner.support.Fixtures
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Environment spike (Section 1/44 groundwork): verifies the Robolectric behaviors
 * the whole suite depends on:
 *  - ContentResolver.openInputStream(file://) works and yields fresh streams
 *  - DocumentFile.fromTreeUri(file://) falls back to RawDocumentFile (SAF-tree scanners testable)
 *  - ExifInterface attribute round-trip works on JVM
 *  - NATIVE graphics mode decodes real images and rejects fake headers
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpikeEnvironmentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun resourceBytes(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.readBytes()

    @Test
    fun `contentResolver openInputStream supports file scheme`() {
        val f = File.createTempFile("spike", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val uri = Uri.fromFile(f)
        val opened = context.contentResolver.openInputStream(uri)
        assertTrue("openInputStream(file://) returned null", opened != null)
        val bytes = opened!!.use { it.readBytes() }
        assertTrue("unexpected bytes: ${bytes.joinToString()}", bytes.contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `contentResolver openInputStream reopens fresh stream per call`() {
        val f = File.createTempFile("spike2", ".bin").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        val uri = Uri.fromFile(f)
        val a = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val b = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `fromTreeUri rejects file scheme - SAF trees must come from ACTION_OPEN_DOCUMENT_TREE`() {
        val dir = createTempDir("spikeTree").also { File(it, "a.txt").writeText("hi") }
        var threw = false
        try { DocumentFile.fromTreeUri(context, Uri.fromFile(dir)) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue("expected IllegalArgumentException for file:// tree URI", threw)
    }

    @Test
    fun `stub SAF provider drives real TreeDocumentFile code path`() {
        val dir = createTempDir("spikeTree").apply { deleteOnExit() }
        File(dir, "a.txt").writeText("hi")
        val sub = File(dir, "sub").apply { mkdirs() }
        File(sub, "nested.jpg").writeBytes(com.vtheonly.recoveryscanner.support.Fixtures.bytes("jpeg_baseline.jpg"))
        val treeUri = com.vtheonly.recoveryscanner.support.SAF.install(dir)
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("null root")
        assertTrue("tree lists files", root.listFiles().mapNotNull { it.name }.containsAll(listOf("a.txt", "sub")))
        val subDoc = root.listFiles().first { it.name == "sub" }
        assertTrue("sub is directory", subDoc.isDirectory)
        assertTrue("nested file found", subDoc.listFiles().any { it.name == "nested.jpg" })
        val nested = subDoc.listFiles().first { it.name == "nested.jpg" }
        assertTrue("size via SAF", nested.length() == Fixtures.size("jpeg_baseline.jpg"))
        val readBack = context.contentResolver.openInputStream(nested.uri)!!.use { it.readBytes() }
        assertTrue("openInputStream via SAF roundtrip", readBack.contentEquals(Fixtures.bytes("jpeg_baseline.jpg")))
        val created = root.createFile("image/jpeg", "x.recovered.jpg")
        assertTrue("createFile failed", created != null && created.exists())
        val named = DocumentFile.fromTreeUri(context, treeUri)!!.findFile("x.recovered.jpg")
        assertTrue("findFile could not locate created file (uniqueName recovery-path risk)", named != null)
        val out = context.contentResolver.openOutputStream(created!!.uri)!!
        out.use { it.write(byteArrayOf(1, 2, 3)) }
        assertTrue("written via SAF", File(dir, "x.recovered.jpg").length() == 3L)
    }

    @Test
    fun `ExifInterface writes and reads DateTimeOriginal on plain JVM`() {
        val jpeg = File.createTempFile("spikeExif", ".jpg").apply { writeBytes(minimalJpeg()) }
        val exif = ExifInterface(jpeg.absolutePath)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:05:21 09:30:00")
        exif.saveAttributes()
        val readBack = ExifInterface(jpeg.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        assertTrue("EXIF roundtrip failed: $readBack", readBack == "2024:05:21 09:30:00")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `NATIVE graphics mode decodes real images and rejects garbage`() {
        val real = resourceBytes("jpeg_baseline.jpg")
        val ok = BitmapFactory.decodeByteArray(real, 0, real.size)
        assertTrue("real JPEG did not decode in NATIVE mode", ok != null)
        val garbage = ByteArray(64) { 0x55 }
        garbage[0] = 0xFF.toByte(); garbage[1] = 0xD8.toByte(); garbage[2] = 0xFF.toByte()
        val bad = BitmapFactory.decodeByteArray(garbage, 0, garbage.size)
        assertTrue("garbage decoded as bitmap: should be null", bad == null)
    }

    private fun minimalJpeg(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))              // SOI
        out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10))  // APP0 marker, len 16
        out.write("JFIF".toByteArray()); out.write(byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))              // EOI
        return out.toByteArray()
    }
}
