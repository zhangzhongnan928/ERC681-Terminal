import XCTest
@testable import OPKTerminalApp
import OPKTerminalCore
@testable import OPKTerminalOperator
import OPKTerminalRPC
import SwiftData

final class ProvisioningAppTests: XCTestCase {
    func testCheckoutPresentationBlocksWhileProvisioningOrRefreshingReadiness() {
        XCTAssertEqual(
            CheckoutPresentationState.evaluate(
                isSubmitting: false,
                isProvisioning: true,
                isRefreshingReadiness: false,
                isBusy: false,
                readiness: .ready
            ),
            .checking(.provisioning)
        )
        XCTAssertEqual(
            CheckoutPresentationState.evaluate(
                isSubmitting: false,
                isProvisioning: false,
                isRefreshingReadiness: true,
                isBusy: false,
                readiness: .ready
            ),
            .checkout(.checking)
        )
        XCTAssertEqual(
            CheckoutPresentationState.evaluate(
                isSubmitting: false,
                isProvisioning: false,
                isRefreshingReadiness: true,
                isBusy: false,
                readiness: .validationRequired
            ),
            .checking(.readiness)
        )
    }

    func testCheckoutPresentationKeepsSubmissionAndActiveReadinessStatesDistinct() {
        XCTAssertEqual(
            CheckoutPresentationState.evaluate(
                isSubmitting: true,
                isProvisioning: false,
                isRefreshingReadiness: false,
                isBusy: true,
                readiness: .validationRequired
            ),
            .checkout(.preparing)
        )
        XCTAssertEqual(
            CheckoutPresentationState.evaluate(
                isSubmitting: true,
                isProvisioning: false,
                isRefreshingReadiness: true,
                isBusy: true,
                readiness: .validationRequired
            ),
            .checkout(.preparing)
        )
        XCTAssertEqual(
            CheckoutPresentationState.evaluate(
                isSubmitting: false,
                isProvisioning: false,
                isRefreshingReadiness: false,
                isBusy: false,
                readiness: .ready
            ),
            .checkout(.ready)
        )
        XCTAssertEqual(
            CheckoutPresentationState.evaluate(
                isSubmitting: false,
                isProvisioning: false,
                isRefreshingReadiness: false,
                isBusy: false,
                readiness: .authorizationRequired
            ),
            .blocked(.authorizationRequired)
        )
    }

