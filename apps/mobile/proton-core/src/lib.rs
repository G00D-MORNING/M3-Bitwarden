//! Proton Authenticator sync for M3 Bitwarden.
//!
//! - Sign-in: Proton's session fork for external clients (as in Proton's own `pass-cli`): the
//!   user signs in on account.proton.me in a browser (password, 2FA and CAPTCHA stay with
//!   Proton); this client receives its own session plus the key password, encrypted for it.
//! - The session renews itself with its refresh token, so no new sign-in is needed while it is
//!   used now and then.
//! - Entries: the authenticator key (32 bytes, PGP-encrypted to the user key) is unlocked with
//!   the user key; entries are decrypted and parsed by Proton's open-source authenticator core.
//!
//! Nothing here stores anything: the caller keeps the [Session] (in the keychain) and passes it in.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{aead::consts::U16, aes::Aes256, AesGcm, Nonce};
use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use pgp::composed::{Deserializable, Message, SignedSecretKey};
use pgp::types::SecretKeyTrait;
use proton_authenticator::{AuthenticatorClient, AuthenticatorEntry};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::time::Duration;

mod ffi;

uniffi::setup_scaffolding!("m3_proton");

pub const CLIENT_ID: &str = "external-pass-m3bitwarden";
const APP_VERSION: &str = "external-pass-m3bitwarden@2.7.1";
const SESSION_API: &str = "https://pass-api.proton.me";
const AUTHENTICATOR_API: &str = "https://authenticator-api.proton.me";
const ACCOUNT_URL: &str = "https://account.proton.me";

#[derive(Debug, thiserror::Error)]
pub enum ProtonError {
    #[error("Keine Verbindung zu Proton: {0}")]
    Network(String),
    #[error("Proton hat die Anfrage abgelehnt ({status}): {message}")]
    Api { status: u16, message: String },
    #[error("Die Proton-Anmeldung ist abgelaufen. Bitte neu verbinden.")]
    SessionExpired,
    #[error("Schlüssel konnte nicht entsperrt werden: {0}")]
    Crypto(String),
    #[error("Unerwartete Antwort von Proton: {0}")]
    Format(String),
}

type Result<T> = std::result::Result<T, ProtonError>;

/// A started browser sign-in: open [url], then poll with the rest.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ForkStart {
    pub url: String,
    pub selector: String,
    pub key_b64: String,
    pub unauth_uid: String,
    pub unauth_token: String,
}

/// A signed-in Proton session. Secret: keep it in the keychain only.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Session {
    pub uid: String,
    pub access_token: String,
    pub refresh_token: String,
    /// Passphrase of the user keys, from the fork payload.
    pub key_password: String,
}

/// One authenticator entry, decrypted.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Entry {
    /// Server id (for updates and deletes).
    pub remote_id: String,
    /// Entry id inside the encrypted content (stable across devices).
    pub id: String,
    pub name: String,
    pub issuer: String,
    pub uri: String,
    pub note: Option<String>,
    pub period: u16,
}

/// Result of a sync: the entries and the session as it is now (tokens may have been renewed).
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SyncResult {
    pub session: Session,
    pub entries: Vec<Entry>,
}

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new().timeout(Duration::from_secs(25)).build()
}

fn call(method: &str, url: &str, auth: Option<(&str, &str)>, body: Option<Value>) -> Result<Value> {
    let mut req = agent()
        .request(method, url)
        .set("x-pm-appversion", APP_VERSION)
        .set("Accept", "application/vnd.protonmail.v1+json");
    if let Some((uid, token)) = auth {
        req = req.set("x-pm-uid", uid).set("Authorization", &format!("Bearer {token}"));
    }
    let resp = match body {
        Some(b) => req.send_json(b),
        None => req.call(),
    };
    match resp {
        Ok(r) => r.into_json::<Value>().or_else(|_| Ok(Value::Null)),
        Err(ureq::Error::Status(status, r)) => {
            let body: Value = r.into_json().unwrap_or(Value::Null);
            if status == 401 {
                return Err(ProtonError::SessionExpired);
            }
            let message = body.get("Error").and_then(Value::as_str).unwrap_or("").to_string();
            Err(ProtonError::Api { status, message })
        }
        Err(e) => Err(ProtonError::Network(e.to_string())),
    }
}

fn str_field(v: &Value, key: &str) -> Result<String> {
    v.get(key).and_then(Value::as_str).map(str::to_string).ok_or_else(|| ProtonError::Format(format!("{key} fehlt")))
}

// MARK: Sign-in

