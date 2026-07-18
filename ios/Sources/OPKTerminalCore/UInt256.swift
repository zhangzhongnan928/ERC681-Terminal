import Foundation

public enum UInt256Error: Error, Equatable, Sendable {
    case empty
    case invalidDecimal
    case invalidHex
    case overflow
    case underflow
}

/// A dependency-free unsigned 256-bit integer. Words are stored little-endian.
public struct UInt256: Hashable, Sendable, Comparable, Codable, CustomStringConvertible {
    private var w0: UInt64
    private var w1: UInt64
    private var w2: UInt64
    private var w3: UInt64

    public static let zero = UInt256(0)
    public static let max = UInt256(w0: .max, w1: .max, w2: .max, w3: .max)

    public init(_ value: UInt64) {
        w0 = value
        w1 = 0
        w2 = 0
        w3 = 0
    }

    private init(w0: UInt64, w1: UInt64, w2: UInt64, w3: UInt64) {
        self.w0 = w0
        self.w1 = w1
        self.w2 = w2
        self.w3 = w3
    }

    public init(decimalString: String) throws {
        guard !decimalString.isEmpty else { throw UInt256Error.empty }
        guard decimalString.allSatisfy({ $0 >= "0" && $0 <= "9" }) else {
            throw UInt256Error.invalidDecimal
        }

        var value = UInt256.zero
        for scalar in decimalString.utf8 {
            let digit = UInt64(scalar - Character("0").asciiValue!)
            let (multiplied, multiplyOverflow) = value.multipliedReportingOverflow(by: 10)
            guard !multiplyOverflow else { throw UInt256Error.overflow }
            let (added, addOverflow) = multiplied.addingReportingOverflow(UInt256(digit))
            guard !addOverflow else { throw UInt256Error.overflow }
            value = added
        }
        self = value
    }

    public init(hex: String) throws {
        let data: Data
        do {
            data = try Data(hex: hex)
        } catch {
            throw UInt256Error.invalidHex
        }
        guard data.count <= 32 else { throw UInt256Error.overflow }
        self.init(bigEndian: data)
    }

    public init(bigEndian: Data) {
        precondition(bigEndian.count <= 32)
        let padded = [UInt8](bigEndian.leftPadded(to: 32))
        func word(at offset: Int) -> UInt64 {
            padded[offset..<(offset + 8)].reduce(0) { ($0 << 8) | UInt64($1) }
        }
        w3 = word(at: 0)
        w2 = word(at: 8)
        w1 = word(at: 16)
        w0 = word(at: 24)
    }

    public var isZero: Bool { w0 == 0 && w1 == 0 && w2 == 0 && w3 == 0 }
    public var uint64Value: UInt64? {
        w1 == 0 && w2 == 0 && w3 == 0 ? w0 : nil
    }
    public var description: String { decimalString }

    public var bigEndianData: Data {
        var result = Data()
        for word in [w3, w2, w1, w0] {
            var big = word.bigEndian
            withUnsafeBytes(of: &big) { result.append(contentsOf: $0) }
        }
        return result
    }

    public var hex: String { bigEndianData.hexString }

    public var decimalString: String {
        guard !isZero else { return "0" }
        var value = self
        var digits = [UInt8]()
        while !value.isZero {
            let (quotient, remainder) = value.divided(by: 10)
            digits.append(UInt8(remainder) + 48)
            value = quotient
        }
        return String(decoding: digits.reversed(), as: UTF8.self)
    }

    public static func < (lhs: UInt256, rhs: UInt256) -> Bool {
        if lhs.w3 != rhs.w3 { return lhs.w3 < rhs.w3 }
        if lhs.w2 != rhs.w2 { return lhs.w2 < rhs.w2 }
        if lhs.w1 != rhs.w1 { return lhs.w1 < rhs.w1 }
        return lhs.w0 < rhs.w0
    }

    public func addingReportingOverflow(_ other: UInt256) -> (partialValue: UInt256, overflow: Bool) {
        let (r0, o0) = w0.addingReportingOverflow(other.w0)
        let (r1a, o1a) = w1.addingReportingOverflow(other.w1)
        let (r1, o1b) = r1a.addingReportingOverflow(o0 ? 1 : 0)
        let carry1 = o1a || o1b
        let (r2a, o2a) = w2.addingReportingOverflow(other.w2)
        let (r2, o2b) = r2a.addingReportingOverflow(carry1 ? 1 : 0)
        let carry2 = o2a || o2b
        let (r3a, o3a) = w3.addingReportingOverflow(other.w3)
        let (r3, o3b) = r3a.addingReportingOverflow(carry2 ? 1 : 0)
        return (UInt256(w0: r0, w1: r1, w2: r2, w3: r3), o3a || o3b)
    }

    public func subtractingReportingOverflow(_ other: UInt256) -> (partialValue: UInt256, overflow: Bool) {
        let (r0, o0) = w0.subtractingReportingOverflow(other.w0)
        let (r1a, o1a) = w1.subtractingReportingOverflow(other.w1)
        let (r1, o1b) = r1a.subtractingReportingOverflow(o0 ? 1 : 0)
        let borrow1 = o1a || o1b
        let (r2a, o2a) = w2.subtractingReportingOverflow(other.w2)
        let (r2, o2b) = r2a.subtractingReportingOverflow(borrow1 ? 1 : 0)
        let borrow2 = o2a || o2b
        let (r3a, o3a) = w3.subtractingReportingOverflow(other.w3)
        let (r3, o3b) = r3a.subtractingReportingOverflow(borrow2 ? 1 : 0)
        return (UInt256(w0: r0, w1: r1, w2: r2, w3: r3), o3a || o3b)
    }

    private func multipliedReportingOverflow(by multiplier: UInt64) -> (UInt256, Bool) {
        let words = [w0, w1, w2, w3]
        var result = [UInt64](repeating: 0, count: 4)
        var carry: UInt64 = 0
        var overflow = false
        for index in 0..<4 {
            let product = words[index].multipliedFullWidth(by: multiplier)
            let (low, carryOverflow) = product.low.addingReportingOverflow(carry)
            result[index] = low
            let (nextCarry, highOverflow) = product.high.addingReportingOverflow(carryOverflow ? 1 : 0)
            carry = nextCarry
            overflow = overflow || highOverflow
        }
        overflow = overflow || carry != 0
        return (UInt256(w0: result[0], w1: result[1], w2: result[2], w3: result[3]), overflow)
    }

    private func divided(by divisor: UInt64) -> (UInt256, UInt64) {
        precondition(divisor > 0)
        var quotient = [UInt64](repeating: 0, count: 4)
        var remainder: UInt64 = 0
        let words = [w3, w2, w1, w0]
        for (index, word) in words.enumerated() {
            let result = divisor.dividingFullWidth((high: remainder, low: word))
            quotient[3 - index] = result.quotient
            remainder = result.remainder
        }
        return (UInt256(w0: quotient[0], w1: quotient[1], w2: quotient[2], w3: quotient[3]), remainder)
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        try self.init(decimalString: container.decode(String.self))
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(decimalString)
    }
}