    @MainActor
    func testReadinessRefreshGateSpansValidationAndOperatorStatusPhases() async throws {
        let savedSettings = AppPreferences.loadSettings()
        defer { AppPreferences.saveSettings(savedSettings) }
        let container = try ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let probe = BlockingReadinessRefreshProbe(
            status: OperatorChainStatus(
                chainID: 84_532,
                balance: TerminalKnownChainProfile.baseSepolia.minimumOperatorNativeReserve,
                isAuthorizedOperator: true,
                isVaultOwner: false,
                isLowGas: false
            )
        )
        let namespace = UUID().uuidString
        let model = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.tests.\(namespace)"
            ),
            operatorWalletLifecycle: BlockingOperatorWalletLifecycle(address: operatorAddress),
            adminPINStore: InMemoryAdminPINStore(pin: "123456"),
            currentConfigurationValidation: { configuration in
                try await probe.validate(configuration)
            },
            operatorStatusReader: { configuration, address in
                try await probe.readStatus(configuration: configuration, address: address)
            }
        )
        var settings = AppSettings()
        settings.provisionedOperatorAddress = operatorAddress.hex
        model.settings = settings

        let task = Task { await model.refreshReadiness() }
        await probe.waitUntilValidationStarts()
        XCTAssertTrue(model.isRefreshingReadiness)
        XCTAssertTrue(model.isBusy)

        await probe.finishValidation()
        await probe.waitUntilStatusReadStarts()
        XCTAssertTrue(model.isRefreshingReadiness)
        XCTAssertFalse(model.isBusy)

        await probe.finishStatusRead()
        await task.value
        XCTAssertFalse(model.isRefreshingReadiness)
        XCTAssertTrue(model.terminalReadiness.isReady)
    }

    func testCheckoutAmountInputPreservesConfiguredTokenPrecision() {
        var amount = CheckoutAmountInput.cleared
        amount = CheckoutAmountInput.appending(digit: 1, to: amount, maximumFractionDigits: 6)
        amount = CheckoutAmountInput.appendingDecimal(to: amount, maximumFractionDigits: 6)
        for digit in [2, 3, 4, 5, 6, 7, 8] {
            amount = CheckoutAmountInput.appending(
                digit: digit,
                to: amount,
                maximumFractionDigits: 6
            )
        }

        XCTAssertEqual(amount, "1.234567")
        XCTAssertTrue(CheckoutAmountInput.isPayable(amount, decimals: 6))
    }

    func testCheckoutAmountInputRejectsZeroAndIncompleteDecimal() {
        XCTAssertFalse(CheckoutAmountInput.isPayable("", decimals: 18))
        XCTAssertFalse(CheckoutAmountInput.isPayable("0", decimals: 18))
        XCTAssertFalse(CheckoutAmountInput.isPayable("1.", decimals: 18))
        XCTAssertTrue(CheckoutAmountInput.isPayable("0.000000000000000001", decimals: 18))
    }

    func testCheckoutAmountInputSupportsClearBackspaceAndZeroDecimalTokens() {
        var normalized = CheckoutAmountInput.appending(
            digit: 0,
            to: "",
            maximumFractionDigits: 18
        )
        normalized = CheckoutAmountInput.appending(
            digit: 0,
            to: normalized,
            maximumFractionDigits: 18
        )
        normalized = CheckoutAmountInput.appending(
            digit: 5,
            to: normalized,
            maximumFractionDigits: 18
        )

        XCTAssertEqual(normalized, "5")
        XCTAssertEqual(
            CheckoutAmountInput.appendingDecimal(to: "12", maximumFractionDigits: 0),
            "12"
        )
        XCTAssertEqual(CheckoutAmountInput.deletingLast(from: "12.3"), "12.")
        XCTAssertEqual(CheckoutAmountInput.deletingLast(from: ""), "")
        XCTAssertEqual(CheckoutAmountInput.cleared, "")
        XCTAssertTrue(CheckoutAmountInput.isPayable("12", decimals: 0))
        XCTAssertEqual(CheckoutAmountInput.displayText(for: "", decimals: 0), "0")
        XCTAssertEqual(CheckoutAmountInput.displayText(for: "", decimals: 1), "0.0")
        XCTAssertEqual(CheckoutAmountInput.displayText(for: "", decimals: 18), "0.00")
    }

    func testCheckoutAmountInputStopsAtUInt256Capacity() {
        let maximum = "115792089237316195423570985008687907853269984665640564039457584007913129639935"
        var amount = ""
        for character in maximum {
            amount = CheckoutAmountInput.appending(
                digit: Int(String(character))!,
                to: amount,
                maximumFractionDigits: 0
            )
        }

        XCTAssertEqual(amount, maximum)
        XCTAssertEqual(
            CheckoutAmountInput.appending(
                digit: 0,
                to: amount,
                maximumFractionDigits: 0
            ),
            maximum
        )
        XCTAssertEqual(
            CheckoutAmountInput.exactReviewText(
                for: maximum,
                decimals: 0,
                symbol: "MAX"
            ),
            "\(maximum) MAX"
        )
    }

    func testStoredInvoicePersistsCompleteSelectedProfileBAndIgnoresLaterSelection() throws {
        let operatorAddress = try address("0x7777777777777777777777777777777777777777")
        let tokenA = try PaymentToken(
            address: address("0x1111111111111111111111111111111111111111"),
            symbol: "AUDM",
            decimals: 18
        )
        let tokenB = try PaymentToken(
            address: address("0x2222222222222222222222222222222222222222"),
            symbol: "USDC",
            decimals: 6
        )
        let configurationA = try TerminalConfiguration(
            chainID: 84_532,
            rpcEndpoints: [URL(string: "https://sepolia.base.org")!],
            protocolVersion: .v1_4_1,
            deployment: OPKDeployment(
                factory: try address("0x3333333333333333333333333333333333333333"),
                receiverImplementation: try address("0x4444444444444444444444444444444444444444"),
                vault: try address("0x5555555555555555555555555555555555555555")
            ),
            tokens: [tokenA],
            confirmationPolicy: .init(requiredBlocks: 2)
        )
        let configurationB = try TerminalConfiguration(
            chainID: 9_999,
            rpcEndpoints: [URL(string: "https://rpc.merchant.example")!],
            protocolVersion: .v1_4_1,
            deployment: OPKDeployment(
                factory: try address("0x6666666666666666666666666666666666666666"),
                receiverImplementation: try address("0x8888888888888888888888888888888888888888"),
                vault: try address("0x9999999999999999999999999999999999999999")
            ),
            tokens: [tokenB],
            confirmationPolicy: .init(requiredBlocks: 7)
        )
        let profileA = try TerminalPaymentProfile(configuration: configurationA)
        let profileB = try TerminalPaymentProfile(configuration: configurationB)
        let originalCatalog = try TerminalPaymentProfileCatalog(
            profiles: [profileA, profileB],
            selectedProfileID: profileA.id
        )
        let selectedB = try originalCatalog.selecting(id: profileB.id)
        let capturedProfile = try XCTUnwrap(selectedB.selected)
        let request = try InvoiceFactory.create(
            terminalIdentifier: TerminalIdentifier(address: operatorAddress),
            amount: TokenAmount(rawValue: UInt256(1_234_567), decimals: tokenB.decimals),
            profile: capturedProfile,
            createdAt: Date(timeIntervalSince1970: 1_234),
            nonce: Bytes32(
                hex: "0xabababababababababababababababababababababababababababababababab"
            )
        )

        let persisted = try StoredInvoice(
            request: request,
            configuration: capturedProfile.configuration
        )
        let laterSelection = try selectedB.selecting(id: profileA.id)

        XCTAssertEqual(laterSelection.selected?.id, profileA.id)
        XCTAssertEqual(persisted.chainID, Int64(configurationB.chainID))
        XCTAssertEqual(persisted.rpcURL, configurationB.rpcEndpoints[0].absoluteString)
        XCTAssertEqual(persisted.protocolVersion, configurationB.protocolVersion.rawValue)
        XCTAssertEqual(persisted.factory, configurationB.deployment.factory.hex)
        XCTAssertEqual(
            persisted.receiverImplementation,
            configurationB.deployment.receiverImplementation.hex
        )
        XCTAssertEqual(persisted.vault, configurationB.deployment.vault.hex)
        XCTAssertEqual(persisted.confirmationBlocks, 7)
        XCTAssertEqual(persisted.tokenAddress, tokenB.address.hex)
        XCTAssertEqual(persisted.tokenSymbol, tokenB.symbol)
        XCTAssertEqual(persisted.tokenDecimals, Int(tokenB.decimals))
        XCTAssertEqual(persisted.terminalIdentifier, operatorAddress.hex)
        XCTAssertEqual(persisted.expectedAmount, "1234567")
    }

    func testSettlementInvoiceOperatorSnapshotsMustMatchTheCurrentDeviceEOA() throws {
        let current = try address("0x1111111111111111111111111111111111111111")
        let other = try address("0x2222222222222222222222222222222222222222")

        XCTAssertTrue(
            invoiceOperatorSnapshotsMatch([current, current], currentOperator: current)
        )
        XCTAssertFalse(
            invoiceOperatorSnapshotsMatch([current, other], currentOperator: current)
        )
        XCTAssertFalse(
            invoiceOperatorSnapshotsMatch([other], currentOperator: current)
        )
        XCTAssertFalse(
            invoiceOperatorSnapshotsMatch([], currentOperator: current)
        )
    }

    func testNewPaymentRouteUsesKnownNetworkDefaultWithoutLeakingFallbackFinality() throws {
        var original = AppSettings()
        original.rpcURL = "https://example-rpc.invalid"
        original.confirmationBlocks = "7"
        original.provisionedOperatorAddress = nil
        let snapshot = original
        let profile = TerminalKnownChainProfile.baseSepolia
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let token = try PaymentToken(
            address: try address("0x2222222222222222222222222222222222222222"),
            symbol: "USDC",
            decimals: 6
        )
        let configuration = try TerminalConfiguration(
            chainID: profile.chainID,
            rpcEndpoints: [URL(string: "https://example-rpc.invalid")!],
            protocolVersion: profile.protocolVersion,
            deployment: try OPKDeployment(
                factory: profile.factory,
                receiverImplementation: profile.receiverImplementation,
                vault: try address("0x3333333333333333333333333333333333333333")
            ),
            tokens: [token],
            confirmationPolicy: .init(requiredBlocks: profile.defaultConfirmationBlocks),
            create2TestVector: profile.create2TestVector
        )

        let candidate = try original.applying(configuration, boundTo: operatorAddress)

        XCTAssertEqual(original, snapshot, "Derivation must not mutate the live settings value")
        XCTAssertEqual(original.confirmationBlocks, "7")
        XCTAssertEqual(
            candidate.confirmationBlocks,
            String(profile.defaultConfirmationBlocks)
        )
        XCTAssertEqual(candidate.rpcURL, "https://example-rpc.invalid")
        XCTAssertEqual(candidate.chainID, "84532")
        XCTAssertEqual(candidate.factory, profile.factory.hex)
        XCTAssertEqual(candidate.receiverImplementation, profile.receiverImplementation.hex)
        XCTAssertEqual(candidate.vault, configuration.deployment.vault.hex)
        XCTAssertEqual(candidate.tokenAddress, token.address.hex)
        XCTAssertEqual(candidate.tokenSymbol, "USDC")
        XCTAssertEqual(candidate.tokenDecimals, "6")
        XCTAssertEqual(candidate.provisionedOperatorAddress, operatorAddress.hex)
    }

    func testReprovisioningSamePaymentRoutePreservesRaisedFinality() throws {
        let profile = TerminalKnownChainProfile.baseSepolia
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let token = try PaymentToken(
            address: address("0x2222222222222222222222222222222222222222"),
            symbol: "USDC",
            decimals: 6
        )
        let configuration = try TerminalConfiguration(
            chainID: profile.chainID,
            rpcEndpoints: [profile.rpcEndpoint],
            protocolVersion: profile.protocolVersion,
            deployment: OPKDeployment(
                factory: profile.factory,
                receiverImplementation: profile.receiverImplementation,
                vault: address("0x3333333333333333333333333333333333333333")
            ),
            tokens: [token],
            confirmationPolicy: .init(requiredBlocks: profile.defaultConfirmationBlocks),
            create2TestVector: profile.create2TestVector
        )
        var raised = try AppSettings().applying(configuration, boundTo: operatorAddress)
        raised.confirmationBlocks = "7"

        let reprovisioned = try raised.applying(configuration, boundTo: operatorAddress)

        XCTAssertEqual(reprovisioned.paymentProfiles.count, 1)
        XCTAssertEqual(reprovisioned.selectedPaymentProfileID, raised.selectedPaymentProfileID)
        XCTAssertEqual(reprovisioned.confirmationBlocks, "7")
    }

    func testRejectedCandidateCannotPartiallyMutateExistingSettings() throws {
        var original = AppSettings()
        original.confirmationBlocks = "2"
        let snapshot = original
        let profile = TerminalKnownChainProfile.baseSepolia
        let token = try PaymentToken(
            address: try address("0x2222222222222222222222222222222222222222"),
            symbol: "AUD",
            decimals: 18
        )
        let invalidCandidate = try TerminalConfiguration(
            chainID: profile.chainID,
            rpcEndpoints: [profile.rpcEndpoint],
            protocolVersion: profile.protocolVersion,
            deployment: try OPKDeployment(
                factory: profile.factory,
                receiverImplementation: profile.receiverImplementation,
                vault: try address("0x3333333333333333333333333333333333333333")
            ),
            tokens: [token, token],
            confirmationPolicy: .init(requiredBlocks: 2),
            create2TestVector: profile.create2TestVector
        )

        XCTAssertThrowsError(
            try original.applying(
                invalidCandidate,
                boundTo: address("0x1111111111111111111111111111111111111111")
            )
        )
        XCTAssertEqual(original, snapshot)
    }

    func testLegacyFlatSettingsMigrateToOneSelectedProfileAndRoundTripV2() throws {
        let operatorAddress = "0x1111111111111111111111111111111111111111"
        let legacy: [String: Any] = [
            "rpcURL": "https://sepolia.base.org",
            "chainID": "84532",
            "protocolVersion": "1.4.1",
            "factory": "0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5",
            "receiverImplementation": "0xdaa292b1bf533737c5ce5d27f220273971db3bdc",
            "vault": "0x3333333333333333333333333333333333333333",
            "tokenAddress": "0x2222222222222222222222222222222222222222",
            "tokenSymbol": "AUDM",
            "tokenDecimals": "18",
            "confirmationBlocks": "7",
            "provisionedOperatorAddress": operatorAddress,
        ]
        let migrated = try JSONDecoder().decode(
            AppSettings.self,
            from: JSONSerialization.data(withJSONObject: legacy)
        )

        XCTAssertEqual(migrated.paymentProfiles.count, 1)
        XCTAssertEqual(migrated.selectedPaymentProfileID, migrated.paymentProfiles[0].id)
        XCTAssertTrue(migrated.paymentProfiles[0].id.hasPrefix("eip155:84532:"))
        XCTAssertEqual(migrated.provisionedOperatorAddress, operatorAddress)
        XCTAssertEqual(migrated.confirmationBlocks, "7")

        let roundTripped = try JSONDecoder().decode(
            AppSettings.self,
            from: JSONEncoder().encode(migrated)
        )
        XCTAssertEqual(roundTripped, migrated)
    }

    func testLegacyFlatFinalityBelowKnownFloorIsRaisedWithoutDeprovisioning() throws {
        let legacy: [String: Any] = [
            "rpcURL": "https://sepolia.base.org",
            "chainID": "84532",
            "protocolVersion": "1.4.1",
            "factory": "0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5",
            "receiverImplementation": "0xdaa292b1bf533737c5ce5d27f220273971db3bdc",
            "vault": "0x3333333333333333333333333333333333333333",
            "tokenAddress": "0x2222222222222222222222222222222222222222",
            "tokenSymbol": "AUDM",
            "tokenDecimals": "18",
            "confirmationBlocks": "1",
            "provisionedOperatorAddress": "0x1111111111111111111111111111111111111111",
        ]

        var migrated = try JSONDecoder().decode(
            AppSettings.self,
            from: JSONSerialization.data(withJSONObject: legacy)
        )

        XCTAssertTrue(migrated.isProvisioned)
        XCTAssertEqual(migrated.confirmationBlocks, "2")
        XCTAssertEqual(
            migrated.migrationNotice?.adjustedConfirmationProfileIDs,
            [try XCTUnwrap(migrated.selectedPaymentProfileID)]
        )
        XCTAssertNoThrow(try migrated.configuration())
        XCTAssertEqual(
            try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(migrated)),
            migrated
        )

        let removedBeforeAcknowledgement = try migrated.removingPaymentProfile(
            id: try XCTUnwrap(migrated.selectedPaymentProfileID)
        )
        XCTAssertNil(removedBeforeAcknowledgement.migrationNotice)
        XCTAssertNil(migrated.clearingProvisioning().migrationNotice)

        let beforeAcknowledgement = migrated
        migrated.acknowledgeMigrationNotice()
        XCTAssertNil(migrated.migrationNotice)
        XCTAssertTrue(migrated.isProvisioned)
        XCTAssertTrue(migrated.hasSamePaymentConfiguration(as: beforeAcknowledgement))
    }

    func testV2CatalogWideFinalityBelowKnownFloorMigratesButV3DoesNot() throws {
        let profile: [String: Any] = [
            "rpcURL": "https://sepolia.base.org",
            "chainID": "84532",
            "protocolVersion": "1.4.1",
            "factory": "0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5",
            "receiverImplementation": "0xdaa292b1bf533737c5ce5d27f220273971db3bdc",
            "vault": "0x3333333333333333333333333333333333333333",
            "tokenAddress": "0x2222222222222222222222222222222222222222",
            "tokenSymbol": "AUDM",
            "tokenDecimals": "18",
            "provisionedOperatorAddress": "0x1111111111111111111111111111111111111111",
        ]
        let v2: [String: Any] = [
            "schemaVersion": 2,
            "confirmationBlocks": "1",
            "paymentProfiles": [profile],
        ]
        let migrated = try JSONDecoder().decode(
            AppSettings.self,
            from: JSONSerialization.data(withJSONObject: v2)
        )
        XCTAssertEqual(migrated.confirmationBlocks, "2")
        XCTAssertTrue(migrated.isProvisioned)
        XCTAssertNotNil(migrated.migrationNotice)

        var secondProfile = profile
        secondProfile["vault"] = "0x4444444444444444444444444444444444444444"
        secondProfile["tokenAddress"] = "0x5555555555555555555555555555555555555555"
        secondProfile["tokenSymbol"] = "USDC"
        let migratedPair = try JSONDecoder().decode(
            AppSettings.self,
            from: JSONSerialization.data(withJSONObject: [
                "schemaVersion": 2,
                "confirmationBlocks": "1",
                "paymentProfiles": [profile, secondProfile],
            ])
        )
        XCTAssertEqual(migratedPair.migrationNotice?.adjustedConfirmationProfileIDs.count, 2)
        let filteredPair = try migratedPair.removingPaymentProfile(
            id: try XCTUnwrap(migratedPair.selectedPaymentProfileID)
        )
        XCTAssertEqual(
            filteredPair.migrationNotice?.adjustedConfirmationProfileIDs,
            [try XCTUnwrap(filteredPair.selectedPaymentProfileID)]
        )

        var v3 = v2
        v3["schemaVersion"] = 3
        v3["selectedPaymentProfileID"] = try XCTUnwrap(migrated.selectedPaymentProfileID)
        v3["paymentProfiles"] = [profile.merging(["confirmationBlocks": "1"]) { _, new in new }]
        XCTAssertThrowsError(
            try JSONDecoder().decode(
                AppSettings.self,
                from: JSONSerialization.data(withJSONObject: v3)
            )
        )

        var currentWithoutSelection = v2
        currentWithoutSelection["schemaVersion"] = 3
        currentWithoutSelection["paymentProfiles"] = [
            profile.merging(["confirmationBlocks": "2"]) { _, new in new },
        ]
        XCTAssertThrowsError(
            try JSONDecoder().decode(
                AppSettings.self,
                from: JSONSerialization.data(withJSONObject: currentWithoutSelection)
            )
        )
    }

    func testPersistedCatalogRejectsUnsupportedChainBeforeOfferingFunding() throws {
        let operatorAddress = "0x1111111111111111111111111111111111111111"
        let untrusted: [String: Any] = [
            "schemaVersion": 2,
            "confirmationBlocks": "2",
            "paymentProfiles": [[
                "rpcURL": "https://ethereum.example",
                "chainID": "1",
                "protocolVersion": "1.4.1",
                "factory": "0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5",
                "receiverImplementation": "0xdaa292b1bf533737c5ce5d27f220273971db3bdc",
                "vault": "0x3333333333333333333333333333333333333333",
                "tokenAddress": "0x2222222222222222222222222222222222222222",
                "tokenSymbol": "USDC",
                "tokenDecimals": "6",
                "provisionedOperatorAddress": operatorAddress,
            ]],
        ]

        XCTAssertThrowsError(
            try JSONDecoder().decode(
                AppSettings.self,
                from: JSONSerialization.data(withJSONObject: untrusted)
            )
        )
    }

    func testProvisioningUpsertsCanonicalProfileAndPreservesOtherEnabledRoutes() throws {
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let sepolia = TerminalKnownChainProfile.baseSepolia
        let sepoliaTokenAddress = try address("0x2222222222222222222222222222222222222222")
        let secondTokenAddress = try address("0x4444444444444444444444444444444444444444")
        let sepoliaConfiguration = try TerminalConfiguration(
            chainID: sepolia.chainID,
            rpcEndpoints: [sepolia.rpcEndpoint],
            protocolVersion: sepolia.protocolVersion,
            deployment: OPKDeployment(
                factory: sepolia.factory,
                receiverImplementation: sepolia.receiverImplementation,
                vault: try address("0x3333333333333333333333333333333333333333")
            ),
            tokens: [PaymentToken(
                address: sepoliaTokenAddress,
                symbol: "AUDM",
                decimals: 18
            )],
            confirmationPolicy: .init(requiredBlocks: 2),
            create2TestVector: sepolia.create2TestVector
        )
        let secondConfiguration = try TerminalConfiguration(
            chainID: sepolia.chainID,
            rpcEndpoints: [sepolia.rpcEndpoint],
            protocolVersion: sepolia.protocolVersion,
            deployment: OPKDeployment(
                factory: sepolia.factory,
                receiverImplementation: sepolia.receiverImplementation,
                vault: try address("0x5555555555555555555555555555555555555555")
            ),
            tokens: [PaymentToken(
                address: secondTokenAddress,
                symbol: "USDC",
                decimals: 6
            )],
            confirmationPolicy: .init(requiredBlocks: 2),
            create2TestVector: sepolia.create2TestVector
        )

        let one = try AppSettings().applying(
            sepoliaConfiguration,
            boundTo: operatorAddress
        )
        let two = try one.applying(secondConfiguration, boundTo: operatorAddress)
        XCTAssertEqual(two.paymentProfiles.count, 2)
        XCTAssertEqual(two.chainID, "84532")
        XCTAssertEqual(two.tokenSymbol, "USDC")
        XCTAssertEqual(two.operatorFundingPayload(for: operatorAddress), "ethereum:\(operatorAddress.hex)@84532")
        XCTAssertEqual(
            try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(two)),
            two
        )

        let firstID = try XCTUnwrap(
            two.paymentProfiles.first(where: { $0.chainID == "84532" })?.id
        )
        let selectedSepolia = try two.selectingPaymentProfile(id: firstID)
        XCTAssertEqual(selectedSepolia.tokenSymbol, "AUDM")
        XCTAssertEqual(selectedSepolia.chainID, "84532")
        XCTAssertNotEqual(selectedSepolia.validationFingerprint, two.validationFingerprint)

        let updatedSecondRoute = try TerminalConfiguration(
            chainID: secondConfiguration.chainID,
            rpcEndpoints: secondConfiguration.rpcEndpoints,
            protocolVersion: secondConfiguration.protocolVersion,
            deployment: secondConfiguration.deployment,
            tokens: [PaymentToken(
                address: secondTokenAddress,
                symbol: "USDC.e",
                decimals: 6
            )],
            confirmationPolicy: secondConfiguration.confirmationPolicy,
            create2TestVector: secondConfiguration.create2TestVector
        )
        let upserted = try selectedSepolia.applying(
            updatedSecondRoute,
            boundTo: operatorAddress
        )
        XCTAssertEqual(upserted.paymentProfiles.count, 2)
        XCTAssertEqual(upserted.tokenSymbol, "USDC.e")

        let removed = try upserted.removingPaymentProfile(
            id: try XCTUnwrap(upserted.selectedPaymentProfileID)
        )
        XCTAssertEqual(removed.paymentProfiles.count, 1)
        XCTAssertEqual(removed.chainID, "84532")
        XCTAssertTrue(removed.isProvisioned)
        XCTAssertFalse(removed.paymentProfiles.contains { $0.tokenSymbol == "USDC.e" })
    }

    func testRemovingSelectedProfileReselectsFirstRemainingInsertion() throws {
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let known = TerminalKnownChainProfile.baseSepolia
        func configuration(vault: String, token: String, symbol: String) throws
            -> TerminalConfiguration {
            try TerminalConfiguration(
                chainID: known.chainID,
                rpcEndpoints: [known.rpcEndpoint],
                protocolVersion: known.protocolVersion,
                deployment: OPKDeployment(
                    factory: known.factory,
                    receiverImplementation: known.receiverImplementation,
                    vault: address(vault)
                ),
                tokens: [PaymentToken(address: address(token), symbol: symbol, decimals: 6)],
                confirmationPolicy: .init(requiredBlocks: known.defaultConfirmationBlocks),
                create2TestVector: known.create2TestVector
            )
        }
        let firstConfiguration = try configuration(
            vault: "0xffffffffffffffffffffffffffffffffffffffff",
            token: "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            symbol: "AUDM"
        )
        let secondConfiguration = try configuration(
            vault: "0x1111111111111111111111111111111111111111",
            token: "0x2222222222222222222222222222222222222222",
            symbol: "USDC"
        )
        let thirdConfiguration = try configuration(
            vault: "0x3333333333333333333333333333333333333333",
            token: "0x4444444444444444444444444444444444444444",
            symbol: "EURC"
        )
        let catalog = try AppSettings()
            .applying(firstConfiguration, boundTo: operatorAddress)
            .applying(secondConfiguration, boundTo: operatorAddress)
            .applying(thirdConfiguration, boundTo: operatorAddress)
        let firstID = try TerminalPaymentProfile(configuration: firstConfiguration).id.rawValue
        let thirdID = try TerminalPaymentProfile(configuration: thirdConfiguration).id.rawValue

        let remaining = try catalog.removingPaymentProfile(id: thirdID)

        XCTAssertEqual(remaining.paymentProfiles.count, 2)
        XCTAssertEqual(remaining.selectedPaymentProfileID, firstID)
        XCTAssertEqual(remaining.tokenSymbol, "AUDM")
    }

    func testProfileSelectionCannotLeakFinalityAndRejectsBelowNetworkFloor() throws {
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let known = TerminalKnownChainProfile.baseSepolia
        func configuration(vault: String, token: String) throws -> TerminalConfiguration {
            try TerminalConfiguration(
                chainID: known.chainID,
                rpcEndpoints: [known.rpcEndpoint],
                protocolVersion: known.protocolVersion,
                deployment: OPKDeployment(
                    factory: known.factory,
                    receiverImplementation: known.receiverImplementation,
                    vault: address(vault)
                ),
                tokens: [PaymentToken(address: address(token), symbol: "USD", decimals: 6)],
                confirmationPolicy: .init(requiredBlocks: known.defaultConfirmationBlocks),
                create2TestVector: known.create2TestVector
            )
        }

        var first = try AppSettings().applying(
            configuration(
                vault: "0x2222222222222222222222222222222222222222",
                token: "0x3333333333333333333333333333333333333333"
            ),
            boundTo: operatorAddress
        )
        first.confirmationBlocks = "7"
        let firstID = try XCTUnwrap(first.selectedPaymentProfileID)
        let second = try first.applying(
            configuration(
                vault: "0x4444444444444444444444444444444444444444",
                token: "0x5555555555555555555555555555555555555555"
            ),
            boundTo: operatorAddress
        )
        let secondID = try XCTUnwrap(second.selectedPaymentProfileID)

        XCTAssertEqual(try second.selectingPaymentProfile(id: firstID).confirmationBlocks, "7")
        XCTAssertEqual(try second.selectingPaymentProfile(id: secondID).confirmationBlocks, "2")
        var unsafe = second.displayedPaymentProfile
        unsafe.confirmationBlocks = "1"
        XCTAssertThrowsError(try unsafe.configuration())
    }

    func testProfileIdentityDisambiguatesDuplicateSymbolsByVault() throws {
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let known = TerminalKnownChainProfile.baseSepolia
        let token = try PaymentToken(
            address: address("0x2222222222222222222222222222222222222222"),
            symbol: "USDC",
            decimals: 6
        )
        func configuration(vault: String) throws -> TerminalConfiguration {
            try TerminalConfiguration(
                chainID: known.chainID,
                rpcEndpoints: [known.rpcEndpoint],
                protocolVersion: known.protocolVersion,
                deployment: OPKDeployment(
                    factory: known.factory,
                    receiverImplementation: known.receiverImplementation,
                    vault: address(vault)
                ),
                tokens: [token],
                create2TestVector: known.create2TestVector
            )
        }
        let first = try AppSettings().applying(
            configuration(vault: "0x3333333333333333333333333333333333333333"),
            boundTo: operatorAddress
        )
        let second = try first.applying(
            configuration(vault: "0x4444444444444444444444444444444444444444"),
            boundTo: operatorAddress
        )
        XCTAssertEqual(second.paymentProfiles.map(\.tokenSymbol), ["USDC", "USDC"])
        XCTAssertEqual(Set(second.paymentProfiles.map(\.id)).count, 2)
        XCTAssertNotEqual(second.paymentProfiles[0].detail, second.paymentProfiles[1].detail)
    }

    func testProfileDisplayDisambiguatesSameSymbolAndVaultByTokenAddress() throws {
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let known = TerminalKnownChainProfile.baseSepolia
        let vault = try address("0x3333333333333333333333333333333333333333")
        func configuration(tokenAddress: String) throws -> TerminalConfiguration {
            try TerminalConfiguration(
                chainID: known.chainID,
                rpcEndpoints: [known.rpcEndpoint],
                protocolVersion: known.protocolVersion,
                deployment: OPKDeployment(
                    factory: known.factory,
                    receiverImplementation: known.receiverImplementation,
                    vault: vault
                ),
                tokens: [PaymentToken(
                    address: address(tokenAddress),
                    symbol: "USD",
                    decimals: 6
                )],
                create2TestVector: known.create2TestVector
            )
        }
        let first = try AppSettings().applying(
            configuration(tokenAddress: "0x2222222222222222222222222222222222222222"),
            boundTo: operatorAddress
        )
        let second = try first.applying(
            configuration(tokenAddress: "0x4444444444444444444444444444444444444444"),
            boundTo: operatorAddress
        )

        XCTAssertEqual(second.paymentProfiles.map(\.displayName), [
            "USD · Base Sepolia",
            "USD · Base Sepolia",
        ])
        XCTAssertNotEqual(second.paymentProfiles[0].detail, second.paymentProfiles[1].detail)
        XCTAssertTrue(second.paymentProfiles[0].detail.contains("0x222222…2222"))
        XCTAssertTrue(second.paymentProfiles[1].detail.contains("0x444444…4444"))
    }

    func testReadinessRequiresCurrentValidationAuthorizationAndMinimumGas() throws {
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        var settings = AppSettings()

        XCTAssertEqual(
            TerminalReadiness.evaluate(
                settings: settings,
                operatorAddress: nil,
                validatedFingerprint: nil,
                operatorStatus: nil
            ),
            .walletRequired
        )
        XCTAssertEqual(
            TerminalReadiness.evaluate(
                settings: settings,
                operatorAddress: operatorAddress,
                validatedFingerprint: nil,
                operatorStatus: nil
            ),
            .configurationRequired
        )

        settings.provisionedOperatorAddress = operatorAddress.hex
        XCTAssertEqual(
            TerminalReadiness.evaluate(
                settings: settings,
                operatorAddress: operatorAddress,
                validatedFingerprint: nil,
                operatorStatus: nil
            ),
            .validationRequired
        )

        let unauthorized = OperatorChainStatus(
            chainID: 84_532,
            balance: TerminalKnownChainProfile.baseSepolia.minimumOperatorNativeReserve,
            isAuthorizedOperator: false,
            isVaultOwner: false,
            isLowGas: false
        )
        XCTAssertEqual(
            TerminalReadiness.evaluate(
                settings: settings,
                operatorAddress: operatorAddress,
                validatedFingerprint: settings.validationFingerprint,
                operatorStatus: unauthorized
            ),
            .authorizationRequired
        )

        let underfunded = OperatorChainStatus(
            chainID: 84_532,
            balance: UInt256(99_999_999_999_999),
            isAuthorizedOperator: true,
            isVaultOwner: false,
            isLowGas: true
        )
        XCTAssertEqual(
            TerminalReadiness.evaluate(
                settings: settings,
                operatorAddress: operatorAddress,
                validatedFingerprint: settings.validationFingerprint,
                operatorStatus: underfunded
            ),
            .gasRequired(
                available: underfunded.balance,
                required: TerminalKnownChainProfile.baseSepolia.minimumOperatorNativeReserve,
                nativeCurrencySymbol: "ETH",
                nativeCurrencyDecimals: 18
            )
        )

        let ready = OperatorChainStatus(
            chainID: 84_532,
            balance: TerminalKnownChainProfile.baseSepolia.minimumOperatorNativeReserve,
            isAuthorizedOperator: true,
            isVaultOwner: false,
            isLowGas: false
        )
        XCTAssertEqual(
            TerminalReadiness.evaluate(
                settings: settings,
                operatorAddress: operatorAddress,
                validatedFingerprint: settings.validationFingerprint,
                operatorStatus: ready
            ),
            .ready
        )
    }

    func testAdminPINFormatAndThrottlePolicy() {
        XCTAssertTrue(KeychainAdminPINStore.isValidFormat("012345"))
        XCTAssertFalse(KeychainAdminPINStore.isValidFormat("12345"))
        XCTAssertFalse(KeychainAdminPINStore.isValidFormat("１２３４５６"))
        XCTAssertFalse(KeychainAdminPINStore.isValidFormat("12345a"))
        XCTAssertNil(KeychainAdminPINStore.throttleDelay(afterFailedAttempts: 2))
        XCTAssertEqual(KeychainAdminPINStore.throttleDelay(afterFailedAttempts: 3), 5)
        XCTAssertEqual(KeychainAdminPINStore.throttleDelay(afterFailedAttempts: 4), 10)
        XCTAssertEqual(KeychainAdminPINStore.throttleDelay(afterFailedAttempts: 20), 300)
    }

    func testAdminPINVerifierPersistsThrottleAndResetsAfterSuccessfulUnlock() throws {
        var currentTime = Date(timeIntervalSince1970: 1_000)
        let store = KeychainAdminPINStore(
            service: "com.openpasskey.terminal.tests.\(UUID().uuidString)",
            now: { currentTime }
        )
        XCTAssertFalse(try store.isConfigured)
        try store.setPIN("123456")
        XCTAssertTrue(try store.isConfigured)

        for attempt in 1...3 {
            XCTAssertThrowsError(try store.verify("000000")) { error in
                let expectedDelay: Int? = attempt == 3 ? 5 : nil
                XCTAssertEqual(
                    error as? AdminPINError,
                    .invalidPIN(retryAfterSeconds: expectedDelay)
                )
            }
        }
        XCTAssertEqual(try store.secondsUntilNextAttempt(), 5)
        XCTAssertThrowsError(try store.verify("123456")) { error in
            XCTAssertEqual(error as? AdminPINError, .throttled(secondsRemaining: 5))
        }

        currentTime.addTimeInterval(6)
        XCTAssertNoThrow(try store.verify("123456"))
        XCTAssertEqual(try store.secondsUntilNextAttempt(), 0)
        XCTAssertThrowsError(try store.verify("000000")) { error in
            XCTAssertEqual(error as? AdminPINError, .invalidPIN(retryAfterSeconds: nil))
        }
    }

    @MainActor
    func testBackgroundLockInvalidatesProvisioningBeforeSettingsCommit() async throws {
        let savedSettings = AppPreferences.loadSettings()
        defer { AppPreferences.saveSettings(savedSettings) }
        let container = try ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let original = AppSettings()
        let configuration = try original.configuration()
        let validator = BlockingProvisioningValidator(
            result: ProvisionedTerminalConfiguration(
                profile: .baseSepolia,
                configuration: configuration,
                validationReport: ConfigurationValidationReport(
                    chainID: configuration.chainID,
                    checks: [ValidationCheck(name: "test", detail: "valid")]
                )
            )
        )
        let lifecycle = BlockingOperatorWalletLifecycle(address: operatorAddress)
        let namespace = UUID().uuidString
        let model = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.tests.\(namespace)"
            ),
            operatorWalletLifecycle: lifecycle,
            provisioningValidator: validator,
            adminPINStore: InMemoryAdminPINStore(pin: "123456")
        )
        model.settings = original
        model.unlockAdmin(with: "123456")
        XCTAssertTrue(model.adminUnlocked)
        let payload = try TerminalProvisioningPayload(
            chainID: configuration.chainID,
            vault: configuration.deployment.vault,
            token: configuration.tokens[0].address,
            operatorAddress: operatorAddress
        )

        let task = Task { await model.provision(payload) }
        await validator.waitUntilDerivationStarts()
        model.lockAdmin()
        await validator.finishDerivation()
        await task.value

        XCTAssertFalse(model.adminUnlocked)
        XCTAssertEqual(model.settings, original)
        XCTAssertFalse(model.settings.isProvisioned)
        XCTAssertTrue(model.errorMessage?.contains("Admin session was locked") == true)
    }

    @MainActor
    func testBackgroundLockInvalidatesResetAtActualKeyDeletionBoundary() async throws {
        let savedSettings = AppPreferences.loadSettings()
        defer { AppPreferences.saveSettings(savedSettings) }
        let container = try ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let deletionProbe = OperatorDeletionProbe()
        let lifecycle = BlockingOperatorWalletLifecycle(
            address: operatorAddress,
            deletionProbe: deletionProbe
        )
        let namespace = UUID().uuidString
        let model = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.tests.\(namespace)"
            ),
            operatorWalletLifecycle: lifecycle,
            adminPINStore: InMemoryAdminPINStore(pin: "123456"),
            operatorResetBalanceReader: { _, _ in
                OperatorNativeBalanceSnapshot(latest: .zero, pending: .zero)
            }
        )
        var provisioned = AppSettings()
        provisioned.provisionedOperatorAddress = operatorAddress.hex
        model.settings = provisioned
        model.unlockAdmin(with: "123456")
        XCTAssertTrue(model.adminUnlocked)

        let task = Task { await model.resetOperatorWallet() }
        await lifecycle.waitUntilDeletionBoundary()
        model.lockAdmin()
        await lifecycle.continueDeletion()
        await task.value

        XCTAssertFalse(deletionProbe.wasDeleted)
        XCTAssertEqual(model.operatorAddress, operatorAddress)
        XCTAssertEqual(model.settings, provisioned)
        XCTAssertTrue(model.errorMessage?.contains("Admin session was locked") == true)
    }

    @MainActor
    func testBackgroundLockInvalidatesConfiguredWalletCreationBeforeKeyPersistence() async throws {
        let container = try ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let address = try self.address("0x1111111111111111111111111111111111111111")
        let persistenceProbe = OperatorPersistenceProbe()
        let lifecycle = BlockingCreateOperatorWalletLifecycle(
            address: address,
            persistenceProbe: persistenceProbe,
            startsBlocked: true
        )
        let namespace = UUID().uuidString
        let model = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.tests.\(namespace)"
            ),
            operatorWalletLifecycle: lifecycle,
            adminPINStore: InMemoryAdminPINStore(pin: "123456")
        )
        model.unlockAdmin(with: "123456")

        let task = Task { await model.createOperatorWallet() }
        await lifecycle.waitUntilPersistenceBoundary()
        model.lockAdmin()
        await lifecycle.continuePersistence()
        await task.value

        XCTAssertFalse(persistenceProbe.wasPersisted)
        XCTAssertNil(model.operatorAddress)
        XCTAssertTrue(model.errorMessage?.contains("Admin session was locked") == true)
    }

    @MainActor
    func testFirstRunWalletCreationRemainsAvailableBeforeAdminPIN() async throws {
        let container = try ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let address = try self.address("0x1111111111111111111111111111111111111111")
        let persistenceProbe = OperatorPersistenceProbe()
        let lifecycle = BlockingCreateOperatorWalletLifecycle(
            address: address,
            persistenceProbe: persistenceProbe,
            startsBlocked: false
        )
        let namespace = UUID().uuidString
        let model = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.tests.\(namespace)"
            ),
            operatorWalletLifecycle: lifecycle,
            adminPINStore: UnconfiguredAdminPINStore()
        )

        await model.createOperatorWallet()

        XCTAssertTrue(persistenceProbe.wasPersisted)
        XCTAssertEqual(model.operatorAddress, address)
        XCTAssertNil(model.errorMessage)
    }

    func testFundingQRRequiresSavedProvisioningBoundToCurrentOperator() throws {
        let current = try address("0x1111111111111111111111111111111111111111")
        let other = try address("0x2222222222222222222222222222222222222222")
        var settings = AppSettings()

        XCTAssertNil(settings.operatorFundingPayload(for: current))
        settings.provisionedOperatorAddress = other.hex
        XCTAssertNil(settings.operatorFundingPayload(for: current))
        settings.provisionedOperatorAddress = current.hex
        XCTAssertEqual(
            settings.operatorFundingPayload(for: current),
            "ethereum:\(current.hex)@84532"
        )
    }

    @MainActor
    func testResetChecksEveryEnabledNetworkAndBlocksFundedKeyDeletion() async throws {
        let savedSettings = AppPreferences.loadSettings()
        defer { AppPreferences.saveSettings(savedSettings) }
        let container = try ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let deletionProbe = OperatorDeletionProbe()
        let lifecycle = BlockingOperatorWalletLifecycle(
            address: operatorAddress,
            deletionProbe: deletionProbe
        )
        let balanceProbe = ResetNetworkBalanceProbe(
            fundedChainID: TerminalKnownChainProfile.baseSepolia.chainID
        )
        let namespace = UUID().uuidString
        let model = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.tests.\(namespace)"
            ),
            operatorWalletLifecycle: lifecycle,
            adminPINStore: InMemoryAdminPINStore(pin: "123456"),
            operatorResetBalanceReader: { configuration, _ in
                balanceProbe.snapshot(chainID: configuration.chainID)
            }
        )
        // No current profile remembers the chain, but the published device EOA may still hold gas
        // on any network enabled by the app.
        model.settings = AppSettings()
        model.unlockAdmin(with: "123456")

        await model.resetOperatorWallet()

        XCTAssertEqual(
            Set(balanceProbe.requestedChainIDs),
            TerminalKnownChainProfile.supportedChainIDs
        )
        XCTAssertFalse(deletionProbe.wasDeleted)
        XCTAssertEqual(model.operatorAddress, operatorAddress)
        XCTAssertNotNil(model.errorMessage)
    }

    func testOperatorResetBlocksUnresolvedPhasesIncludingNeedsReview() {
        for phase in [
            SettlementTransactionPhase.pending,
            .mined,
            .unknown,
            .needsReview,
        ] {
            XCTAssertTrue(OperatorResetSafety.isUnresolved(phase), "Expected \(phase) to block")
        }
        XCTAssertFalse(OperatorResetSafety.isUnresolved(.final))
        XCTAssertFalse(OperatorResetSafety.isUnresolved(.failed))
    }

    func testOperatorKeyResetIsAllowedOnlyBeforeAnyInvoiceIsIssued() {
        XCTAssertTrue(OperatorResetSafety.allowsOperatorKeyDeletion(issuedInvoiceCount: 0))
        XCTAssertFalse(OperatorResetSafety.allowsOperatorKeyDeletion(issuedInvoiceCount: 1))
        XCTAssertFalse(OperatorResetSafety.allowsOperatorKeyDeletion(issuedInvoiceCount: 10_000))

        XCTAssertNoThrow(try OperatorResetSafety.requireEmptyNativeBalance(
            OperatorNativeBalanceSnapshot(latest: .zero, pending: .zero)
        ))
        XCTAssertThrowsError(try OperatorResetSafety.requireEmptyNativeBalance(
            OperatorNativeBalanceSnapshot(latest: UInt256(1), pending: .zero)
        ))
        XCTAssertThrowsError(try OperatorResetSafety.requireEmptyNativeBalance(
            OperatorNativeBalanceSnapshot(latest: .zero, pending: UInt256(1))
        ))
    }

    func testRecurringSettlementDescriptorsArePersistenceBounded() {
        XCTAssertEqual(
            SettlementReconciliationPolicy.activeFetchDescriptor().fetchLimit,
            SettlementReconciliationPolicy.activeBatchLimit
        )
        XCTAssertEqual(
            SettlementReconciliationPolicy.evidenceFetchDescriptor().fetchLimit,
            SettlementReconciliationPolicy.evidenceBatchLimit
        )
        XCTAssertEqual(
            SettlementReconciliationPolicy.cumulativeReviewFetchDescriptor().fetchLimit,
            SettlementReconciliationPolicy.cumulativeReviewBatchLimit
        )
    }

    @MainActor
    func testFailedPersistenceRollsBackProofIndexMutationsBeforeLaterSave() throws {
        let container = try ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let invoice = try storedInvoice()
        container.mainContext.insert(invoice)
        try container.mainContext.save()
        let namespace = UUID().uuidString
        let model = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.tests.\(namespace)"
            ),
            adminPINStore: KeychainAdminPINStore(
                service: "com.openpasskey.terminal.admin-pin.tests.\(namespace)"
            ),
            persistMainContext: { _ in throw InjectedPersistenceError.failure }
        )

        invoice.confirmedCumulativeSweptAmount = "1000"
        container.mainContext.insert(StoredCanonicalSweepProof(
            identity: "84532:0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:0",
            settlementID: UUID()
        ))
        XCTAssertThrowsError(try model.saveMainContextOrRollback()) { error in
            XCTAssertEqual(error as? InjectedPersistenceError, .failure)
        }

        let restoredInvoice = try XCTUnwrap(
            container.mainContext.fetch(FetchDescriptor<StoredInvoice>()).first
        )
        XCTAssertEqual(restoredInvoice.confirmedCumulativeSweptAmount, "0")
        XCTAssertTrue(
            try container.mainContext.fetch(FetchDescriptor<StoredCanonicalSweepProof>()).isEmpty
        )

        // A later unrelated successful save must not carry either failed proof mutation.
        restoredInvoice.statusLabel = "Unrelated update"
        try container.mainContext.save()
        XCTAssertEqual(restoredInvoice.confirmedCumulativeSweptAmount, "0")
        XCTAssertTrue(
            try container.mainContext.fetch(FetchDescriptor<StoredCanonicalSweepProof>()).isEmpty
        )
    }

    @MainActor
    func testCorruptProofRowBacksOffWithoutStarvingLaterValidEvidenceAcrossRestart() throws {
        let now = Date(timeIntervalSince1970: 20_000)
        let container = try ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let invoice = try storedInvoice()
        let corrupt = try storedFinalSettlement(
            for: invoice,
            transactionHash: "0xdddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
            block: 100
        )
        corrupt.createdAt = now.addingTimeInterval(-2)
        corrupt.eventProofsData = Data("not-json".utf8)
        corrupt.cumulativeEvidenceIndexed = false
        let valid = try storedFinalSettlement(
            for: invoice,
            transactionHash: "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            block: 101
        )
        valid.createdAt = now.addingTimeInterval(-1)
        valid.cumulativeEvidenceIndexed = false
        container.mainContext.insert(invoice)
        container.mainContext.insert(corrupt)
        container.mainContext.insert(valid)
        try container.mainContext.save()

        let firstNamespace = UUID().uuidString
        let firstModel = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.tests.\(firstNamespace)"
            ),
            adminPINStore: KeychainAdminPINStore(
                service: "com.openpasskey.terminal.admin-pin.tests.\(firstNamespace)"
            )
        )
        try firstModel.indexCanonicalSettlementEvidence(now: now, batchLimit: 1)

        let corruptID = corrupt.id
        let validID = valid.id
        let failed = try XCTUnwrap(
            container.mainContext.fetch(FetchDescriptor<StoredSettlement>(
                predicate: #Predicate { $0.id == corruptID }
            )).first
        )
        XCTAssertFalse(failed.cumulativeEvidenceIndexed)
        XCTAssertEqual(failed.cumulativeEvidenceFailureCount, 1)
        XCTAssertEqual(failed.cumulativeEvidenceLastAttemptAt, now)
        XCTAssertEqual(
            failed.cumulativeEvidenceNextAttemptAt,
            now.addingTimeInterval(
                SettlementReconciliationPolicy.evidenceInitialFailureDelay
            )
        )
        XCTAssertNotNil(failed.cumulativeEvidenceLastError)
        XCTAssertTrue(failed.statusLabel.contains("Proof review"))
        XCTAssertEqual(invoice.confirmedCumulativeSweptAmount, "0")
        XCTAssertTrue(
            try container.mainContext.fetch(FetchDescriptor<StoredCanonicalSweepProof>()).isEmpty
        )

        // A fresh AppModel represents process restoration. The durable backoff excludes the
        // corrupt first row, allowing the healthy row to advance in the next bounded pass.
        container.mainContext.rollback()
        let secondNamespace = UUID().uuidString
        let restoredModel = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.tests.\(secondNamespace)"
            ),
            adminPINStore: KeychainAdminPINStore(
                service: "com.openpasskey.terminal.admin-pin.tests.\(secondNamespace)"
            )
        )
        try restoredModel.indexCanonicalSettlementEvidence(
            now: now.addingTimeInterval(1),
            batchLimit: 1
        )

        let indexed = try XCTUnwrap(
            container.mainContext.fetch(FetchDescriptor<StoredSettlement>(
                predicate: #Predicate { $0.id == validID }
            )).first
        )
        XCTAssertTrue(indexed.cumulativeEvidenceIndexed)
        let restoredInvoice = try XCTUnwrap(
            container.mainContext.fetch(FetchDescriptor<StoredInvoice>()).first
        )
        XCTAssertEqual(
            restoredInvoice.confirmedCumulativeSweptAmount,
            restoredInvoice.expectedAmount
        )
        XCTAssertEqual(
            try container.mainContext.fetch(FetchDescriptor<StoredCanonicalSweepProof>()).count,
            1
        )
        XCTAssertFalse(failed.cumulativeEvidenceIndexed)
    }

    @MainActor
    func testCorruptCumulativeReviewBacksOffWithoutStarvingLaterRepairAcrossRestart() throws {
        let now = Date(timeIntervalSince1970: 30_000)
        let container = try ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let invoice = try storedInvoice()
        invoice.confirmedCumulativeSweptAmount = invoice.expectedAmount
        let corrupt = try storedFinalSettlement(
            for: invoice,
            transactionHash: "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            block: 110
        )
        corrupt.phase = .needsReview
        corrupt.cumulativeEvidenceIndexed = true
        corrupt.intentData = Data("not-json".utf8)
        corrupt.createdAt = now.addingTimeInterval(-2)
        corrupt.updatedAt = now.addingTimeInterval(-2)
        let valid = try storedFinalSettlement(
            for: invoice,
            transactionHash: "0xabababababababababababababababababababababababababababababababab",
            block: 111
        )
        valid.phase = .needsReview
        valid.failureReason = "Waiting for cumulative completion."
        valid.cumulativeEvidenceIndexed = true
        valid.createdAt = now.addingTimeInterval(-1)
        valid.updatedAt = now.addingTimeInterval(-1)
        container.mainContext.insert(invoice)
        container.mainContext.insert(corrupt)
        container.mainContext.insert(valid)
        try container.mainContext.save()

        let corruptID = corrupt.id
        let validID = valid.id
        let firstNamespace = UUID().uuidString
        let firstModel = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.tests.\(firstNamespace)"
            ),
            adminPINStore: KeychainAdminPINStore(
                service: "com.openpasskey.terminal.admin-pin.tests.\(firstNamespace)"
            )
        )
        try firstModel.healCumulativeSettlementEvidence(now: now, batchLimit: 1)

        let failed = try XCTUnwrap(
            container.mainContext.fetch(FetchDescriptor<StoredSettlement>(
                predicate: #Predicate { $0.id == corruptID }
            )).first
        )
        XCTAssertEqual(failed.phase, .needsReview)
        XCTAssertEqual(failed.cumulativeReviewFailureCount, 1)
        XCTAssertEqual(failed.cumulativeReviewLastAttemptAt, now)
        XCTAssertEqual(
            failed.cumulativeReviewNextAttemptAt,
            now.addingTimeInterval(SettlementReconciliationPolicy.evidenceInitialFailureDelay)
        )
        XCTAssertNotNil(failed.cumulativeReviewLastError)
        XCTAssertTrue(failed.statusLabel.contains("Repair blocked"))
        XCTAssertEqual(valid.phase, .needsReview)

        // Restoring the process retains the corrupt row's backoff. The next bounded pass can
        // select and finalize the later healthy row from the already-indexed invoice total.
        container.mainContext.rollback()
        let secondNamespace = UUID().uuidString
        let restoredModel = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.tests.\(secondNamespace)"
            ),
            adminPINStore: KeychainAdminPINStore(
                service: "com.openpasskey.terminal.admin-pin.tests.\(secondNamespace)"
            )
        )
        try restoredModel.healCumulativeSettlementEvidence(
            now: now.addingTimeInterval(1),
            batchLimit: 1
        )

        let repaired = try XCTUnwrap(
            container.mainContext.fetch(FetchDescriptor<StoredSettlement>(
                predicate: #Predicate { $0.id == validID }
            )).first
        )
        XCTAssertEqual(repaired.phase, .final)
        XCTAssertNil(repaired.failureReason)
        let stillFailed = try XCTUnwrap(
            container.mainContext.fetch(FetchDescriptor<StoredSettlement>(
                predicate: #Predicate { $0.id == corruptID }
            )).first
        )
        XCTAssertEqual(stillFailed.phase, .needsReview)
        XCTAssertEqual(stillFailed.cumulativeReviewFailureCount, 1)
    }

    @MainActor
    func testForegroundReconciliationFetchIsPersistenceBoundedAndCancellationRollsBackReservation() throws {
        let now = Date(timeIntervalSince1970: 10_000)
        let container = try ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let invoices = try (0..<6).map { index in
            let invoice = try storedInvoice()
            invoice.invoiceID = String(format: "0x%064x", index + 1)
            invoice.createdAt = now.addingTimeInterval(TimeInterval(index))
            container.mainContext.insert(invoice)
            return invoice
        }
        try container.mainContext.save()

        let descriptor = ForegroundInvoiceReconciliationPolicy.fetchDescriptor(
            now: now
        )
        XCTAssertEqual(descriptor.fetchLimit, 4)
        let firstBatch = try container.mainContext.fetch(descriptor)
        XCTAssertEqual(firstBatch.count, 4)
        firstBatch.forEach { $0.beginForegroundReconciliation(at: now) }
        XCTAssertEqual(
            firstBatch[0].nextReconciliationAt,
            now.addingTimeInterval(ForegroundInvoiceReconciliationPolicy.inFlightLeaseDelay)
        )
        XCTAssertLessThan(
            ForegroundInvoiceReconciliationPolicy.inFlightLeaseDelay,
            ForegroundInvoiceReconciliationPolicy.maximumFailureDelay
        )
        try container.mainContext.save()

        let secondBatch = try container.mainContext.fetch(
            ForegroundInvoiceReconciliationPolicy.fetchDescriptor(now: now)
        )
        XCTAssertEqual(secondBatch.map(\.invoiceID), Array(invoices[4...5]).map(\.invoiceID))

        let failing = firstBatch[0]
        failing.recordForegroundReconciliationFailure("RPC unavailable", at: now)
        XCTAssertEqual(failing.reconciliationFailureCount, 1)
        XCTAssertEqual(
            failing.nextReconciliationAt,
            now.addingTimeInterval(ForegroundInvoiceReconciliationPolicy.initialFailureDelay)
        )
        failing.recordForegroundReconciliationFailure("RPC unavailable", at: now)
        XCTAssertEqual(
            failing.nextReconciliationAt,
            now.addingTimeInterval(
                ForegroundInvoiceReconciliationPolicy.initialFailureDelay * 2
            )
        )
        XCTAssertEqual(
            ForegroundInvoiceReconciliationPolicy.failureDelay(failureCount: 99),
            ForegroundInvoiceReconciliationPolicy.maximumFailureDelay
        )

        let priorFailureCount = failing.reconciliationFailureCount
        let priorFailure = failing.lastReconciliationError
        failing.beginForegroundReconciliation(at: now.addingTimeInterval(100))
        failing.cancelForegroundReconciliation(at: now.addingTimeInterval(101))
        XCTAssertEqual(failing.nextReconciliationAt, now.addingTimeInterval(101))
        XCTAssertEqual(failing.reconciliationFailureCount, priorFailureCount)
        XCTAssertEqual(failing.lastReconciliationError, priorFailure)

        let newer = invoices[5]
        newer.observedBlock = 200
        newer.observedBalance = "9"
        try newer.recordForegroundReconciliationSuccess(
            PaymentObservation(
                invoiceID: try Bytes32(hex: newer.invoiceID),
                blockNumber: 199,
                blockHash: appBlockHash(199),
                balance: .zero,
                status: .waiting,
                thresholdBlock: nil,
                thresholdBlockHash: nil
            ),
            cumulativeConfirmedSweptAmount: .zero,
            at: now
        )
        XCTAssertEqual(newer.observedBlock, 200)
        XCTAssertEqual(newer.observedBalance, "9")
    }

    func testSweepableConfirmationCoversInitialPartialCumulativeAndRepeatBalances() throws {
        let invoice = try storedInvoice()
        let invoiceID = try Bytes32(hex: invoice.invoiceID)

        try invoice.apply(
            observation(invoiceID: invoiceID, balance: UInt256(1_000), block: 10),
            cumulativeConfirmedSweptAmount: .zero
        )
        XCTAssertFalse(invoice.hasConfirmedSweepableFunds)
        let initialCursor = try XCTUnwrap(invoice.sweepableConfirmationCursor)
        try invoice.apply(
            observation(
                invoiceID: invoiceID,
                balance: UInt256(1_000),
                block: 11,
                validatedCursors: [initialCursor]
            ),
            cumulativeConfirmedSweptAmount: .zero
        )
        XCTAssertTrue(invoice.hasConfirmedSweepableFunds)
        let initialSnapshot = try XCTUnwrap(
            invoice.confirmedSweepableSnapshot(confirmedCumulative: .zero)
        )

        // A partial cumulative proof changes the remaining requirement and must start a new
        // confirmation window for the exact currently sweepable balance.
        try invoice.apply(
            observation(invoiceID: invoiceID, balance: UInt256(599), block: 20),
            cumulativeConfirmedSweptAmount: UInt256(400)
        )
        XCTAssertFalse(invoice.hasConfirmedSweepableFunds)
        try invoice.apply(
            observation(invoiceID: invoiceID, balance: UInt256(600), block: 21),
            cumulativeConfirmedSweptAmount: UInt256(400)
        )
        XCTAssertFalse(invoice.hasConfirmedSweepableFunds)
        let partialCursor = try XCTUnwrap(invoice.sweepableConfirmationCursor)
        try invoice.apply(
            observation(
                invoiceID: invoiceID,
                balance: UInt256(600),
                block: 22,
                validatedCursors: [partialCursor]
            ),
            cumulativeConfirmedSweptAmount: UInt256(400)
        )
        XCTAssertTrue(invoice.hasConfirmedSweepableFunds)
        XCTAssertNotEqual(
            invoice.confirmedSweepableSnapshot(confirmedCumulative: UInt256(400)),
            initialSnapshot,
            "A cumulative-proof change must produce a distinct confirmation snapshot"
        )

        // Once cumulative proof reaches the original amount, any positive repeat is sweepable,
        // but it receives its own immutable two-block confirmation window.
        try invoice.apply(
            observation(invoiceID: invoiceID, balance: UInt256(1), block: 30),
            cumulativeConfirmedSweptAmount: UInt256(1_000)
        )
        XCTAssertFalse(invoice.hasConfirmedSweepableFunds)
        XCTAssertFalse(
            invoice.hasConfirmedSweepableFunds(confirmedCumulative: UInt256(999)),
            "A changed proof baseline must invalidate the saved confirmation cursor"
        )
        let repeatCursor = try XCTUnwrap(invoice.sweepableConfirmationCursor)
        try invoice.apply(
            observation(
                invoiceID: invoiceID,
                balance: UInt256(1),
                block: 31,
                validatedCursors: [repeatCursor]
            ),
            cumulativeConfirmedSweptAmount: UInt256(1_000)
        )
        XCTAssertTrue(invoice.hasConfirmedSweepableFunds)

        try invoice.apply(
            observation(invoiceID: invoiceID, balance: UInt256(2), block: 32),
            cumulativeConfirmedSweptAmount: UInt256(1_000)
        )
        XCTAssertFalse(
            invoice.hasConfirmedSweepableFunds,
            "An added one-block payment must not reuse the prior balance's confirmations"
        )
    }

    func testReplacementForkWithSameBalanceResetsSweepableCursorAndFailsLiveRevalidation() throws {
        let invoice = try storedInvoice()
        let invoiceID = try Bytes32(hex: invoice.invoiceID)

        try invoice.apply(
            observation(invoiceID: invoiceID, balance: UInt256(1_000), block: 40),
            cumulativeConfirmedSweptAmount: .zero
        )
        let originalCursor = try XCTUnwrap(invoice.sweepableConfirmationCursor)
        try invoice.apply(
            observation(
                invoiceID: invoiceID,
                balance: UInt256(1_000),
                block: 41,
                validatedCursors: [originalCursor]
            ),
            cumulativeConfirmedSweptAmount: .zero
        )
        XCTAssertTrue(invoice.hasConfirmedSweepableFunds)
        let preparedSnapshot = try XCTUnwrap(
            invoice.confirmedSweepableSnapshot(confirmedCumulative: .zero)
        )

        let replacementObservation = observation(
            invoiceID: invoiceID,
            balance: UInt256(1_000),
            block: 42,
            fork: 1,
            validatedCursors: []
        )
        XCTAssertFalse(
            preparedSnapshot.isRevalidated(by: replacementObservation),
            "Prepare/sign validation must reject equal balance when the saved hash is displaced"
        )
        try invoice.apply(
            replacementObservation,
            cumulativeConfirmedSweptAmount: .zero
        )
        XCTAssertFalse(invoice.hasConfirmedSweepableFunds)
        XCTAssertEqual(invoice.sweepableThresholdBlock, 42)
        XCTAssertEqual(invoice.sweepableThresholdBlockHash, appBlockHash(42, fork: 1).hex)
    }

    @MainActor
    func testMatchingFinalCumulativeSweepProofIsCanonicalAndReleasesActiveClaim() throws {
        let invoice = try storedInvoice()
        invoice.statusLabel = "Paid"
        invoice.observedBalance = invoice.expectedAmount
        let key = try invoice.cumulativeSettlementKey()
        let request = try invoice.paymentRequest()
        let intent = try SettlementIntent(
            chainID: request.chainID,
            vault: request.vault,
            token: request.token.address,
            sessions: [
                SettlementSession(
                    invoiceID: request.invoiceID,
                    receiver: request.receiver,
                    expectedAmount: request.expectedAmount
                ),
            ]
        )
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let transactionHash = try Bytes32(
            hex: "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        )
        let prepared = PreparedSettlement(
            intent: intent,
            operatorAddress: operatorAddress,
            calldata: Data([0x01]),
            gasLimit: 100_000,
            feeQuote: EIP1559FeeQuote(
                maxPriorityFeePerGas: 1,
                maxFeePerGas: 2,
                source: .eip1559
            ),
            l1DataFeeReserve: .zero,
            maximumGasCost: UInt256(200_000),
            operatorBalance: UInt256(200_000),
            observedTokenBalances: [request.expectedAmount]
        )
        let signed = SignedSettlement(
            intent: intent,
            transactionHash: transactionHash,
            nonce: 0,
            rawTransaction: Data([0x02])
        )
        let record = try StoredSettlement(
            signed: signed,
            prepared: prepared,
            rpcURL: URL(string: "https://example-rpc.invalid")!,
            tokenSymbol: request.token.symbol,
            tokenDecimals: request.token.decimals,
            requiredConfirmations: 2
        )
        try record.apply(
            SettlementSubmission(
                intent: intent,
                transactionHash: transactionHash,
                nonce: 0,
                rawTransaction: Data([0x02]),
                phase: .unknown,
                broadcastError: "already known"
            )
        )
        XCTAssertEqual(record.broadcastError, "already known")
        try record.apply(
            SettlementReconciliation(
                phase: .final,
                blockNumber: 100,
                confirmations: 2,
                verifiedSweeps: [
                    VerifiedSweep(
                        invoiceID: request.invoiceID,
                        receiver: request.receiver,
                        token: request.token.address,
                        sweptAmount: request.expectedAmount,
                        expectedAmount: request.expectedAmount,
                        fee: .zero,
                        logIndex: 0,
                        blockHash: try Bytes32(
                            hex: "0xcccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                        ),
                        transactionHash: transactionHash
                    ),
                ],
                failureReason: nil
            )
        )
        XCTAssertNil(record.broadcastError)

        let totals = try OperatorResetSafety.confirmedCumulativeTotals([record])
        XCTAssertEqual(totals[key], request.expectedAmount)
        XCTAssertFalse(record.isActiveClaim, "Final proof must not hide a later receiver payment")
        record.phase = .pending
        XCTAssertTrue(record.isActiveClaim)
        record.phase = .needsReview
        record.failureReason = "Persisted partial review"
        try OperatorResetSafety.applyCumulativeSettlementEvidence([record])
        XCTAssertEqual(record.phase, .final)
        XCTAssertNil(record.failureReason)

        let container = try ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        container.mainContext.insert(invoice)
        container.mainContext.insert(record)
        try container.mainContext.save()
        let namespace = UUID().uuidString
        let model = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.tests.\(namespace)"
            ),
            adminPINStore: KeychainAdminPINStore(
                service: "com.openpasskey.terminal.admin-pin.tests.\(namespace)"
            )
        )

        record.cumulativeEvidenceIndexed = false
        try model.indexCanonicalSettlementEvidence()
        XCTAssertEqual(invoice.confirmedCumulativeSweptAmount, request.expectedAmount.decimalString)
        XCTAssertEqual(invoice.confirmedCumulativeSweptThroughBlock, 100)
        XCTAssertTrue(record.cumulativeEvidenceIndexed)
        XCTAssertEqual(
            try container.mainContext.fetch(FetchDescriptor<StoredCanonicalSweepProof>()).count,
            1
        )

        record.cumulativeEvidenceIndexed = false
        try model.indexCanonicalSettlementEvidence()
        XCTAssertEqual(
            invoice.confirmedCumulativeSweptAmount,
            request.expectedAmount.decimalString,
            "Re-indexing the same transaction/log proof must be idempotent"
        )
    }

    func testReplacementCanonicalReceiptClearsProvisionalProofWithoutCumulativeCredit() throws {
        let invoice = try storedInvoice()
        let request = try invoice.paymentRequest()
        let transactionHash = try Bytes32(
            hex: "0xdddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        )
        let record = try storedFinalSettlement(
            for: invoice,
            transactionHash: transactionHash.hex,
            block: 100
        )
        let provisionalProof = VerifiedSweep(
            invoiceID: request.invoiceID,
            receiver: request.receiver,
            token: request.token.address,
            sweptAmount: request.expectedAmount,
            expectedAmount: request.expectedAmount,
            fee: .zero,
            logIndex: 0,
            blockHash: appBlockHash(100),
            transactionHash: transactionHash
        )

        try record.apply(SettlementReconciliation(
            phase: .mined,
            blockNumber: 100,
            confirmations: 1,
            verifiedSweeps: [provisionalProof],
            failureReason: nil
        ))
        XCTAssertEqual(try record.eventProofs().count, 1)

        try record.apply(SettlementReconciliation(
            phase: .unknown,
            blockNumber: 100,
            confirmations: 1,
            verifiedSweeps: [],
            failureReason: "Receipt identity changed"
        ))
        XCTAssertTrue(try record.eventProofs().isEmpty)

        // A replacement canonical receipt succeeds but its only session has no positive proof.
        // Its exact empty subset must replace receipt A's provisional evidence.
        try record.apply(SettlementReconciliation(
            phase: .needsReview,
            blockNumber: 101,
            confirmations: 2,
            verifiedSweeps: [],
            failureReason: "No unique nonzero event"
        ))

        XCTAssertEqual(record.phase, .needsReview)
        XCTAssertTrue(try record.eventProofs().isEmpty)
        XCTAssertTrue(try OperatorResetSafety.canonicalEvidence(in: record).isEmpty)
        XCTAssertTrue(try OperatorResetSafety.confirmedCumulativeTotals([record]).isEmpty)
        XCTAssertFalse(record.cumulativeEvidenceIndexed)
    }

    @MainActor
    func testAppModelLifecycleOperationGateIsNonReentrant() {
        let gate = AppModelOperationGate()
        XCTAssertTrue(gate.acquire())
        XCTAssertFalse(gate.acquire())
        gate.release()
        XCTAssertTrue(gate.acquire())
        gate.release()
    }

    @MainActor
    func testSaleOwnsProfileSelectionGateAndAbortsIfConfigurationChanges() async throws {
        let savedSettings = AppPreferences.loadSettings()
        defer { AppPreferences.saveSettings(savedSettings) }
        let container = try ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let first = try AppSettings().applying(
            paymentConfiguration(
                vault: "0x2222222222222222222222222222222222222222",
                token: "0x3333333333333333333333333333333333333333",
                symbol: "AUDM"
            ),
            boundTo: operatorAddress
        )
        let firstID = try XCTUnwrap(first.selectedPaymentProfileID)
        let initial = try first.applying(
            paymentConfiguration(
                vault: "0x4444444444444444444444444444444444444444",
                token: "0x5555555555555555555555555555555555555555",
                symbol: "USDC"
            ),
            boundTo: operatorAddress
        )
        let secondID = try XCTUnwrap(initial.selectedPaymentProfileID)
        let probe = BlockingReadinessRefreshProbe(
            status: OperatorChainStatus(
                chainID: TerminalKnownChainProfile.baseSepolia.chainID,
                balance: TerminalKnownChainProfile.baseSepolia.minimumOperatorNativeReserve,
                isAuthorizedOperator: true,
                isVaultOwner: false,
                isLowGas: false
            )
        )
        let namespace = UUID().uuidString
        let model = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.tests.\(namespace)"
            ),
            operatorWalletLifecycle: BlockingOperatorWalletLifecycle(address: operatorAddress),
            adminPINStore: InMemoryAdminPINStore(pin: "123456"),
            currentConfigurationValidation: { configuration in
                try await probe.validate(configuration)
            }
        )
        model.settings = initial

        let sale = Task { await model.createSale(displayAmount: "10.50") }
        await probe.waitUntilValidationStarts()
        XCTAssertTrue(model.operationBusy)

        await model.selectPaymentProfile(id: firstID)
        XCTAssertEqual(model.settings.selectedPaymentProfileID, secondID)
        XCTAssertEqual(
            model.errorMessage,
            "Another terminal lifecycle operation is already in progress."
        )

        // Simulate any out-of-band settings mutation at the validation boundary. The sale must
        // reject the stale snapshot before it can query status, derive an invoice, or persist it.
        model.settings = try initial.selectingPaymentProfile(id: firstID)
        await probe.finishValidation()
        await sale.value

        XCTAssertEqual(model.settings.selectedPaymentProfileID, firstID)
        XCTAssertTrue(model.errorMessage?.contains("settings changed during validation") == true)
        XCTAssertTrue(
            try container.mainContext.fetch(FetchDescriptor<StoredInvoice>()).isEmpty
        )
        XCTAssertFalse(model.operationBusy)
    }

    @MainActor
    func testRemovingPaymentProfileRequiresUnlockedAdminSession() async throws {
        let savedSettings = AppPreferences.loadSettings()
        defer { AppPreferences.saveSettings(savedSettings) }
        let container = try ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let first = try AppSettings().applying(
            paymentConfiguration(
                vault: "0x2222222222222222222222222222222222222222",
                token: "0x3333333333333333333333333333333333333333",
                symbol: "AUDM"
            ),
            boundTo: operatorAddress
        )
        let initial = try first.applying(
            paymentConfiguration(
                vault: "0x4444444444444444444444444444444444444444",
                token: "0x5555555555555555555555555555555555555555",
                symbol: "USDC"
            ),
            boundTo: operatorAddress
        )
        let selectedID = try XCTUnwrap(initial.selectedPaymentProfileID)
        let namespace = UUID().uuidString
        let model = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.tests.\(namespace)"
            ),
            operatorWalletLifecycle: BlockingOperatorWalletLifecycle(address: operatorAddress),
            adminPINStore: InMemoryAdminPINStore(pin: "123456")
        )
        model.settings = initial
        XCTAssertFalse(model.adminUnlocked)

        await model.removePaymentProfile(id: selectedID)

        XCTAssertEqual(model.settings, initial)
        XCTAssertEqual(model.errorMessage, "Unlock Admin before removing a payment profile.")
    }

    @MainActor
    func testAcknowledgingMigrationNoticePreservesValidatedReadinessFingerprint() async throws {
        let savedSettings = AppPreferences.loadSettings()
        defer { AppPreferences.saveSettings(savedSettings) }
        let container = try ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let current = try AppSettings().applying(
            paymentConfiguration(
                vault: "0x2222222222222222222222222222222222222222",
                token: "0x3333333333333333333333333333333333333333",
                symbol: "AUDM"
            ),
            boundTo: operatorAddress
        )
        var legacyJSON = try XCTUnwrap(
            JSONSerialization.jsonObject(with: JSONEncoder().encode(current))
                as? [String: Any]
        )
        legacyJSON["schemaVersion"] = 2
        legacyJSON["confirmationBlocks"] = "1"
        var legacyProfiles = try XCTUnwrap(legacyJSON["paymentProfiles"] as? [[String: Any]])
        legacyProfiles[0]["confirmationBlocks"] = "1"
        legacyJSON["paymentProfiles"] = legacyProfiles
        let migrated = try JSONDecoder().decode(
            AppSettings.self,
            from: JSONSerialization.data(withJSONObject: legacyJSON)
        )
        XCTAssertNotNil(migrated.migrationNotice)

        let namespace = UUID().uuidString
        let model = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.tests.\(namespace)"
            ),
            operatorWalletLifecycle: BlockingOperatorWalletLifecycle(address: operatorAddress),
            adminPINStore: InMemoryAdminPINStore(pin: "123456"),
            currentConfigurationValidation: { _ in }
        )
        model.settings = migrated
        let validated = await model.validateConfiguration()
        XCTAssertTrue(validated)
        let validatedFingerprint = try XCTUnwrap(model.validatedConfigurationFingerprint)

        model.acknowledgeSettingsMigrationNotice()

        XCTAssertNil(model.settings.migrationNotice)
        XCTAssertEqual(model.validatedConfigurationFingerprint, validatedFingerprint)
        XCTAssertEqual(model.validationMessage, "On-chain validation passed")
    }

    @MainActor
    func testForegroundReconciliationGateCannotBeClearedByOverlappingInvocation() throws {
        let gate = ForegroundInvoiceReconciliationGate()
        let owner = try XCTUnwrap(gate.acquire())
        XCTAssertTrue(gate.isInFlight)
        XCTAssertNil(gate.acquire())

        gate.release(UUID())
        XCTAssertTrue(gate.isInFlight)

        gate.release(owner)
        XCTAssertFalse(gate.isInFlight)
    }

    func testPersistedSettingsCannotOverrideImmutableDeploymentPins() throws {
        let profile = TerminalKnownChainProfile.baseSepolia
        var settings = AppSettings()
        XCTAssertEqual(try settings.configuration().deployment.factory, profile.factory)
        XCTAssertEqual(
            try settings.configuration().deployment.receiverImplementation,
            profile.receiverImplementation
        )
        XCTAssertEqual(try settings.configuration().create2TestVector, profile.create2TestVector)

        settings.factory = "0x1111111111111111111111111111111111111111"
        XCTAssertThrowsError(try settings.configuration())
        settings = AppSettings()
        settings.receiverImplementation = "0x2222222222222222222222222222222222222222"
        XCTAssertThrowsError(try settings.configuration())
        settings = AppSettings()
        settings.chainID = "1"
        XCTAssertThrowsError(try settings.configuration())

        settings = AppSettings()
        settings.tokenSymbol = " AUD "
        XCTAssertThrowsError(try settings.configuration())
        settings.tokenSymbol = "AU\u{202E}D"
        XCTAssertThrowsError(try settings.configuration())
    }

    func testReprovisioningDoesNotRewriteHistoricalInvoiceConfiguration() throws {
        let oldInvoice = try storedInvoice()
        let oldSnapshot = try oldInvoice.configurationSnapshot()
        let profile = TerminalKnownChainProfile.baseSepolia
        let newVault = try address("0x3333333333333333333333333333333333333333")
        let currentOperator = try address("0x1111111111111111111111111111111111111111")
        let newConfiguration = try TerminalConfiguration(
            chainID: profile.chainID,
            rpcEndpoints: [URL(string: "https://new-operational-rpc.example.invalid")!],
            protocolVersion: profile.protocolVersion,
            deployment: try OPKDeployment(
                factory: profile.factory,
                receiverImplementation: profile.receiverImplementation,
                vault: newVault
            ),
            tokens: oldSnapshot.tokens,
            confirmationPolicy: oldSnapshot.confirmationPolicy,
            create2TestVector: profile.create2TestVector
        )

        let currentSettings = try AppSettings().applying(
            newConfiguration,
            boundTo: currentOperator
        )

        XCTAssertEqual(currentSettings.vault, newVault.hex)
        XCTAssertEqual(try oldInvoice.configurationSnapshot(), oldSnapshot)
        XCTAssertNotEqual(try oldInvoice.configurationSnapshot().deployment.vault, newVault)
    }

    private func address(_ value: String) throws -> EthereumAddress {
        try EthereumAddress(hex: value, allowZero: false)
    }

    private func paymentConfiguration(
        vault: String,
        token: String,
        symbol: String
    ) throws -> TerminalConfiguration {
        let profile = TerminalKnownChainProfile.baseSepolia
        return try TerminalConfiguration(
            chainID: profile.chainID,
            rpcEndpoints: [profile.rpcEndpoint],
            protocolVersion: profile.protocolVersion,
            deployment: OPKDeployment(
                factory: profile.factory,
                receiverImplementation: profile.receiverImplementation,
                vault: address(vault)
            ),
            tokens: [PaymentToken(
                address: address(token),
                symbol: symbol,
                decimals: 6
            )],
            confirmationPolicy: ConfirmationPolicy(
                requiredBlocks: profile.defaultConfirmationBlocks
            ),
            create2TestVector: profile.create2TestVector
        )
    }

    private func observation(
        invoiceID: Bytes32,
        balance: UInt256,
        block: UInt64,
        fork: UInt64 = 0,
        validatedCursors: [PaymentConfirmationCursor] = []
    ) -> PaymentObservation {
        PaymentObservation(
            invoiceID: invoiceID,
            blockNumber: block,
            blockHash: appBlockHash(block, fork: fork),
            balance: balance,
            status: balance.isZero ? .waiting : .partial(received: balance),
            thresholdBlock: nil,
            thresholdBlockHash: nil,
            validatedPreviousCursors: validatedCursors
        )
    }

    private func storedInvoice() throws -> StoredInvoice {
        let configuration = try AppSettings().configuration()
        let request = try InvoiceFactory.create(
            terminalIdentifier: TerminalIdentifier(
                address: address("0x1111111111111111111111111111111111111111")
            ),
            amount: UInt256(1_000),
            token: configuration.tokens[0],
            configuration: configuration,
            createdAt: Date(timeIntervalSince1970: 1_000),
            nonce: Bytes32(
                hex: "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            )
        )
        return try StoredInvoice(request: request, configuration: configuration)
    }

    private func storedFinalSettlement(
        for invoice: StoredInvoice,
        transactionHash: String,
        block: UInt64
    ) throws -> StoredSettlement {
        let request = try invoice.paymentRequest()
        let intent = try SettlementIntent(
            chainID: request.chainID,
            vault: request.vault,
            token: request.token.address,
            sessions: [
                SettlementSession(
                    invoiceID: request.invoiceID,
                    receiver: request.receiver,
                    expectedAmount: request.expectedAmount
                ),
            ]
        )
        let operatorAddress = try address("0x1111111111111111111111111111111111111111")
        let hash = try Bytes32(hex: transactionHash)
        let prepared = PreparedSettlement(
            intent: intent,
            operatorAddress: operatorAddress,
            calldata: Data([0x01]),
            gasLimit: 100_000,
            feeQuote: EIP1559FeeQuote(
                maxPriorityFeePerGas: 1,
                maxFeePerGas: 2,
                source: .eip1559
            ),
            l1DataFeeReserve: .zero,
            maximumGasCost: UInt256(200_000),
            operatorBalance: UInt256(200_000),
            observedTokenBalances: [request.expectedAmount]
        )
        let signed = SignedSettlement(
            intent: intent,
            transactionHash: hash,
            nonce: 0,
            rawTransaction: Data([0x02])
        )
        let record = try StoredSettlement(
            signed: signed,
            prepared: prepared,
            rpcURL: URL(string: "https://example-rpc.invalid")!,
            tokenSymbol: request.token.symbol,
            tokenDecimals: request.token.decimals,
            requiredConfirmations: 2
        )
        try record.apply(SettlementReconciliation(
            phase: .final,
            blockNumber: block,
            confirmations: 2,
            verifiedSweeps: [
                VerifiedSweep(
                    invoiceID: request.invoiceID,
                    receiver: request.receiver,
                    token: request.token.address,
                    sweptAmount: request.expectedAmount,
                    expectedAmount: request.expectedAmount,
                    fee: .zero,
                    logIndex: 0,
                    blockHash: appBlockHash(block),
                    transactionHash: hash
                ),
            ],
            failureReason: nil
        ))
        return record
    }
}

