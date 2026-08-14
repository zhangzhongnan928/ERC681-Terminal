import OPKTerminalCore
import SwiftUI
import UIKit

private enum SettingsFocusField: Hashable {
    case createPIN
    case confirmPIN
    case unlockPIN
    case merchantName
    case merchantABN
    case rpcURL
    case vault
    case token
}

struct SettingsExternalLink: Equatable, Sendable {
    let label: String
    let destination: URL
}

enum SettingsExternalLinks {
    static let privacyPolicy = SettingsExternalLink(
        label: "Privacy Policy",
        destination: URL(string: "https://www.openpasskey.com/privacy")!
    )
    static let support = SettingsExternalLink(
        label: "Support",
        destination: URL(string: "https://www.openpasskey.com/support")!
    )
}

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var didCopyOperator = false
    @State private var createPIN = ""
    @State private var confirmPIN = ""
    @State private var unlockPIN = ""
    @State private var manualVault = ""
    @State private var manualToken = ""
    @State private var manualChainID = TerminalKnownChainProfile.baseMainnet.chainID
    @State private var confirmationBlocksDraft = 1
    @State private var merchantReceiptNameDraft = ""
    @State private var merchantReceiptABNDraft = ""
    @State private var rpcChainID = TerminalKnownChainProfile.baseMainnet.chainID
    @State private var rpcURLDraft = ""
    @State private var isRPCURLVisible = false
    @State private var isConfirmingRPCUpdate = false
    @State private var isConfirmingRPCRemoval = false
    @State private var isPresentingProvisioningScanner = false
    @State private var isConfirmingWalletReset = false
    @State private var isConfirmingUnreadableSettingsReset = false
    @State private var profilePendingRemoval: AppPaymentProfile?
    @FocusState private var focusedField: SettingsFocusField?

    var body: some View {
        NavigationStack {
            Form {
                if model.settingsRecoveryRequired {
                    unreadableSettingsRecoverySection
                    if !model.canAccessAdmin { lockedContent }
                } else if model.canAccessAdmin {
                    adminContent
                } else {
                    lockedContent
                }
                externalLinksSection
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle(model.canAccessAdmin ? "Terminal Setup" : "Terminal Status")
            .toolbar {
                KeyboardDismissToolbar { focusedField = nil }
            }
        }
        .sheet(isPresented: $isPresentingProvisioningScanner) {
            ProvisioningScannerSheet { payload in
                Task { await model.provision(payload) }
            }
        }
        .confirmationDialog(
            "Reset unreadable terminal setup?",
            isPresented: $isConfirmingUnreadableSettingsReset,
            titleVisibility: .visible
        ) {
            Button("Quarantine and reset setup", role: .destructive) {
                model.resetUnreadableSettingsForRecovery()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The unreadable payment-profile configuration will be retained in local quarantine, then terminal setup will return to the unprovisioned state. The device operator wallet and all invoice and settlement history remain unchanged.")
        }
        .confirmationDialog(
            "Permanently reset operator wallet?",
            isPresented: $isConfirmingWalletReset,
            titleVisibility: .visible
        ) {
            Button("Reset operator wallet", role: .destructive) {
                Task { await model.resetOperatorWallet() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Reset permanently deletes this device key and is available only before the terminal has issued its first payment QR. Withdraw all gas first: both latest and pending native-currency balances must be exactly zero on every network supported by this app build, including networks whose local profile was removed. A later deposit to the published address would be unrecoverable. Once any payment QR is published, revoke or reprovision without deleting this key.")
        }
        .confirmationDialog(
            "Remove payment profile?",
            isPresented: removalConfirmationBinding,
            titleVisibility: .visible
        ) {
            if let profile = profilePendingRemoval {
                Button("Remove \(profile.displayName)", role: .destructive) {
                    profilePendingRemoval = nil
                    Task { await model.removePaymentProfile(id: profile.id) }
                }
            }
            Button("Cancel", role: .cancel) { profilePendingRemoval = nil }
        } message: {
            if let profile = profilePendingRemoval {
                Text("This removes \(profile.displayName) for \(profile.detail) from this terminal only. Historical invoices and settlements remain available, and on-chain operator authorization is not revoked.")
            }
        }
        .confirmationDialog(
            "Trust this RPC provider?",
            isPresented: $isConfirmingRPCUpdate,
            titleVisibility: .visible
        ) {
            Button("Verify and save endpoint") {
                focusedField = nil
                Task {
                    if await model.updateRPCEndpoint(rpcURLDraft, for: rpcChainID) {
                        rpcURLDraft = ""
                        isRPCURLVisible = false
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This provider will receive this terminal's read-only chain queries and signed settlement broadcasts for the selected network. The app will verify the network before saving the URL in this device's Keychain.")
        }
        .confirmationDialog(
            "Use the built-in public RPC?",
            isPresented: $isConfirmingRPCRemoval,
            titleVisibility: .visible
        ) {
            Button("Remove dedicated endpoint", role: .destructive) {
                focusedField = nil
                Task { await model.removeRPCEndpoint(for: rpcChainID) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The dedicated endpoint will be removed from Keychain. Public endpoints can be rate-limited and are intended as a fallback.")
        }
        .onAppear {
            if manualVault.isEmpty { manualVault = model.settings.vault }
            if manualToken.isEmpty { manualToken = model.settings.tokenAddress }
            if let selectedChainID = UInt64(model.settings.chainID),
               TerminalKnownChainProfile.profile(for: selectedChainID) != nil {
                manualChainID = selectedChainID
                rpcChainID = selectedChainID
            }
            syncConfirmationBlocksDraft()
            syncMerchantReceiptDraft()
        }
        .onChange(of: model.settings.selectedPaymentProfileID) {
            syncConfirmationBlocksDraft()
        }
        .onDisappear { focusedField = nil }
    }

    private var externalLinksSection: some View {
        Section("About OPK Terminal") {
            Link(
                SettingsExternalLinks.privacyPolicy.label,
                destination: SettingsExternalLinks.privacyPolicy.destination
            )
            .accessibilityIdentifier("privacyPolicyLink")
            .accessibilityHint("Opens in your web browser")

            Link(
                SettingsExternalLinks.support.label,
                destination: SettingsExternalLinks.support.destination
            )
            .accessibilityIdentifier("supportLink")
            .accessibilityHint("Opens in your web browser")
        }
    }

    @ViewBuilder
    private var unreadableSettingsRecoverySection: some View {
        Section("Setup recovery required") {
            Label("Saved setup could not be verified", systemImage: "exclamationmark.shield.fill")
                .foregroundStyle(.orange)
            if let message = model.settingsRecoveryMessage {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if model.adminPINConfigurationUnavailableMessage != nil {
                Text("Restore Keychain access and restart the app before resetting this setup.")
                    .font(.footnote.weight(.semibold))
            } else if model.canAccessAdmin {
                Button("Reset unreadable setup…", role: .destructive) {
                    isConfirmingUnreadableSettingsReset = true
                }
                .disabled(model.operationBusy || model.isProvisioning)
            } else {
                Text("Unlock Admin below to start recovery.")
                    .font(.footnote.weight(.semibold))
            }
        }
    }

    private var rpcEndpointSection: some View {
        Section("3. RPC endpoints") {
            Picker("Network", selection: $rpcChainID) {
                ForEach(TerminalKnownChainProfile.all, id: \.chainID) { profile in
                    Text(profile.networkName).tag(profile.chainID)
                }
            }
            .accessibilityIdentifier("rpcNetworkPicker")

            let status = model.rpcEndpointStatus(for: rpcChainID)
            LabeledContent("Status", value: status.summary)
                .accessibilityIdentifier("rpcEndpointStatus")
            if case let .unavailable(message) = status {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
            if let profile = TerminalKnownChainProfile.profile(for: rpcChainID) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Built-in public fallback")
                        .font(.caption.weight(.semibold))
                    Text(profile.rpcEndpoint.absoluteString)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
            }

            HStack {
                Group {
                    if isRPCURLVisible {
                        TextField("New HTTPS RPC URL", text: $rpcURLDraft)
                    } else {
                        SecureField("New HTTPS RPC URL", text: $rpcURLDraft)
                    }
                }
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focusedField, equals: .rpcURL)
                .accessibilityIdentifier("rpcURLField")

                Button {
                    isRPCURLVisible.toggle()
                } label: {
                    Label(
                        isRPCURLVisible ? "Hide RPC URL" : "Show RPC URL",
                        systemImage: isRPCURLVisible ? "eye.slash" : "eye"
                    )
                    .labelStyle(.iconOnly)
                }
                .buttonStyle(.borderless)
                .accessibilityIdentifier("toggleRPCURLVisibility")
            }
            .disabled(!model.adminPINConfigured || model.operationBusy || model.isProvisioning)

            Button("Verify and save dedicated RPC") {
                focusedField = nil
                isConfirmingRPCUpdate = true
            }
            .disabled(
                rpcURLDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    || !model.adminPINConfigured
                    || model.operationBusy
                    || model.isProvisioning
                    || model.isRefreshingReadiness
            )
            .accessibilityIdentifier("saveRPCURLButton")

            if status != .builtIn {
                Button("Use built-in public RPC", role: .destructive) {
                    focusedField = nil
                    isConfirmingRPCRemoval = true
                }
                .disabled(
                    model.operationBusy
                        || model.isProvisioning
                        || model.isRefreshingReadiness
                )
                .accessibilityIdentifier("removeRPCURLButton")
            }
            if let message = model.rpcEndpointMessage {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Text("Dedicated URLs are verified against the selected chain, stored only in this device's Keychain, and intentionally not displayed again after saving. Public endpoints can be rate-limited.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var adminContent: some View {
        operatorSetupSection

        if !model.adminPINConfigured {
            Section("2. Protect Admin") {
                Text("After creating the operator, set a six-digit local PIN before importing or changing portal configuration. Only a salted verifier is stored in this device's Keychain.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                SecureField("Six-digit admin PIN", text: digitsBinding($createPIN))
                    .keyboardType(.numberPad)
                    .focused($focusedField, equals: .createPIN)
                    .accessibilityIdentifier("createAdminPIN")
                SecureField("Confirm PIN", text: digitsBinding($confirmPIN))
                    .keyboardType(.numberPad)
                    .focused($focusedField, equals: .confirmPIN)
                    .accessibilityIdentifier("confirmAdminPIN")
                Button("Create local admin PIN") {
                    focusedField = nil
                    model.configureAdminPIN(createPIN, confirmation: confirmPIN)
                    if model.adminPINConfigured {
                        createPIN = ""
                        confirmPIN = ""
                    }
                }
                .disabled(createPIN.count != 6 || confirmPIN.count != 6)
                .accessibilityIdentifier("createAdminPINButton")
            }
        } else {
            Section("2. Admin session") {
                Label("Admin unlocked on this device", systemImage: "lock.open.fill")
                    .foregroundStyle(.orange)
                Button("Lock Admin now") { model.lockAdmin() }
            }
        }

        rpcEndpointSection

        Section("4. Import portal setup") {
            Text("Each QR adds or updates one chain, vault, and token profile for this terminal's public operator. Existing payment profiles are preserved. RPC and deployment trust anchors never come from the QR.")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Button {
                focusedField = nil
                isPresentingProvisioningScanner = true
            } label: {
                Label(
                    model.settings.isProvisioned ? "Add or update payment profile" : "Scan first payment profile",
                    systemImage: "qrcode.viewfinder"
                )
            }
            .buttonStyle(.borderedProminent)
            .disabled(
                model.operatorAddress == nil
                    || !model.adminPINConfigured
                    || model.isProvisioning
            )
            .accessibilityIdentifier("scanProvisioningButton")

            if model.isProvisioning {
                HStack {
                    ProgressView()
                    Text("Validating on chain…")
                }
            }
            if let message = model.pendingSettingsMigrationMessage {
                VStack(alignment: .leading, spacing: 8) {
                    Label("Safety policy updated", systemImage: "checkmark.shield")
                        .font(.footnote.weight(.semibold))
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Button("Acknowledge safety update") {
                        model.acknowledgeSettingsMigrationNotice()
                    }
                    .font(.footnote.weight(.semibold))
                }
            }
            if let message = model.provisioningMessage {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }

        if model.settings.isProvisioned {
            Section("Payment profiles (\(model.settings.paymentProfiles.count))") {
                ForEach(model.settings.paymentProfiles) { profile in
                    HStack(spacing: 12) {
                        Button {
                            Task { await model.selectPaymentProfile(id: profile.id) }
                        } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(profile.displayName)
                                    .font(.headline)
                                    .foregroundStyle(.primary)
                                Text(profile.detail)
                                    .font(.caption.monospaced())
                                    .foregroundStyle(.secondary)
                                Text(
                                    "\(profile.confirmationBlocks) confirmation"
                                        + (profile.confirmationBlocks == "1" ? "" : "s")
                                )
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                        .disabled(
                            profile.id == model.settings.selectedPaymentProfileID
                                || model.operationBusy
                                || model.isRefreshingReadiness
                        )
                        .accessibilityLabel(
                            "\(profile.displayName), \(profile.detail)"
                                + (profile.id == model.settings.selectedPaymentProfileID
                                    ? ", selected" : ", select profile")
                        )

                        Spacer()
                        if profile.id == model.settings.selectedPaymentProfileID {
                            Label("Selected", systemImage: "checkmark.circle.fill")
                                .labelStyle(.iconOnly)
                                .foregroundStyle(.green)
                        }
                        Button(role: .destructive) {
                            profilePendingRemoval = profile
                        } label: {
                            Label("Remove \(profile.displayName)", systemImage: "trash")
                                .labelStyle(.iconOnly)
                        }
                        .buttonStyle(.borderless)
                        .disabled(model.operationBusy || model.isRefreshingReadiness)
                    }
                }
            }

            Section("Selected profile") {
                LabeledContent(
                    "Network",
                    value: model.settings.displayedPaymentProfile.networkName
                )
                LabeledContent("Chain", value: model.settings.chainID)
                LabeledContent("Vault", value: abbreviatedSetup(model.settings.vault))
                LabeledContent("Factory", value: abbreviatedSetup(model.settings.factory))
                LabeledContent(
                    "Receiver implementation",
                    value: abbreviatedSetup(model.settings.receiverImplementation)
                )
                LabeledContent(
                    "Asset",
                    value: settingsAssetLabel(
                        model.settings.tokenAddress,
                        nativeSymbol: model.settings.tokenSymbol
                    )
                )
                LabeledContent("Symbol", value: model.settings.tokenSymbol)
                LabeledContent("Decimals", value: model.settings.tokenDecimals)
                LabeledContent("Built-in RPC", value: model.settings.rpcURL)
                LabeledContent(
                    "Confirmations",
                    value: "\(model.settings.confirmationBlocks) block"
                        + (model.settings.confirmationBlocks == "1" ? "" : "s")
                )
                if model.adminPINConfigured && model.adminUnlocked {
                    Stepper(
                        value: $confirmationBlocksDraft,
                        in: selectedConfirmationBlockRange
                    ) {
                        LabeledContent(
                            "Required for new payments",
                            value: "\(confirmationBlocksDraft)"
                        )
                    }
                    Button("Apply to all \(model.settings.displayedPaymentProfile.networkName) profiles") {
                        guard let chainID = UInt64(model.settings.chainID) else { return }
                        Task {
                            await model.updateConfirmationBlocks(
                                UInt64(confirmationBlocksDraft),
                                for: chainID
                            )
                        }
                    }
                    .disabled(
                        confirmationBlocksDraft == currentConfirmationBlocks
                            || model.operationBusy
                            || model.isProvisioning
                            || model.isRefreshingReadiness
                    )
                    .accessibilityIdentifier("applyNetworkConfirmationPolicy")
                    Text("One confirmation means the payment's inclusion block is enough. This setting applies to every configured payment profile on this network. Existing invoice and settlement snapshots keep the policy captured when they were created.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    Text("Create and unlock the local Admin PIN before changing this network's confirmation policy.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }

        Section("Receipt and automatic settlement") {
            TextField("Merchant name", text: $merchantReceiptNameDraft)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .focused($focusedField, equals: .merchantName)
                .accessibilityIdentifier("merchantReceiptName")
            TextField("ABN (optional)", text: $merchantReceiptABNDraft)
                .keyboardType(.numberPad)
                .focused($focusedField, equals: .merchantABN)
                .accessibilityIdentifier("merchantReceiptABN")
            Button("Save receipt details") {
                focusedField = nil
                model.updateMerchantReceiptProfile(
                    name: merchantReceiptNameDraft,
                    abn: merchantReceiptABNDraft
                )
                if model.errorMessage == nil { syncMerchantReceiptDraft() }
            }
            .disabled(
                merchantReceiptNameDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    || model.operationBusy
                    || model.isProvisioning
            )
            .accessibilityIdentifier("saveMerchantReceiptProfile")

            Toggle(
                "Auto-prepare newly paid invoices",
                isOn: Binding(
                    get: { model.settings.autoSweepEnabled },
                    set: { model.updateAutoSweepEnabled($0) }
                )
            )
            .disabled(
                !model.settings.autoSweepEnabled
                    && (model.operationBusy || model.isProvisioning)
            )
            .accessibilityIdentifier("autoSweepEnabled")
            Text("Auto-sweep is off by default. When enabled, the app can prepare one newly paid, receipt-evidenced invoice while it is active and open the existing settlement review. It never signs or broadcasts without explicit confirmation and device-owner authentication. Late payments remain manual.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }

        Section("5. Readiness") {
            if model.settings.isProvisioned {
                LabeledContent(
                    "Selected route",
                    value: model.settings.displayedPaymentProfile.displayName
                )
            }
            ReadinessLabel(
                readiness: model.terminalReadiness,
                staleNotice: model.preservedReadinessNotice
            )
            ValidationStatusLabel(message: model.validationMessage)
            if let status = model.operatorStatus {
                LabeledContent(
                    "Operator balance",
                    value: "\(TokenAmount(rawValue: status.balance, decimals: selectedNativeCurrencyDecimals).displayString()) \(selectedNativeCurrencySymbol)"
                )
                LabeledContent(
                    "Vault access",
                    value: status.isAuthorizedOperator ? "Authorized" : "Awaiting authorization"
                )
            }
            Button {
                Task { await model.refreshReadiness() }
            } label: {
                Label("Refresh on-chain readiness", systemImage: "arrow.clockwise")
            }
            .disabled(
                model.isBusy
                    || model.isProvisioning
                    || model.isRefreshingReadiness
                    || model.operationBusy
            )
        }

        if let operatorAddress = model.operatorAddress, model.adminPINConfigured {
            Section {
                DisclosureGroup("Advanced manual setup") {
                    Text("Choose a trusted EVM network, then enter or scan only the vault and payment-asset identifier. Factory, implementation, decimals, and symbol are derived and pinned on chain. A successful import adds or updates one payment profile.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Picker("Network", selection: $manualChainID) {
                        ForEach(TerminalKnownChainProfile.all, id: \.chainID) { profile in
                            Text(
                                profile.isTestnet
                                    ? "\(profile.networkName) (testnet)"
                                    : "\(profile.networkName)"
                            )
                            .tag(profile.chainID)
                        }
                    }
                    .accessibilityIdentifier("advancedNetworkPicker")
                    .onChange(of: manualChainID) {
                        manualVault = ""
                        manualToken = ""
                        focusedField = .vault
                    }
                    Text(
                        manualNetworkIsTestnet
                            ? "Base Sepolia is for testing only. It uses test assets and does not change or replace existing Base Mainnet profiles or invoice history."
                            : "Base Mainnet is the default for new terminal setup. Adding this profile does not change or replace existing testnet profiles or invoice history."
                    )
                    .font(.footnote)
                    .foregroundStyle(manualNetworkIsTestnet ? .orange : .secondary)
                    .accessibilityIdentifier("advancedNetworkHelp")
                    AddressField(
                        "Vault",
                        text: $manualVault,
                        focus: $focusedField,
                        field: .vault,
                        onSubmit: { focusedField = .token }
                    )
                    AddressField(
                        "Payment asset",
                        text: $manualToken,
                        focus: $focusedField,
                        field: .token,
                        onSubmit: { focusedField = nil }
                    )
                    Button("Derive and validate manual addresses") {
                        focusedField = nil
                        do {
                            let payload = try TerminalProvisioningPayload(
                                chainID: manualChainID,
                                vault: EthereumAddress(hex: manualVault, allowZero: false),
                                token: EthereumAddress(hex: manualToken, allowZero: false),
                                operatorAddress: operatorAddress
                            )
                            Task { await model.provision(payload) }
                        } catch {
                            model.errorMessage = error.localizedDescription
                        }
                    }
                    .disabled(model.isProvisioning)
                }
            }

            Section("Operator reset") {
                Text("Destructive key reset is allowed only before the first payment QR is issued and only while both latest and pending operator native-currency balances are zero on every network supported by this app build, including networks whose local profile was removed. Withdraw gas first. Anyone can still send to the old public address later, and those late funds would be unrecoverable after deletion. After the first payment QR, retain this key and use merchant-portal revocation or terminal reprovisioning.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button("Reset operator wallet…", role: .destructive) {
                    isConfirmingWalletReset = true
                }
                .disabled(model.operationBusy)
            }
        }
    }

    @ViewBuilder
    private var operatorSetupSection: some View {
        Section("1. Terminal operator") {
            if let address = model.operatorAddress,
               let pairingPayload = model.operatorPairingPayload {
                Text(address.hex)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
                    .accessibilityIdentifier("operatorAddress")

                Button {
                    UIPasteboard.general.string = address.hex
                    didCopyOperator = true
                } label: {
                    Label(
                        didCopyOperator ? "Operator copied" : "Copy operator address",
                        systemImage: didCopyOperator ? "checkmark" : "doc.on.doc"
                    )
                }
                .buttonStyle(.bordered)

                VStack(spacing: 10) {
                    Text("Scan this pairing QR in the merchant portal")
                        .font(.headline)
                    QRCodeImage(
                        payload: pairingPayload,
                        size: 210,
                        accessibilityLabel: "Terminal operator pairing QR code",
                        failureDescription: "Copy the operator address instead."
                    )
                    Text(pairingPayload)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                        .accessibilityIdentifier("operatorPairingPayload")
                }
                .frame(maxWidth: .infinity)

                if let fundingPayload = model.operatorFundingPayload {
                    DisclosureGroup("Gas funding QR") {
                        VStack(spacing: 10) {
                            QRCodeImage(
                                payload: fundingPayload,
                                size: 180,
                                accessibilityLabel: "Operator gas funding QR code",
                                failureDescription: "Copy the operator address instead."
                            )
                            Text("Send \(selectedNetworkName) \(selectedNativeCurrencySymbol) for settlement gas only.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity)
                    }
                } else {
                    Text("The gas funding QR appears after a portal setup is saved for this operator and chain.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } else {
                Text("Create the device-local secp256k1 EOA first. It becomes this terminal's public identity and constrained settlement signer; no admin PIN is required for this one-time creation step.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button {
                    Task { await model.createOperatorWallet() }
                } label: {
                    Label("Create protected operator wallet", systemImage: "key.fill")
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.operationBusy)
                .accessibilityIdentifier("createOperatorWalletButton")
            }
        }
    }

    @ViewBuilder
    private var lockedContent: some View {
        if let address = model.operatorAddress {
            Section("Terminal operator") {
                Text(address.hex)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
                    .accessibilityIdentifier("lockedOperatorAddress")
                Button {
                    UIPasteboard.general.string = address.hex
                    didCopyOperator = true
                } label: {
                    Label(
                        didCopyOperator ? "Operator copied" : "Copy operator address",
                        systemImage: didCopyOperator ? "checkmark" : "doc.on.doc"
                    )
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("lockedCopyOperatorAddress")
                Text("This public EOA receives only settlement gas. Setup controls and its private signing key remain protected.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }

        Section("Terminal readiness") {
            if model.settings.isProvisioned {
                LabeledContent(
                    "Selected route",
                    value: model.settings.displayedPaymentProfile.displayName
                )
            }
            ReadinessLabel(
                readiness: model.terminalReadiness,
                staleNotice: model.preservedReadinessNotice
            )
            ValidationStatusLabel(message: model.validationMessage)
            if let status = model.operatorStatus {
                LabeledContent(
                    "\(selectedNativeCurrencySymbol) balance",
                    value: TokenAmount(
                        rawValue: status.balance,
                        decimals: selectedNativeCurrencyDecimals
                    ).displayString()
                )
                LabeledContent(
                    "Vault authorization",
                    value: status.isAuthorizedOperator ? "Authorized" : "Awaiting"
                )
            }
            Button("Refresh status") {
                Task { await model.refreshReadiness() }
            }
            .disabled(
                model.isBusy
                    || model.isProvisioning
                    || model.isRefreshingReadiness
                    || model.operationBusy
            )
        }

        if let fundingPayload = model.operatorFundingPayload {
            Section("Fund settlement gas") {
                QRCodeImage(
                    payload: fundingPayload,
                    size: 180,
                    accessibilityLabel: "Operator gas funding QR code",
                    failureDescription: "Copy the operator address above instead."
                )
                .frame(maxWidth: .infinity)
                Text("Send \(selectedNetworkName) \(selectedNativeCurrencySymbol) for settlement gas only. This QR is shown only for the operator and chain bound by the selected payment profile.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }

        if let unavailable = model.adminPINConfigurationUnavailableMessage {
            Section("Admin protection unavailable") {
                Label(
                    "Admin PIN verifier could not be accessed",
                    systemImage: "lock.trianglebadge.exclamationmark"
                )
                .foregroundStyle(.orange)
                Text(unavailable)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text("Restore Keychain access and restart the app before making setup changes.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } else {
            Section("Admin locked") {
                Text("Setup, reprovisioning, network/vault changes, and wallet reset are hidden until the local admin PIN is verified.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                SecureField("Six-digit admin PIN", text: digitsBinding($unlockPIN))
                    .keyboardType(.numberPad)
                    .focused($focusedField, equals: .unlockPIN)
                    .accessibilityIdentifier("unlockAdminPIN")
                Button("Unlock Admin") {
                    focusedField = nil
                    model.unlockAdmin(with: unlockPIN)
                    if model.adminUnlocked { unlockPIN = "" }
                }
                .disabled(unlockPIN.count != 6)
                .accessibilityIdentifier("unlockAdminButton")
            }
        }
    }

    private func digitsBinding(_ source: Binding<String>) -> Binding<String> {
        Binding(
            get: { source.wrappedValue },
            set: { source.wrappedValue = String($0.filter(\.isNumber).prefix(6)) }
        )
    }

    private var selectedNetworkName: String {
        model.settings.displayedPaymentProfile.networkName
    }

    private var selectedNativeCurrencySymbol: String {
        model.settings.displayedPaymentProfile.nativeCurrencySymbol
    }

    private var selectedNativeCurrencyDecimals: UInt8 {
        model.settings.displayedPaymentProfile.nativeCurrencyDecimals
    }

    private var currentConfirmationBlocks: Int {
        Int(model.settings.confirmationBlocks) ?? AppSettings.adjustableConfirmationBlockRange.lowerBound
    }

    private var selectedConfirmationBlockRange: ClosedRange<Int> {
        let knownMinimum = UInt64(model.settings.chainID)
            .flatMap(TerminalKnownChainProfile.profile(for:))?
            .minimumConfirmationBlocks
        let lowerBound = min(
            max(
                Int(knownMinimum ?? 1),
                AppSettings.adjustableConfirmationBlockRange.lowerBound
            ),
            AppSettings.adjustableConfirmationBlockRange.upperBound
        )
        return lowerBound...AppSettings.adjustableConfirmationBlockRange.upperBound
    }

    private var manualNetworkIsTestnet: Bool {
        TerminalKnownChainProfile.profile(for: manualChainID)?.isTestnet == true
    }

    private func syncConfirmationBlocksDraft() {
        confirmationBlocksDraft = min(
            max(
                currentConfirmationBlocks,
                selectedConfirmationBlockRange.lowerBound
            ),
            selectedConfirmationBlockRange.upperBound
        )
    }

    private func syncMerchantReceiptDraft() {
        merchantReceiptNameDraft = model.settings.merchantReceiptName
        merchantReceiptABNDraft = model.settings.merchantReceiptABN
    }

    private var removalConfirmationBinding: Binding<Bool> {
        Binding(
            get: { profilePendingRemoval != nil },
            set: { if !$0 { profilePendingRemoval = nil } }
        )
    }
}

private func settingsAssetLabel(_ address: String, nativeSymbol: String) -> String {
    guard let parsed = try? EthereumAddress(hex: address, allowZero: false) else {
        return abbreviatedSetup(address)
    }
    return NativeAsset.isNative(parsed) ? nativeSymbol : abbreviatedSetup(address)
}

private struct ReadinessLabel: View {
    let readiness: TerminalReadiness
    var staleNotice: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(readiness.title, systemImage: readiness.systemImage)
                .font(.headline)
                .foregroundStyle(readiness.isReady ? .green : .orange)
            Text(readiness.detail)
                .font(.footnote)
                .foregroundStyle(.secondary)
            if let staleNotice {
                Label {
                    Text(staleNotice)
                        .foregroundStyle(.primary)
                } icon: {
                    Image(systemName: "arrow.clockwise.circle")
                        .foregroundStyle(.orange)
                }
                .font(.footnote)
                .accessibilityIdentifier("settingsStaleReadinessNotice")
            }
        }
    }
}

private struct ValidationStatusLabel: View {
    let message: String

    var body: some View {
        Label(message, systemImage: passed ? "checkmark.shield.fill" : "shield.lefthalf.filled")
            .font(.subheadline)
            .foregroundStyle(passed ? .green : .secondary)
            .accessibilityIdentifier("onChainValidationStatus")
    }

    private var passed: Bool {
        message == "On-chain validation passed"
    }
}

private struct AddressField: View {
    let label: String
    @Binding var text: String
    let focus: FocusState<SettingsFocusField?>.Binding
    let field: SettingsFocusField
    let onSubmit: () -> Void
    @State private var isPresentingScanner = false

    init(
        _ label: String,
        text: Binding<String>,
        focus: FocusState<SettingsFocusField?>.Binding,
        field: SettingsFocusField,
        onSubmit: @escaping () -> Void
    ) {
        self.label = label
        _text = text
        self.focus = focus
        self.field = field
        self.onSubmit = onSubmit
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)

            HStack(spacing: 10) {
                TextField("0x…", text: $text)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.system(.body, design: .monospaced))
                    .focused(focus, equals: field)
                    .submitLabel(.next)
                    .onSubmit(onSubmit)
                    .accessibilityLabel("\(label) address")

                Button {
                    focus.wrappedValue = nil
                    isPresentingScanner = true
                } label: {
                    Label("Scan \(label)", systemImage: "qrcode.viewfinder")
                        .labelStyle(.iconOnly)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .accessibilityLabel("Scan \(label) address")
                .accessibilityHint("Opens the camera QR code scanner")
            }
        }
        .sheet(isPresented: $isPresentingScanner) {
            AddressScannerSheet(fieldLabel: label) { address in
                text = address.hex
            }
        }
    }
}

/// Existing address-only scanner behavior remains deliberately separate from provisioning.
private struct AddressScannerSheet: View {
    let fieldLabel: String
    let onAddress: (EthereumAddress) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var scanError: String?

    var body: some View {
        NavigationStack {
            ZStack {
                ConfigurationAddressScanner(onPayload: handlePayload)
                    .ignoresSafeArea(edges: .bottom)

                scannerOverlay(
                    instructions: "Scan a raw 0x address or an address-only ethereum:0x… or ethereum://0x… QR code. Payment URIs are rejected.",
                    scanError: scanError
                )
            }
            .background(Color.black)
            .navigationTitle("Scan \(fieldLabel)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func handlePayload(_ payload: String) -> Bool {
        do {
            let address = try EthereumAddressQRPayloadParser.parse(payload)
            onAddress(address)
            dismiss()
            return true
        } catch {
            scanError = "This QR code does not contain a valid non-zero Ethereum address."
            return false
        }
    }
}

private struct ProvisioningScannerSheet: View {
    let onPayload: (TerminalProvisioningPayload) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var scanError: String?

    var body: some View {
        NavigationStack {
            ZStack {
                ConfigurationAddressScanner(onPayload: handlePayload)
                    .ignoresSafeArea(edges: .bottom)

                scannerOverlay(
                    instructions: "Scan the exact OPK terminal provisioning QR shown by the merchant portal. Payment and address-only QRs are rejected.",
                    scanError: scanError
                )
            }
            .background(Color.black)
            .navigationTitle("Provision terminal")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func handlePayload(_ rawPayload: String) -> Bool {
        do {
            let payload = try TerminalProvisioningPayload.parse(rawPayload)
            onPayload(payload)
            dismiss()
            return true
        } catch {
            scanError = error.localizedDescription
            return false
        }
    }
}

@MainActor
private func scannerOverlay(instructions: String, scanError: String?) -> some View {
    VStack(spacing: 20) {
        Text(instructions)
            .font(.footnote)
            .multilineTextAlignment(.center)
            .padding(12)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))

        Spacer()

        RoundedRectangle(cornerRadius: 20)
            .stroke(Color.white, style: StrokeStyle(lineWidth: 3, dash: [12, 8]))
            .frame(width: 250, height: 250)
            .shadow(color: .black.opacity(0.6), radius: 4)
            .accessibilityHidden(true)

        Spacer()

        if let scanError {
            Label(scanError, systemImage: "exclamationmark.triangle.fill")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .padding(12)
                .background(Color.red.opacity(0.9), in: RoundedRectangle(cornerRadius: 12))
                .onAppear {
                    UIAccessibility.post(notification: .announcement, argument: scanError)
                }
        }
    }
    .padding()
}

private func abbreviatedSetup(_ value: String) -> String {
    guard value.count > 18 else { return value }
    return "\(value.prefix(10))…\(value.suffix(6))"
}
