import SwiftUI

enum ReviewerDemoCopy {
    static let safetyBannerLabel =
        "OFFLINE DEMO · BASE MAINNET FORMAT · SIMULATED · NO NETWORK · NO REAL FUNDS"
    static let isolationDetail =
        "Offline, in-memory product tour. No wallet, network request, signing, broadcast, or storage is used."

    static let sampleAmount = "1.00"
    static let sampleSymbol = "USDC"
    static let sampleReceiver = "0x2222222222222222222222222222222222222222"
    static let sampleInvoice = "DEMO-0001"
    static let sampleMarker =
        "opk-demo:v1?network=base-mainnet&chainId=8453&simulated=true"
}

enum ReviewerDemoTab: Hashable {
    case checkout
    case history
    case settlement
}

enum ReviewerDemoPaymentState: Equatable {
    case waiting
    case paid

    var label: String {
        switch self {
        case .waiting: "Waiting for payment"
        case .paid: "Paid"
        }
    }
}

struct ReviewerDemoState: Equatable {
    var selectedTab: ReviewerDemoTab = .checkout
    private(set) var paymentState: ReviewerDemoPaymentState = .waiting

    mutating func simulatePayment() {
        paymentState = .paid
    }

    mutating func reset() {
        self = Self()
    }
}

struct ReviewerDemoView: View {
    @State private var state = ReviewerDemoState()
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ReviewerDemoSafetyBanner()
            Divider()

            TabView(selection: $state.selectedTab) {
                ReviewerDemoCheckoutView(state: $state, onClose: onClose)
                    .tabItem {
                        Label("Checkout", systemImage: "qrcode")
                    }
                    .tag(ReviewerDemoTab.checkout)

                ReviewerDemoHistoryView(state: $state, onClose: onClose)
                    .tabItem {
                        Label("History", systemImage: "clock.arrow.circlepath")
                    }
                    .tag(ReviewerDemoTab.history)

                ReviewerDemoSettlementView(state: $state, onClose: onClose)
                    .tabItem {
                        Label("Settlement", systemImage: "arrow.triangle.2.circlepath")
                    }
                    .tag(ReviewerDemoTab.settlement)
            }
        }
        .accessibilityIdentifier("reviewerDemoRoot")
        .onAppear {
            state.reset()
        }
        .onDisappear {
            state.reset()
        }
    }
}

private struct ReviewerDemoSafetyBanner: View {
    var body: some View {
        VStack(spacing: 5) {
            Text(ReviewerDemoCopy.safetyBannerLabel)
                .font(.caption.weight(.black))
                .foregroundStyle(.orange)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("reviewerDemoSafetyLabel")
            Text(ReviewerDemoCopy.isolationDetail)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("reviewerDemoIsolationDetail")
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal)
        .padding(.vertical, 10)
        .background(Color(.secondarySystemBackground))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("reviewerDemoSafetyBanner")
    }
}

private struct ReviewerDemoCheckoutView: View {
    @Binding var state: ReviewerDemoState
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    VStack(spacing: 4) {
                        Text(ReviewerDemoCopy.sampleAmount)
                            .font(.system(.largeTitle, design: .rounded, weight: .bold))
                        Text(ReviewerDemoCopy.sampleSymbol)
                            .font(.title3)
                            .foregroundStyle(.secondary)
                    }

                    ReviewerDemoPaymentStatusLabel(state: state.paymentState)