private final class InMemoryAdminPINStore: AdminPINManaging, @unchecked Sendable {
    private let pin: String

    init(pin: String) {
        self.pin = pin
    }

    var isConfigured: Bool { true }

    func setPIN(_ pin: String) throws {
        throw AdminPINError.alreadyConfigured
    }

    func verify(_ pin: String) throws {
        guard pin == self.pin else {
            throw AdminPINError.invalidPIN(retryAfterSeconds: nil)
        }
    }

    func secondsUntilNextAttempt() throws -> Int { 0 }
}

private final class UnconfiguredAdminPINStore: AdminPINManaging, @unchecked Sendable {
    var isConfigured: Bool { false }

    func setPIN(_ pin: String) throws {}

    func verify(_ pin: String) throws {
        throw AdminPINError.notConfigured
    }

    func secondsUntilNextAttempt() throws -> Int { 0 }
}

private final class ResetNetworkBalanceProbe: @unchecked Sendable {
    private let lock = NSLock()
    private let fundedChainID: UInt64
    private var chains = [UInt64]()

    init(fundedChainID: UInt64) {
        self.fundedChainID = fundedChainID
    }

    var requestedChainIDs: [UInt64] {
        lock.withLock { chains }
    }

    func snapshot(chainID: UInt64) -> OperatorNativeBalanceSnapshot {
        lock.withLock { chains.append(chainID) }
        let balance = chainID == fundedChainID ? UInt256(1) : .zero
        return OperatorNativeBalanceSnapshot(latest: balance, pending: balance)
    }
}

