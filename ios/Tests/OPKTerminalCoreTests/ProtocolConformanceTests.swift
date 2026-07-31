#if canImport(XCTest)
import Foundation
import XCTest
@testable import OPKTerminalCore

final class ProtocolConformanceTests: XCTestCase {
    private let factory = try! EthereumAddress(hex: "0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f")
    private let implementation = try! EthereumAddress(hex: "0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18")
    private let vectorVault = try! EthereumAddress(hex: "0x1111111111111111111111111111111111111111")
    private let invoiceID = try! Bytes32(hex: "0xd5ab0fb2beaa1c3d789ae8a50b9429257b7f830830c8c4e23177a0fb2e116c77")

    func testSupportedProtocolVersions() {
        XCTAssertNil(OPKProtocolVersion(rawValue: "1.4.1"))
        XCTAssertEqual(OPKProtocolVersion(rawValue: "1.5"), .v1_5)
        XCTAssertEqual(OPKProtocolVersion(rawValue: "1.6"), .v1_6)
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
        XCTAssertEqual(salt.hex, "0x8b43abe81bab80f024d08540d6ffed9dab76ebd2f0096a53671e7c9aa94462ab")
        XCTAssertEqual(
            Keccak256.hash(initCode).hex,
            "0xd237f12377830073f2b667364b744f01cc0f00724e949159e2665134248ca4ad"
        )
        XCTAssertEqual(receiver.hex, "0xd7bb9c5f5a337b9d9ebcd65e1f840f782985291d")

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
        XCTAssertEqual(receiver.hex, "0xbbd352de4428d535ac79849abefa8d69bb51c671")

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
            "ethereum:0x7ffba642bc902880a737cb1c18a4e9540879e211@84532/transfer?address=0xbbd352de4428d535ac79849abefa8d69bb51c671&uint256=12340000000000000000"
        )
        XCTAssertEqual(try ERC681TransferRequest.parse(uri, expectedChainID: 84_532).canonicalString, uri)
        XCTAssertThrowsError(try ERC681TransferRequest.parse(uri, expectedChainID: 1))

        let nativeURI = try ERC681TransferRequest(
            token: NativeAsset.address,
            chainID: 84_532,
            recipient: receiver,
            amount: rawAmount
        ).canonicalString
        XCTAssertEqual(
            nativeURI,
            "ethereum:0xbbd352de4428d535ac79849abefa8d69bb51c671@84532?value=12340000000000000000"
        )
        XCTAssertFalse(nativeURI.lowercased().contains(NativeAsset.address.hex))
        XCTAssertEqual(
            try ERC681TransferRequest.parse(nativeURI, expectedChainID: 84_532).token,
            NativeAsset.address
        )
    }

    func testReadOnlyABISelectorsMatchFoundry() throws {
        XCTAssertEqual(ABI.balanceOfSelector.hexString, "0x70a08231")
        XCTAssertEqual(ABI.nativeAssetSelector.hexString, "0xbf53253b")
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
            protocolVersion: .v1_6,
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
