package net.primal.android.scanner.analysis

/**
 * Packs a CameraX Y plane into width*height bytes.
 * Camera2 often pads each row (`rowStride` > width). Treating that buffer as packed
 * luminance shifts every row and the QR decoder never sees the code.
 */
internal fun packYPlane(
    y: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
): ByteArray? {
    val sizeOk = width > 0 && height > 0 && pixelStride > 0
    val strideOk = sizeOk && rowStride >= width
    val needed = if (strideOk) {
        (height - 1) * rowStride + (width - 1) * pixelStride + 1
    } else {
        Int.MAX_VALUE
    }
    val bufferOk = strideOk && y.size >= needed
    return when {
        !bufferOk -> null
        pixelStride == 1 && rowStride == width -> packedRows(y = y, width = width, height = height)
        else -> stridedRows(
            y = y,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
        )
    }
}

private fun packedRows(y: ByteArray, width: Int, height: Int): ByteArray =
    if (y.size == width * height) y else y.copyOf(width * height)

private fun stridedRows(
    y: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
): ByteArray {
    val packed = ByteArray(width * height)
    var destination = 0
    for (row in 0 until height) {
        var source = row * rowStride
        repeat(width) {
            packed[destination] = y[source]
            destination += 1
            source += pixelStride
        }
    }
    return packed
}

/**
 * First non-empty barcode string. ML Kit sometimes leaves [raw] empty for a URL
 * and puts the link on the display value or the URL bookmark.
 */
internal fun chooseQrText(raw: String?, display: String?, url: String?): String? =
    sequenceOf(raw, display, url).firstOrNull { !it.isNullOrEmpty() }
