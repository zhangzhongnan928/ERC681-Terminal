import SwiftUI

@main
struct OPKTerminalApp: App {
    @State private var launchState = TerminalLaunchState()

    var body: some Scene {
        WindowGroup {
            launchRoot
        }
    }

    @ViewBuilder
    private var launchRoot: some View {
        switch launchState.destination {
        case .chooser:
            TerminalLaunchChooserView(
                onOpenLiveTerminal: {
                    launchState.openLiveTerminal()
                },
                onExploreDemo: {
                    launchState.openReviewerDemo()
                }
            )
        case .reviewerDemo:
            ReviewerDemoView {
                launchState.returnToChooser()
            }
        case .liveTerminal:
            LiveTerminalRoot()
        }
    }
}
