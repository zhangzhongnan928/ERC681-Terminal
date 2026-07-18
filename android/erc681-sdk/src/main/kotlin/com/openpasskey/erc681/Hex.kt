package com.openpasskey.erc681

internal object Hex {
    private val digits = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray, prefix: Boolean = true): String {
        val offset = if (prefix) 2 else 0
        val chars = CharArray(bytes.size * 2 + offset)
        if (prefix) {
            chars[0] = '0'
            chars[1] = 'x'
        }
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[offset + index * 2] = digits[value ushr 4]
            chars[offset + index * 2 + 1] = digits[value and 0x0f]
        }
        return chars.concatToString()
    }

    fun decode(value: String, expectedBytes: Int? = null): ByteArray {
        require(value.startsWith("0x")) { "Hex value must start with 0x" }
        val body = value.substring(2)
        require(body.length % 2 == 0) { "Hex value must contain complete bytes" }
        if (expectedBytes != null) {
            require(body.length == expectedBytes * 2) { "Expected $expectedBytes bytes" }
        }
        require(body.all { it.isHexDigit() }) { "Hex value contains a non-hexadecimal character" }

        return ByteArray(body.length / 2) { index ->
            ((body[index * 2].hexValue() shl 4) or body[index * 2 + 1].hexValue()).toByte()
        }
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'f' -> code - 'a'.code + 10
        else -> code - 'A'.code + 10
    }
}
