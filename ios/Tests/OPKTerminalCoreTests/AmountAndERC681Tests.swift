#if canImport(XCTest)
import Foundation
import XCTest
@testable import OPKTerminalCore

final class AmountAndERC681Tests: XCTestCase {
    func testStrictAmountConversion() throws {
        let amount = try TokenAmount(display: "12.3456", decimals: 6)
        XCTAssertEqual(amount.rawValue, UInt256(12_345_600))
        XCTAssertEqual(amount.displayString(), "12.3456")
        XCTAssertEqual(TokenAmount(rawValue: UInt256(1), decimals: 6).displayString(), "0.000001")
        XCTAssertEqual(TokenAmount(rawValue: UInt256(12_000_000), decimals: 6).displayString(), "12")
    }

    func testAmountRejectsLossyOrAmbiguousInput() {
        XCTAssertThrowsError(try TokenAmount(display: "1.234", decimals: 2))
        XCTAssertThrowsError(try TokenAmount(display: " 1", decimals: 2))
        XCTAssertThrowsError(try TokenAmount(display: ".5", decimals: 2))
        XCTAssertThrowsError(try TokenAmount(display: "1.", decimals: 2))
        XCTAssertThrowsError(try TokenAmount(display: "1e3", decimals: 2))
        XCTAssertThrowsError(try TokenAmount(display: "0", decimals: 2))
    }

    func testERC681CanonicalRoundTrip() throws {
        let token = try EthereumAddress(hex: "0x7fFbA642bc902880a737cb1c18a4E9540879e211")
        let receiver = try EthereumAddress(hex: "0x546896359eB84798a301dB60c98872E76F66cb58")
        let request = try ERC681TransferRequest(
            token: token,
            chainID: 84_532,
            recipient: receiver,
            amount: UInt256(1_000_000)
        )
        XCTAssertEqual(
            request.canonicalString,
            "ethereum:0x7ffba642bc902880a737cb1c18a4e9540879e211@84532/transfer?address=0x546896359eb84798a301db60c98872e76f66cb58&uint256=1000000"
        )
        XCTAssertEqual(try ERC681TransferRequest.parse(request.canonicalString), request)
    }

    func testERC681RejectsNativeValueWrongFunctionAndExtras() {
        let token = "0x1111111111111111111111111111111111111111"
        let receiver = "0x2222222222222222222222222222222222222222"
        XCTAssertThrowsError(try ERC681TransferRequest.parse(
            "ethereum:\(receiver)@1?value=1"
        ))
        XCTAssertThrowsError(try ERC681TransferRequest.parse(
            "ethereum:\(token)@1/approve?address=\(receiver)&uint256=1"
        ))
        XCTAssertThrowsError(try ERC681TransferRequest.parse(
            "ethereum:\(token)@1/transfer?address=\(receiver)&uint256=1&value=1"
        ))
        XCTAssertThrowsError(try ERC681TransferRequest.parse(
            "ethereum:\(token)@1/transfer?address=\(receiver)&uint256=01"
        ))
        XCTAssertThrowsError(try ERC681TransferRequest.parse(
            "ethereum:\(token)@1/transfer?address=\(receiver)&uint256=0"
        ))
        XCTAssertThrowsError(try ERC681TransferRequest.parse(
            "ethereum:\(token)@1/transfer?uint256=1&address=\(receiver)"
        ))
        XCTAssertThrowsError(try ERC681TransferRequest.parse(
            "ethereum:\(token)@1/transfer?address=\(receiver)&uint256=%31"
        ))
        XCTAssertThrowsError(try ERC681TransferRequest.parse(
            "ethereum:\(token)@01/transfer?address=\(receiver)&uint256=1"
        ))
    }

    func testRPCURLPolicyRejectsCredentialsAndInsecureRemoteHTTP() throws {
        XCTAssertNoThrow(try RPCURLPolicy.validate(URL(string: "https://rpc.example/path")!))
        XCTAssertNoThrow(try RPCURLPolicy.validate(URL(string: "http://localhost:8545")!))
        XCTAssertThrowsError(try RPCURLPolicy.validate(URL(string: "http://rpc.example")!))
        XCTAssertThrowsError(try RPCURLPolicy.validate(URL(string: "https://user:pass@rpc.example")!))
    }
}
#endif