/// Starts a browser sign-in for this app.
pub fn fork_start() -> Result<ForkStart> {
    let unauth = call("POST", &format!("{SESSION_API}/auth/v4/sessions"), None, None)?;
    let uid = str_field(&unauth, "UID")?;
    let token = str_field(&unauth, "AccessToken")?;
    let fork = call("GET", &format!("{SESSION_API}/auth/v4/sessions/forks"), Some((&uid, &token)), None)?;
    let user_code = str_field(&fork, "UserCode")?;
    let selector = str_field(&fork, "Selector")?;
    let mut key = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut key);
    let key_b64 = B64.encode(key);
    let payload = format!("0:{user_code}:{key_b64}:{CLIENT_ID}");
    let url = format!("{ACCOUNT_URL}/desktop/login?app=pass#payload={}", urlencoding::encode(&payload));
    Ok(ForkStart { url, selector, key_b64, unauth_uid: uid, unauth_token: token })
}

/// One poll: the session once the user has signed in, None while still waiting.
pub fn fork_poll(start: &ForkStart) -> Result<Option<Session>> {
    let url = format!("{SESSION_API}/auth/v4/sessions/forks/{}", start.selector);
    match call("GET", &url, Some((&start.unauth_uid, &start.unauth_token)), None) {
        Ok(v) if v.get("AccessToken").is_some() => {
            let key = B64.decode(&start.key_b64).map_err(|e| ProtonError::Format(e.to_string()))?;
            let payload = decrypt_fork_payload(&key, &str_field(&v, "Payload")?)?;
            Ok(Some(Session {
                uid: str_field(&v, "UID")?,
                access_token: str_field(&v, "AccessToken")?,
                refresh_token: str_field(&v, "RefreshToken")?,
                key_password: payload,
            }))
        }
        Ok(_) => Ok(None),
        // 422 / code 2001 while the user has not signed in yet.
        Err(ProtonError::Api { status: 422, .. }) => Ok(None),
        Err(e) => Err(e),
    }
}

/// The fork payload: AES-256-GCM with a 16-byte nonce, JSON {"keyPassword": …}.
fn decrypt_fork_payload(key: &[u8], payload_b64: &str) -> Result<String> {
    let data = B64.decode(payload_b64).map_err(|e| ProtonError::Format(e.to_string()))?;
    if data.len() < 16 {
        return Err(ProtonError::Format("Payload zu kurz".into()));
    }
    let (nonce, cipher) = data.split_at(16);
    let aes = AesGcm::<Aes256, U16>::new_from_slice(key).map_err(|e| ProtonError::Crypto(e.to_string()))?;
    let plain = aes
        .decrypt(Nonce::<U16>::from_slice(nonce), Payload { msg: cipher, aad: &[] })
        .map_err(|_| ProtonError::Crypto("Payload nicht lesbar".into()))?;
    let v: Value = serde_json::from_slice(&plain).map_err(|e| ProtonError::Format(e.to_string()))?;
    str_field(&v, "keyPassword")
}

/// Renews the access token with the refresh token (Proton keeps a session alive while it is used).
pub fn refresh(session: &Session) -> Result<Session> {
    let body = json!({
        "ResponseType": "token",
        "GrantType": "refresh_token",
        "RefreshToken": session.refresh_token,
        "RedirectURI": "https://protonmail.com",
    });
    let v = call("POST", &format!("{SESSION_API}/auth/v4/refresh"), Some((&session.uid, &session.access_token)), Some(body))
        .or_else(|e| match e {
            // An expired access token still identifies the session by its UID.
            ProtonError::SessionExpired => Err(e),
            other => Err(other),
        })?;
    Ok(Session {
        uid: session.uid.clone(),
        access_token: str_field(&v, "AccessToken")?,
        refresh_token: str_field(&v, "RefreshToken")?,
        key_password: session.key_password.clone(),
    })
}

/// Signs this app's session out at Proton.
pub fn sign_out(session: &Session) {
    let _ = call("DELETE", &format!("{SESSION_API}/auth/v4"), Some((&session.uid, &session.access_token)), None);
}

// MARK: Keys

