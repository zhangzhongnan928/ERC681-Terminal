import OPKTerminalCore
import SwiftData
import SwiftUI
import UIKit

struct ContentView: View {
    @EnvironmentObject private var model: AppModel
    @State private var selectedTab: RootTab = .checkout

    var body: some View {
#if DEBUG
        if Self.isReadyCheckoutUITestFixtureEnabled {
            CheckoutReadyUITestFixture()
        } else {
            rootTabs
        }
#else
        rootTabs
#endif
    }

    private var rootTabs: some View {
        TabView(selection: $selectedTab) {
            CheckoutView {
                selectedTab = .settings
            }
                .tabItem { Label("Checkout", systemImage: "square.grid.3x3.fill") }
                .tag(RootTab.checkout)
            HistoryView()
                .tabItem { Label("History", systemImage: "clock.arrow.circlepath") }
                .tag(RootTab.history)
            SettlementView()
                .tabItem { Label("Settle", systemImage: "arrow.triangle.2.circlepath") }
                .tag(RootTab.settle)
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag(RootTab.settings)
        }
        .alert("OPK Terminal", isPresented: errorBinding) {
            Button("OK", role: .cancel) { model.errorMessage = nil }
        } message: {
            Text(model.errorMessage ?? "Unknown error")
        }
    }

#if DEBUG
    private static var isReadyCheckoutUITestFixtureEnabled: Bool {
        let environment = ProcessInfo.processInfo.environment
        return environment["OPK_UI_TEST_KEYCHAIN_NAMESPACE"] != nil
            && environment["OPK_UI_TEST_CHECKOUT_FIXTURE"] == "ready"
    }
#endif

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { model.errorMessage != nil },
            set: { if !$0 { model.errorMessage = nil } }
        )
    }
}

private enum RootTab: Hashable {
    case checkout
    case history
    case settle
    case settings
}

private struct CheckoutView: View {
    @EnvironmentObject private var model: AppModel
    @State private var amount = ""
    @State private var isSubmitting = false
    let onOpenSettings: () -> Void

    var body: some View {
        NavigationStack {
            if let request = model.activeRequest {
                PaymentPresentationView(
                    request: request,
                    observation: model.activeObservation,
                    onClose: {
                        model.closeActiveSale()
                        amount = ""
                    }
                )
            } else {
                Group {
                    switch CheckoutPresentationState.evaluate(
                        isSubmitting: isSubmitting,
                        isProvisioning: model.isProvisioning,
                        isRefreshingReadiness: model.isRefreshingReadiness,
                        isBusy: model.isBusy,
                        readiness: model.terminalReadiness
                    ) {
                    case let .checkout(status):
                        CheckoutReadyView(
                            amount: $amount,
                            tokenSymbol: model.settings.tokenSymbol,
                            tokenDecimals: Int(model.settings.tokenDecimals) ?? 0,
                            chainID: model.settings.chainID,
                            status: status,
                            isSubmitting: isSubmitting,
                            isInteractionEnabled: allowsQRCreation
                        ) { displayAmount in
                            isSubmitting = true
                            Task {
                                await model.createSale(displayAmount: displayAmount)
                                isSubmitting = false
                            }
                        }
                    case let .checking(kind):
                        CheckoutCheckingView(kind: kind)
                    case let .blocked(readiness):
                        CheckoutBlockedView(
                            readiness: readiness,
                            onOpenSettings: onOpenSettings
                        )
                    }
                }
                .navigationTitle("Checkout")
            }
        }
        .onChange(of: model.settings.tokenAddress) {
            amount = ""
        }
    }

    private var allowsQRCreation: Bool {
        !isSubmitting
            && !model.isBusy
            && !model.isProvisioning
            && !model.isRefreshingReadiness
            && !model.operationBusy
            && model.terminalReadiness.isReady
    }
}

private struct CheckoutReadyView: View {
    @Binding var amount: String
    let tokenSymbol: String
    let tokenDecimals: Int
    let chainID: String
    let status: CheckoutOperationalStatus
    let isSubmitting: Bool
    let isInteractionEnabled: Bool
    let onSubmit: (String) -> Void
    @ScaledMetric(relativeTo: .largeTitle) private var amountFontSize = 64

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                CheckoutStatusHeader(chainID: chainID, status: status)

