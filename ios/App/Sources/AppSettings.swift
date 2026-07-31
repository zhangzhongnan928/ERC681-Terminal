import Foundation
import OPKTerminalCore

private let maximumAdjustableConfirmationBlocks: UInt64 = 64
private let maximumStoredConfirmationBlocks = UInt64(Int64.max)

/// Persisted configuration for one merchant-selectable currency route. Its identity includes the
/// chain, vault, and token; display symbols are metadata and never identify a route.
struct AppPaymentProfile: Codable, Equatable, Identifiable {
    var rpcURL: String
    var chainID: String
    var protocolVersion: String
    var factory: String
    var receiverImplementation: String
    var vault: String
    var tokenAddress: String
    var tokenSymbol: String
    var tokenDecimals: String
    var confirmationBlocks: String
    var provisionedOperatorAddress: String?

    init(
        rpcURL: String = "https://sepolia.base.org",
        chainID: String = "84532",
        protocolVersion: String = OPKProtocolVersion.v1_6.rawValue,
        factory: String = "0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f",
        receiverImplementation: String = "0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18",
        vault: String = "0x1111111111111111111111111111111111111111",
        tokenAddress: String = "0x7ffba642bc902880a737cb1c18a4e9540879e211",
        tokenSymbol: String = "AUD",
        tokenDecimals: String = "18",
        confirmationBlocks: String = "1",
        provisionedOperatorAddress: String? = nil
    ) {
        self.rpcURL = rpcURL
        self.chainID = chainID
        self.protocolVersion = protocolVersion
        self.factory = factory
        self.receiverImplementation = receiverImplementation
        self.vault = vault
        self.tokenAddress = tokenAddress
        self.tokenSymbol = tokenSymbol
        self.tokenDecimals = tokenDecimals
        self.confirmationBlocks = confirmationBlocks
        self.provisionedOperatorAddress = provisionedOperatorAddress
    }

    init(
        configuration: TerminalConfiguration,
        token: PaymentToken,
        operatorAddress: EthereumAddress
    ) throws {
        guard configuration.tokens.contains(token),
              let endpoint = configuration.rpcEndpoints.first,
              configuration.chainID <= UInt64(Int64.max),
              ABI.isSafeTokenSymbol(token.symbol)
        else { throw AppSettingsError.invalidValue }
        rpcURL = endpoint.absoluteString
        chainID = String(configuration.chainID)
        protocolVersion = configuration.protocolVersion.rawValue
        factory = configuration.deployment.factory.hex
        receiverImplementation = configuration.deployment.receiverImplementation.hex
        vault = configuration.deployment.vault.hex
        tokenAddress = token.address.hex
        tokenSymbol = token.symbol
        tokenDecimals = String(token.decimals)
        confirmationBlocks = String(configuration.confirmationPolicy.requiredBlocks)
        provisionedOperatorAddress = operatorAddress.hex
    }

    var id: String {
        guard let canonicalChainID = UInt64(chainID),
              let canonicalVault = try? EthereumAddress(hex: vault, allowZero: false),
              let canonicalToken = try? EthereumAddress(hex: tokenAddress, allowZero: false)
        else {
            return "eip155:\(chainID):\(vault.lowercased()):\(tokenAddress.lowercased())"
        }
        return TerminalPaymentProfileIdentifier(
            chainID: canonicalChainID,
            vault: canonicalVault,
            token: canonicalToken
        ).rawValue
    }

    var networkName: String {
        guard let chain = UInt64(chainID),
              let known = TerminalKnownChainProfile.profile(for: chain)
        else { return "Chain \(chainID)" }
        return known.networkName
    }

    var isTestnet: Bool {
        guard let chain = UInt64(chainID),
              let known = TerminalKnownChainProfile.profile(for: chain)
        else { return false }
        return known.isTestnet
    }

    var nativeCurrencySymbol: String {
        knownChainProfile?.nativeCurrencySymbol ?? "native currency"
    }

    var nativeCurrencyDecimals: UInt8 {
        knownChainProfile?.nativeCurrencyDecimals ?? 18
    }

    var minimumOperatorNativeReserve: UInt256? {
        knownChainProfile?.minimumOperatorNativeReserve
    }

    private var knownChainProfile: TerminalKnownChainProfile? {
        guard let chain = UInt64(chainID) else { return nil }
        return TerminalKnownChainProfile.profile(for: chain)
    }

    var displayName: String { "\(tokenSymbol) · \(networkName)" }

