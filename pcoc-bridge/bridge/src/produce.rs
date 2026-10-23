//! Produce, in-process.
//!
//! Start hike uses the SDK live header source (`GossipSource`) and posts
//! waypoints to Bari. A source that fails closed writes no object. The
//! scripted path is `pcoc_produce::produce_on_rig` for tests. Stop writes
//! nothing. Host tests pass a mock source and do not dial.

use crate::redact;
use pcoc::hexutil::{hex_decode, hex_encode};
use pcoc::profile::Profile;
#[cfg(test)]
use pcoc_produce::bari::SilentBari;
use pcoc_produce::bari::{BariHub, HttpBari, LiveEvent, Progress as LiveProgress, RESEND_AFTER};
use pcoc_produce::live::{produce_live, write_atomically, LiveRunOpts, LiveStop};
#[cfg(test)]
use pcoc_produce::source::{FailClosedSource, HeldTip, ManualSource, FAIL_CLOSED};
use pcoc_produce::source::{GossipSource, TipSource};
use pcoc_produce::{
    cases_profile, cases_rig, encode_produced, path_skip_stderr, produce_on_rig, Case,
    ProduceOutcome, Progress,
};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;

pub const STOPPED: &str = "stopped by the user";

const SETTINGS_REFUSED: &str = "the produce settings were refused by the reference parser";

type Emit = Arc<dyn Fn(Value) + Send + Sync>;

#[derive(Clone, Debug)]
pub struct LiveConfig {
    pub public_key_hex: String,
    pub output: String,
    /// `host:port`. Empty selects the Bitcoin DNS seeds.
    pub gossip_peer: Option<String>,
}

/// Live produce. `emit` is called on the produce thread and on the Bari threads.
pub fn run_live(config: LiveConfig, stop: &Arc<AtomicBool>, emit: &Emit) {
    let Some(key) = decode_pubkey(&config.public_key_hex) else {
        fail(emit, SETTINGS_REFUSED);
        return;
    };
    if let Err(err) = parent_dir_exists(&config.output) {
        fail(emit, &err);
        return;
    }
    emit(json!({
        "kind": "started",
        "output": config.output,
    }));
    let peer = config.gossip_peer.clone().unwrap_or_default();
    let source = GossipSource::spawn(&peer, Arc::clone(stop));
    let progress = map_progress(emit);
    let bari = match HttpBari::open(
        crate::BARI_URL,
        RESEND_AFTER,
        source.tip_fn(),
        Arc::clone(&progress),
    ) {
        Ok(bari) => bari,
        Err(message) => {
            drop(source);
            fail(emit, &message);
            return;
        }
    };
    finish(
        &config,
        key,
        &Profile::production(),
        &source,
        &bari,
        stop,
        emit,
        &progress,
    );
}

