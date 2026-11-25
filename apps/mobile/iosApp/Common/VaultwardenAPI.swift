import Foundation

/// Bitwarden API calls against Vaultwarden, over a session that presents the device certificate.
final class VaultwardenAPI {
    enum Failure: LocalizedError {
        case noCertificate
        case blockedByCloudflare
        case twoFactorRequired(providers: [Int])
        case invalidCredentials(String)
        case server(Int, String)
        case notSignedIn

        var errorDescription: String? {
            switch self {
            case .noCertificate:
                "Dieses Gerät hat noch kein Zertifikat für den Server."
            case .blockedByCloudflare:
                "Cloudflare hat die Anfrage abgelehnt. Prüfe, ob das Gerätezertifikat in Cloudflare noch gültig ist."
            case .twoFactorRequired:
                "Zwei-Faktor-Code erforderlich."
            case let .invalidCredentials(message):
                message.isEmpty ? "E-Mail oder Master-Passwort ist falsch." : message
            case let .server(code, message):
                "Serverfehler \(code)\(message.isEmpty ? "" : ": \(message)")"
            case .notSignedIn:
                "Nicht angemeldet."
            }
        }
    }

    static let shared = VaultwardenAPI()

    private let session: URLSession
    private let delegate = ClientCertificateDelegate()