private actor BlockingReadinessRefreshProbe {
    private let status: OperatorChainStatus
    private var validationStarted = false
    private var validationReleased = false
    private var statusReadStarted = false
    private var statusReadReleased = false
    private var validationStartWaiters = [CheckedContinuation<Void, Never>]()
    private var validationReleaseContinuation: CheckedContinuation<Void, Never>?
    private var statusStartWaiters = [CheckedContinuation<Void, Never>]()
    private var statusReleaseContinuation: CheckedContinuation<Void, Never>?

    init(status: OperatorChainStatus) {
        self.status = status
    }

    func validate(_ configuration: TerminalConfiguration) async throws {
        validationStarted = true
        validationStartWaiters.forEach { $0.resume() }
        validationStartWaiters.removeAll()
        if !validationReleased {
            await withCheckedContinuation { continuation in
                validationReleaseContinuation = continuation
            }
        }
    }

    func readStatus(
        configuration: TerminalConfiguration,
        address: EthereumAddress
    ) async throws -> OperatorChainStatus {
        statusReadStarted = true
        statusStartWaiters.forEach { $0.resume() }
        statusStartWaiters.removeAll()
        if !statusReadReleased {
            await withCheckedContinuation { continuation in
                statusReleaseContinuation = continuation
            }
        }
        return status
    }

    func waitUntilValidationStarts() async {
        guard !validationStarted else { return }
        await withCheckedContinuation { continuation in
            validationStartWaiters.append(continuation)
        }
    }

    func finishValidation() {
        validationReleased = true
        validationReleaseContinuation?.resume()
        validationReleaseContinuation = nil
    }

    func waitUntilStatusReadStarts() async {
        guard !statusReadStarted else { return }
        await withCheckedContinuation { continuation in
            statusStartWaiters.append(continuation)
        }
    }

    func finishStatusRead() {
        statusReadReleased = true
        statusReleaseContinuation?.resume()
        statusReleaseContinuation = nil
    }
}

