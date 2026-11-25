//! The app-facing API (Swift and Kotlin through uniffi). Sessions travel as JSON strings, so the
//! apps can keep them in the keychain as they are.

use crate::{ForkStart, ProtonError, Session};

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum ProtonFfiError {
    #[error("{0}")]
    Network(String),
    #[error("{0}")]
    Expired(String),
    #[error("{0}")]
    Other(String),
}

impl From<ProtonError> for ProtonFfiError {
    fn from(e: ProtonError) -> Self {
        match e {
            ProtonError::Network(_) => ProtonFfiError::Network(e.to_string()),
            ProtonError::SessionExpired => ProtonFfiError::Expired(e.to_string()),
            other => ProtonFfiError::Other(other.to_string()),
        }
    }
}

type Result<T> = std::result::Result<T, ProtonFfiError>;

fn json<T: serde::Serialize>(v: &T) -> String {
    serde_json::to_string(v).unwrap_or_default()
}

fn parse<T: serde::de::DeserializeOwned>(text: &str) -> Result<T> {
    serde_json::from_str(text).map_err(|e| ProtonFfiError::Other(e.to_string()))
}

/// A started browser sign-in.
#[derive(uniffi::Record)]
pub struct ProtonSignIn {
    /// Open this in a browser.
    pub url: String,
    /// Pass back to [proton_sign_in_poll].
    pub state: String,
}

/// One authenticator entry (secret: the uri holds the TOTP key).
#[derive(uniffi::Record)]
pub struct ProtonEntry {
    pub remote_id: String,
    pub id: String,
    pub name: String,
    pub issuer: String,
    pub uri: String,
    pub note: Option<String>,
    pub period: u16,
}

#[derive(uniffi::Record)]
pub struct ProtonSync {
    /// The session to keep (tokens may have been renewed).
    pub session: String,
    pub entries: Vec<ProtonEntry>,
}

#[uniffi::export]
pub fn proton_sign_in_start() -> Result<ProtonSignIn> {
    let start = crate::fork_start()?;
    Ok(ProtonSignIn { url: start.url.clone(), state: json(&start) })
}

/// The session (JSON) once the user has signed in in the browser, None while waiting.
#[uniffi::export]
pub fn proton_sign_in_poll(state: String) -> Result<Option<String>> {
    let start: ForkStart = parse(&state)?;
    Ok(crate::fork_poll(&start)?.map(|s| json(&s)))
}

/// Loads every entry; renews the session if needed.
#[uniffi::export]
pub fn proton_sync(session: String) -> Result<ProtonSync> {
    let s: Session = parse(&session)?;
    let r = crate::sync(&s)?;
    Ok(ProtonSync {
        session: json(&r.session),
        entries: r
            .entries
            .into_iter()
            .map(|e| ProtonEntry { remote_id: e.remote_id, id: e.id, name: e.name, issuer: e.issuer, uri: e.uri, note: e.note, period: e.period })
            .collect(),
    })
}

#[uniffi::export]
pub fn proton_sign_out(session: String) {
    if let Ok(s) = parse::<Session>(&session) {
        crate::sign_out(&s);
    }
}

/// Current code for an otpauth or steam URI.
#[uniffi::export]
pub fn proton_code(uri: String, unix_time: u64) -> Option<String> {
    crate::code(&uri, unix_time)
}