    private init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.httpAdditionalHeaders = [
            "Bitwarden-Client-Name": M3BConfig.clientId,
            "Bitwarden-Client-Version": M3BConfig.clientVersion,
            "Device-Type": String(M3BConfig.deviceType),
        ]
        session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
    }

    // MARK: Identity

    func prelogin(email: String) async throws -> PreloginResponse {
        try await send(json: ["email": email], to: M3BConfig.identityURL.appendingPathComponent("accounts/prelogin"), auth: false)
    }

    /// Password grant. [passwordHash] is the SDK's server-authorization hash, never the password.
    func login(
        email: String,
        passwordHash: String,
        deviceIdentifier: String,
        deviceName: String,
        twoFactor: (provider: Int, code: String)? = nil
    ) async throws -> TokenResponse {
        var form: [(String, String)] = [
            ("grant_type", "password"),
            ("username", email),
            ("password", passwordHash),
            ("scope", "api offline_access"),
            ("client_id", M3BConfig.clientId),
            ("deviceIdentifier", deviceIdentifier),
            ("deviceName", deviceName),
            ("deviceType", String(M3BConfig.deviceType)),
        ]
        if let twoFactor {
            form += [("twoFactorToken", twoFactor.code), ("twoFactorProvider", String(twoFactor.provider)), ("twoFactorRemember", "1")]
        }
        return try await token(form: form, email: email)
    }

    func refresh(refreshToken: String) async throws -> TokenResponse {
        try await token(form: [
            ("grant_type", "refresh_token"),
            ("client_id", M3BConfig.clientId),
            ("refresh_token", refreshToken),
        ], email: nil)
    }

    // MARK: Registration

    /// Starts a registration. Returns the verification token when the server hands it out right
    /// away (mail off or no e-mail check); nil when it sent a confirmation link by e-mail.
    func registerVerification(email: String, name: String?) async throws -> String? {
        var body: [String: Any] = ["email": email]
        if let name, !name.isEmpty { body["name"] = name }
        var req = request(M3BConfig.identityURL.appendingPathComponent("accounts/register/send-verification-email"), method: "POST")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let data = try await raw(req, auth: false)
        guard !data.isEmpty else { return nil }
        if let token = try? JSONDecoder().decode(String.self, from: data) { return token }
        return String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Creates the account with keys made by the SDK (nothing secret leaves the device unencrypted).
    func registerFinish(_ body: [String: Any]) async throws {
        var req = request(M3BConfig.identityURL.appendingPathComponent("accounts/register/finish"), method: "POST")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        _ = try await raw(req, auth: false)
    }

    /// Asks the server to e-mail a two-factor code (provider 1).
    func sendEmailCode(email: String, passwordHash: String) async throws {
        let _: EmptyResponse = try await send(
            json: ["email": email, "masterPasswordHash": passwordHash],
            to: M3BConfig.apiURL.appendingPathComponent("two-factor/send-email-login"),
            auth: false
        )
    }

    // MARK: Vault

    func sync() async throws -> Data {
        var url = URLComponents(url: M3BConfig.apiURL.appendingPathComponent("sync"), resolvingAgainstBaseURL: false)!
        url.queryItems = [URLQueryItem(name: "excludeDomains", value: "false")]
        return try await raw(request(url.url!, method: "GET"), auth: true)
    }

    func createCipher(_ body: CipherRequestModel) async throws -> CipherModel {
        try await send(encodable: body, to: M3BConfig.apiURL.appendingPathComponent("ciphers"), method: "POST")
    }

    func updateCipher(id: String, _ body: CipherRequestModel) async throws -> CipherModel {
        try await send(encodable: body, to: M3BConfig.apiURL.appendingPathComponent("ciphers/\(id)"), method: "PUT")
    }

    /// Like [createCipher]/[updateCipher], but reads only the id of the saved item from the answer,
    /// so a field this app does not model can never turn a successful save into an error.
    func saveCipher(id: String?, _ body: CipherRequestModel) async throws -> String {
        let url = M3BConfig.apiURL.appendingPathComponent(id.map { "ciphers/\($0)" } ?? "ciphers")
        let saved: SavedId = try await send(encodable: body, to: url, method: id == nil ? "POST" : "PUT")
        return saved.id
    }

    private struct SavedId: Decodable { let id: String }

    /// Uploads an encrypted text Send (secure link) and returns its access id.
    func createSend(_ body: [String: Any]) async throws -> String {
        let created: SendCreated = try await send(json: body, to: M3BConfig.apiURL.appendingPathComponent("sends"), auth: true)
        return created.accessId
    }

    private struct SendCreated: Decodable { let accessId: String }

    func createFolder(name: String) async throws -> FolderModel {
        try await send(encodable: ["name": name], to: M3BConfig.apiURL.appendingPathComponent("folders"), method: "POST")
    }

    /// Website icon from the server's icon service (`/icons/<host>/icon.png`), or nil.
    func icon(host: String) async -> Data? {
        guard host.range(of: #"^[a-z0-9.-]+$"#, options: .regularExpression) != nil else { return nil }
        let url = M3BConfig.serverURL.appendingPathComponent("icons").appendingPathComponent(host).appendingPathComponent("icon.png")
        var req = URLRequest(url: url)
        req.timeoutInterval = 15
        guard let (data, response) = try? await perform(req), response.statusCode == 200,
              (response.value(forHTTPHeaderField: "Content-Type") ?? "image").hasPrefix("image"), data.count < 300_000
        else { return nil }
        return data
    }

    /// Moves the item to the trash (restorable in the web vault).
    func softDeleteCipher(id: String) async throws {
        _ = try await raw(request(M3BConfig.apiURL.appendingPathComponent("ciphers/\(id)/delete"), method: "PUT"), auth: true)
    }

    // MARK: Plumbing

    private struct EmptyResponse: Decodable {}

    private func token(form: [(String, String)], email: String?) async throws -> TokenResponse {
        var req = request(M3BConfig.identityURL.appendingPathComponent("connect/token"), method: "POST")
        req.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
        if let email {
            req.setValue(Data(email.utf8).base64URLEncoded, forHTTPHeaderField: "Auth-Email")
        }
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        req.httpBody = form
            .map { "\($0.0)=\($0.1.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")" }
            .joined(separator: "&")
            .data(using: .utf8)

        let (data, response) = try await perform(req)
        if response.statusCode == 200 {
            return try JSON.decoder.decode(TokenResponse.self, from: data)
        }
        let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        if let providers = body["TwoFactorProviders"] as? [Any] ?? body["twoFactorProviders"] as? [Any] {
            throw Failure.twoFactorRequired(providers: providers.compactMap { ($0 as? Int) ?? Int("\($0)") })
        }
        if response.statusCode == 400 || response.statusCode == 401 {
            throw Failure.invalidCredentials(Self.message(in: body))
        }
        throw Failure.server(response.statusCode, Self.describe(req, message: Self.message(in: body), data: data))
    }

    /// The server's message, or which request failed and the start of the answer (no request body,
    /// no tokens), so an error the server does not explain can still be traced.
    private static func describe(_ req: URLRequest, message: String, data: Data) -> String {
        if !message.isEmpty { return message }
        let path = req.url.map { url in url.path.replacingOccurrences(of: M3BConfig.serverURL.path, with: "") } ?? ""
        let text = String(decoding: data.prefix(2000), as: UTF8.self)
            .replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
            .split(whereSeparator: \.isWhitespace).joined(separator: " ")
        return "\(req.httpMethod ?? "") \(path)" + (text.isEmpty ? "" : " – \(text.prefix(400))")
    }

    private func send<T: Decodable>(json: [String: Any], to url: URL, auth: Bool) async throws -> T {
        var req = request(url, method: "POST")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: json)
        let data = try await raw(req, auth: auth)
        if T.self == EmptyResponse.self, data.isEmpty { return EmptyResponse() as! T } // swiftlint:disable:this force_cast
        return try JSON.decoder.decode(T.self, from: data)
    }

    private func send<T: Decodable, B: Encodable>(encodable body: B, to url: URL, method: String) async throws -> T {
        var req = request(url, method: method)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSON.encoder.encode(body)
        return try JSON.decoder.decode(T.self, from: try await raw(req, auth: true))
    }

    private func request(_ url: URL, method: String) -> URLRequest {
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        return req
    }

    /// Sends [req], adding (and once refreshing) the access token when [auth] is set.
    private func raw(_ req: URLRequest, auth: Bool) async throws -> Data {
        var req = req
        if auth { req.setValue("Bearer \(try await TokenStore.validAccessToken())", forHTTPHeaderField: "Authorization") }
        var (data, response) = try await perform(req)
        if auth, response.statusCode == 401 {
            req.setValue("Bearer \(try await TokenStore.validAccessToken(forceRefresh: true))", forHTTPHeaderField: "Authorization")
            (data, response) = try await perform(req)
        }
        guard (200..<300).contains(response.statusCode) else {
            let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
            if response.statusCode == 401 { throw Failure.notSignedIn }
            throw Failure.server(response.statusCode, Self.describe(req, message: Self.message(in: body), data: data))
        }
        return data
    }

    private func perform(_ req: URLRequest) async throws -> (Data, HTTPURLResponse) {
        guard DeviceCertificate.isInstalled else { throw Failure.noCertificate }
        let (data, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse else { throw Failure.server(0, "") }
        // Cloudflare's WAF answers with an HTML page when the certificate is missing or revoked.
        if http.statusCode == 403, (http.value(forHTTPHeaderField: "Content-Type") ?? "").contains("text/html") {
            throw Failure.blockedByCloudflare
        }
        return (data, http)
    }

    private static func message(in body: [String: Any]) -> String {
        if let model = body["ErrorModel"] as? [String: Any] ?? body["errorModel"] as? [String: Any],
           let message = model["Message"] as? String ?? model["message"] as? String { return message }
        return body["error_description"] as? String ?? body["message"] as? String ?? ""
    }
}

/// Access/refresh tokens in the shared keychain, refreshed on demand.
enum TokenStore {
    private static let refreshLock = AsyncLock()

    static func save(_ response: TokenResponse) throws {
        let previous = SecureStore.getCodable(StoredTokens.self, .tokens)
        guard let refresh = response.refreshToken ?? previous?.refreshToken else { throw VaultwardenAPI.Failure.notSignedIn }
        try SecureStore.setCodable(
            StoredTokens(accessToken: response.accessToken, refreshToken: refresh, expiresAt: Date().addingTimeInterval(TimeInterval(response.expiresIn))),
            for: .tokens
        )
    }

    static func validAccessToken(forceRefresh: Bool = false) async throws -> String {
        try await refreshLock.run {
            guard let tokens = SecureStore.getCodable(StoredTokens.self, .tokens) else { throw VaultwardenAPI.Failure.notSignedIn }
            if !forceRefresh, tokens.expiresAt.timeIntervalSinceNow > 120 { return tokens.accessToken }
            let response = try await VaultwardenAPI.shared.refresh(refreshToken: tokens.refreshToken)
            try save(response)
            return response.accessToken
        }
    }
}

/// Serialises async work (one token refresh at a time).
actor AsyncLock {
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func run<T>(_ work: () async throws -> T) async throws -> T {
        while busy {
            await withCheckedContinuation { waiters.append($0) }
        }
        busy = true
        defer {
            busy = false
            if !waiters.isEmpty { waiters.removeFirst().resume() }
        }
        return try await work()
    }
}

extension Data {
    var base64URLEncoded: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
