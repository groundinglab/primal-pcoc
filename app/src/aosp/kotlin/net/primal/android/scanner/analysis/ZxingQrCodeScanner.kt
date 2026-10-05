package net.primal.android.scanner.analysis

import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer
import javax.inject.Inject
import net.primal.android.scanner.domain.QrCodeDataType
import net.primal.android.scanner.domain.QrCodeResult
import net.primal.core.utils.runCatching

class ZxingQrCodeScanner @Inject constructor() : QrCodeResultDecoder {

    private val reader = QRCodeReader()

    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    override fun process(imageProxy: ImageProxy): QrCodeResult? {
        val text = read(imageProxy) ?: return null
        val type = QrCodeDataType.from(text) ?: return null
        return QrCodeResult(value = text, type = type)
    }

    override fun decodeText(imageProxy: ImageProxy): String? = read(imageProxy)

    private fun read(imageProxy: ImageProxy): String? {
        val frame = imageProxy.use { proxy -> copyFrame(proxy) } ?: return null
        return decode(bytes = frame.bytes, width = frame.width, height = frame.height)
            ?: decode(bytes = frame.bytes.invertColors(), width = frame.width, height = frame.height)
    }

    private fun copyFrame(proxy: ImageProxy): Frame? {
        val plane = runCatching { proxy.planes.firstOrNull() }.getOrNull() ?: return null
        val packed = packYPlane(
            y = plane.buffer.copyBytes(),
            width = proxy.width,
            height = proxy.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
        ) ?: return null
        return Frame(bytes = packed, width = proxy.width, height = proxy.height)
    }

    private fun decode(bytes: ByteArray, width: Int, height: Int): String? {
        val text = runCatching {
            val source = PlanarYUVLuminanceSource(
                bytes,
                width,
                height,
                0,
                0,
                width,
                height,
                false,
            )
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        }.getOrNull()
        reader.reset()
        return text
    }

    @Suppress("MagicNumber")
    private fun ByteArray.invertColors(): ByteArray {
        val inverted = ByteArray(this.size)
        for (i in this.indices) {
            inverted[i] = (255 - this[i].toInt()).toByte()
        }
        return inverted
    }

    private fun ByteBuffer.copyBytes(): ByteArray {
        val view = duplicate()
        view.rewind()
        return ByteArray(view.remaining()).also { view.get(it) }
    }

    private data class Frame(val bytes: ByteArray, val width: Int, val height: Int)
}
