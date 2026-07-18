import SwiftData
import SwiftUI

@main
struct OPKTerminalApp: App {
    @Environment(\.scenePhase) private var scenePhase
    private let container: ModelContainer
    @StateObject private var model: AppModel

    init() {
        let container = try! ModelContainer(for: StoredInvoice.self)
        self.container = container
        _model = StateObject(wrappedValue: AppModel(container: container))
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .onChange(of: scenePhase) { _, phase in
                    guard phase == .active else { return }
                    Task { await model.reconcileForegroundInvoices() }
                }
        }
        .modelContainer(container)
    }
}