private actor BlockingProvisioningValidator: TerminalProvisioningValidating {
    private let result: ProvisionedTerminalConfiguration
    private var derivationStarted = false
    private var derivationReleased = false
    private var startWaiters = [CheckedContinuation<Void, Never>]()
    private var releaseContinuation: CheckedContinuation<Void, Never>?

    init(result: ProvisionedTerminalConfiguration) {
        self.result = result
    }

    func deriveAndValidate(
        _ payload: TerminalProvisioningPayload,
        expectedOperator: EthereumAddress,
        confirmationPolicy: ConfirmationPolicy,
        rpcEndpointOverride: URL?
    ) async throws -> ProvisionedTerminalConfiguration {
        derivationStarted = true
        startWaiters.forEach { $0.resume() }
        startWaiters.removeAll()
        if !derivationReleased {
            await withCheckedContinuation { continuation in
                releaseContinuation = continuation
            }
        }
        return result
    }

    func waitUntilDerivationStarts() async {
        guard !derivationStarted else { return }
        await withCheckedContinuation { continuation in
            startWaiters.append(continuation)
        }
    }

    func finishDerivation() {
        derivationReleased = true
        releaseContinuation?.resume()
        releaseContinuation = nil
    }
}

private final class OperatorDeletionProbe: @unchecked Sendable {
    private let lock = NSLock()
    private var deleted = false

