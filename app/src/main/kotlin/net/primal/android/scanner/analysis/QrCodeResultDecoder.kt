package net.primal.android.scanner.analysis

import androidx.camera.core.ImageProxy
import net.primal.android.scanner.domain.QrCodeResult

interface QrCodeResultDecoder {
    fun process(imageProxy: ImageProxy): QrCodeResult?

    /**
     * Decoded QR text, including payloads that are not a known [net.primal.android.scanner.domain.QrCodeDataType].
     * Closes [imageProxy]. The default keeps older decoders on the typed path.
     */
    fun decodeText(imageProxy: ImageProxy): String? = process(imageProxy)?.value
}
