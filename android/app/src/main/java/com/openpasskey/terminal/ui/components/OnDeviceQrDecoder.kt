package com.openpasskey.terminal.ui.components

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

/**
 * Decodes QR frames entirely in-process. No camera frame, decoded value, identifier, or diagnostic
 * leaves the device.
 */
internal object OnDeviceQrDecoder {
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
    )

    fun copyLuminancePlane(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): ByteArray {
        require(width > 0 && height > 0) { "Camera frame dimensions must be positive" }
        require(rowStride >= width) { "Camera row stride is smaller than the frame width" }
        require(pixelStride > 0) { "Camera pixel stride must be positive" }

        val plane = buffer.duplicate()
        val base = plane.position()
        val output = ByteArray(Math.multiplyExact(width, height))
        for (row in 0 until height) {
            for (column in 0 until width) {
                val sourceIndex = Math.addExact(
                    base,
                    Math.addExact(
                        Math.multiplyExact(row, rowStride),
                        Math.multiplyExact(column, pixelStride),
                    ),
                )
                require(sourceIndex < plane.limit()) { "Camera luminance plane is truncated" }
                output[row * width + column] = plane.get(sourceIndex)
            }
        }
        return output
    }

    fun decode(
        luminance: ByteArray,
        width: Int,
        height: Int,
    ): String? {
        require(width > 0 && height > 0) { "QR frame dimensions must be positive" }
        require(luminance.size == Math.multiplyExact(width, height)) {
            "QR luminance data does not match its dimensions"
        }

        val source = PlanarYUVLuminanceSource(
            luminance,
            width,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        return decode(source) ?: decode(source.invert())
    }

    private fun decode(source: com.google.zxing.LuminanceSource): String? {
        val reader = QRCodeReader()
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
