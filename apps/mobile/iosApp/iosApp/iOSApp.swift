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
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