    var detail: String {
        let asset = (try? EthereumAddress(hex: tokenAddress, allowZero: false))
            .map { NativeAsset.isNative($0) ? tokenSymbol : Self.abbreviated(tokenAddress) }
            ?? Self.abbreviated(tokenAddress)
        return "Vault \(Self.abbreviated(vault)) · Asset \(asset)"
    }

    func configuration() throws -> TerminalConfiguration {
        guard let chainID = UInt64(chainID), chainID > 0,
              chainID <= UInt64(Int64.max),
              let endpoint = URL(string: rpcURL),
              let version = OPKProtocolVersion(rawValue: protocolVersion),
              let decimals = UInt8(tokenDecimals),
              let blocks = UInt64(confirmationBlocks), blocks > 0,
              blocks <= maximumStoredConfirmationBlocks
        else { throw AppSettingsError.invalidValue }
        guard let profile = TerminalKnownChainProfile.profile(for: chainID) else {
            throw AppSettingsError.unsupportedChain
        }
        guard blocks >= profile.minimumConfirmationBlocks else {
            throw AppSettingsError.invalidValue
        }
        guard let configuredFactory = try? EthereumAddress(hex: factory, allowZero: false),
              configuredFactory == profile.factory,
              let configuredImplementation = try? EthereumAddress(
                  hex: receiverImplementation,
                  allowZero: false
              ),
              configuredImplementation == profile.receiverImplementation
        else { throw AppSettingsError.pinMismatch }
        guard ABI.isSafeTokenSymbol(tokenSymbol) else {
            throw AppSettingsError.invalidValue
        }

        let token = try PaymentToken(
            address: EthereumAddress(hex: tokenAddress, allowZero: false),
            symbol: tokenSymbol,
            decimals: decimals
        )
        guard version == profile.protocolVersion(for: token.address) else {
            throw AppSettingsError.unsupportedProtocol
        }
        if token.isNativeAsset {
            guard token.symbol == profile.nativeCurrencySymbol,
                  token.decimals == profile.nativeCurrencyDecimals,
                  token.decimals == NativeAsset.decimals
            else { throw AppSettingsError.invalidValue }
        }
        return try TerminalConfiguration(
            chainID: chainID,
            rpcEndpoints: [endpoint],
            protocolVersion: version,
            deployment: OPKDeployment(
                factory: profile.factory,
                receiverImplementation: profile.receiverImplementation,
                vault: EthereumAddress(hex: vault, allowZero: false)
            ),
            tokens: [token],
            confirmationPolicy: .init(requiredBlocks: blocks),
            create2TestVector: profile.create2TestVector
        )
    }

    var validationFingerprint: String {
        [
            rpcURL,
            chainID,
            protocolVersion,
            factory.lowercased(),
            receiverImplementation.lowercased(),
            vault.lowercased(),
            tokenAddress.lowercased(),
            tokenSymbol,
            tokenDecimals,
            confirmationBlocks,
            provisionedOperatorAddress?.lowercased() ?? "unprovisioned",
        ].joined(separator: "|")
    }

    private static func abbreviated(_ value: String) -> String {
        guard value.count > 14 else { return value }
        return "\(value.prefix(8))…\(value.suffix(4))"
    }

