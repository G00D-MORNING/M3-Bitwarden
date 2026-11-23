import Foundation

/// Fixed settings shared by the app and the AutoFill extension.
enum M3BConfig {
    /// Vaultwarden behind Cloudflare: this host only answers devices with a client certificate.
    static let serverURL = URL(string: "https://vault.jochenderrochen.de")!
    /// Web vault (behind Cloudflare Access), used only for links.
    static let webVaultURL = URL(string: "https://tresor.jochenderrochen.de")!

    static var apiURL: URL { serverURL.appendingPathComponent("api") }
    static var identityURL: URL { serverURL.appendingPathComponent("identity") }

    /// App Group shared by app and extension; also used as the shared keychain access group.
    static let appGroup = "group.com.framepersecond.m3bitwarden"

    /// "mobile" is the OAuth client every Bitwarden phone app uses.
    static let clientId = "mobile"
    /// Bitwarden device type for iOS.
    static let deviceType = 1
    static let clientVersion = "2026.11.0"

    /// Lock automatically after this long in the background.
    static let autoLockSeconds: TimeInterval = 5 * 60

    static var containerURL: URL {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }
}
