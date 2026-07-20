import SwiftUI

/// A consistent escape hatch for keyboards, including number and decimal pads
/// that do not provide a return key of their own.
struct KeyboardDismissToolbar: ToolbarContent {
    let dismiss: () -> Void

    var body: some ToolbarContent {
        ToolbarItemGroup(placement: .keyboard) {
            Spacer()
            Button {
                dismiss()
            } label: {
                Label("Done", systemImage: "keyboard.chevron.compact.down")
            }
            .accessibilityLabel("Hide keyboard")
            .accessibilityIdentifier("keyboardDismissButton")
        }
    }
}