    private enum CodingKeys: String, CodingKey {
        case rpcURL
        case chainID
        case protocolVersion
        case factory
        case receiverImplementation
        case vault
        case tokenAddress
        case tokenSymbol
        case tokenDecimals
        case confirmationBlocks
        case provisionedOperatorAddress
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        rpcURL = try container.decode(String.self, forKey: .rpcURL)
        chainID = try container.decode(String.self, forKey: .chainID)
        protocolVersion = try container.decode(String.self, forKey: .protocolVersion)
        factory = try container.decode(String.self, forKey: .factory)
        receiverImplementation = try container.decode(String.self, forKey: .receiverImplementation)
        vault = try container.decode(String.self, forKey: .vault)
        tokenAddress = try container.decode(String.self, forKey: .tokenAddress)
        tokenSymbol = try container.decode(String.self, forKey: .tokenSymbol)
        tokenDecimals = try container.decode(String.self, forKey: .tokenDecimals)
        // Empty is a migration sentinel. AppSettings replaces it with the legacy catalog-wide
        // value (v2) or this chain's compiled default before validating the catalog.
        confirmationBlocks = try container.decodeIfPresent(
            String.self,
            forKey: .confirmationBlocks
        ) ?? ""
        provisionedOperatorAddress = try container.decodeIfPresent(
            String.self,
            forKey: .provisionedOperatorAddress
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(rpcURL, forKey: .rpcURL)
        try container.encode(chainID, forKey: .chainID)
        try container.encode(protocolVersion, forKey: .protocolVersion)
        try container.encode(factory, forKey: .factory)
        try container.encode(receiverImplementation, forKey: .receiverImplementation)
        try container.encode(vault, forKey: .vault)
        try container.encode(tokenAddress, forKey: .tokenAddress)
        try container.encode(tokenSymbol, forKey: .tokenSymbol)
        try container.encode(tokenDecimals, forKey: .tokenDecimals)
        try container.encode(confirmationBlocks, forKey: .confirmationBlocks)
        try container.encodeIfPresent(
            provisionedOperatorAddress,
            forKey: .provisionedOperatorAddress
        )
    }
}

/// Non-secret metadata retained until the app tells the merchant that a legacy safety policy was
/// raised to the strongest stored network policy or the current compiled network minimum.
struct AppSettingsMigrationNotice: Codable, Equatable {
    let adjustedConfirmationProfileIDs: [String]
}

struct AppSettings: Codable, Equatable {
    private static let schemaVersion = 3
    static let maximumPaymentProfileCount = TerminalPaymentProfileCatalog.maximumProfileCount
    static let adjustableConfirmationBlockRange = 1...Int(maximumAdjustableConfirmationBlocks)

    private(set) var paymentProfiles: [AppPaymentProfile]
    var selectedPaymentProfileID: String?
    private var fallbackProfile: AppPaymentProfile
    private(set) var migrationNotice: AppSettingsMigrationNotice?

    init() {
        paymentProfiles = []
        selectedPaymentProfileID = nil
        fallbackProfile = AppPaymentProfile()
        migrationNotice = nil
    }

    var selectedPaymentProfile: AppPaymentProfile? {
        guard let selectedPaymentProfileID else { return nil }
        return paymentProfiles.first { $0.id == selectedPaymentProfileID }
    }

    var displayedPaymentProfile: AppPaymentProfile {
        selectedPaymentProfile ?? fallbackProfile
    }

    /// Read-only compatibility facade for consumers that display the selected network policy.
    /// Mutations must use `updatingConfirmationBlocks(for:to:)` so every route on the network
    /// remains aligned.
    var confirmationBlocks: String {
        displayedPaymentProfile.confirmationBlocks
    }

    var isProvisioned: Bool {
        selectedPaymentProfile?.provisionedOperatorAddress != nil
    }

    var rpcURL: String {
        get { displayedPaymentProfile.rpcURL }
        set { mutateDisplayedProfile { $0.rpcURL = newValue } }
    }

    var chainID: String {
        get { displayedPaymentProfile.chainID }
        set { mutateDisplayedProfile { $0.chainID = newValue } }
    }

    var protocolVersion: String {
        get { displayedPaymentProfile.protocolVersion }
        set { mutateDisplayedProfile { $0.protocolVersion = newValue } }
    }

    var factory: String {
        get { displayedPaymentProfile.factory }
        set { mutateDisplayedProfile { $0.factory = newValue } }
    }

    var receiverImplementation: String {
        get { displayedPaymentProfile.receiverImplementation }
        set { mutateDisplayedProfile { $0.receiverImplementation = newValue } }
    }

    var vault: String {
        get { displayedPaymentProfile.vault }
        set { mutateDisplayedProfile { $0.vault = newValue } }
    }

    var tokenAddress: String {
        get { displayedPaymentProfile.tokenAddress }
        set { mutateDisplayedProfile { $0.tokenAddress = newValue } }
    }

    var tokenSymbol: String {
        get { displayedPaymentProfile.tokenSymbol }
        set { mutateDisplayedProfile { $0.tokenSymbol = newValue } }
    }

    var tokenDecimals: String {
        get { displayedPaymentProfile.tokenDecimals }
        set { mutateDisplayedProfile { $0.tokenDecimals = newValue } }
    }

    var provisionedOperatorAddress: String? {
        get { displayedPaymentProfile.provisionedOperatorAddress }
        set {
            if selectedPaymentProfile == nil, newValue != nil {
                fallbackProfile.provisionedOperatorAddress = newValue
                paymentProfiles = [fallbackProfile]
                selectedPaymentProfileID = fallbackProfile.id
            } else {
                mutateDisplayedProfile { $0.provisionedOperatorAddress = newValue }
            }
        }
    }

    func configuration() throws -> TerminalConfiguration {
        try displayedPaymentProfile.configuration()
    }

