import Foundation
import OPKTerminalCore

struct EthereumRecoverableSignature: Hashable, Sendable {
    let r: Data
    let s: Data
    let yParity: UInt8

    init(r: Data, s: Data, yParity: UInt8) throws {
        guard r.count == 32, s.count == 32, yParity <= 1 else {
            throw SettlementOperatorError.invalidReceipt("invalid recoverable signature")
        }
        self.r = r
        self.s = s
        self.yParity = yParity
    }
}

struct EIP1559Transaction: Hashable, Sendable {
    let chainID: UInt64
    let nonce: UInt64
    let maxPriorityFeePerGas: UInt64
    let maxFeePerGas: UInt64
    let gasLimit: UInt64
    let destination: EthereumAddress
    let value: UInt256
    let data: Data

    init(
        chainID: UInt64,
        nonce: UInt64,
        maxPriorityFeePerGas: UInt64,
        maxFeePerGas: UInt64,
        gasLimit: UInt64,
        destination: EthereumAddress,
        value: UInt256 = .zero,
        data: Data
    ) {
        self.chainID = chainID
        self.nonce = nonce
        self.maxPriorityFeePerGas = maxPriorityFeePerGas
        self.maxFeePerGas = maxFeePerGas
        self.gasLimit = gasLimit
        self.destination = destination
        self.value = value
        self.data = data
    }

    var signingPayload: Data {
        Data([0x02]) + RLP.encode(.list(unsignedFields))
    }

    var signingDigest: Bytes32 {
        Keccak256.hash(signingPayload)
    }

    func serialized(with signature: EthereumRecoverableSignature) -> Data {
        let signedFields = unsignedFields + [
            .bytes(RLP.integer(UInt64(signature.yParity))),
            .bytes(RLP.trimmedInteger(signature.r)),
            .bytes(RLP.trimmedInteger(signature.s)),
        ]
        return Data([0x02]) + RLP.encode(.list(signedFields))
    }

    static func validatePersistedSweep(
        rawTransaction: Data,
        intent: SettlementIntent,
        nonce: UInt64
    ) throws {
        guard rawTransaction.first == 0x02 else {
            throw SettlementOperatorError.tamperedPreparation
        }
        let decoded = try RLP.decode(Data(rawTransaction.dropFirst()))
        guard case let .list(fields) = decoded, fields.count == 12,
              try RLP.unsignedInteger(fields[0]) == intent.chainID,
              try RLP.unsignedInteger(fields[1]) == nonce,
              try RLP.unsignedInteger(fields[2]) <= RLP.unsignedInteger(fields[3]),
              try RLP.unsignedInteger(fields[4]) > 0,
              try RLP.bytes(fields[5]) == intent.vault.data,
              try RLP.unsignedInteger(fields[6]) == 0,
              try RLP.bytes(fields[7]) == SettlementABI.encodeSweepSessions(intent),
              case let .list(accessList) = fields[8], accessList.isEmpty,
              try RLP.unsignedInteger(fields[9]) <= 1,
              (1...32).contains(try RLP.bytes(fields[10]).count),
              (1...32).contains(try RLP.bytes(fields[11]).count)
        else { throw SettlementOperatorError.tamperedPreparation }
    }

    private var unsignedFields: [RLP.Item] {
        [
            .bytes(RLP.integer(chainID)),
            .bytes(RLP.integer(nonce)),
            .bytes(RLP.integer(maxPriorityFeePerGas)),
            .bytes(RLP.integer(maxFeePerGas)),
            .bytes(RLP.integer(gasLimit)),
            .bytes(destination.data),
            .bytes(RLP.trimmedInteger(value.bigEndianData)),
            .bytes(data),
            .list([]),
        ]
    }
}

enum RLP {
    indirect enum Item: Hashable, Sendable {
        case bytes(Data)
        case list([Item])
    }

    static func encode(_ item: Item) -> Data {
        switch item {
        case let .bytes(bytes):
            encodeBytes(bytes)
        case let .list(items):
            encodeList(items)
        }
    }

    static func decode(_ data: Data) throws -> Item {
        let bytes = [UInt8](data)
        var offset = 0
        let item = try decode(bytes, offset: &offset, limit: bytes.count)
        guard offset == bytes.count else { throw SettlementOperatorError.tamperedPreparation }
        return item
    }

    static func bytes(_ item: Item) throws -> Data {
        guard case let .bytes(value) = item else {
            throw SettlementOperatorError.tamperedPreparation
        }
        return value
    }

