import SwiftData
import SwiftUI
import OPKTerminalOperator

@main
struct OPKTerminalApp: App {
    @Environment(\.scenePhase) private var scenePhase
    private let container: ModelContainer
    @StateObject private var model: AppModel

    init() {
        let container = try! ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self
        )
        self.container = container
        let model: AppModel
#if DEBUG
        if let namespace = ProcessInfo.processInfo.environment["OPK_UI_TEST_KEYCHAIN_NAMESPACE"] {
            AppPreferences.resetForUITesting()
            model = AppModel(
                container: container,
                operatorWallet: KeychainOperatorWallet(
                    service: "com.openpasskey.terminal.operator-wallet.ui-tests.\(namespace)"
                ),
                adminPINStore: KeychainAdminPINStore(
                    service: "com.openpasskey.terminal.admin-pin.ui-tests.\(namespace)"
                )
            )
        } else {
            model = AppModel(container: container)
        }
#else
        model = AppModel(container: container)
#endif
        _model = StateObject(wrappedValue: model)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .onChange(of: scenePhase) { _, phase in
                    if phase != .active {
                        model.lockAdmin()
                    }
                }
                .task(id: scenePhase) {
                    guard scenePhase == .active else { return }
                    while !Task.isCancelled {
                        await model.reconcileForegroundInvoices()
                        guard !Task.isCancelled else { return }
                        await model.reconcileSettlements()
                        guard !Task.isCancelled else { return }
                        await model.refreshReadiness()
                        guard !Task.isCancelled else { return }
                        do {
                            try await Task.sleep(for: .seconds(30))
                        } catch {
                            return
                        }
                    }
                }
        }
        .modelContainer(container)
    }
}