    func configuration(for profileID: String) throws -> TerminalConfiguration {
        guard let profile = paymentProfiles.first(where: { $0.id == profileID }) else {
            throw AppSettingsError.profileNotFound
        }
        return try profile.configuration()
    }

    func configurations() throws -> [TerminalConfiguration] {
        try paymentProfiles.map { try $0.configuration() }
    }

    func selectingPaymentProfile(id: String) throws -> AppSettings {
        guard paymentProfiles.contains(where: { $0.id == id }) else {
            throw AppSettingsError.profileNotFound
        }
        var candidate = self
        candidate.selectedPaymentProfileID = id
        if let selected = candidate.selectedPaymentProfile {
            candidate.fallbackProfile = selected
        }
        return candidate
    }

    func removingPaymentProfile(id: String) throws -> AppSettings {
        guard paymentProfiles.contains(where: { $0.id == id }) else {
            throw AppSettingsError.profileNotFound
        }
        var candidate = self
        candidate.paymentProfiles.removeAll { $0.id == id }
        if candidate.selectedPaymentProfileID == id {
            candidate.selectedPaymentProfileID = candidate.paymentProfiles.first?.id
        }
        if let selected = candidate.selectedPaymentProfile {
            candidate.fallbackProfile = selected
        } else {
            candidate.fallbackProfile = AppPaymentProfile()
        }
        candidate.retainMigrationNotice(
            forProfileIDs: Set(candidate.paymentProfiles.map(\.id))
        )
        return candidate
    }

    /// Applies one merchant-selected finality policy to every payment route on a network.
    /// Existing invoices retain the confirmation policy captured in their immutable snapshot.
    func updatingConfirmationBlocks(
        for chainID: UInt64,
        to requiredBlocks: UInt64
    ) throws -> AppSettings {
        guard let known = TerminalKnownChainProfile.profile(for: chainID) else {
            throw AppSettingsError.unsupportedChain
        }
        guard requiredBlocks >= known.minimumConfirmationBlocks,
              requiredBlocks <= maximumAdjustableConfirmationBlocks
        else { throw AppSettingsError.invalidValue }

        var candidate = self
        let matchingIndices = candidate.paymentProfiles.indices.filter {
            UInt64(candidate.paymentProfiles[$0].chainID) == chainID
        }
        guard !matchingIndices.isEmpty else { throw AppSettingsError.profileNotFound }

        for index in matchingIndices {
            candidate.paymentProfiles[index].confirmationBlocks = String(requiredBlocks)
            _ = try candidate.paymentProfiles[index].configuration()
        }
        if let selected = candidate.selectedPaymentProfile {
            candidate.fallbackProfile = selected
        }
        return candidate
    }

    func applying(
        _ configuration: TerminalConfiguration,
        boundTo operatorAddress: EthereumAddress
    ) throws -> AppSettings {
        guard let profile = TerminalKnownChainProfile.profile(for: configuration.chainID),
              configuration.tokens.count == 1,
              let token = configuration.tokens.first,
              configuration.protocolVersion == profile.protocolVersion(for: token.address),
              configuration.deployment.factory == profile.factory,
              configuration.deployment.receiverImplementation == profile.receiverImplementation,
              configuration.create2TestVector == profile.create2TestVector,
              (!token.isNativeAsset
                  || (
                      token.symbol == profile.nativeCurrencySymbol
                          && token.decimals == profile.nativeCurrencyDecimals
                          && token.decimals == NativeAsset.decimals
                  )),
              configuration.confirmationPolicy.requiredBlocks == profile.defaultConfirmationBlocks,
              (paymentProfiles.allSatisfy {
                  $0.provisionedOperatorAddress?.lowercased() == operatorAddress.hex.lowercased()
              })
        else { throw AppSettingsError.invalidValue }

        var appliedProfile = try AppPaymentProfile(
            configuration: configuration,
            token: token,
            operatorAddress: operatorAddress
        )
        // Constructing the profile again verifies all stored trust pins and display metadata.
        _ = try appliedProfile.configuration()

        var candidate = self
        let existingNetworkConfirmations = try candidate.paymentProfiles
            .filter { UInt64($0.chainID) == configuration.chainID }
            .map { try $0.configuration().confirmationPolicy.requiredBlocks }
        let networkConfirmationBlocks = Self.confirmationBlocksForProvisioning(
            existing: existingNetworkConfirmations,
            compiledDefault: profile.defaultConfirmationBlocks
        )
        appliedProfile.confirmationBlocks = String(networkConfirmationBlocks)
        _ = try appliedProfile.configuration()

        if let index = candidate.paymentProfiles.firstIndex(where: { $0.id == appliedProfile.id }) {
            candidate.paymentProfiles[index] = appliedProfile
        } else {
            guard candidate.paymentProfiles.count < Self.maximumPaymentProfileCount else {
                throw AppSettingsError.profileLimitExceeded
            }
            candidate.paymentProfiles.append(appliedProfile)
        }
        for index in candidate.paymentProfiles.indices
            where UInt64(candidate.paymentProfiles[index].chainID) == configuration.chainID {
            candidate.paymentProfiles[index].confirmationBlocks = String(networkConfirmationBlocks)
        }
        candidate.selectedPaymentProfileID = appliedProfile.id
        candidate.fallbackProfile = appliedProfile
        return candidate
    }

