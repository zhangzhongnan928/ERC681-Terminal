#if canImport(XCTest)
import Foundation
import XCTest
@testable import OPKTerminalCore

final class KeccakAndPrimitiveTests: XCTestCase {
    func testEthereumKeccakVectors() throws {
        XCTAssertEqual(
            Keccak256.hash(Data()).hex,
            "0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"
        )
        XCTAssertEqual(
            Keccak256.hash(utf8: "abc").hex,
            "0x4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45"
        )
        XCTAssertEqual(ABI.selector("transfer(address,uint256)").hexString, "0xa9059cbb")
    }

    func testAddressAndBytes32Validation() throws {
        let address = try EthereumAddress(hex: "0x1111111111111111111111111111111111111111")
        XCTAssertEqual(address.data.count, 20)
        XCTAssertEqual(address.hex, "0x1111111111111111111111111111111111111111")
        XCTAssertThrowsError(try EthereumAddress(hex: "0x1234"))
        XCTAssertThrowsError(try EthereumAddress(hex: String(repeating: "0", count: 40), allowZero: false))

        let bytes = try Bytes32(hex: "0x" + String(repeating: "ab", count: 32))
        XCTAssertEqual(bytes.data.count, 32)
        XCTAssertThrowsError(try Bytes32(hex: "0x00"))
    }

    func testUInt256DecimalHexAndBounds() throws {
        let maximum = "115792089237316195423570985008687907853269984665640564039457584007913129639935"
        XCTAssertEqual(try UInt256(decimalString: maximum), .max)
        XCTAssertEqual(UInt256.max.decimalString, maximum)
        XCTAssertEqual(UInt256.max.hex, "0x" + String(repeating: "ff", count: 32))
        XCTAssertThrowsError(
            try UInt256(decimalString: "115792089237316195423570985008687907853269984665640564039457584007913129639936")
        )
        XCTAssertThrowsError(try UInt256(decimalString: "1e18"))
        XCTAssertThrowsError(try UInt256(decimalString: "-1"))
        XCTAssertEqual(try UInt256(hex: "0x2a"), UInt256(42))
    }

    func testUInt256CodableUsesCanonicalDecimal() throws {
        let value = try UInt256(decimalString: "1000000000000000001")
        let encoded = try JSONEncoder().encode(value)
        XCTAssertEqual(String(decoding: encoded, as: UTF8.self), "\"1000000000000000001\"")
        XCTAssertEqual(try JSONDecoder().decode(UInt256.self, from: encoded), value)
    }
}
#endif