    var wasDeleted: Bool {
        lock.lock()
        defer { lock.unlock() }
        return deleted
    }

    func markDeleted() {
        lock.lock()
        deleted = true
        lock.unlock()
    }
}

private final class OperatorPersistenceProbe: @unchecked Sendable {
    private let lock = NSLock()
    private var persisted = false

    var wasPersisted: Bool {
        lock.lock()
        defer { lock.unlock() }
        return persisted
    }

    func markPersisted() {
        lock.lock()
        persisted = true
        lock.unlock()
    }
}

private actor BlockingCreateOperatorWalletLifecycle: OperatorWalletLifecycleManaging {
    private let address: EthereumAddress
    private let persistenceProbe: OperatorPersistenceProbe
    private var persistenceBoundaryReached = false
    private var persistenceReleased: Bool
    private var boundaryWaiters = [CheckedContinuation<Void, Never>]()
    private var releaseContinuation: CheckedContinuation<Void, Never>?

    init(
        address: EthereumAddress,
        persistenceProbe: OperatorPersistenceProbe,
        startsBlocked: Bool
    ) {
        self.address = address
        self.persistenceProbe = persistenceProbe
        persistenceReleased = !startsBlocked
    }

    nonisolated func existingAddress() throws -> EthereumAddress? { nil }

    func create(
        reason: String,
        persistenceAuthorization: @Sendable (
            _ persistence: () throws -> EthereumAddress
        ) throws -> EthereumAddress
    ) async throws -> EthereumAddress {
        persistenceBoundaryReached = true
        boundaryWaiters.forEach { $0.resume() }
        boundaryWaiters.removeAll()
        if !persistenceReleased {
            await withCheckedContinuation { continuation in
                releaseContinuation = continuation
            }
        }
        return try persistenceAuthorization { [address, persistenceProbe] in
            persistenceProbe.markPersisted()
            return address
        }
    }

    func reset(
        reason: String,
        beforeDeletion: @Sendable () async throws -> Void,
        deletionAuthorization: @Sendable (
            _ deletion: () throws -> Void
        ) throws -> Void
    ) async throws {
        throw OperatorWalletError.walletNotCreated
    }

    func waitUntilPersistenceBoundary() async {
        guard !persistenceBoundaryReached else { return }
        await withCheckedContinuation { continuation in
            boundaryWaiters.append(continuation)
        }
    }

    func continuePersistence() {
        persistenceReleased = true
        releaseContinuation?.resume()
        releaseContinuation = nil
    }
}