fn fail(emit: &Emit, message: &str) {
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

/// Phone JSON for the SDK live events. Leg counts reset when a stage closes.
fn map_progress(emit: &Emit) -> LiveProgress {
    let emit = Arc::clone(emit);
    let legs = Arc::new(AtomicU64::new(0));
    let seeded = Arc::new(AtomicBool::new(false));
    let beacon_height = Arc::new(AtomicU64::new(0));
    Arc::new(move |event| match event {
        LiveEvent::Terrain => emit(json!({ "kind": "terrain" })),
        LiveEvent::Leg { stage, leg, .. } => {
            let stored = legs.fetch_add(1, Ordering::SeqCst) + 1;
            emit(json!({
                "kind": "leg",
                "stage": stage,
                "j": leg,
                "stored": stored,
            }));
        }
        LiveEvent::Interrupt { stage, height } => {
            let stored = legs.swap(0, Ordering::SeqCst);
            beacon_height.store(height, Ordering::SeqCst);
            emit(json!({
                "kind": "stage_closed",
                "stage": stage,
                "legs": stored,
                "height": height,
            }));
        }
        LiveEvent::Header { height, n_time, .. } => {
            if !seeded.swap(true, Ordering::SeqCst) {
                emit(json!({
                    "kind": "seed",
                    "height": height,
                    "time": n_time,
                }));
            }
            emit(json!({ "kind": "tip", "height": height }));
        }
        LiveEvent::Waypoint { .. } => {
            emit(json!({
                "kind": "posting",
                "beacon_height": beacon_height.load(Ordering::SeqCst),
            }));
        }
        LiveEvent::Alert { name, .. } => emit(json!({ "kind": "log", "line": name })),
        LiveEvent::Posted {
            stage,
            status,
            succeeded,
            ..
        } => {
            let code = status
                .map(|code| code.to_string())
                .unwrap_or_else(|| "none".to_owned());
            emit(json!({
                "kind": "log",
                "line": format!("waypoint {stage} posted={succeeded} status={code}"),
            }));
        }
        LiveEvent::Fetch { stage, worked, .. } => emit(json!({
            "kind": "log",
            "line": format!("path {stage} worked={worked}"),
        })),
        LiveEvent::Checked { .. } => {}
        LiveEvent::Closed => emit(json!({ "kind": "settling" })),
    })
}

fn finish(
    config: &LiveConfig,
    key: [u8; 32],
    profile: &Profile,
    source: &dyn TipSource,
    bari: &dyn BariHub,
    stop: &AtomicBool,
    emit: &Emit,
    progress: &LiveProgress,
) {
    let out = PathBuf::from(&config.output);
    let opts = LiveRunOpts {
        public_key: &key,
        profile,
        out: &out,
        cancel: stop,
    };
    match produce_live(&opts, source, bari, progress) {
        Ok(product) => match write_atomically(&out, &product.bytes) {
            Ok(()) => {
                let lines: Vec<String> = product.stderr.lines().map(str::to_owned).collect();
                emit(json!({ "kind": "paths", "lines": lines }));
                emit(json!({
                    "kind": "written",
                    "path": config.output,
                    "bytes": product.bytes.len(),
                    "sha256": hex_encode(&pcoc::hash::sha256(&product.bytes)),
                }));
            }
            Err(err) => fail(emit, &format!("could not write the object: {err}")),
        },
        Err(LiveStop::Cancelled) => emit(json!({ "kind": "stopped" })),
        Err(LiveStop::Failed(message)) => fail(emit, &message),
    }
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

pub fn spawn_live(config: LiveConfig, stop: Arc<AtomicBool>, emit: Emit) {
    std::thread::Builder::new()
        .name("pcoc-produce".into())
        .spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                run_live(config, &stop, &emit);
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
fn collect(emit_into: &Arc<std::sync::Mutex<Vec<Value>>>) -> Emit {
    let slot = Arc::clone(emit_into);
    Arc::new(move |event| {
        slot.lock().expect("events").push(event);
    })
}

#[cfg(test)]
fn small_profile() -> Profile {
    Profile::conformance_small(32, 64, 65_536, 1).expect("profile")
}

#[cfg(test)]
fn mock_tip(height: u64) -> HeldTip {
    let mut hash = [0u8; 32];
    hash[..8].copy_from_slice(&height.to_le_bytes());
    HeldTip {
        hash,
        height,
        header: [0u8; 80],
        n_time: 1_700_000_000,
    }
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
        let events = Arc::new(std::sync::Mutex::new(Vec::new()));
        let emit = collect(&events);
        let config = LiveConfig {
            public_key_hex: "11".repeat(32),
            output: dest.display().to_string(),
            gossip_peer: Some("192.0.2.7:8333".into()),
        };
        let key = decode_pubkey(&config.public_key_hex).unwrap();
        let progress = map_progress(&emit);
        finish(
            &config,
            key,
            &small_profile(),
            &FailClosedSource::new(FAIL_CLOSED),
            &SilentBari,
            &AtomicBool::new(false),
            &emit,
            &progress,
        );
        let events = events.lock().expect("events");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0]["kind"], "failed");
        assert_eq!(events[0]["message"], FAIL_CLOSED);
        assert!(!dest.exists());
        drop(events);
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn a_bad_key_is_refused_before_the_header_source() {
        let dir = std::env::temp_dir().join(format!("pcoc-bridge-badkey-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let dest = dir.join("hike.cbor");
        let events = Arc::new(std::sync::Mutex::new(Vec::new()));
        let emit = collect(&events);
        run_live(
            LiveConfig {
                public_key_hex: "zz".into(),
                output: dest.display().to_string(),
                gossip_peer: None,
            },
            &Arc::new(AtomicBool::new(false)),
            &emit,
        );
        let events = events.lock().expect("events");
        assert_eq!(events[0]["message"], SETTINGS_REFUSED);
        assert!(!dest.exists());
        drop(events);
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn stop_on_the_live_path_writes_no_object() {
        let dir =
            std::env::temp_dir().join(format!("pcoc-bridge-live-stop-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let dest = dir.join("hike.cbor");
        let events = Arc::new(std::sync::Mutex::new(Vec::new()));
        let emit = collect(&events);
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = Arc::clone(&stop);
        let emit: Emit = Arc::new(move |event: Value| {
            if event.get("kind").and_then(Value::as_str) == Some("leg") {
                stop_flag.store(true, Ordering::SeqCst);
            }
            emit(event);
        });
        let config = LiveConfig {
            public_key_hex: "22".repeat(32),
            output: dest.display().to_string(),
            gossip_peer: None,
        };
        let key = decode_pubkey(&config.public_key_hex).unwrap();
        let progress = map_progress(&emit);
        finish(
            &config,
            key,
            &small_profile(),
            &ManualSource::holding(mock_tip(972_685)),
            &SilentBari,
            stop.as_ref(),
            &emit,
            &progress,
        );
        let events = events.lock().expect("events");
        assert!(events.iter().any(|event| event["kind"] == "leg"));
        assert!(events.iter().any(|event| event["kind"] == "stopped"));
        assert!(!events.iter().any(|event| event["kind"] == "written"));
        assert!(!dest.exists());
        drop(events);
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