                    if state.paymentState == .waiting {
                        VStack {
                            QRCodeImage(
                                payload: ReviewerDemoCopy.sampleMarker,
                                size: 220,
                                accessibilityLabel: "Non-payment demo marker QR code",
                                failureDescription: "The local demo QR could not be rendered."
                            )
                            .accessibilityHidden(true)
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("Non-payment demo marker QR code")
                        .accessibilityIdentifier("reviewerDemoQRCode")

                        Text("Non-payment simulation marker generated locally for this demo.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)

                        Button {
                            state.simulatePayment()
                        } label: {
                            Label("Simulate test payment", systemImage: "play.circle.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .accessibilityIdentifier("reviewerDemoSimulatePaymentButton")
                    } else {
                        ContentUnavailableView(
                            "Demo payment marked paid",
                            systemImage: "checkmark.circle.fill",
                            description: Text(
                                "This status changed only in memory. No transaction was submitted."
                            )
                        )
                        .foregroundStyle(.green)

                        Button {
                            state.selectedTab = .history
                        } label: {
                            Label("View demo history", systemImage: "clock.arrow.circlepath")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .accessibilityIdentifier("reviewerDemoViewHistoryButton")
                    }

                    GroupBox("Sample payment details") {
                        VStack(alignment: .leading, spacing: 8) {
                            ReviewerDemoDetailRow(
                                label: "Network",
                                value: "Base Mainnet · 8453"
                            )
                            ReviewerDemoDetailRow(
                                label: "Receiver",
                                value: abbreviatedDemoValue(ReviewerDemoCopy.sampleReceiver)
                            )
                            ReviewerDemoDetailRow(
                                label: "Invoice",
                                value: ReviewerDemoCopy.sampleInvoice
                            )
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding()
            }
            .navigationTitle("Demo Checkout")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close demo", action: onClose)
                        .accessibilityIdentifier("reviewerDemoCloseButton")
                }
            }
        }
    }
}

private struct ReviewerDemoPaymentStatusLabel: View {
    let state: ReviewerDemoPaymentState

    var body: some View {
        Label(
            state.label,
            systemImage: state == .paid ? "checkmark.circle.fill" : "hourglass"
        )
        .font(.headline)
        .foregroundStyle(state == .paid ? .green : .secondary)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(
            (state == .paid ? Color.green : Color.secondary).opacity(0.12),
            in: Capsule()
        )
        .accessibilityIdentifier("reviewerDemoPaymentStatus")
    }
}

private struct ReviewerDemoHistoryView: View {
    @Binding var state: ReviewerDemoState
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section("Sample invoice") {
                    LabeledContent(
                        "Amount",
                        value: "\(ReviewerDemoCopy.sampleAmount) \(ReviewerDemoCopy.sampleSymbol)"
                    )
                    LabeledContent("Status", value: state.paymentState.label)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("Status")
                        .accessibilityValue(state.paymentState.label)
                        .accessibilityIdentifier("reviewerDemoHistoryStatus")
                    LabeledContent("Network", value: "Base Mainnet")
                    LabeledContent("Invoice", value: ReviewerDemoCopy.sampleInvoice)
                }

                Section {
                    Text(
                        "This sample exists only while the demo is open and is discarded when you close it."
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Demo History")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close demo", action: onClose)
                        .accessibilityIdentifier("reviewerDemoCloseButton")
                }
            }
        }
    }
}

private struct ReviewerDemoSettlementView: View {
    @Binding var state: ReviewerDemoState
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            List {
                if state.paymentState == .waiting {
                    Section {
                        ContentUnavailableView(
                            "No simulated payment yet",
                            systemImage: "hourglass",
                            description: Text(
                                "Simulate the sample payment in Demo Checkout to preview settlement."
                            )
                        )

                        Button {
                            state.selectedTab = .checkout
                        } label: {
                            Label("Return to Demo Checkout", systemImage: "arrow.left")
                        }
                    }
                } else {
                    Section("Illustrative settlement") {
                        LabeledContent("Sessions", value: "1")
                        LabeledContent(
                            "Amount",
                            value: "\(ReviewerDemoCopy.sampleAmount) \(ReviewerDemoCopy.sampleSymbol)"
                        )
                        LabeledContent(
                            "Receiver",
                            value: abbreviatedDemoValue(ReviewerDemoCopy.sampleReceiver)
                        )
                        LabeledContent("Action", value: "Preview only")
                    }

                    Section {
                        Button(action: {}) {
                            Label("Settlement disabled in demo", systemImage: "lock.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(true)
                        .accessibilityIdentifier("reviewerDemoSettlementDisabledButton")
                    } footer: {
                        Text(
                            "The demo cannot authenticate, sign, broadcast, or change settlement data."
                        )
                    }
                }
            }
            .navigationTitle("Demo Settlement")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close demo", action: onClose)
                        .accessibilityIdentifier("reviewerDemoCloseButton")
                }
            }
        }
    }
}

private struct ReviewerDemoDetailRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .font(.system(.body, design: .monospaced))
        }
    }
}

private func abbreviatedDemoValue(_ value: String) -> String {
    guard value.count > 18 else { return value }
    return "\(value.prefix(10))…\(value.suffix(6))"
}
