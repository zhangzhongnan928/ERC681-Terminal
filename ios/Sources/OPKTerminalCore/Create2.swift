import Foundation

public enum Create2Error: Error, Equatable, Sendable {
    case invalidInitCodeLength(Int)
    case vectorMismatch(expected: EthereumAddress, actual: EthereumAddress)
}

public struct OPKDeployment: Hashable, Sendable, Codable {
    public let factory: EthereumAddress
    public let receiverImplementation: EthereumAddress
    public let vault: EthereumAddress

    public init(
        factory: EthereumAddress,
        receiverImplementation: EthereumAddress,
        vault: EthereumAddress
    ) throws {
        guard !factory.isZero, !receiverImplementation.isZero, !vault.isZero else {
            throw FixedBytesError.zeroAddress
        }
        self.factory = factory
        self.receiverImplementation = receiverImplementation
        self.vault = vault
    }
}

public struct Create2TestVector: Hashable, Sendable, Codable {
    public let vault: EthereumAddress
    public let invoiceID: Bytes32
    public let salt: Bytes32
    public let initCodeHash: Bytes32
    public let expectedReceiver: EthereumAddress

    public init(
        vault: EthereumAddress,
        invoiceID: Bytes32,
        salt: Bytes32,
        initCodeHash: Bytes32,
        expectedReceiver: EthereumAddress
    ) {
        self.vault = vault
        self.invoiceID = invoiceID
        self.salt = salt
        self.initCodeHash = initCodeHash
        self.expectedReceiver = expectedReceiver
    }
}

public enum ReceiverDerivation {
    public static let creationPrefix = try! Data(hex: "0x604d80600b6000396000f3")
    public static let runtimePart1 = try! Data(hex: "0x363d3d373d3d3d363d73")
    public static let runtimePart2 = try! Data(hex: "0x5af43d82803e903d91602b57fd5bf3")

    public static func salt(vault: EthereumAddress, invoiceID: Bytes32) -> Bytes32 {
        Keccak256.hash(ABI.word(vault) + ABI.word(invoiceID))
    }

    public static func initCode(
        vault: EthereumAddress,
        receiverImplementation: EthereumAddress
    ) throws -> Data {
        let code = creationPrefix
            + runtimePart1
            + receiverImplementation.data
            + runtimePart2
            + ABI.word(vault)
        guard code.count == 88 else { throw Create2Error.invalidInitCodeLength(code.count) }
        return code
    }

    public static func receiver(
        factory: EthereumAddress,
        receiverImplementation: EthereumAddress,
        vault: EthereumAddress,
        invoiceID: Bytes32
    ) throws -> EthereumAddress {
        let salt = salt(vault: vault, invoiceID: invoiceID)
        let initHash = Keccak256.hash(try initCode(vault: vault, receiverImplementation: receiverImplementation))
        let preimage = Data([0xff]) + factory.data + salt.data + initHash.data
        return try EthereumAddress(data: Keccak256.hash(preimage).data.suffix(20), allowZero: false)
    }

    public static func validate(
        _ vector: Create2TestVector,
        factory: EthereumAddress,
        receiverImplementation: EthereumAddress
    ) throws {
        let actualSalt = salt(vault: vector.vault, invoiceID: vector.invoiceID)
        guard actualSalt == vector.salt else {
            let actual = try receiver(
                factory: factory,
                receiverImplementation: receiverImplementation,
                vault: vector.vault,
                invoiceID: vector.invoiceID
            )
            throw Create2Error.vectorMismatch(expected: vector.expectedReceiver, actual: actual)
        }
        let actualInitHash = Keccak256.hash(
            try initCode(vault: vector.vault, receiverImplementation: receiverImplementation)
        )
        guard actualInitHash == vector.initCodeHash else {
            let actual = try receiver(
                factory: factory,
                receiverImplementation: receiverImplementation,
                vault: vector.vault,
                invoiceID: vector.invoiceID
            )
            throw Create2Error.vectorMismatch(expected: vector.expectedReceiver, actual: actual)
        }
        let actual = try receiver(
            factory: factory,
            receiverImplementation: receiverImplementation,
            vault: vector.vault,
            invoiceID: vector.invoiceID
        )
        guard actual == vector.expectedReceiver else {
            throw Create2Error.vectorMismatch(expected: vector.expectedReceiver, actual: actual)
        }
    }
}
