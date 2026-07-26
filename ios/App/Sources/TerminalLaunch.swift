import SwiftUI

enum TerminalLaunchDestination: Equatable {
    case chooser
    case reviewerDemo
    case liveTerminal
}

struct TerminalLaunchState: Equatable {
    private(set) var destination: TerminalLaunchDestination = .chooser

    mutating func openReviewerDemo() {
        guard destination == .chooser else { return }
        destination = .reviewerDemo
    }

    mutating func openLiveTerminal() {
        guard destination == .chooser else { return }
        destination = .liveTerminal
    }

    mutating func returnToChooser() {
        guard destination == .reviewerDemo else { return }
        destination = .chooser
    }

    var requiresLiveDependencies: Bool {
        destination == .liveTerminal
    }
}

struct TerminalLaunchChooserView: View {
    let onOpenLiveTerminal: () -> Void
    let onExploreDemo: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 22) {
                    Image(systemName: "rectangle.and.hand.point.up.left.filled")
                        .font(.system(size: 54))
                        .foregroundStyle(.tint)
                        .accessibilityHidden(true)

                    VStack(spacing: 8) {
                        Text("OPK Terminal")
                            .font(.largeTitle.weight(.bold))
                        Text("Terminal Setup")
                            .font(.title2.weight(.semibold))
                    }

                    Text(
                        "Open the live merchant terminal to continue setup and operations, or explore the isolated App Review demo."
                    )
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                    VStack(spacing: 12) {
                        Button(action: onOpenLiveTerminal) {
                            Label("Open live terminal", systemImage: "arrow.right.circle.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .accessibilityIdentifier("launchOpenLiveTerminalButton")

                        Button(action: onExploreDemo) {
                            Label("Explore offline demo", systemImage: "sparkles")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                        .accessibilityIdentifier("launchReviewerDemoButton")
                    }

                    GroupBox {
                        VStack(alignment: .leading, spacing: 8) {
                            Label("OFFLINE DEMO", systemImage: "eye.fill")
                                .font(.headline)
                            Text(
                                "BASE SEPOLIA TESTNET · SIMULATED · NO NETWORK · NO REAL FUNDS"
                            )
                                .font(.caption.weight(.bold))
                                .foregroundStyle(.orange)
                            Text(
                                "The demo is offline and in memory. It does not open the live terminal, create a wallet, read saved setup, or connect to a network."
                            )
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .frame(maxWidth: 560)
                .padding(24)
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
            .navigationTitle("Choose mode")
            .navigationBarTitleDisplayMode(.inline)
        }
        .accessibilityIdentifier("terminalLaunchChooser")
    }
}
