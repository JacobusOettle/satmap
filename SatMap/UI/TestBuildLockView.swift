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
            // The texts may be long (a 120-character title, a 500-character message) and the text large: then it
            // scrolls, so the buttons can always be reached (as on Android). When it fits it doesn't.
            ViewThatFits(in: .vertical) {
                content
                ScrollView {
                    content.frame(maxWidth: .infinity)
                }
            }
        }
        .foregroundColor(.white)
        .accessibilityIdentifier("testBuildLock")
        .accessibilityAddTraits(.isModal)
    }

    private var content: some View {
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
}

/// Puts `TestBuildLockView` in a window of its own above every sheet and alert, in each of the phone's scenes, so the
/// app underneath can't be reached, and takes it away when the switch turns on again. Driven by `TestBuildGate.lock`
/// from `SatMapApp`, on the main thread: each scene's view sends the lock when it appears, so a scene iOS connects
/// anew (it may disconnect the phone's scene and keep the app running for CarPlay) is covered too.
///
/// The lock's window is shown but never made key. The app's own window stays the key window, so whatever the app
/// presents later (an alert or a toast from the groups, a share sheet; `UIApplication.screensWindow`) goes there, under
/// the lock, never over it. Touches still reach only the lock, the highest window.
final class TestBuildLockHost {
    static let shared = TestBuildLockHost()
    /// The lock's windows, one for each scene it covers.
    private var windows: [UIWindow] = []
    /// The app's windows under the lock, hidden from VoiceOver while it shows.
    private let covered = NSHashTable<UIWindow>.weakObjects()

    func update(_ lock: TestBuildSwitch.Lock?) {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        // Unlocked, or its scene has gone (a window left on a disconnected scene shows nothing): the window goes.
        windows.removeAll { w in
            guard lock == nil || !scenes.contains(where: { $0 === w.windowScene }) else { return false }
            w.isHidden = true
            w.rootViewController = nil
            return true
        }
        guard let lock = lock else {
            for w in covered.allObjects { w.accessibilityElementsHidden = false }
            covered.removeAllObjects()
            return
        }
        for scene in scenes {
            let host = windows.first(where: { $0.windowScene === scene }) ?? makeWindow(scene)
            if let controller = host.rootViewController as? UIHostingController<TestBuildLockView> {
                controller.rootView = TestBuildLockView(lock: lock)
            } else {
                let controller = UIHostingController(rootView: TestBuildLockView(lock: lock))
                controller.overrideUserInterfaceStyle = .dark
                controller.view.backgroundColor = .black
                host.rootViewController = controller
            }
            host.isHidden = false
        }
        cover()
        // The scene's own window may be in place only once its view has appeared.
        DispatchQueue.main.async { [weak self] in self?.cover() }
    }

    /// The app's windows under the lock: no typing in them (the keyboard's window floats above every other) and hidden
    /// from VoiceOver.
    private func cover() {
        for host in windows {
            for w in host.windowScene?.windows ?? [] where w.windowLevel == .normal && !windows.contains(where: { $0 === w }) {
                w.endEditing(true)
                w.accessibilityElementsHidden = true
                covered.add(w)
            }
        }
    }

    private func makeWindow(_ scene: UIWindowScene) -> UIWindow {
        let host = UIWindow(windowScene: scene)
        host.windowLevel = .alert + 1
        host.backgroundColor = .black
        host.overrideUserInterfaceStyle = .dark
        host.accessibilityViewIsModal = true
        windows.append(host)
        return host
    }
}

extension UIApplication {
    /// The window the app's screens are in, to present an alert, a sheet or a toast over: the key window at the normal
    /// level (or the first one shown). Never the test-build lock's window above it (`TestBuildLockHost`): nothing is
    /// presented over the lock.
    var screensWindow: UIWindow? {
        let windows = connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap { $0.windows }.filter { $0.windowLevel == .normal }
        return windows.first(where: \.isKeyWindow) ?? windows.first(where: { !$0.isHidden })
    }
}