                VStack(spacing: 8) {
                    HStack {
                        Spacer()
                        Button {
                            amount = CheckoutAmountInput.cleared
                        } label: {
                            Label("Clear", systemImage: "xmark.circle")
                        }
                        .buttonStyle(.borderless)
                        .disabled(amount.isEmpty)
                        .accessibilityLabel("Clear amount")
                        .accessibilityIdentifier("checkoutClearButton")
                    }

                    CheckoutAmountDisplay(
                        amount: CheckoutAmountInput.displayText(
                            for: amount,
                            decimals: tokenDecimals
                        ),
                        symbol: tokenSymbol,
                        fontSize: amountFontSize
                    )
                }

                CheckoutKeypad(
                    amount: $amount,
                    maximumFractionDigits: tokenDecimals
                )
            }
            .padding(.horizontal)
            .padding(.bottom, 8)
        }
        .safeAreaInset(edge: .bottom) {
            checkoutAction
        }
    }

    private var checkoutAction: some View {
        VStack(spacing: 0) {
            Divider()
            VStack(alignment: .leading, spacing: 10) {
                Text("Exact amount")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                ScrollView(.horizontal, showsIndicators: true) {
                    Text(exactAmountReview)
                        .font(.subheadline.monospacedDigit())
                        .fixedSize(horizontal: true, vertical: false)
                        .textSelection(.enabled)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Exact payment amount")
                .accessibilityValue(exactAmountReview)
                .accessibilityIdentifier("checkoutExactAmountReview")

                Button {
                    onSubmit(amount)
                } label: {
                    HStack(spacing: 10) {
                        if isSubmitting {
                            ProgressView()
                                .tint(.white)
                        } else {
                            Image(systemName: "qrcode")
                        }
                        Text(isSubmitting ? "Preparing payment QR" : "Show payment QR")
                    }
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 38)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .controlSize(.large)
                .disabled(!canSubmit)
                .accessibilityLabel(
                    isSubmitting
                        ? "Preparing payment QR for \(exactAmountReview)"
                        : "Show payment QR for \(exactAmountReview)"
                )
                .accessibilityIdentifier("showPaymentQRButton")
            }
            .padding()
        }
        .background(Color(.systemBackground))
    }

    private var canSubmit: Bool {
        isInteractionEnabled
            && !isSubmitting
            && CheckoutAmountInput.isPayable(amount, decimals: tokenDecimals)
    }

    private var exactAmountReview: String {
        CheckoutAmountInput.exactReviewText(
            for: amount,
            decimals: tokenDecimals,
            symbol: tokenSymbol
        )
    }
}

#if DEBUG
private struct CheckoutReadyUITestFixture: View {
    @State private var amount = ""

    var body: some View {
        TabView {
            NavigationStack {
                CheckoutReadyView(
                    amount: $amount,
                    tokenSymbol: "AUD",
                    tokenDecimals: 18,
                    chainID: "84532",
                    status: .ready,
                    isSubmitting: false,
                    isInteractionEnabled: true,
                    onSubmit: { _ in }
                )
                .navigationTitle("Checkout")
            }
            .tabItem { Label("Checkout", systemImage: "square.grid.3x3.fill") }
        }
    }
}
#endif

private struct CheckoutAmountDisplay: View {
    let amount: String
    let symbol: String
    let fontSize: CGFloat
    private let trailingAnchor = "checkoutAmountTrailingAnchor"

    var body: some View {
        VStack(spacing: 4) {
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: true) {
                    HStack(spacing: 0) {
                        Text(amount)
                            .font(.system(size: fontSize, weight: .semibold, design: .rounded))
                            .monospacedDigit()
                            .fixedSize(horizontal: true, vertical: false)
                        Color.clear
                            .frame(width: 1, height: 1)
                            .id(trailingAnchor)
                    }
                    .padding(.horizontal, 2)
                }
                .onAppear {
                    proxy.scrollTo(trailingAnchor, anchor: .trailing)
                }
                .onChange(of: amount) {
                    proxy.scrollTo(trailingAnchor, anchor: .trailing)
                }
            }
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Checkout amount")
            .accessibilityValue("\(amount) \(symbol)")
            .accessibilityIdentifier("checkoutAmountDisplay")

            Text(symbol)
                .font(.headline)
                .foregroundStyle(.secondary)
        }
    }
}

enum CheckoutCheckingKind: Equatable {
    case provisioning
    case readiness
}

enum CheckoutOperationalStatus: Equatable {
    case ready
    case checking
    case preparing
}

enum CheckoutPresentationState: Equatable {
    case checkout(CheckoutOperationalStatus)
    case checking(CheckoutCheckingKind)
    case blocked(TerminalReadiness)

