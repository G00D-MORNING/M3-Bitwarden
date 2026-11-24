import Shared
import SwiftUI

@main
struct iOSApp: App {
    init() {
        // iOS 26 and later: tab bar, new-item button and item toolbar are drawn as Liquid Glass
        // in SwiftUI (GlassChrome); Compose leaves them out. Must be set before Compose starts.
        if #available(iOS 26, *) {
            NativeChrome.shared.enabled = true
        }
        // Lets the vault lock itself before iOS suspends the app in the background.
        VaultService.shared.requestBackgroundTime = { expiring in
            var task = UIBackgroundTaskIdentifier.invalid
            task = UIApplication.shared.beginBackgroundTask(withName: "Tresor sperren") { expiring() }
            return {
                guard task != .invalid else { return }
                UIApplication.shared.endBackgroundTask(task)
                task = .invalid
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
