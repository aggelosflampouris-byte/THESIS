package com.example.thesis.ui.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal data class QrCodeMatrix(
    val size: Int,
    private val modules: BooleanArray
) {
    init {
        require(size > 0 && modules.size == size * size) { "Invalid QR matrix dimensions" }
    }

    fun isDark(row: Int, col: Int): Boolean {
        require(row in 0 until size && col in 0 until size) {
            "QR coordinate ($row, $col) out of bounds for size $size"
        }
        return modules[row * size + col]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrCodeMatrix) return false
        return size == other.size && modules.contentEquals(other.modules)
    }

    override fun hashCode(): Int {
        var result = size
        result = 31 * result + modules.contentHashCode()
        return result
    }
}

internal object QrCodeGenerator {
    fun encode(content: String): QrCodeMatrix {
        require(content.isNotBlank()) { "QR content must not be blank" }
        // Omitimos el margen interno para controlar la zona tranquila exacta en la interfaz Compose.
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 0
        )
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = bitMatrix.width
        require(size > 0 && bitMatrix.height == size) { "Expected square QR matrix" }

        val modules = BooleanArray(size * size)
        for (row in 0 until size) {
            val rowOffset = row * size
            for (col in 0 until size) {
                modules[rowOffset + col] = bitMatrix.get(col, row)
            }
        }
        return QrCodeMatrix(size = size, modules = modules)
    }
}
