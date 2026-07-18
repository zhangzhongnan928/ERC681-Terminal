import Foundation

public enum HexError: Error, Equatable, Sendable {
    case missingPrefix
    case oddLength
    case invalidCharacter(Character)
}

extension Data {
    public init(hex: String, requirePrefix: Bool = true) throws {
        var source = hex
        if source.hasPrefix("0x") || source.hasPrefix("0X") {
            source.removeFirst(2)
        } else if requirePrefix {
            throw HexError.missingPrefix
        }

        guard source.count.isMultiple(of: 2) else {
            throw HexError.oddLength
        }

        var bytes = [UInt8]()
        bytes.reserveCapacity(source.count / 2)
        var index = source.startIndex
        while index < source.endIndex {
            let next = source.index(index, offsetBy: 2)
            let pair = source[index..<next]
            guard let byte = UInt8(pair, radix: 16) else {
                let invalid = pair.first { !$0.isHexDigit } ?? pair.first!
                throw HexError.invalidCharacter(invalid)
            }
            bytes.append(byte)
            index = next
        }
        self.init(bytes)
    }

    public var hexString: String {
        "0x" + map { String(format: "%02x", $0) }.joined()
    }

    package func leftPadded(to count: Int) -> Data {
        precondition(self.count <= count)
        return Data(repeating: 0, count: count - self.count) + self
    }
}

extension Character {
    fileprivate var isHexDigit: Bool {
        switch self {
        case "0"..."9", "a"..."f", "A"..."F": true
        default: false
        }
    }
}
