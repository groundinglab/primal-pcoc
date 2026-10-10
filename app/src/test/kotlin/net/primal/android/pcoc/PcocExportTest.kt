package net.primal.android.pcoc

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PcocExportTest {

    @Test
    fun `save as uses the storage access framework and share uses a content uri`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val save = ActivityResultContracts.CreateDocument(PCOC_OBJECT_MIME).createIntent(context, "hike.cbor")
        save.action shouldBe Intent.ACTION_CREATE_DOCUMENT
        save.type shouldBe PCOC_OBJECT_MIME
        save.getStringExtra(Intent.EXTRA_TITLE) shouldBe "hike.cbor"

        val log = ActivityResultContracts.CreateDocument(PCOC_LOG_MIME).createIntent(context, "hike.cbor.events")
        log.action shouldBe Intent.ACTION_CREATE_DOCUMENT
        log.type shouldBe PCOC_LOG_MIME
        log.getStringExtra(Intent.EXTRA_TITLE) shouldBe "hike.cbor.events"

        val uri = Uri.parse("content://net.primal.android.provider/pcoc_export/hike.cbor")
        val chooser = shareIntent(uri, PCOC_OBJECT_MIME, "hike.cbor")
        chooser.action shouldBe Intent.ACTION_CHOOSER
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        send!!.action shouldBe Intent.ACTION_SEND
        send.type shouldBe PCOC_OBJECT_MIME
        @Suppress("DEPRECATION")
        val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        stream shouldBe uri
        send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION shouldBe Intent.FLAG_GRANT_READ_URI_PERMISSION
    }

    @Test
    fun `export reads the object and the produce event log`() {
        val root = kotlin.io.path.createTempDirectory("pcoc-export").toFile()
        val objectFile = File(root, "hike.cbor").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        File(root, "hike.cbor.events").writeText("block\n")

        objectExportName(objectFile.path) shouldBe "hike.cbor"
        logExportName(objectFile.path) shouldBe "hike.cbor.events"
        objectExportName("") shouldBe "pcoc.cbor"

        bytesForExport(PcocExportKind.Object, objectFile, emptyList())!!.contentEquals(byteArrayOf(1, 2, 3)) shouldBe true
        bytesForExport(PcocExportKind.Log, objectFile, listOf("screen"))!!.decodeToString() shouldBe "block\n"
        bytesForExport(PcocExportKind.Object, File(root, "missing.cbor"), emptyList()) shouldBe null

        File(root, "hike.cbor.events").delete()
        bytesForExport(PcocExportKind.Log, objectFile, listOf("a", "b"))!!.decodeToString() shouldBe "a\nb"
        bytesForExport(PcocExportKind.Log, objectFile, emptyList()) shouldBe null

        val staged = stageShareFile(root, "hike cbor", byteArrayOf(9))
        staged.name shouldBe "hike_cbor"
        staged.readBytes().contentEquals(byteArrayOf(9)) shouldBe true
    }
}