    static func evaluate(
        isSubmitting: Bool,
        isProvisioning: Bool,
        isRefreshingReadiness: Bool,
        isBusy: Bool,
        readiness: TerminalReadiness
    ) -> CheckoutPresentationState {
        if isProvisioning {
            return .checking(.provisioning)
        }
        if isSubmitting {
            return .checkout(.preparing)
        }
        if isRefreshingReadiness {
            if readiness.isReady {
                return .checkout(.checking)
            }
            return .checking(.readiness)
        }
        if isBusy {
            if readiness.isReady {
                return .checkout(.checking)
            }
            return .checking(.readiness)
        }
        if readiness.isReady {
            return .checkout(.ready)
        }
        return .blocked(readiness)
    }
}

private struct CheckoutCheckingView: View {
    let kind: CheckoutCheckingKind

    var body: some View {
        VStack {
            VStack(spacing: 16) {
                ProgressView()
                    .controlSize(.large)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.title2.weight(.semibold))
                    .multilineTextAlignment(.center)
                Text(detail)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(24)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(title)
            .accessibilityValue(detail)
            .accessibilityIdentifier("checkoutReadinessChecking")

            Spacer()
        }
        .padding()
    }

    private var title: String {
        switch kind {
        case .provisioning: "Validating terminal setup"
        case .readiness: "Checking terminal readiness"
        }
    }

    private var detail: String {
        switch kind {
        case .provisioning:
            "The portal setup is being derived and checked on chain. Checkout will be available when validation finishes."
        case .readiness:
            "The saved deployment, authorization, and gas status are being refreshed on chain."
        }
    }
}

private struct CheckoutStatusHeader: View {
    let chainID: String
    let status: CheckoutOperationalStatus

    var body: some View {
        HStack(spacing: 10) {
            Label(statusTitle, systemImage: statusSystemImage)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(statusColor)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(statusColor.opacity(0.12), in: Capsule())
                .accessibilityIdentifier("checkoutReadyStatus")

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                Text(chainID == "84532" ? "Base Sepolia" : "Chain \(chainID)")
                    .font(.subheadline.weight(.semibold))
                Text("TESTNET")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.orange)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(
                chainID == "84532" ? "Base Sepolia testnet" : "Chain \(chainID) testnet"
            )
            .accessibilityIdentifier("checkoutNetworkStatus")
        }
    }

    private var statusTitle: String {
        switch status {
        case .ready: "Ready"
        case .checking: "Checking"
        case .preparing: "Preparing QR"
        }
    }

    private var statusSystemImage: String {
        switch status {
        case .ready: "checkmark.circle.fill"
        case .checking: "arrow.clockwise.circle.fill"
        case .preparing: "hourglass.circle.fill"
        }
    }

    private var statusColor: Color {
        switch status {
        case .ready: .green
        case .checking: .orange
        case .preparing: .blue
        }
    }
}

private struct CheckoutBlockedView: View {
    let readiness: TerminalReadiness
    let onOpenSettings: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(systemName: readiness.systemImage)
                    .font(.largeTitle)
                    .foregroundStyle(.orange)
                    .accessibilityHidden(true)
                Text(readiness.title)
                    .font(.title2.weight(.semibold))
                    .multilineTextAlignment(.center)
                Text(readiness.detail)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button(action: onOpenSettings) {
                    Label(actionTitle, systemImage: actionSystemImage)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .accessibilityIdentifier("checkoutReadinessActionButton")
            }
            .padding(24)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("checkoutReadinessBlocker")
        }
        .padding()
        .scrollBounceBehavior(.basedOnSize)
    }

    private var actionTitle: String {
        switch readiness {
        case .walletRequired, .configurationRequired:
            "Finish terminal setup"
        default:
            "Open Settings"
        }
    }

    private var actionSystemImage: String {
        switch readiness {
        case .walletRequired, .configurationRequired:
            "arrow.right.circle.fill"
        default:
            "gearshape"
        }
    }
}

private struct CheckoutKeypad: View {
    @Binding var amount: String
    let maximumFractionDigits: Int

    private let columns = Array(
        repeating: GridItem(.flexible(), spacing: 10),
        count: 3
    )

