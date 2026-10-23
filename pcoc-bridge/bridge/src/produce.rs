//! Produce, in-process.
//!
//! Live produce fails closed. The SDK build has no live header source and no
//! Bari client, so Start hike does not dial and writes nothing. The scripted
//! path is `pcoc_produce::produce_on_rig` for tests. Stop writes nothing.

use crate::redact;
use pcoc::hexutil::hex_decode;
use pcoc_produce::{
    cases_profile, cases_rig, encode_produced, path_skip_stderr, produce_on_rig, Case,
    ProduceOutcome, Progress,
};
use serde_json::{json, Value};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

pub const STOPPED: &str = "stopped by the user";

/// The sentence the desktop app shows when produce is started without a rig.
pub const LIVE_HEADER_SOURCE: &str = "live header source is not in this build";

const SETTINGS_REFUSED: &str = "the produce settings were refused by the reference parser";

#[derive(Clone, Debug)]
pub struct LiveConfig {
    pub public_key_hex: String,
    pub output: String,
    /// `host:port`. Kept for a later header source. This build does not dial it.
    pub gossip_peer: Option<String>,
}

/// Live produce. `emit` is called on the produce thread. Nothing is written.
pub fn run_live(config: LiveConfig, _stop: &AtomicBool, emit: &dyn Fn(Value)) {
    if decode_pubkey(&config.public_key_hex).is_none() {
        fail(emit, SETTINGS_REFUSED);
        return;
    }
    if let Err(err) = parent_dir_exists(&config.output) {
        fail(emit, &err);
        return;
    }
    // `config.gossip_peer` is kept for a later header source. This build does not dial it.
    fail(emit, LIVE_HEADER_SOURCE);
}

fn fail(emit: &dyn Fn(Value), message: &str) {
    emit(json!({
        "kind": "failed",
        "message": redact(message),
    }));
}

fn decode_pubkey(hex: &str) -> Option<[u8; 32]> {
    let bytes = hex_decode(hex.trim())?;
    if bytes.len() != 32 {
        return None;
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&bytes);
    Some(key)
}

fn parent_dir_exists(path: &str) -> Result<(), String> {
    if path.is_empty() {
        return Err("path is empty".into());
    }
    let path = Path::new(path);
    if path.is_dir() {
        return Err("path is a directory".into());
    }
    let parent = match path.parent() {
        Some(parent) if !parent.as_os_str().is_empty() => parent,
        _ => Path::new("."),
    };
    if !parent.is_dir() {
        return Err("parent directory does not exist".into());
    }
    Ok(())
}

#[cfg(test)]
fn is_stop(err: &str) -> bool {
    err == STOPPED
}

/// CASES S1 rig. `stop_after` cancels once that many legs have been reported.
/// Not a live dial. The object kind is the SDK export.
pub fn scripted(stop_after: Option<u64>) -> (Result<Vec<u8>, String>, Vec<Value>) {
    let profile = cases_profile(Case::S1);
    let rig = cases_rig(Case::S1);
    let seed_height = rig.seed_height;
    let key = [0x11u8; 32];
    let stop = Arc::new(AtomicBool::new(false));
    let stop_cb = Arc::clone(&stop);
    let mut events = Vec::new();
    let mut legs_in_stage = 0u64;
    let mut legs_seen = 0u64;
    let outcome = produce_on_rig(
        &key,
        &profile,
        &rig,
        stop.as_ref(),
        |progress| match progress {
            Progress::TerrainFilled => {
                events.push(json!({ "kind": "seed", "height": seed_height }));
                events.push(json!({ "kind": "terrain" }));
            }
            Progress::Leg { stage, leg, .. } => {
                legs_in_stage = leg as u64 + 1;
                legs_seen += 1;
                events.push(json!({
                    "kind": "leg",
                    "stage": stage,
                    "j": leg,
                    "stored": legs_in_stage,
                }));
                if stop_after.is_some_and(|limit| legs_seen >= limit) {
                    stop_cb.store(true, Ordering::SeqCst);
                }
            }
            Progress::Interrupt { stage, height } => {
                events.push(json!({
                    "kind": "stage_closed",
                    "stage": stage,
                    "legs": legs_in_stage,
                    "height": height,
                }));
                legs_in_stage = 0;
            }
            Progress::Closed => events.push(json!({ "kind": "settling" })),
        },
    );
    let result = match outcome {
        ProduceOutcome::Cancelled => Err(STOPPED.to_owned()),
        ProduceOutcome::Object(obj) => {
            let lines: Vec<String> = path_skip_stderr().lines().map(str::to_owned).collect();
            events.push(json!({ "kind": "paths", "lines": lines }));
            Ok(encode_produced(&obj))
        }
    };
    (result, events)
}