    static func confirmationBlocksForProvisioning(
        existing: [UInt64],
        compiledDefault: UInt64
    ) -> UInt64 {
        existing.max() ?? compiledDefault
    }

    func clearingProvisioning() -> AppSettings {
        var candidate = self
        candidate.paymentProfiles.removeAll()
        candidate.selectedPaymentProfileID = nil
        candidate.fallbackProfile.provisionedOperatorAddress = nil
        candidate.migrationNotice = nil
        return candidate
    }

    mutating func acknowledgeMigrationNotice() {
        migrationNotice = nil
    }

    /// Migration acknowledgement is presentation metadata, not payment-routing configuration.
    /// AppModel uses this comparison to keep already validated readiness intact when only that
    /// notice changes.
    func hasSamePaymentConfiguration(as other: AppSettings) -> Bool {
        var lhs = self
        var rhs = other
        lhs.migrationNotice = nil
        rhs.migrationNotice = nil
        return lhs == rhs
    }

    func operatorFundingPayload(for operatorAddress: EthereumAddress) -> String? {
        guard let profile = selectedPaymentProfile,
              (try? profile.configuration()) != nil,
              let boundValue = profile.provisionedOperatorAddress,
              let boundAddress = try? EthereumAddress(hex: boundValue, allowZero: false),
              boundAddress == operatorAddress,
              let chainID = UInt64(profile.chainID),
              chainID > 0,
              chainID <= UInt64(Int64.max)
        else { return nil }
        return "ethereum:\(operatorAddress.hex)@\(chainID)"
    }

    func rpcOverride(for chainID: UInt64) -> URL? {
        guard let trusted = TerminalKnownChainProfile.profile(for: chainID) else { return nil }
        let matchingProfiles = paymentProfiles.filter { $0.chainID == String(chainID) }
        let selectedFirst = matchingProfiles.sorted {
            ($0.id == selectedPaymentProfileID ? 0 : 1)
                < ($1.id == selectedPaymentProfileID ? 0 : 1)
        }
        return selectedFirst.lazy.compactMap { profile -> URL? in
            guard let endpoint = URL(string: profile.rpcURL),
                  endpoint != trusted.rpcEndpoint,
                  (try? RPCURLPolicy.validate(endpoint)) != nil
            else { return nil }
            return endpoint
        }.first
    }

    var validationFingerprint: String {
        displayedPaymentProfile.validationFingerprint
    }

    private mutating func mutateDisplayedProfile(
        _ mutation: (inout AppPaymentProfile) -> Void
    ) {
        guard let currentID = selectedPaymentProfileID,
              let index = paymentProfiles.firstIndex(where: { $0.id == currentID })
        else {
            mutation(&fallbackProfile)
            return
        }
        mutation(&paymentProfiles[index])
        selectedPaymentProfileID = paymentProfiles[index].id
        fallbackProfile = paymentProfiles[index]
    }

    private enum CodingKeys: String, CodingKey {
        case schemaVersion
        case paymentProfiles
        case selectedPaymentProfileID
        case confirmationBlocks
        case fallbackProfile
        case migrationNotice

        // v1 flat settings retained for migration and downgrade visibility.
        case rpcURL
        case chainID
        case protocolVersion
        case factory
        case receiverImplementation
        case vault
        case tokenAddress
        case tokenSymbol
        case tokenDecimals
        case provisionedOperatorAddress
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let storedSchemaVersion = try container.decodeIfPresent(Int.self, forKey: .schemaVersion)
        let isLegacySchema = (storedSchemaVersion ?? 1) < Self.schemaVersion
        let legacyConfirmationBlocks = try container.decodeIfPresent(
            String.self,
            forKey: .confirmationBlocks
        )