    var body: some View {
        LazyVGrid(columns: columns, spacing: 10) {
            ForEach(1...9, id: \.self) { digit in
                CheckoutKeyButton(
                    title: String(digit),
                    accessibilityLabel: String(digit),
                    identifier: "checkoutKey\(digit)"
                ) {
                    amount = CheckoutAmountInput.appending(
                        digit: digit,
                        to: amount,
                        maximumFractionDigits: maximumFractionDigits
                    )
                }
            }

            CheckoutKeyButton(
                title: ".",
                accessibilityLabel: "Decimal point",
                identifier: "checkoutDecimalKey",
                isEnabled: maximumFractionDigits > 0 && !amount.contains(".")
            ) {
                amount = CheckoutAmountInput.appendingDecimal(
                    to: amount,
                    maximumFractionDigits: maximumFractionDigits
                )
            }

            CheckoutKeyButton(
                title: "0",
                accessibilityLabel: "0",
                identifier: "checkoutKey0"
            ) {
                amount = CheckoutAmountInput.appending(
                    digit: 0,
                    to: amount,
                    maximumFractionDigits: maximumFractionDigits
                )
            }

            CheckoutKeyButton(
                systemImage: "delete.left",
                accessibilityLabel: "Delete last digit",
                identifier: "checkoutBackspaceKey",
                isEnabled: !amount.isEmpty
            ) {
                amount = CheckoutAmountInput.deletingLast(from: amount)
            }
        }
    }
}

private struct CheckoutKeyButton: View {
    var title: String?
    var systemImage: String?
    let accessibilityLabel: String
    let identifier: String
    var isEnabled = true
    let action: () -> Void

    init(
        title: String,
        accessibilityLabel: String,
        identifier: String,
        isEnabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.title = title
        systemImage = nil
        self.accessibilityLabel = accessibilityLabel
        self.identifier = identifier
        self.isEnabled = isEnabled
        self.action = action
    }

    init(
        systemImage: String,
        accessibilityLabel: String,
        identifier: String,
        isEnabled: Bool = true,
        action: @escaping () -> Void
    ) {
        title = nil
        self.systemImage = systemImage
        self.accessibilityLabel = accessibilityLabel
        self.identifier = identifier
        self.isEnabled = isEnabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Group {
                if let title {
                    Text(title)
                } else if let systemImage {
                    Image(systemName: systemImage)
                }
            }
            .font(.title.weight(.medium))
            .frame(maxWidth: .infinity, minHeight: 62)
            .contentShape(Rectangle())
        }
        .buttonStyle(CheckoutKeyButtonStyle())
        .disabled(!isEnabled)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityIdentifier(identifier)
    }
}

private struct CheckoutKeyButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(isEnabled ? Color.primary : Color.secondary)
            .background(
                configuration.isPressed
                    ? Color(.tertiarySystemFill)
                    : Color(.secondarySystemBackground),
                in: RoundedRectangle(cornerRadius: 14)
            )
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.1), value: configuration.isPressed)
    }
}

enum CheckoutAmountInput {
    static let cleared = ""

    static func appending(
        digit: Int,
        to amount: String,
        maximumFractionDigits: Int
    ) -> String {
        guard (0...9).contains(digit) else { return amount }
        let candidate: String
        if let decimalIndex = amount.firstIndex(of: ".") {
            let fractionStart = amount.index(after: decimalIndex)
            let fractionCount = amount.distance(from: fractionStart, to: amount.endIndex)
            guard fractionCount < max(0, maximumFractionDigits) else { return amount }
            candidate = amount + String(digit)
        } else if amount == "0" {
            candidate = digit == 0 ? amount : String(digit)
        } else {
            candidate = amount + String(digit)
        }
        guard isRepresentable(candidate, decimals: maximumFractionDigits) else {
            return amount
        }
        return candidate
    }

    static func appendingDecimal(
        to amount: String,
        maximumFractionDigits: Int
    ) -> String {
        guard maximumFractionDigits > 0, !amount.contains(".") else { return amount }
        return (amount.isEmpty ? "0" : amount) + "."
    }

    static func deletingLast(from amount: String) -> String {
        String(amount.dropLast())
    }

    static func isPayable(_ amount: String, decimals: Int) -> Bool {
        guard let decimals = UInt8(exactly: decimals) else { return false }
        return (try? TokenAmount(display: amount, decimals: decimals)) != nil
    }

    static func displayText(for amount: String, decimals: Int) -> String {
        if amount.isEmpty {
            switch decimals {
            case ...0: return "0"
            case 1: return "0.0"
            default: return "0.00"
            }
        }
        return amount
    }

    static func exactReviewText(for amount: String, decimals: Int, symbol: String) -> String {
        "\(displayText(for: amount, decimals: decimals)) \(symbol)"
    }

    private static func isRepresentable(_ amount: String, decimals: Int) -> Bool {
        guard let decimals = UInt8(exactly: decimals) else { return false }
        return (try? TokenAmount(display: amount, decimals: decimals, allowZero: true)) != nil
    }
}

private struct PaymentPresentationView: View {
    let request: PaymentRequest
    let observation: PaymentObservation?
    let onClose: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Text(TokenAmount(rawValue: request.expectedAmount, decimals: request.token.decimals).displayString())
                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                Text(request.token.symbol)
                    .font(.title3)
                    .foregroundStyle(.secondary)

