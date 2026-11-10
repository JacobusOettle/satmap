import SwiftUI
import UIKit

/// The full-screen lock shown while test builds are switched off (`TestBuildGate`): the title, the message and the
/// store buttons. It fills the screen above everything, can't be dismissed, and nothing behind it can be reached.
/// Shown through `TestBuildLockHost`, which puts it in its own window over all the app's sheets.
struct TestBuildLockView: View {
    let lock: TestBuildSwitch.Lock

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 20) {
                Spacer()
                Image(systemName: "lock.fill")
                    .font(.system(size: 52, weight: .semibold))
                    .foregroundColor(.yellow)
                    .accessibilityHidden(true)
                Text(lock.title)
                    .font(.title2.weight(.bold))
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                Text(lock.message)
                    .font(.body)
                    .foregroundColor(.white.opacity(0.85))
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer()
                VStack(spacing: 12) {
                    ForEach(lock.buttons, id: \.kind) { button in
                        Button {
                            if let url = URL(string: button.url) { UIApplication.shared.open(url) }
                        } label: {
                            Text(button.title)
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .background(button.kind == .website ? Color.white.opacity(0.14) : Color.yellow)
                                .foregroundColor(button.kind == .website ? .white : .black)
                                .clipShape(RoundedRectangle(cornerRadius: 14))
                        }
                        .accessibilityIdentifier("testBuildLink-" + button.kind.rawValue)
                    }
                }
            }
            .padding(.horizontal, 28)
            .padding(.vertical, 40)
            .frame(maxWidth: 480)
        }
        .foregroundColor(.white)
        .accessibilityIdentifier("testBuildLock")
        .accessibilityAddTraits(.isModal)
    }
}

/// Puts `TestBuildLockView` in its own window above every sheet and alert, so the app underneath can't be reached, and
/// takes it away when the switch turns on again. Driven by `TestBuildGate.lock` from `SatMapApp` on the main thread.
final class TestBuildLockHost {
    static let shared = TestBuildLockHost()
    private var window: UIWindow?

    func update(_ lock: TestBuildSwitch.Lock?) {
        guard let lock = lock else {
            window?.isHidden = true
            window = nil
            return
        }
        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }) ?? UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first else { return }
        let host = window ?? UIWindow(windowScene: scene)
        host.windowLevel = .alert + 1
        host.backgroundColor = .black
        let controller = UIHostingController(rootView: TestBuildLockView(lock: lock))
        controller.overrideUserInterfaceStyle = .dark
        host.rootViewController = controller
        host.isHidden = false
        host.makeKeyAndVisible()
        window = host
    }
}
