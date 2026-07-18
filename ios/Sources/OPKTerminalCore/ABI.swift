import Foundation

public enum ABIError: Error, Equatable, Sendable {
    case invalidWordLength(Int)
    case invalidBoolean
    case invalidDynamicData
}

public enum ABI {
    public static let balanceOfSelector = selector("balanceOf(address)")
    public static let decimalsSelector = selector("decimals()")
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
        return try EthereumAddress(data: data.suffix(20))
    }

    public static func decodeBool(_ data: Data) throws -> Bool {
        let value = try decodeUInt256(data)
        if value == .zero { return false }
        if value == UInt256(1) { return true }
        throw ABIError.invalidBoolean
    }
}
