// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

internal object Keccak256 {
    private const val RATE_BYTES = 136

    private val roundConstants = longArrayOf(
        0x0000000000000001uL.toLong(), 0x0000000000008082uL.toLong(),
        0x800000000000808auL.toLong(), 0x8000000080008000uL.toLong(),
        0x000000000000808buL.toLong(), 0x0000000080000001uL.toLong(),
        0x8000000080008081uL.toLong(), 0x8000000000008009uL.toLong(),
        0x000000000000008auL.toLong(), 0x0000000000000088uL.toLong(),
        0x0000000080008009uL.toLong(), 0x000000008000000auL.toLong(),
        0x000000008000808buL.toLong(), 0x800000000000008buL.toLong(),
        0x8000000000008089uL.toLong(), 0x8000000000008003uL.toLong(),
        0x8000000000008002uL.toLong(), 0x8000000000000080uL.toLong(),
        0x000000000000800auL.toLong(), 0x800000008000000auL.toLong(),
        0x8000000080008081uL.toLong(), 0x8000000000008080uL.toLong(),
        0x0000000080000001uL.toLong(), 0x8000000080008008uL.toLong(),
    )

    private val rotationOffsets = intArrayOf(
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14,
    )

    fun digest(input: ByteArray): ByteArray {
        val state = LongArray(25)
        var offset = 0

        while (input.size - offset >= RATE_BYTES) {
            absorbBlock(state, input, offset)
            permute(state)
            offset += RATE_BYTES
        }

        val finalBlock = ByteArray(RATE_BYTES)
        val remaining = input.size - offset
        input.copyInto(finalBlock, endIndex = input.size, destinationOffset = 0, startIndex = offset)
        finalBlock[remaining] = 0x01
        finalBlock[RATE_BYTES - 1] = (finalBlock[RATE_BYTES - 1].toInt() or 0x80).toByte()
        absorbBlock(state, finalBlock, 0)
        permute(state)

        return ByteArray(32).also { output ->
            for (index in output.indices) {
                output[index] = (state[index / 8] ushr ((index % 8) * 8)).toByte()
            }
        }
    }

    private fun absorbBlock(state: LongArray, block: ByteArray, offset: Int) {
        for (lane in 0 until RATE_BYTES / 8) {
            var value = 0L
            for (byteIndex in 0 until 8) {
                value = value or ((block[offset + lane * 8 + byteIndex].toLong() and 0xffL) shl (byteIndex * 8))
            }
            state[lane] = state[lane] xor value
        }
    }

    private fun permute(state: LongArray) {
        val c = LongArray(5)
        val d = LongArray(5)
        val b = LongArray(25)

        for (roundConstant in roundConstants) {
            for (x in 0 until 5) {
                c[x] = state[x] xor state[x + 5] xor state[x + 10] xor state[x + 15] xor state[x + 20]
            }
            for (x in 0 until 5) {
                d[x] = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
            }
            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    state[x + 5 * y] = state[x + 5 * y] xor d[x]
                }
            }

            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    val targetX = y
                    val targetY = (2 * x + 3 * y) % 5
                    b[targetX + 5 * targetY] =
                        java.lang.Long.rotateLeft(state[x + 5 * y], rotationOffsets[x + 5 * y])
                }
            }

            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    state[x + 5 * y] = b[x + 5 * y] xor
                        (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
                }
            }
            state[0] = state[0] xor roundConstant
        }
    }
}