/// Unlocks the user keys with the key password and returns every authenticator key (id → 32 bytes).
fn authenticator_keys(session: &Session) -> Result<Vec<(String, Vec<u8>)>> {
    let auth = Some((session.uid.as_str(), session.access_token.as_str()));
    let user = call("GET", &format!("{AUTHENTICATOR_API}/core/v4/users"), auth, None)?;
    let mut user_keys = Vec::new();
    for k in user.pointer("/User/Keys").and_then(Value::as_array).cloned().unwrap_or_default() {
        if k.get("Active").and_then(Value::as_i64) == Some(0) {
            continue;
        }
        let Some(armored) = k.get("PrivateKey").and_then(Value::as_str) else { continue };
        match SignedSecretKey::from_string(armored) {
            Ok((key, _)) => user_keys.push(key),
            Err(e) => diag(&format!("user key parse: {e}")),
        }
    }
    if user_keys.is_empty() {
        return Err(ProtonError::Crypto("kein Benutzerschlüssel".into()));
    }
    for key in &user_keys {
        let pw = session.key_password.clone();
        diag(&format!("user key unlock: {:?}", key.unlock(|| pw.clone(), |_| Ok(())).map_err(|e| e.to_string())));
    }
    let keys = call("GET", &format!("{AUTHENTICATOR_API}/authenticator/v1/key"), auth, None)?;
    let mut out = Vec::new();
    for k in keys.pointer("/Keys/Keys").and_then(Value::as_array).cloned().unwrap_or_default() {
        let id = str_field(&k, "KeyID")?;
        let wrapped = B64.decode(str_field(&k, "Key")?).map_err(|e| ProtonError::Format(e.to_string()))?;
        if let Some(plain) = pgp_decrypt(&wrapped, &user_keys, &session.key_password) {
            out.push((id, plain));
        }
    }
    if out.is_empty() {
        return Err(ProtonError::Crypto("Authenticator-Schlüssel nicht lesbar".into()));
    }
    Ok(out)
}

fn pgp_decrypt(binary: &[u8], keys: &[SignedSecretKey], password: &str) -> Option<Vec<u8>> {
    let msg = match Message::from_bytes(binary) {
        Ok(m) => m,
        Err(e) => { diag(&format!("message parse: {e}")); return None; }
    };
    for key in keys {
        let pw = password.to_string();
        match msg.decrypt(|| pw.clone(), &[key]) {
            Ok((decrypted, _)) => {
                let decrypted = if matches!(decrypted, Message::Compressed(_)) { decrypted.decompress().ok()? } else { decrypted };
                match decrypted.get_content() {
                    Ok(Some(content)) => return Some(content),
                    other => diag(&format!("content: {:?}", other.map(|c| c.map(|c| c.len())))),
                }
            }
            Err(e) => diag(&format!("decrypt: {e}")),
        }
    }
    None
}

/// Debug output for the Mac probe (no secrets: only error texts and kinds).
fn diag(text: &str) {
    if std::env::var_os("M3B_PROTON_DIAG").is_some() {
        eprintln!("diag: {text}");
    }
}

// MARK: Entries

/// Loads and decrypts every authenticator entry. Renews the session first if the access token
/// has expired; the returned session is the one to keep.
pub fn sync(session: &Session) -> Result<SyncResult> {
    match load(session) {
        Err(ProtonError::SessionExpired) => {
            let renewed = refresh(session)?;
            let entries = load(&renewed)?;
            Ok(SyncResult { session: renewed, entries })
        }
        other => other.map(|entries| SyncResult { session: session.clone(), entries }),
    }
}

fn load(session: &Session) -> Result<Vec<Entry>> {
    let keys = authenticator_keys(session)?;
    let auth = Some((session.uid.as_str(), session.access_token.as_str()));
    let mut out = Vec::new();
    let mut since: Option<String> = None;
    loop {
        let url = match &since {
            Some(last) => format!("{AUTHENTICATOR_API}/authenticator/v1/entry?Since={last}"),
            None => format!("{AUTHENTICATOR_API}/authenticator/v1/entry"),
        };
        let page = call("GET", &url, auth, None)?;
        let list = page.pointer("/Entries/Entries").and_then(Value::as_array).cloned().unwrap_or_default();
        for e in &list {
            let key_id = str_field(e, "AuthenticatorKeyID")?;
            let Some((_, key)) = keys.iter().find(|(id, _)| *id == key_id) else { continue };
            let content = B64.decode(str_field(e, "Content")?).map_err(|e| ProtonError::Format(e.to_string()))?;
            let Ok(mut decrypted) = proton_authenticator::decrypt_entries(vec![content], key.clone()) else { continue };
            let Some(entry) = decrypted.pop() else { continue };
            out.push(to_entry(str_field(e, "EntryID")?, &entry));
        }
        let last = page.pointer("/Entries/LastID").and_then(Value::as_str).map(str::to_string);
        if list.is_empty() || last.is_none() || last == since {
            break;
        }
        since = last;
    }
    Ok(out)
}

fn to_entry(remote_id: String, e: &AuthenticatorEntry) -> Entry {
    Entry {
        remote_id,
        id: e.id.clone(),
        name: e.name(),
        issuer: e.issuer(),
        uri: e.uri(),
        note: e.note.clone(),
        period: e.period(),
    }
}

/// Current code for an otpauth/steam URI (Proton's generator).
pub fn code(uri: &str, unix_time: u64) -> Option<String> {
    let client = AuthenticatorClient::new();
    let entry = client.entry_from_uri(uri.to_string()).ok()?;
    client.generate_codes(&[entry], unix_time).ok()?.pop().map(|c| c.current_code)
}
