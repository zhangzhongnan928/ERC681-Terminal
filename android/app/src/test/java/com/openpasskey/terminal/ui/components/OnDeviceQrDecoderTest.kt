package com.openpasskey.terminal.ui.components

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnDeviceQrDecoderTest {
    @Test
    fun `decodes a QR code without a network SDK`() {
        val value = "opk-terminal:operator?v=1&address=0x1111111111111111111111111111111111111111"
        val luminance = qrLuminance(value)

        assertEquals(
            value,
            OnDeviceQrDecoder.decode(luminance, QR_SIZE, QR_SIZE),
        )
    }

    @Test
    fun `decodes camera frames at every right-angle rotation`() {
        val value = "opk-terminal:provision?v=1&chainId=84532"
        val upright = qrLuminance(value)
        val clockwise90 = rotateClockwise(upright, QR_SIZE)
        val clockwise180 = rotateClockwise(clockwise90, QR_SIZE)
        val clockwise270 = rotateClockwise(clockwise180, QR_SIZE)

        listOf(upright, clockwise90, clockwise180, clockwise270).forEach { luminance ->
            assertEquals(value, OnDeviceQrDecoder.decode(luminance, QR_SIZE, QR_SIZE))
        }
    }

    @Test
    fun `decodes an inverted QR code`() {
        val value = "0x1111111111111111111111111111111111111111"
        val inverted = qrLuminance(value)
            .map { byte -> (byte.toInt() xor 0xff).toByte() }
            .toByteArray()

        assertEquals(value, OnDeviceQrDecoder.decode(inverted, QR_SIZE, QR_SIZE))
    }

    @Test
    fun `copies a padded luminance plane using its strides`() {
        val padded = byteArrayOf(
            1, 9, 2, 9, 3, 9, 0, 0,
            4, 9, 5, 9, 6, 9, 0, 0,
        )

        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6),
            OnDeviceQrDecoder.copyLuminancePlane(
                buffer = ByteBuffer.wrap(padded),
                width = 3,
                height = 2,
                rowStride = 8,
                pixelStride = 2,
            ),
        )
    }

    @Test
    fun `returns no value for a frame without a QR code`() {
        val luminance = ByteArray(160 * 160) { 0xff.toByte() }

        assertNull(OnDeviceQrDecoder.decode(luminance, 160, 160))
    }

    private fun qrLuminance(value: String): ByteArray {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
        return ByteArray(matrix.width * matrix.height) { index ->
            val x = index % matrix.width
            val y = index / matrix.width
            if (matrix[x, y]) 0 else 0xff.toByte()
        }
    }

    private fun rotateClockwise(source: ByteArray, size: Int): ByteArray =
        ByteArray(source.size) { targetIndex ->
            val targetX = targetIndex % size
            val targetY = targetIndex / size
            source[(size - 1 - targetX) * size + targetY]
        }

    private companion object {
        const val QR_SIZE = 320
    }
}
