import Foundation

public enum FixedBytesError: Error, Equatable, Sendable {
    case wrongLength(expected: Int, actual: Int)
    case zeroAddress
}

public struct EthereumAddress: Hashable, Sendable, Codable, CustomStringConvertible {
    public static let byteCount = 20
    private let storage: Data

    public init(data: Data, allowZero: Bool = true) throws {
        guard data.count == Self.byteCount else {
            throw FixedBytesError.wrongLength(expected: Self.byteCount, actual: data.count)
        }
        if !allowZero && data.allSatisfy({ $0 == 0 }) {
            throw FixedBytesError.zeroAddress
        }
        storage = data
    }

    public init(hex: String, allowZero: Bool = true) throws {
        try self.init(data: Data(hex: hex), allowZero: allowZero)
    }

    public var data: Data { storage }
    public var hex: String { storage.hexString }
    public var description: String { hex }
    public var isZero: Bool { storage.allSatisfy { $0 == 0 } }

    public static func random() -> EthereumAddress {
        var generator = SystemRandomNumberGenerator()
        let bytes = (0..<byteCount).map { _ in UInt8.random(in: .min ... .max, using: &generator) }
        return try! EthereumAddress(data: Data(bytes), allowZero: false)
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        try self.init(hex: container.decode(String.self))
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(hex)
    }
}

public struct Bytes32: Hashable, Sendable, Codable, CustomStringConvertible {
    public static let byteCount = 32
    private let storage: Data

    public init(data: Data) throws {
        guard data.count == Self.byteCount else {
            throw FixedBytesError.wrongLength(expected: Self.byteCount, actual: data.count)
        }
        storage = data
    }

    public init(hex: String) throws {
        try self.init(data: Data(hex: hex))
    }

    public var data: Data { storage }
    public var hex: String { storage.hexString }
    public var description: String { hex }

    public static var zero: Bytes32 {
        try! Bytes32(data: Data(repeating: 0, count: byteCount))
    }

    public static func random() -> Bytes32 {
        var generator = SystemRandomNumberGenerator()
        let bytes = (0..<byteCount).map { _ in UInt8.random(in: .min ... .max, using: &generator) }
        return try! Bytes32(data: Data(bytes))
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        try self.init(hex: container.decode(String.self))
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(hex)
    }
}

/// A generic, non-secret 20-byte invoice namespace defined by the protocol. Applications may
/// impose a stricter identity policy; the shipped terminal app always wraps its operator EOA.
public struct TerminalIdentifier: Hashable, Sendable, Codable, CustomStringConvertible {
    public let address: EthereumAddress

    public init(address: EthereumAddress) {
        self.address = address
    }

    public static func random() -> TerminalIdentifier {
        TerminalIdentifier(address: .random())
    }

    public var description: String { address.hex }
}