private actor BlockingOperatorWalletLifecycle: OperatorWalletLifecycleManaging {
    nonisolated let address: EthereumAddress
    private let deletionProbe: OperatorDeletionProbe
    private var deletionBoundaryReached = false
    private var deletionReleased = false
    private var boundaryWaiters = [CheckedContinuation<Void, Never>]()
    private var releaseContinuation: CheckedContinuation<Void, Never>?

    init(
        address: EthereumAddress,
        deletionProbe: OperatorDeletionProbe = OperatorDeletionProbe()
    ) {
        self.address = address
        self.deletionProbe = deletionProbe
    }

    nonisolated func existingAddress() throws -> EthereumAddress? { address }

    func create(
        reason: String,
        persistenceAuthorization: @Sendable (
            _ persistence: () throws -> EthereumAddress
        ) throws -> EthereumAddress
    ) async throws -> EthereumAddress {
        try persistenceAuthorization { address }
    }

    func reset(
        reason: String,
        beforeDeletion: @Sendable () async throws -> Void,
        deletionAuthorization: @Sendable (
            _ deletion: () throws -> Void
        ) throws -> Void
    ) async throws {
        try await beforeDeletion()
        deletionBoundaryReached = true
        boundaryWaiters.forEach { $0.resume() }
        boundaryWaiters.removeAll()
        if !deletionReleased {
            await withCheckedContinuation { continuation in
                releaseContinuation = continuation
            }
        }
        try deletionAuthorization { [deletionProbe] in
            deletionProbe.markDeleted()
        }
    }

    func waitUntilDeletionBoundary() async {
        guard !deletionBoundaryReached else { return }
        await withCheckedContinuation { continuation in
            boundaryWaiters.append(continuation)
        }
    }

    func continueDeletion() {
        deletionReleased = true
        releaseContinuation?.resume()
        releaseContinuation = nil
    }
}

private func appBlockHash(_ block: UInt64, fork: UInt64 = 0) -> Bytes32 {
    let prefix = String(repeating: "0", count: 32)
    let forkHex = String(format: "%016llx", fork)
    let blockHex = String(format: "%016llx", block)
    return try! Bytes32(hex: "0x\(prefix)\(forkHex)\(blockHex)")
}

private enum InjectedPersistenceError: Error, Equatable {
    case failure
}
