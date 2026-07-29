// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

import Foundation

/// Ethereum Keccak-256 (legacy Keccak padding 0x01, not standardized SHA3-256).
public enum Keccak256 {
    private static let rate = 136
    private static let roundConstants: [UInt64] = [
        0x0000000000000001, 0x0000000000008082,
        0x800000000000808a, 0x8000000080008000,
        0x000000000000808b, 0x0000000080000001,
        0x8000000080008081, 0x8000000000008009,
        0x000000000000008a, 0x0000000000000088,
        0x0000000080008009, 0x000000008000000a,
        0x000000008000808b, 0x800000000000008b,
        0x8000000000008089, 0x8000000000008003,
        0x8000000000008002, 0x8000000000000080,
        0x000000000000800a, 0x800000008000000a,
        0x8000000080008081, 0x8000000000008080,
        0x0000000080000001, 0x8000000080008008,
    ]

    // Indexed as x + 5*y.
    private static let rotations: [UInt64] = [
         0,  1, 62, 28, 27,
        36, 44,  6, 55, 20,
         3, 10, 43, 25, 39,
        41, 45, 15, 21,  8,
        18,  2, 61, 56, 14,
    ]

    public static func hash(_ data: Data) -> Bytes32 {
        var state = [UInt64](repeating: 0, count: 25)
        let input = [UInt8](data)
        var offset = 0

        while input.count - offset >= rate {
            absorb(Array(input[offset..<(offset + rate)]), into: &state)
            permute(&state)
            offset += rate
        }

        var finalBlock = [UInt8](repeating: 0, count: rate)
        let remainder = input.count - offset
        if remainder > 0 {
            finalBlock.replaceSubrange(0..<remainder, with: input[offset..<input.count])
        }
        finalBlock[remainder] ^= 0x01
        finalBlock[rate - 1] ^= 0x80
        absorb(finalBlock, into: &state)
        permute(&state)

        var output = Data()
        output.reserveCapacity(32)
        for lane in state.prefix(4) {
            var little = lane.littleEndian
            withUnsafeBytes(of: &little) { output.append(contentsOf: $0) }
        }
        return try! Bytes32(data: output)
    }

    public static func hash(utf8 string: String) -> Bytes32 {
        hash(Data(string.utf8))
    }

    private static func absorb(_ block: [UInt8], into state: inout [UInt64]) {
        for laneIndex in 0..<(rate / 8) {
            let start = laneIndex * 8
            var lane: UInt64 = 0
            for byteIndex in 0..<8 {
                lane |= UInt64(block[start + byteIndex]) << UInt64(byteIndex * 8)
            }
            state[laneIndex] ^= lane
        }
    }

    private static func permute(_ state: inout [UInt64]) {
        for roundConstant in roundConstants {
            var columns = [UInt64](repeating: 0, count: 5)
            for x in 0..<5 {
                columns[x] = state[x] ^ state[x + 5] ^ state[x + 10] ^ state[x + 15] ^ state[x + 20]
            }
            for x in 0..<5 {
                let delta = columns[(x + 4) % 5] ^ columns[(x + 1) % 5].rotatedLeft(by: 1)
                for y in 0..<5 { state[x + 5 * y] ^= delta }
            }

            var moved = [UInt64](repeating: 0, count: 25)
            for x in 0..<5 {
                for y in 0..<5 {
                    let destinationX = y
                    let destinationY = (2 * x + 3 * y) % 5
                    let index = x + 5 * y
                    moved[destinationX + 5 * destinationY] = state[index].rotatedLeft(by: rotations[index])
                }
            }

            for y in 0..<5 {
                for x in 0..<5 {
                    state[x + 5 * y] = moved[x + 5 * y]
                        ^ ((~moved[(x + 1) % 5 + 5 * y]) & moved[(x + 2) % 5 + 5 * y])
                }
            }
            state[0] ^= roundConstant
        }
    }
}

extension UInt64 {
    fileprivate func rotatedLeft(by count: UInt64) -> UInt64 {
        guard count != 0 else { return self }
        return (self << count) | (self >> (64 - count))
    }
}