        if container.contains(.paymentProfiles) {
            paymentProfiles = try container.decode(
                [AppPaymentProfile].self,
                forKey: .paymentProfiles
            )
            selectedPaymentProfileID = try container.decodeIfPresent(
                String.self,
                forKey: .selectedPaymentProfileID
            )
            fallbackProfile = try container.decodeIfPresent(
                AppPaymentProfile.self,
                forKey: .fallbackProfile
            ) ?? paymentProfiles.first ?? AppPaymentProfile()
        } else {
            let defaults = AppPaymentProfile()
            fallbackProfile = AppPaymentProfile(
                rpcURL: try container.decodeIfPresent(String.self, forKey: .rpcURL)
                    ?? defaults.rpcURL,
                chainID: try container.decodeIfPresent(String.self, forKey: .chainID)
                    ?? defaults.chainID,
                protocolVersion: try container.decodeIfPresent(
                    String.self,
                    forKey: .protocolVersion
                ) ?? defaults.protocolVersion,
                factory: try container.decodeIfPresent(String.self, forKey: .factory)
                    ?? defaults.factory,
                receiverImplementation: try container.decodeIfPresent(
                    String.self,
                    forKey: .receiverImplementation
                ) ?? defaults.receiverImplementation,
                vault: try container.decodeIfPresent(String.self, forKey: .vault)
                    ?? defaults.vault,
                tokenAddress: try container.decodeIfPresent(
                    String.self,
                    forKey: .tokenAddress
                ) ?? defaults.tokenAddress,
                tokenSymbol: try container.decodeIfPresent(String.self, forKey: .tokenSymbol)
                    ?? defaults.tokenSymbol,
                tokenDecimals: try container.decodeIfPresent(
                    String.self,
                    forKey: .tokenDecimals
                ) ?? defaults.tokenDecimals,
                confirmationBlocks: legacyConfirmationBlocks
                    ?? defaults.confirmationBlocks,
                provisionedOperatorAddress: try container.decodeIfPresent(
                    String.self,
                    forKey: .provisionedOperatorAddress
                )
            )
            if fallbackProfile.provisionedOperatorAddress != nil {
                paymentProfiles = [fallbackProfile]
                selectedPaymentProfileID = fallbackProfile.id
            } else {
                paymentProfiles = []
                selectedPaymentProfileID = nil
            }
        }

        for index in paymentProfiles.indices where paymentProfiles[index].confirmationBlocks.isEmpty {
            paymentProfiles[index].confirmationBlocks = legacyConfirmationBlocks
                ?? Self.defaultConfirmationBlocks(for: paymentProfiles[index])
        }
        if fallbackProfile.confirmationBlocks.isEmpty {
            fallbackProfile.confirmationBlocks = legacyConfirmationBlocks
                ?? Self.defaultConfirmationBlocks(for: fallbackProfile)
        }

        if isLegacySchema {
            var adjustedProfileIDs = Set<String>()
            for index in paymentProfiles.indices {
                if Self.raiseLegacyConfirmationFloor(for: &paymentProfiles[index]) {
                    adjustedProfileIDs.insert(paymentProfiles[index].id)
                }
            }
            _ = Self.raiseLegacyConfirmationFloor(for: &fallbackProfile)
            migrationNotice = adjustedProfileIDs.isEmpty
                ? nil
                : AppSettingsMigrationNotice(
                    adjustedConfirmationProfileIDs: adjustedProfileIDs.sorted()
                )
        } else {
            migrationNotice = try container.decodeIfPresent(
                AppSettingsMigrationNotice.self,
                forKey: .migrationNotice
            )
        }
        let networkAlignedProfileIDs = Self.normalizeConfirmationPoliciesByChain(
            in: &paymentProfiles
        )
        let noticeProfileIDs = Set(
            migrationNotice?.adjustedConfirmationProfileIDs ?? []
        ).union(networkAlignedProfileIDs)
        migrationNotice = noticeProfileIDs.isEmpty
            ? nil
            : AppSettingsMigrationNotice(
                adjustedConfirmationProfileIDs: noticeProfileIDs.sorted()
            )

