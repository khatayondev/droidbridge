import SwiftUI

/// Small sheet-less window opened from the menu bar to dial a number on the paired phone.
struct CallDialView: View {
    let onCall: (String) -> Void

    @State private var number: String = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Call a Number")
                .font(.headline)
            Text("Places the call on your paired Android phone, over the encrypted bridge. Call audio stays on the phone.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            TextField("+1 555 010 1234", text: $number)
                .textFieldStyle(.roundedBorder)
                .focused($focused)
                .onSubmit(submit)

            HStack {
                Spacer()
                Button("Cancel") {
                    NSApp.keyWindow?.close()
                }
                Button("Call", action: submit)
                    .keyboardShortcut(.defaultAction)
                    .disabled(number.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(16)
        .frame(width: 340)
        .onAppear { focused = true }
    }

    private func submit() {
        let trimmed = number.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        onCall(trimmed)
        NSApp.keyWindow?.close()
    }
}
