import OPKTerminalCore
import SwiftUI
import UIKit

private enum SettingsFocusField: Hashable {
    case createPIN
    case confirmPIN
    case unlockPIN
    case vault
    case token
}

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var didCopyOperator = false
    @State private var createPIN = ""
    @State private var confirmPIN = ""
    @State private var unlockPIN = ""
    @State private var manualVault = ""
    @State private var manualToken = ""
    @State private var manualChainID = TerminalKnownChainProfile.baseSepolia.chainID
    @State private var isPresentingProvisioningScanner = false
    @State private var isConfirmingWalletReset = false
    @State private var profilePendingRemoval: AppPaymentProfile?
    @FocusState private var focusedField: SettingsFocusField?

    var body: some View {
        NavigationStack {
            Form {
                if model.canAccessAdmin {
                    adminContent
                } else {
                    lockedContent
                }
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
        .onAppear {
            if manualVault.isEmpty { manualVault = model.settings.vault }
            if manualToken.isEmpty { manualToken = model.settings.tokenAddress }
            if let selectedChainID = UInt64(model.settings.chainID),
               TerminalKnownChainProfile.profile(for: selectedChainID) != nil {
                manualChainID = selectedChainID
            }
        }
        .onDisappear { focusedField = nil }
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

        Section("3. Import portal setup") {
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
                LabeledContent("Token", value: abbreviatedSetup(model.settings.tokenAddress))
                LabeledContent("Symbol", value: model.settings.tokenSymbol)
                LabeledContent("Decimals", value: model.settings.tokenDecimals)
                LabeledContent("RPC", value: model.settings.rpcURL)
            }
        }

        Section("4. Readiness") {
            if model.settings.isProvisioned {
                LabeledContent(
                    "Selected route",
                    value: model.settings.displayedPaymentProfile.displayName
                )
            }
            ReadinessLabel(readiness: model.terminalReadiness)
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
                    Text("Choose a trusted EVM network, then enter or scan only the vault and token. Factory, implementation, decimals, and symbol are derived and pinned on chain. A successful import adds or updates one payment profile.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Picker("Network", selection: $manualChainID) {
                        ForEach(TerminalKnownChainProfile.all, id: \.chainID) { profile in
                            Text(profile.networkName).tag(profile.chainID)
                        }
                    }
                    .onChange(of: manualChainID) {
                        manualVault = ""
                        manualToken = ""
                        focusedField = .vault
                    }
                    AddressField(
                        "Vault",
                        text: $manualVault,
                        focus: $focusedField,
                        field: .vault,
                        onSubmit: { focusedField = .token }
                    )
                    AddressField(
                        "Token",
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
            ReadinessLabel(readiness: model.terminalReadiness)
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

    private var removalConfirmationBinding: Binding<Bool> {
        Binding(
            get: { profilePendingRemoval != nil },
            set: { if !$0 { profilePendingRemoval = nil } }
        )
    }
}

private struct ReadinessLabel: View {
    let readiness: TerminalReadiness

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(readiness.title, systemImage: readiness.systemImage)
                .font(.headline)
                .foregroundStyle(readiness.isReady ? .green : .orange)
            Text(readiness.detail)
                .font(.footnote)
                .foregroundStyle(.secondary)
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