/// Temporary sibling, sync, then rename. A failure leaves no partial object.
/// Live produce does not call this: that path writes nothing.
#[cfg(test)]
fn write_atomically(dest: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let parent = match dest.parent() {
        Some(parent) if !parent.as_os_str().is_empty() => parent,
        _ => Path::new("."),
    };
    let name = dest
        .file_name()
        .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::InvalidInput, "no file name"))?;
    let tmp = parent.join(format!(
        ".{}.tmp-{}",
        name.to_string_lossy(),
        std::process::id()
    ));
    let result = (|| {
        use std::io::Write;
        let mut file = std::fs::File::create(&tmp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        std::fs::rename(&tmp, dest)
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    result
}

pub fn spawn_live(
    config: LiveConfig,
    stop: Arc<AtomicBool>,
    emit: Arc<dyn Fn(Value) + Send + Sync>,
) {
    std::thread::Builder::new()
        .name("pcoc-produce".into())
        .spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                run_live(config, &stop, &|event| emit(event));
            }));
            if result.is_err() {
                emit(json!({
                    "kind": "failed",
                    "message": "the hike engine stopped unexpectedly",
                }));
            }
        })
        .expect("spawn produce thread");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn events_follow_the_sdk_stage_boundaries() {
        let (result, events) = scripted(None);
        let bytes = result.expect("scripted produce");
        let obj = pcoc::decode_object(&bytes).expect("sdk object");
        assert_eq!(obj.kind, pcoc::constants::KIND);
        assert_eq!(obj.fill, pcoc::constants::FILL);
        assert_eq!(obj.public_key, [0x11; 32]);

        let closed: Vec<(u64, u64)> = events
            .iter()
            .filter_map(|event| {
                if event.get("kind").and_then(Value::as_str) != Some("stage_closed") {
                    return None;
                }
                Some((
                    event.get("stage").and_then(Value::as_u64).unwrap(),
                    event.get("legs").and_then(Value::as_u64).unwrap(),
                ))
            })
            .collect();
        assert_eq!(closed.len(), 18);
        assert!(closed.iter().all(|(_, legs)| *legs == 1));
        assert_eq!(
            events
                .iter()
                .filter(|event| event.get("kind").and_then(Value::as_str) == Some("terrain"))
                .count(),
            1
        );
        assert_eq!(
            events
                .iter()
                .filter(|event| event.get("kind").and_then(Value::as_str) == Some("settling"))
                .count(),
            1
        );
        assert!(events.iter().any(|event| {
            event.get("kind").and_then(Value::as_str) == Some("seed")
                && event.get("height").and_then(Value::as_u64) == Some(1_000)
        }));
        assert!(events
            .iter()
            .any(|event| { event.get("kind").and_then(Value::as_str) == Some("paths") }));

        let outcome = crate::verify::verify_object_sized(&bytes, "http://127.0.0.1:9", 2);
        match outcome {
            crate::verify::VerifyOutcome::Record { json, reads, .. } => {
                assert!(json.contains("\"profile\":\"conformance-small\""));
                assert!(reads.issued > 0);
                assert_eq!(reads.answered, 0);
            }
            crate::verify::VerifyOutcome::Unreadable => panic!("scripted object did not measure"),
        }
    }

    #[test]
    fn stop_writes_no_object() {
        let (result, events) = scripted(Some(2));
        assert!(result.is_err());
        assert!(is_stop(&result.unwrap_err()));
        assert!(events
            .iter()
            .any(|event| event.get("kind").and_then(Value::as_str) == Some("leg")));
        assert!(!events
            .iter()
            .any(|event| event.get("kind").and_then(Value::as_str) == Some("written")));
    }

    #[test]
    fn live_produce_fails_closed_without_a_header_source() {
        let dir = std::env::temp_dir().join(format!("pcoc-bridge-live-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let dest = dir.join("hike.cbor");
        let events = std::cell::RefCell::new(Vec::new());
        let stop = AtomicBool::new(false);
        run_live(
            LiveConfig {
                public_key_hex: "11".repeat(32),
                output: dest.display().to_string(),
                gossip_peer: Some("192.0.2.7:8333".into()),
            },
            &stop,
            &|event| events.borrow_mut().push(event),
        );
        let events = events.into_inner();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0]["kind"], "failed");
        assert_eq!(events[0]["message"], LIVE_HEADER_SOURCE);
        assert!(!dest.exists());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn a_bad_key_is_refused_before_the_header_source() {
        let dir = std::env::temp_dir().join(format!("pcoc-bridge-badkey-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let dest = dir.join("hike.cbor");
        let events = std::cell::RefCell::new(Vec::new());
        run_live(
            LiveConfig {
                public_key_hex: "zz".into(),
                output: dest.display().to_string(),
                gossip_peer: None,
            },
            &AtomicBool::new(false),
            &|event| events.borrow_mut().push(event),
        );
        let events = events.into_inner();
        assert_eq!(events[0]["message"], SETTINGS_REFUSED);
        assert!(!dest.exists());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn atomic_write_leaves_only_the_object() {
        let dir = std::env::temp_dir().join(format!("pcoc-bridge-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let dest = dir.join("hike.cbor");
        write_atomically(&dest, b"abc").unwrap();
        assert_eq!(std::fs::read(&dest).unwrap(), b"abc");
        let names: Vec<_> = std::fs::read_dir(&dir)
            .unwrap()
            .map(|entry| entry.unwrap().file_name())
            .collect();
        assert_eq!(names.len(), 1);
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
