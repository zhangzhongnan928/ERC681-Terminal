// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

import Foundation

public enum ABIError: Error, Equatable, Sendable {
    case invalidWordLength(Int)
    case invalidBoolean
    case invalidAddressPadding
    case invalidDynamicData
}

public enum ABI {
    public static let balanceOfSelector = selector("balanceOf(address)")
    public static let decimalsSelector = selector("decimals()")
    public static let symbolSelector = selector("symbol()")
    public static let factorySelector = selector("factory()")
    public static let implementationSelector = selector("implementation()")
    public static let isOperatorSelector = selector("isOperator(address)")
    public static let isPaymentTokenSelector = selector("isPaymentToken(address)")
    public static let computeReceiverSelector = selector("computeReceiver(address,bytes32)")
    public static let settledSelector = selector("settled(bytes32,address)")

    public static func selector(_ signature: String) -> Data {
        Keccak256.hash(utf8: signature).data.prefix(4)
    }

    public static func word(_ address: EthereumAddress) -> Data {
        address.data.leftPadded(to: 32)
    }

    public static func word(_ value: UInt256) -> Data {
        value.bigEndianData
    }

    public static func word(_ value: UInt64) -> Data {
        UInt256(value).bigEndianData
    }

    public static func word(_ value: Bytes32) -> Data {
        value.data
    }

    public static func encodeInvoiceID(
        terminal: EthereumAddress,
        timestamp: UInt64,
        nonce: UInt256
    ) -> Data {
        word(terminal) + word(timestamp) + word(nonce)
    }

    public static func encodeCall(selector: Data, words: [Data] = []) -> Data {
        precondition(selector.count == 4)
        precondition(words.allSatisfy { $0.count == 32 })
        return words.reduce(into: selector) { $0.append($1) }
    }

    public static func decodeUInt256(_ data: Data) throws -> UInt256 {
        guard data.count == 32 else { throw ABIError.invalidWordLength(data.count) }
        return UInt256(bigEndian: data)
    }

    public static func decodeAddress(_ data: Data) throws -> EthereumAddress {
        guard data.count == 32 else { throw ABIError.invalidWordLength(data.count) }
        guard data.prefix(12).allSatisfy({ $0 == 0 }) else {
            throw ABIError.invalidAddressPadding
        }
        return try EthereumAddress(data: data.suffix(20))
    }

    public static func decodeBool(_ data: Data) throws -> Bool {
        let value = try decodeUInt256(data)
        if value == .zero { return false }
        if value == UInt256(1) { return true }
        throw ABIError.invalidBoolean
    }

    public static func isSafeTokenSymbol(
        _ value: String,
        maximumUTF8Bytes: Int = 32
    ) -> Bool {
        maximumUTF8Bytes > 0
            && !value.isEmpty
            && value.utf8.count <= maximumUTF8Bytes
            && value == value.trimmingCharacters(in: .whitespacesAndNewlines)
            && value.unicodeScalars.allSatisfy { scalar in
                switch scalar.properties.generalCategory {
                case .control, .format, .lineSeparator, .paragraphSeparator:
                    false
                default:
                    true
                }
            }
    }

    /// Decodes one canonical ABI dynamic string with an exact, bounded UTF-8 payload.
    /// Legacy bytes32 token symbols are deliberately rejected.
    public static func decodeDynamicString(
        _ data: Data,
        maximumUTF8Bytes: Int = 32
    ) throws -> String {
        guard maximumUTF8Bytes > 0,
              data.count >= 96,
              data.count.isMultiple(of: 32)
        else { throw ABIError.invalidDynamicData }

        let offsetWord = Data(data.prefix(32))
        guard try decodeUInt256(offsetWord).uint64Value == 32 else {
            throw ABIError.invalidDynamicData
        }
        let lengthWord = Data(data[32..<64])
        guard let rawLength = try decodeUInt256(lengthWord).uint64Value,
              rawLength > 0,
              rawLength <= UInt64(maximumUTF8Bytes),
              let length = Int(exactly: rawLength)
        else { throw ABIError.invalidDynamicData }

        let paddedLength = ((length + 31) / 32) * 32
        guard data.count == 64 + paddedLength else { throw ABIError.invalidDynamicData }
        let payload = Data(data[64..<(64 + length)])
        let padding = data[(64 + length)..<(64 + paddedLength)]
        guard padding.allSatisfy({ $0 == 0 }),
              let value = String(data: payload, encoding: .utf8),
              value.utf8.count == length,
              isSafeTokenSymbol(value, maximumUTF8Bytes: maximumUTF8Bytes)
        else { throw ABIError.invalidDynamicData }
        return value
    }
}
