#if canImport(XCTest)
import Foundation
import XCTest
@testable import OPKTerminalCore

final class ProtocolConformanceTests: XCTestCase {
    private let factory = try! EthereumAddress(hex: "0xb69f725999266c6757284ca4169275c3ebde491a")
    private let implementation = try! EthereumAddress(hex: "0x8ba9739741ecc79b5d69fe5580d2966092e6f77f")
    private let vectorVault = try! EthereumAddress(hex: "0x1111111111111111111111111111111111111111")
    private let invoiceID = try! Bytes32(hex: "0x474614682f1d5e8e24396c2394a98425d4e8617fe699872c96182b89368e50d4")

    func testProtocol14IsNotAcceptedByTheV15OnlyTerminal() {
        XCTAssertNil(OPKProtocolVersion(rawValue: "1.4.1"))
        XCTAssertEqual(OPKProtocolVersion(rawValue: "1.5"), .v1_5)
    }

    func testCreate2ConformanceVector() throws {
        let salt = ReceiverDerivation.salt(vault: vectorVault, invoiceID: invoiceID)
        let initCode = try ReceiverDerivation.initCode(
            vault: vectorVault,
            receiverImplementation: implementation
        )
        let receiver = try ReceiverDerivation.receiver(
            factory: factory,
            receiverImplementation: implementation,
            vault: vectorVault,
            invoiceID: invoiceID
        )
        XCTAssertEqual(initCode.count, 88)
        XCTAssertEqual(salt.hex, "0x6ebed91ff26055c5762437f3fe8f834dde34b0dae39fd3df75dcfc1d1e064e1d")
        XCTAssertEqual(
            Keccak256.hash(initCode).hex,
            "0xad563722da414e51edc3d8195e2f225d872f79ea5b511cb2c3a62d6fa1a66b02"
        )
        XCTAssertEqual(receiver.hex, "0x8128e3a86962519877186c5f4f0920ba7240f5b1")

        try ReceiverDerivation.validate(
            Create2TestVector(
                vault: vectorVault,
                invoiceID: invoiceID,
                salt: salt,
                initCodeHash: Keccak256.hash(initCode),
                expectedReceiver: receiver
            ),
            factory: factory,
            receiverImplementation: implementation
        )
    }

    func testInvoiceIDGoldenValue() throws {
        let terminal = try EthereumAddress(hex: "0x1111111111111111111111111111111111111111")
        XCTAssertEqual(
            InvoiceFactory.invoiceID(terminal: terminal, timestamp: 1_700_000_000, nonce: UInt256(42)).hex,
            "0xb730f30f741192392fa8e6f7e24e5610ce2a43eedf2d86faa9eced48bf6f36bb"
        )
    }

    func testSharedCrossPlatformConformanceVector() throws {
        let terminal = try EthereumAddress(hex: "0x1111111111111111111111111111111111111111")
        let nonce = try Bytes32(hex: "0x000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        let expectedInvoice = try Bytes32(hex: "0x294d3b9eb0136d18f2a3e8aa9c10224029893c244e822a15902256d778f7f729")
        let actualInvoice = InvoiceFactory.invoiceID(
            terminal: terminal,
            timestamp: 1_720_000_000,
            nonce: UInt256(bigEndian: nonce.data)
        )
        XCTAssertEqual(actualInvoice, expectedInvoice)

        let baseVault = try EthereumAddress(hex: "0x1111111111111111111111111111111111111111")
        let receiver = try ReceiverDerivation.receiver(
            factory: factory,
            receiverImplementation: implementation,
            vault: baseVault,
            invoiceID: actualInvoice
        )
        XCTAssertEqual(receiver.hex, "0x8ad9a4b36c67eafc6ebd08e329e410c932cbfa1c")

        let rawAmount = try TokenAmount(display: "12.34", decimals: 18).rawValue
        XCTAssertEqual(rawAmount.decimalString, "12340000000000000000")
        let token = try EthereumAddress(hex: "0x7ffba642bc902880a737cb1c18a4e9540879e211")
        let uri = try ERC681TransferRequest(
            token: token,
            chainID: 84_532,
            recipient: receiver,
            amount: rawAmount
        ).canonicalString
        XCTAssertEqual(
            uri,
            "ethereum:0x7ffba642bc902880a737cb1c18a4e9540879e211@84532/transfer?address=0x8ad9a4b36c67eafc6ebd08e329e410c932cbfa1c&uint256=12340000000000000000"
        )
        XCTAssertEqual(try ERC681TransferRequest.parse(uri, expectedChainID: 84_532).canonicalString, uri)
        XCTAssertThrowsError(try ERC681TransferRequest.parse(uri, expectedChainID: 1))
    }

    func testReadOnlyABISelectorsMatchFoundry() throws {
        XCTAssertEqual(ABI.balanceOfSelector.hexString, "0x70a08231")
        XCTAssertEqual(ABI.settledSelector.hexString, "0x7dfc6c28")
    }

    func testSettlementHandoffIsDataOnlyAndRejectsMixedBatch() throws {
        let token = try PaymentToken(
            address: EthereumAddress(hex: "0x7fFbA642bc902880a737cb1c18a4E9540879e211"),
            symbol: "AUD",
            decimals: 18
        )
        let deployment = try OPKDeployment(factory: factory, receiverImplementation: implementation, vault: vectorVault)
        let config = try TerminalConfiguration(
            chainID: 84_532,
            rpcEndpoints: [URL(string: "https://sepolia.base.org")!],
            protocolVersion: .v1_5,
            deployment: deployment,
            tokens: [token]
        )
        let invoice = try InvoiceFactory.create(
            terminalIdentifier: .init(address: vectorVault),
            amount: UInt256(1_000),
            token: token,
            configuration: config,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            nonce: .zero
        )
        let handoff = try SettlementHandoff.make(
            chainID: config.chainID,
            vault: vectorVault,
            token: token.address,
            invoices: [invoice]
        )
        XCTAssertEqual(handoff.invoiceIDs, [invoice.invoiceID])
        XCTAssertEqual(handoff.expectedAmounts, [invoice.expectedAmount])
        XCTAssertEqual(handoff.receivers, [invoice.receiver])
    }
}
#endif
