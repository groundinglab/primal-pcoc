package net.primal.android.scanner.analysis

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import javax.inject.Inject
import net.primal.android.scanner.domain.QrCodeDataType
import net.primal.android.scanner.domain.QrCodeResult
import net.primal.core.utils.runCatching

class GoogleBarcodeScanner @Inject constructor() : QrCodeResultDecoder {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )

    @OptIn(ExperimentalGetImage::class)
    override fun process(imageProxy: ImageProxy): QrCodeResult? {
        val text = read(imageProxy) ?: return null
        val type = QrCodeDataType.from(text) ?: return null
        return QrCodeResult(value = text, type = type)
    }

    @OptIn(ExperimentalGetImage::class)
    override fun decodeText(imageProxy: ImageProxy): String? = read(imageProxy)

    @OptIn(ExperimentalGetImage::class)
    private fun read(imageProxy: ImageProxy): String? =
        imageProxy.use { proxy ->
            val mediaImage = proxy.image ?: return@use null
            val readyImage = InputImage.fromMediaImage(
                mediaImage,
                proxy.imageInfo.rotationDegrees,
            )
            val barcodes = runCatching { Tasks.await(scanner.process(readyImage)) }.getOrNull() ?: return@use null
            barcodes.firstNotNullOfOrNull { barcode ->
                chooseQrText(raw = barcode.rawValue, display = barcode.displayValue, url = barcode.url?.url)
            }
        }
}