        let identifiers = paymentProfiles.map(\.id)
        guard paymentProfiles.count <= Self.maximumPaymentProfileCount,
              Set(identifiers).count == identifiers.count
        else {
            throw DecodingError.dataCorruptedError(
                forKey: .paymentProfiles,
                in: container,
                debugDescription: "Duplicate terminal payment profile identity"
            )
        }
        if paymentProfiles.isEmpty {
            selectedPaymentProfileID = nil
        } else if selectedPaymentProfileID == nil {
            guard isLegacySchema else {
                throw DecodingError.dataCorruptedError(
                    forKey: .selectedPaymentProfileID,
                    in: container,
                    debugDescription: "Current terminal payment catalog is missing its selection"
                )
            }
            selectedPaymentProfileID = paymentProfiles[0].id
        } else if selectedPaymentProfile == nil {
            throw DecodingError.dataCorruptedError(
                forKey: .selectedPaymentProfileID,
                in: container,
                debugDescription: "Selected terminal payment profile is missing"
            )
        }
        if let selected = selectedPaymentProfile {
            fallbackProfile = selected
        }

        do {
            let boundOperators = try paymentProfiles.map { profile -> EthereumAddress in
                _ = try profile.configuration()
                guard let value = profile.provisionedOperatorAddress else {
                    throw AppSettingsError.invalidValue
                }
                return try EthereumAddress(hex: value, allowZero: false)
            }
            guard Set(boundOperators.map { $0.hex.lowercased() }).count <= 1 else {
                throw AppSettingsError.invalidValue
            }
        } catch {
            throw DecodingError.dataCorruptedError(
                forKey: .paymentProfiles,
                in: container,
                debugDescription: "Stored payment profiles do not match trusted network pins"
            )
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(Self.schemaVersion, forKey: .schemaVersion)
        try container.encode(paymentProfiles, forKey: .paymentProfiles)
        try container.encodeIfPresent(
            selectedPaymentProfileID,
            forKey: .selectedPaymentProfileID
        )
        // Retain the selected profile's value for downgrade visibility. v3 readers ignore this
        // catalog-wide compatibility key whenever a profile carries its own value.
        try container.encode(confirmationBlocks, forKey: .confirmationBlocks)
        try container.encode(fallbackProfile, forKey: .fallbackProfile)
        try container.encodeIfPresent(migrationNotice, forKey: .migrationNotice)

        // Keep the selected route visible to older builds while v2 remains forward-migratable.
        let legacy = displayedPaymentProfile
        try container.encode(legacy.rpcURL, forKey: .rpcURL)
        try container.encode(legacy.chainID, forKey: .chainID)
        try container.encode(legacy.protocolVersion, forKey: .protocolVersion)
        try container.encode(legacy.factory, forKey: .factory)
        try container.encode(legacy.receiverImplementation, forKey: .receiverImplementation)
        try container.encode(legacy.vault, forKey: .vault)
        try container.encode(legacy.tokenAddress, forKey: .tokenAddress)
        try container.encode(legacy.tokenSymbol, forKey: .tokenSymbol)
        try container.encode(legacy.tokenDecimals, forKey: .tokenDecimals)
        try container.encodeIfPresent(
            legacy.provisionedOperatorAddress,
            forKey: .provisionedOperatorAddress
        )
    }

    private static func defaultConfirmationBlocks(for profile: AppPaymentProfile) -> String {
        guard let chainID = UInt64(profile.chainID),
              let known = TerminalKnownChainProfile.profile(for: chainID)
        else { return "1" }
        return String(known.defaultConfirmationBlocks)
    }

    private mutating func retainMigrationNotice(forProfileIDs retainedIDs: Set<String>) {
        guard let migrationNotice else { return }
        let retained = migrationNotice.adjustedConfirmationProfileIDs
            .filter(retainedIDs.contains)
        self.migrationNotice = retained.isEmpty
            ? nil
            : AppSettingsMigrationNotice(adjustedConfirmationProfileIDs: retained)
    }

    /// Older catalogs could persist route-specific values on the same chain. A network policy
    /// must be deterministic, so retain the strongest stored value and never weaken finality.
    private static func normalizeConfirmationPoliciesByChain(
        in profiles: inout [AppPaymentProfile]
    ) -> Set<String> {
        var maximumByChain = [UInt64: UInt64]()
        var invalidChains = Set<UInt64>()
        var adjustedProfileIDs = Set<String>()
        for profile in profiles {
            guard let chainID = UInt64(profile.chainID),
                  let known = TerminalKnownChainProfile.profile(for: chainID)
            else { continue }
            guard let requiredBlocks = UInt64(profile.confirmationBlocks),
                  requiredBlocks >= known.minimumConfirmationBlocks,
                  requiredBlocks <= maximumStoredConfirmationBlocks
            else {
                invalidChains.insert(chainID)
                continue
            }
            maximumByChain[chainID] = max(
                maximumByChain[chainID] ?? requiredBlocks,
                requiredBlocks
            )
        }
        for index in profiles.indices {
            guard let chainID = UInt64(profiles[index].chainID),
                  !invalidChains.contains(chainID),
                  let requiredBlocks = maximumByChain[chainID]
            else { continue }
            if profiles[index].confirmationBlocks != String(requiredBlocks) {
                adjustedProfileIDs.insert(profiles[index].id)
            }
            profiles[index].confirmationBlocks = String(requiredBlocks)
        }
        return adjustedProfileIDs
    }