                PaymentStatusLabel(status: observation?.status ?? .waiting)

                if shouldPresentQR {
                    QRCodeImage(payload: request.erc681URI)

                    Button {
                        UIPasteboard.general.string = request.erc681URI
                    } label: {
                        Label("Copy payment URI", systemImage: "doc.on.doc")
                    }
                    .buttonStyle(.bordered)
                } else {
                    ContentUnavailableView(
                        "QR closed",
                        systemImage: "qrcode",
                        description: Text("This payment request is no longer being presented.")
                    )
                }

                GroupBox("Payment details") {
                    VStack(alignment: .leading, spacing: 8) {
                        DetailRow(label: "Receiver", value: abbreviated(request.receiver.hex))
                        DetailRow(label: "Invoice", value: abbreviated(request.invoiceID.hex))
                        if let observation {
                            DetailRow(
                                label: "Observed",
                                value: TokenAmount(
                                    rawValue: observation.balance,
                                    decimals: request.token.decimals
                                ).displayString()
                            )
                            DetailRow(label: "Block", value: String(observation.blockNumber))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                Button("Close", action: onClose)
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
            }
            .padding()
        }
        .navigationTitle("Payment")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    private var shouldPresentQR: Bool {
        guard let status = observation?.status else { return true }
        switch status {
        case .waiting, .partial, .confirming: return true
        case .paid, .overpaid, .expired: return false
        }
    }
}

private struct PaymentStatusLabel: View {
    let status: PaymentStatus

    var body: some View {
        Label(label, systemImage: symbol)
            .font(.headline)
            .foregroundStyle(color)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(color.opacity(0.12), in: Capsule())
    }

    private var label: String {
        switch status {
        case .waiting: "Waiting for payment"
        case .partial: "Partially funded"
        case let .confirming(_, confirmations, required): "Confirming \(confirmations)/\(required)"
        case .paid: "Paid"
        case .overpaid: "Paid — overpayment"
        case .expired: "Expired"
        }
    }

    private var symbol: String {
        switch status {
        case .waiting, .confirming: "hourglass"
        case .partial: "circle.lefthalf.filled"
        case .paid, .overpaid: "checkmark.circle.fill"
        case .expired: "clock.badge.exclamationmark"
        }
    }

    private var color: Color {
        switch status {
        case .paid, .overpaid: .green
        case .partial, .confirming: .orange
        case .expired: .red
        case .waiting: .secondary
        }
    }
}

private struct HistoryView: View {
    @Query(sort: \StoredInvoice.createdAt, order: .reverse) private var invoices: [StoredInvoice]

    var body: some View {
        NavigationStack {
            List(invoices) { invoice in
                NavigationLink {
                    InvoiceDetailView(invoice: invoice)
                } label: {
                    VStack(alignment: .leading, spacing: 5) {
                        HStack {
                            Text(invoice.formattedAmount)
                                .font(.headline)
                            Spacer()
                            Text(invoice.statusLabel)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Text(invoice.createdAt, format: .dateTime.day().month().hour().minute())
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .overlay {
                if invoices.isEmpty {
                    ContentUnavailableView(
                        "No invoices",
                        systemImage: "clock",
                        description: Text("New payment requests appear here.")
                    )
                }
            }
            .navigationTitle("History")
        }
    }
}

private struct InvoiceDetailView: View {
    let invoice: StoredInvoice

    var body: some View {
        List {
            Section {
                LabeledContent("Amount", value: invoice.formattedAmount)
                LabeledContent("Status", value: invoice.statusLabel)
                LabeledContent("Balance", value: invoice.observedBalance)
                LabeledContent("Receiver", value: abbreviated(invoice.receiver))
                LabeledContent("Invoice", value: abbreviated(invoice.invoiceID))
            }
            Section("QR") {
                if invoice.shouldPresentQRCode {
                    QRCodeImage(payload: invoice.erc681URI, size: 240)
                        .frame(maxWidth: .infinity)
                } else {
                    ContentUnavailableView(
                        "QR closed",
                        systemImage: "qrcode",
                        description: Text("This invoice is \(invoice.statusLabel.lowercased()) and is no longer payable from the terminal.")
                    )
                }
            }
        }
        .navigationTitle("Invoice")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct DetailRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).font(.system(.body, design: .monospaced))
        }
    }
}

private func abbreviated(_ value: String) -> String {
    guard value.count > 18 else { return value }
    return "\(value.prefix(10))…\(value.suffix(6))"
}
