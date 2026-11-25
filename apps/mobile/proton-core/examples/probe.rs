//! Mac test: browser sign-in (or a saved test session), then refresh + sync; prints only counts.
use m3_proton_core as core;
fn main() {
    let mut args = std::env::args().skip(1);
    let url_file = args.next().expect("url file");
    let session_file = args.next().expect("session file");
    let session: core::Session = match std::fs::read_to_string(&session_file).ok().and_then(|t| serde_json::from_str(&t).ok()) {
        Some(s) => { println!("using saved test session"); s }
        None => {
            let start = core::fork_start().expect("fork");
            std::fs::write(&url_file, &start.url).unwrap();
            println!("waiting for sign-in");
            loop {
                if let Some(s) = core::fork_poll(&start).expect("poll") { break s; }
                std::thread::sleep(std::time::Duration::from_secs(5));
            }
        }
    };
    let save = |s: &core::Session| {
        std::fs::write(&session_file, serde_json::to_string(s).unwrap()).unwrap();
        #[cfg(unix)] { use std::os::unix::fs::PermissionsExt; std::fs::set_permissions(&session_file, std::fs::Permissions::from_mode(0o600)).unwrap(); }
    };
    save(&session);
    let session = core::refresh(&session).expect("refresh");
    save(&session);
    println!("refresh ok");
    match core::sync(&session) {
        Ok(result) => {
            save(&result.session);
            println!("entries: {}", result.entries.len());
            let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs();
            for e in &result.entries {
                println!("- issuer={:?} period={} code_digits={}", e.issuer, e.period, core::code(&e.uri, now).map(|c| c.len()).unwrap_or(0));
            }
        }
        Err(e) => println!("sync error: {e}"),
    }
}
