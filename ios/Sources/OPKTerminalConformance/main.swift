import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import OPKTerminalCore
import OPKTerminalRPC

private enum ConformanceFailure: Error, CustomStringConvertible {
    case failed(String)

    var description: String {
        switch self {
        case let .failed(message): "Conformance failure: \(message)"
        }
    }
}

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    guard condition() else { throw ConformanceFailure.failed(message) }
}

private func requireThrows(_ message: String, _ operation: () throws -> Void) throws {
    do {
        try operation()
        throw ConformanceFailure.failed(message)
    } catch is ConformanceFailure {
        throw ConformanceFailure.failed(message)
    } catch {
        return
    }
}

private struct ConformanceFixture: Decodable {
    let schemaVersion: Int
    let paymentVectorVersion: String
    let deploymentProtocolVersion: String
    let configuration: Configuration
    let invoiceVector: InvoiceVector
    let receiverVector: ReceiverVector
    let amountVector: AmountVector
    let readOnlyAbi: [String: String]
    let erc681: String
    let mustReject: [String]

    struct Configuration: Decodable {
        let chainId: UInt64
        let factory: String
        let receiverImplementation: String
        let vault: String
        let token: Token

        struct Token: Decodable {
            let address: String
            let symbol: String
            let decimals: UInt8
        }
    }

    struct InvoiceVector: Decodable {
        let terminalIdentifier: String
        let timestampSeconds: UInt64
        let nonce: String
        let abiEncoded: String
        let invoiceId: String
    }

    struct ReceiverVector: Decodable {
        let salt: String
        let initCode: String
        let initCodeBytes: Int
        let initCodeHash: String
        let receiver: String
    }

    struct AmountVector: Decodable {
        let display: String
        let rawUnits: String
    }
}

private actor FixtureTransport: RPCTransport {
    let chainID: UInt64
    let factory: EthereumAddress
    let implementation: EthereumAddress
    let vault: EthereumAddress
    let token: EthereumAddress
    let tokenDecimals: UInt8
    let balance: UInt256

    init(
        chainID: UInt64,
        factory: EthereumAddress,
        implementation: EthereumAddress,
        vault: EthereumAddress,
        token: EthereumAddress,
        tokenDecimals: UInt8,
        balance: UInt256
    ) {
        self.chainID = chainID
        self.factory = factory
        self.implementation = implementation
        self.vault = vault
        self.token = token
        self.tokenDecimals = tokenDecimals
        self.balance = balance
    }

    func send(_ request: URLRequest) async throws -> RPCTransportResponse {
        guard let body = request.httpBody,
              let object = try JSONSerialization.jsonObject(with: body) as? [String: Any],
              let id = object["id"] as? NSNumber,
              let method = object["method"] as? String
        else { throw ConformanceFailure.failed("malformed JSON-RPC request") }

        let result: String
        switch method {
        case "eth_chainId":
            result = "0x" + String(chainID, radix: 16)
        case "eth_blockNumber":
            result = "0x64"
        case "eth_getCode":
            result = "0x6001"
        case "eth_call":
            guard let params = object["params"] as? [Any],
                  let call = params.first as? [String: Any],
                  let toText = call["to"] as? String,
                  let dataText = call["data"] as? String,
                  let to = try? EthereumAddress(hex: toText),
                  let data = try? Data(hex: dataText)
            else { throw ConformanceFailure.failed("malformed eth_call") }
            let selector = Data(data.prefix(4))
            if to == factory && selector == ABI.implementationSelector {
                result = ABI.word(implementation).hexString
            } else if to == vault && selector == ABI.factorySelector {
                result = ABI.word(factory).hexString
            } else if to == vault && selector == ABI.isPaymentTokenSelector {
                result = ABI.word(UInt64(1)).hexString
            } else if to == token && selector == ABI.decimalsSelector {
                result = ABI.word(UInt64(tokenDecimals)).hexString
            } else if to == token && selector == ABI.balanceOfSelector {
                result = ABI.word(balance).hexString
            } else {
                throw ConformanceFailure.failed("unexpected read-only eth_call")
            }
        default:
            throw ConformanceFailure.failed("non-read-only RPC method \(method)")
        }

        let response: [String: Any] = [
            "jsonrpc": "2.0",
            "id": id,
            "result": result,
        ]
        return RPCTransportResponse(
            statusCode: 200,
            body: try JSONSerialization.data(withJSONObject: response)
        )
    }
}