    static func unsignedInteger(_ item: Item) throws -> UInt64 {
        let value = try bytes(item)
        guard value.count <= 8,
              value.isEmpty || value.first != 0
        else { throw SettlementOperatorError.tamperedPreparation }
        return value.reduce(0) { ($0 << 8) | UInt64($1) }
    }

    static func integer(_ value: UInt64) -> Data {
        guard value != 0 else { return Data() }
        var bigEndian = value.bigEndian
        return withUnsafeBytes(of: &bigEndian) { trimmedInteger(Data($0)) }
    }

    static func trimmedInteger(_ bytes: Data) -> Data {
        guard let firstNonzero = bytes.firstIndex(where: { $0 != 0 }) else { return Data() }
        return Data(bytes[firstNonzero...])
    }

    private static func encodeBytes(_ bytes: Data) -> Data {
        if bytes.count == 1, let byte = bytes.first, byte < 0x80 {
            return bytes
        }
        return encodedLength(bytes.count, shortBase: 0x80, longBase: 0xb7) + bytes
    }

    private static func encodeList(_ items: [Item]) -> Data {
        let payload = items.reduce(into: Data()) { result, item in
            result.append(encode(item))
        }
        return encodedLength(payload.count, shortBase: 0xc0, longBase: 0xf7) + payload
    }

    private static func encodedLength(_ length: Int, shortBase: UInt8, longBase: UInt8) -> Data {
        if length <= 55 {
            return Data([shortBase + UInt8(length)])
        }
        let lengthBytes = integer(UInt64(length))
        return Data([longBase + UInt8(lengthBytes.count)]) + lengthBytes
    }

    private static func decode(
        _ source: [UInt8],
        offset: inout Int,
        limit: Int
    ) throws -> Item {
        guard offset < limit else { throw SettlementOperatorError.tamperedPreparation }
        let prefix = source[offset]
        offset += 1
        switch prefix {
        case 0x00...0x7f:
            return .bytes(Data([prefix]))
        case 0x80...0xb7:
            let length = Int(prefix - 0x80)
            let value = try take(source, offset: &offset, count: length, limit: limit)
            guard !(length == 1 && value[0] < 0x80) else {
                throw SettlementOperatorError.tamperedPreparation
            }
            return .bytes(Data(value))
        case 0xb8...0xbf:
            let length = try decodeLongLength(
                source,
                offset: &offset,
                byteCount: Int(prefix - 0xb7),
                limit: limit
            )
            guard length >= 56 else { throw SettlementOperatorError.tamperedPreparation }
            return .bytes(Data(try take(source, offset: &offset, count: length, limit: limit)))
        case 0xc0...0xf7:
            return try decodeList(
                source,
                offset: &offset,
                payloadLength: Int(prefix - 0xc0),
                limit: limit
            )
        default:
            let length = try decodeLongLength(
                source,
                offset: &offset,
                byteCount: Int(prefix - 0xf7),
                limit: limit
            )
            guard length >= 56 else { throw SettlementOperatorError.tamperedPreparation }
            return try decodeList(source, offset: &offset, payloadLength: length, limit: limit)
        }
    }

    private static func decodeList(
        _ source: [UInt8],
        offset: inout Int,
        payloadLength: Int,
        limit: Int
    ) throws -> Item {
        guard payloadLength <= limit - offset else {
            throw SettlementOperatorError.tamperedPreparation
        }
        let end = offset + payloadLength
        var items = [Item]()
        while offset < end {
            items.append(try decode(source, offset: &offset, limit: end))
        }
        guard offset == end else { throw SettlementOperatorError.tamperedPreparation }
        return .list(items)
    }

    private static func decodeLongLength(
        _ source: [UInt8],
        offset: inout Int,
        byteCount: Int,
        limit: Int
    ) throws -> Int {
        guard byteCount > 0, byteCount <= 8 else {
            throw SettlementOperatorError.tamperedPreparation
        }
        let bytes = try take(source, offset: &offset, count: byteCount, limit: limit)
        guard bytes.first != 0 else { throw SettlementOperatorError.tamperedPreparation }
        let length = bytes.reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        guard length <= UInt64(Int.max) else { throw SettlementOperatorError.tamperedPreparation }
        return Int(length)
    }

    private static func take(
        _ source: [UInt8],
        offset: inout Int,
        count: Int,
        limit: Int
    ) throws -> ArraySlice<UInt8> {
        guard count >= 0, count <= limit - offset else {
            throw SettlementOperatorError.tamperedPreparation
        }
        let result = source[offset..<(offset + count)]
        offset += count
        return result
    }
}