    /// Returns true only when a previously valid positive legacy value was raised to the floor.
    /// Zero, malformed, unknown-chain, and current-schema values remain fail-closed.
    private static func raiseLegacyConfirmationFloor(
        for profile: inout AppPaymentProfile
    ) -> Bool {
        guard let chainID = UInt64(profile.chainID),
              let known = TerminalKnownChainProfile.profile(for: chainID),
              let current = UInt64(profile.confirmationBlocks),
              current > 0,
              current < known.minimumConfirmationBlocks
        else { return false }
        profile.confirmationBlocks = String(known.minimumConfirmationBlocks)
        return true
    }
}

enum AppSettingsError: LocalizedError {
    case invalidValue
    case unsupportedChain
    case unsupportedProtocol
    case pinMismatch
    case profileNotFound
    case profileLimitExceeded

    var errorDescription: String? {
        switch self {
        case .invalidValue: "One or more settings are invalid."
        case .unsupportedChain: "This EVM chain does not have a trusted terminal deployment profile."
        case .unsupportedProtocol: "The selected network uses an unsupported terminal protocol deployment."
        case .pinMismatch: "The saved deployment pins do not match the trusted network profile."
        case .profileNotFound: "The selected payment profile is no longer configured on this terminal."
        case .profileLimitExceeded:
            "This terminal already has the maximum of \(AppSettings.maximumPaymentProfileCount) payment profiles."
        }
    }
}

struct AppSettingsLoadResult {
    let settings: AppSettings
    let recoveryRequired: Bool
}

enum AppPreferences {
    private static let settingsKey = "opk.app.settings.v1"
    private static let quarantineKey = "opk.app.settings.quarantine.v1"

    static func loadSettings() -> AppSettings {
        loadSettingsResult().settings
    }

    static func loadSettingsResult() -> AppSettingsLoadResult {
        guard let data = UserDefaults.standard.data(forKey: settingsKey) else {
            return AppSettingsLoadResult(settings: AppSettings(), recoveryRequired: false)
        }
        guard let value = try? JSONDecoder().decode(AppSettings.self, from: data) else {
            // Keep the authoritative bytes in place. AppModel blocks all incidental persistence
            // until the device admin explicitly moves this blob into quarantine.
            return AppSettingsLoadResult(settings: AppSettings(), recoveryRequired: true)
        }
        // Make the normalized v3 value durable immediately. The notice remains pending until the
        // presentation layer explicitly acknowledges it after informing the merchant.
        if value.migrationNotice != nil {
            _ = saveSettings(value)
        }
        return AppSettingsLoadResult(settings: value, recoveryRequired: false)
    }

    /// Retains unreadable settings before clearing the active slot. Quarantine is append-only and
    /// deduplicated so a crash between the two UserDefaults operations cannot destroy evidence.
    static func quarantineUnreadableSettings() -> Bool {
        let defaults = UserDefaults.standard
        guard let data = defaults.data(forKey: settingsKey),
              (try? JSONDecoder().decode(AppSettings.self, from: data)) == nil
        else { return false }

        var quarantined = defaults.array(forKey: quarantineKey) as? [Data] ?? []
        if !quarantined.contains(data) {
            quarantined.append(data)
            defaults.set(quarantined, forKey: quarantineKey)
        }
        guard (defaults.array(forKey: quarantineKey) as? [Data])?.contains(data) == true else {
            return false
        }
        defaults.removeObject(forKey: settingsKey)
        return defaults.data(forKey: settingsKey) == nil
    }

    @discardableResult
    static func saveSettings(_ settings: AppSettings) -> Bool {
        guard let data = try? JSONEncoder().encode(settings) else { return false }
        UserDefaults.standard.set(data, forKey: settingsKey)
        return true
    }

#if DEBUG
    static func resetForUITesting() {
        UserDefaults.standard.removeObject(forKey: settingsKey)
    }
#endif
}
