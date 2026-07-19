#if canImport(XCTest)
import XCTest
@testable import OPKTerminalCore

final class EthereumAddressQRPayloadParserTests: XCTestCase {
    private let target = "0x7fFbA642bc902880a737cb1c18a4E9540879e211"
    private let recipient = "0x546896359eB84798a301dB60c98872E76F66cb58"

    func testParsesRawAddressAndNormalizesHex() throws {
        let parsed = try EthereumAddressQRPayloadParser.parse(target)
        XCTAssertEqual(parsed.hex, target.lowercased())
    }

    func testParsesAddressOnlyEthereumURI() throws {
        XCTAssertEqual(
            try EthereumAddressQRPayloadParser.parse("ETHEREUM:\(target)").hex,
            target.lowercased()
        )
        XCTAssertEqual(
            try EthereumAddressQRPayloadParser.parse("ethereum://\(target)").hex,
            target.lowercased()
        )
    }

    func testRejectsCanonicalConformancePaymentURI() {
        let paymentURI = "ethereum:\(target.lowercased())@84532/transfer?address=\(recipient.lowercased())&uint256=1000000"
        XCTAssertThrowsError(try EthereumAddressQRPayloadParser.parse(paymentURI))
    }

    func testRejectsUnsafeOrMalformedPayloads() {
        let zero = "0x0000000000000000000000000000000000000000"
        let malformedPayloads = [
            "",
            " \(target)",
            "\(target)\n",
            "bitcoin:\(target)",
            "https://example.com/\(target)",
            "wc:1234@2?relay-protocol=irn",
            "{\"address\":\"\(target)\"}",
            "0x0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "ethereum:///\(target)",
            "ethereum:%30x7ffba642bc902880a737cb1c18a4e9540879e211",
            "ethereum:\(target)@84532",
            "ethereum:\(target)/transfer",
            "ethereum:\(target)?value=1",
            "ethereum:\(target)#fragment",
            zero,
            "ethereum:\(zero)",
        ]

        for payload in malformedPayloads {
            XCTAssertThrowsError(
                try EthereumAddressQRPayloadParser.parse(payload),
                "Expected payload to be rejected: \(payload)"
            )
        }
    }
}
#endif