@main
private struct OPKTerminalConformanceMain {
    static func main() async throws {
        let fixture = try loadFixture()
        try coreVectors(fixture)
        try await readOnlyRPCVector(fixture)
        print("OPKTerminalConformance: all checks passed")
    }

    private static func loadFixture() throws -> ConformanceFixture {
        let fileManager = FileManager.default
        let currentDirectory = URL(
            fileURLWithPath: fileManager.currentDirectoryPath,
            isDirectory: true
        )
        let sourceFile = URL(fileURLWithPath: #filePath)
        let repositoryRoot = sourceFile
            .deletingLastPathComponent() // OPKTerminalConformance
            .deletingLastPathComponent() // Sources
            .deletingLastPathComponent() // ios
            .deletingLastPathComponent() // repository root
        let candidates = [
            currentDirectory.appendingPathComponent("../conformance/opk-erc681-v1.json"),
            currentDirectory.appendingPathComponent("conformance/opk-erc681-v1.json"),
            repositoryRoot.appendingPathComponent("conformance/opk-erc681-v1.json"),
        ].map(\.standardizedFileURL)

        guard let fixtureURL = candidates.first(where: {
            fileManager.fileExists(atPath: $0.path)
        }) else {
            let searched = candidates.map(\.path).joined(separator: ", ")
            throw ConformanceFailure.failed("shared fixture is missing; searched: \(searched)")
        }

        do {
            return try JSONDecoder().decode(
                ConformanceFixture.self,
                from: Data(contentsOf: fixtureURL)
            )
        } catch {
            throw ConformanceFailure.failed(
                "could not decode shared fixture at \(fixtureURL.path): \(error)"
            )
        }
    }

    private static func coreVectors(_ fixture: ConformanceFixture) throws {
        try require(fixture.schemaVersion == 2, "fixture schema version")
        try require(fixture.paymentVectorVersion == "1.5", "payment vector version")
        try require(
            fixture.deploymentProtocolVersion == "1.4.1",
            "deployment protocol version"
        )
        try require(
            Keccak256.hash(Data()).hex ==
                "0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
            "Keccak-256 empty vector"
        )
        try require(ABI.selector("transfer(address,uint256)").hexString == "0xa9059cbb", "ABI selector")
        let abiVectors: [(String, Data)] = [
            ("implementation()", ABI.implementationSelector),
            ("factory()", ABI.factorySelector),
            ("isPaymentToken(address)", ABI.isPaymentTokenSelector),
            ("decimals()", ABI.decimalsSelector),
            ("balanceOf(address)", ABI.balanceOfSelector),
            ("computeReceiver(address,bytes32)", ABI.computeReceiverSelector),
        ]
        for (signature, actual) in abiVectors {
            try require(
                fixture.readOnlyAbi[signature] == actual.hexString,
                "shared ABI vector for \(signature)"
            )
        }
        let sweptSignature = "Swept(address,address,bytes32,address,uint256,uint256,uint256)"
        try require(
            fixture.readOnlyAbi[sweptSignature] == Keccak256.hash(utf8: sweptSignature).hex,
            "shared Swept event topic"
        )

        let tokenDecimals = fixture.configuration.token.decimals
        let amount = try TokenAmount(display: fixture.amountVector.display, decimals: tokenDecimals)
        try require(amount.rawValue.decimalString == fixture.amountVector.rawUnits, "strict amount conversion")
        try require(amount.displayString() == fixture.amountVector.display, "amount formatting")
        try requireThrows("excess fractional digits must fail") {
            _ = try TokenAmount(display: "1.0000001", decimals: 6)
        }

        let terminal = try EthereumAddress(hex: fixture.invoiceVector.terminalIdentifier)
        let nonce = try Bytes32(hex: fixture.invoiceVector.nonce)
        let encodedInvoice = ABI.encodeInvoiceID(
            terminal: terminal,
            timestamp: fixture.invoiceVector.timestampSeconds,
            nonce: UInt256(bigEndian: nonce.data)
        )
        try require(encodedInvoice.hexString == fixture.invoiceVector.abiEncoded, "shared invoice ABI encoding")
        let invoice = InvoiceFactory.invoiceID(
            terminal: terminal,
            timestamp: fixture.invoiceVector.timestampSeconds,
            nonce: UInt256(bigEndian: nonce.data)
        )
        try require(invoice.hex == fixture.invoiceVector.invoiceId, "shared invoice vector")

        let factory = try EthereumAddress(hex: fixture.configuration.factory)
        let implementation = try EthereumAddress(hex: fixture.configuration.receiverImplementation)
        let vault = try EthereumAddress(hex: fixture.configuration.vault)
        let salt = ReceiverDerivation.salt(vault: vault, invoiceID: invoice)
        try require(salt.hex == fixture.receiverVector.salt, "shared CREATE2 salt")
        let initCode = try ReceiverDerivation.initCode(
            vault: vault,
            receiverImplementation: implementation
        )
        try require(initCode.count == fixture.receiverVector.initCodeBytes, "shared init-code length")
        try require(initCode.hexString == fixture.receiverVector.initCode, "shared init code")
        try require(
            Keccak256.hash(initCode).hex == fixture.receiverVector.initCodeHash,
            "shared init-code hash"
        )
        let receiver = try ReceiverDerivation.receiver(
            factory: factory,
            receiverImplementation: implementation,
            vault: vault,
            invoiceID: invoice
        )
        try require(receiver.hex == fixture.receiverVector.receiver, "shared CREATE2 receiver vector")

        let token = try EthereumAddress(hex: fixture.configuration.token.address)
        let uri = try ERC681TransferRequest(
            token: token,
            chainID: fixture.configuration.chainId,
            recipient: receiver,
            amount: amount.rawValue
        ).canonicalString
        try require(uri == fixture.erc681, "shared canonical ERC-681 URI")
        let reparsed = try ERC681TransferRequest.parse(uri, expectedChainID: fixture.configuration.chainId)
        try require(reparsed.canonicalString == uri, "ERC-681 round trip")

        let additionalRejected = [
            "ethereum:0x7ffba642bc902880a737cb1c18a4e9540879e211@84532/transfer?uint256=1&address=0x9107decd2cb06c57c40a663648e19cde1d52f606",
            "ethereum:0x7ffba642bc902880a737cb1c18a4e9540879e211@84532/transfer?address=0x9107decd2cb06c57c40a663648e19cde1d52f606&foo=1&uint256=1",
            "ethereum:0x7ffba642bc902880a737cb1c18a4e9540879e211@84532/transfer?address=0x9107decd2cb06c57c40a663648e19cde1d52f606&uint256=%31",
        ]
        for invalid in fixture.mustReject + additionalRejected {
            try requireThrows("must reject \(invalid)") {
                _ = try ERC681TransferRequest.parse(invalid, expectedChainID: fixture.configuration.chainId)
            }
        }
        try requireThrows("wrong chain must fail closed") {
            _ = try ERC681TransferRequest.parse(uri, expectedChainID: 1)
        }

        try RPCURLPolicy.validate(URL(string: "http://127.0.0.1:8545")!)
        try requireThrows("non-loopback HTTP RPC must fail") {
            try RPCURLPolicy.validate(URL(string: "http://rpc.example")!)
        }
        try requireThrows("credential-bearing RPC URL must fail") {
            try RPCURLPolicy.validate(URL(string: "https://user:password@rpc.example")!)
        }
        try requireThrows("fragment-bearing RPC URL must fail") {
            try RPCURLPolicy.validate(URL(string: "https://rpc.example/#fragment")!)
        }
    }

    private static func readOnlyRPCVector(_ fixture: ConformanceFixture) async throws {
        let configurationVector = fixture.configuration
        let factory = try EthereumAddress(hex: configurationVector.factory)
        let implementation = try EthereumAddress(hex: configurationVector.receiverImplementation)
        let vault = try EthereumAddress(hex: configurationVector.vault)
        let tokenAddress = try EthereumAddress(hex: configurationVector.token.address)
        let amount = try UInt256(decimalString: fixture.amountVector.rawUnits)
        let transport = FixtureTransport(
            chainID: configurationVector.chainId,
            factory: factory,
            implementation: implementation,
            vault: vault,
            token: tokenAddress,
            tokenDecimals: configurationVector.token.decimals,
            balance: amount
        )
        let client = try JSONRPCEthereumClient(
            endpoint: URL(string: "https://rpc.example")!,
            transport: transport
        )
        let decodedChainID = try await client.chainID()
        try require(decodedChainID == configurationVector.chainId, "JSON-RPC quantity decoding")

        let token = try PaymentToken(
            address: tokenAddress,
            symbol: configurationVector.token.symbol,
            decimals: configurationVector.token.decimals
        )
        let configuration = try TerminalConfiguration(
            chainID: configurationVector.chainId,
            rpcEndpoints: [URL(string: "https://rpc.example")!],
            protocolVersion: .v1_4_1,
            deployment: OPKDeployment(
                factory: factory,
                receiverImplementation: implementation,
                vault: vault
            ),
            tokens: [token],
            confirmationPolicy: .init(requiredBlocks: 1)
        )
        let report = try await ConfigurationValidator(rpc: client).validate(configuration)
        try require(report.checks.count == 5, "read-only configuration validation")

        let request = try InvoiceFactory.create(
            terminalIdentifier: .init(address: try EthereumAddress(hex: fixture.invoiceVector.terminalIdentifier)),
            amount: amount,
            token: token,
            configuration: configuration,
            createdAt: Date(timeIntervalSince1970: TimeInterval(fixture.invoiceVector.timestampSeconds)),
            expiresAt: Date(timeIntervalSince1970: TimeInterval(fixture.invoiceVector.timestampSeconds + 60)),
            nonce: try Bytes32(hex: fixture.invoiceVector.nonce)
        )
        let monitor = PaymentMonitor(
            rpc: client,
            confirmationPolicy: .init(requiredBlocks: 1)
        )
        let observation = try await monitor.sample(request)
        try require(observation.status == .paid(received: amount), "mocked balance observation")
        try require(observation.blockNumber == 100, "explicit balance block")

        let partial = UInt256(1)
        let expired = monitor.classify(
            request,
            balance: partial,
            block: 101,
            now: Date(timeIntervalSince1970: TimeInterval(fixture.invoiceVector.timestampSeconds + 61))
        )
        try require(
            expired.status == .expired(lastObserved: partial),
            "underfunded invoice closes at expiry"
        )

        let expiredTransport = FixtureTransport(
            chainID: configurationVector.chainId,
            factory: factory,
            implementation: implementation,
            vault: vault,
            token: tokenAddress,
            tokenDecimals: configurationVector.token.decimals,
            balance: partial
        )
        let expiredClient = try JSONRPCEthereumClient(
            endpoint: URL(string: "https://rpc.example")!,
            transport: expiredTransport
        )
        let expiredMonitor = PaymentMonitor(
            rpc: expiredClient,
            confirmationPolicy: .init(requiredBlocks: 1),
            pollIntervalNanoseconds: 1
        )
        var streamed = [PaymentObservation]()
        for try await value in expiredMonitor.observations(for: request) {
            streamed.append(value)
        }
        try require(streamed.count == 1, "expired observation stream finishes")
        if let first = streamed.first {
            guard case .expired = first.status else {
                throw ConformanceFailure.failed("expired stream terminal status")
            }
        }
    }
}
