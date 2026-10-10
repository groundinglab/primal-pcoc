//! Produce, in-process, on the reference live profile.
//!
//! Progress comes from the crate's own callbacks. Stop is checked at a stage
//! boundary, after the leg in progress. The object is written only when the
//! run returns. A stop writes nothing.

use crate::redact;
use hike::{ProduceFailure, Sizes, Stage};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::cell::RefCell;
use std::io::Write;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

pub const STOPPED: &str = "stopped by the user";

#[derive(Clone, Debug)]
pub struct LiveConfig {
    pub public_key_hex: String,
    pub output: String,
    /// `host:port`. Empty starts from the mainnet DNS seeds.
    pub gossip_peer: Option<String>,
}

pub fn produce_args(config: &LiveConfig) -> Vec<String> {
    let mut args = vec![
        "--pubkey-hex".to_owned(),
        config.public_key_hex.trim().to_owned(),
        "--output".to_owned(),
        config.output.clone(),
        "--profile".to_owned(),
        "live".to_owned(),
    ];
    if let Some(peer) = config
        .gossip_peer
        .as_ref()
        .map(|peer| peer.trim())
        .filter(|peer| !peer.is_empty())
    {
        args.push("--gossip-peer".to_owned());
        args.push(peer.to_owned());
    }
    args
}

struct Tracker<'a> {
    emit: &'a dyn Fn(Value),
    calls: u64,
    stage: usize,
    legs_in_stage: u64,
    beacon: ([u8; 32], u64),
}

impl Tracker<'_> {
    fn observed(&mut self, tip: [u8; 32], height: u64, header_time: u32) {
        self.calls += 1;
        if self.calls == 1 {
            self.beacon = (tip, height);
            (self.emit)(json!({
                "kind": "seed",
                "height": height,
                "hash": hike::encode_hex32(&tip),
                "time": header_time,
            }));
            (self.emit)(tip_event(tip, height));
            return;
        }
        if self.calls == 2 {
            (self.emit)(json!({ "kind": "terrain" }));
        }
        let j = self.legs_in_stage;
        self.legs_in_stage += 1;
        (self.emit)(json!({
            "kind": "leg",
            "stage": self.stage,
            "j": j,
            "stored": j + 1,
        }));
        if tip != self.beacon.0 {
            (self.emit)(tip_event(tip, height));
        }
        if tip != self.beacon.0 && height > self.beacon.1 {
            (self.emit)(json!({
                "kind": "stage_closed",
                "stage": self.stage,
                "legs": self.legs_in_stage,
                "height": height,
            }));
            self.stage += 1;
            self.legs_in_stage = 0;
            self.beacon = (tip, height);
        }
    }
}

fn tip_event(hash: [u8; 32], height: u64) -> Value {
    json!({
        "kind": "tip",
        "height": height,
        "hash": hike::encode_hex32(&hash),
    })
}

/// Run the crate's produce with progress events and a stop flag.
pub fn drive<O, P, S>(
    public_key: [u8; 32],
    sizes: Sizes,
    stop: &AtomicBool,
    emit: &dyn Fn(Value),
    mut observe: O,
    mut open_stage: P,
    mut settle: S,
) -> Result<Vec<u8>, ProduceFailure>
where
    O: FnMut() -> Result<([u8; 32], u64, u32), ProduceFailure>,
    P: FnMut(&[u8; 32], u64) -> Result<Vec<u8>, ProduceFailure>,
    S: FnMut(&mut [Stage], u64) -> Result<(), ProduceFailure>,
{
    let tracker = RefCell::new(Tracker {
        emit,
        calls: 0,
        stage: 0,
        legs_in_stage: 0,
        beacon: ([0; 32], 0),
    });
    let stopped = || stop.load(Ordering::SeqCst);
    hike::produce_with_settlement_sized(
        public_key,
        sizes,
        || {
            if stopped() {
                return Err(ProduceFailure::new(STOPPED));
            }
            let (tip, height, header_time) = observe()?;
            tracker.borrow_mut().observed(tip, height, header_time);
            Ok((tip, height))
        },
        |waypoint, beacon_height| {
            if stopped() {
                return Err(ProduceFailure::new(STOPPED));
            }
            open_stage(waypoint, beacon_height)
        },
        |stages, cut_off| {
            if stopped() {
                return Err(ProduceFailure::new(STOPPED));
            }
            emit(json!({ "kind": "settling" }));
            settle(stages, cut_off)
        },
    )
}

pub fn is_stop(err: &ProduceFailure) -> bool {
    err.message == STOPPED
}

/// Live produce. `emit` is called on the produce thread.
pub fn run_live(config: LiveConfig, stop: &AtomicBool, emit: &dyn Fn(Value)) {
    let parsed = match hike::parse_produce(&produce_args(&config)) {
        Ok(parsed) => parsed,
        Err(_) => {
            emit(json!({
                "kind": "failed",
                "message": "the produce settings were refused by the reference parser",
            }));
            return;
        }
    };
    if let Err(err) = hike::parent_dir_exists(&parsed.output) {
        emit(json!({ "kind": "failed", "message": err }));
        return;
    }
    let (dial, network) = match parsed.clock {
        hike::ProduceClock::Gossip { dial, network } => (dial, network),
        hike::ProduceClock::Http { .. } => {
            emit(json!({
                "kind": "failed",
                "message": "HTTP tip is not a produce clock",
            }));
            return;
        }
    };
    emit(json!({
        "kind": "started",
        "output": config.output,
        "public_key": hike::encode_hex32(&parsed.public_key),
    }));

    let mut book = hike::LivePathBook::new(hike::produce_http_agent(), parsed.bari_url.clone());
    let log_path = hike::produce_event_log_path(&parsed.output);
    match book.open_event_log(&parsed.output) {
        Ok(()) => emit(json!({
            "kind": "log",
            "line": format!("event log {}", log_path.display()),
        })),
        Err(err) => emit(json!({
            "kind": "log",
            "line": format!("event log {}: {err}", log_path.display()),
        })),
    }

    let clock = match dial {
        hike::GossipDial::Named(peer) => {
            emit(json!({ "kind": "log", "line": format!("gossip peer {peer}") }));
            hike::HeaderClock::start(peer, network)
        }
        hike::GossipDial::MainnetDnsSeeds => {
            emit(json!({
                "kind": "log",
                "line": "gossip from the mainnet DNS seeds",
            }));
            hike::HeaderClock::start_mainnet_dns_seeds()
        }
    };
    let bits = hike::effective_bits();
    let produced = produce_live(&clock, book, bits, parsed.public_key, stop, emit);
    let ops = hike::take_ops_capture();
    let (path_lines, other): (Vec<&str>, Vec<&str>) = ops.lines().partition(|line| {
        line.starts_with("path_skip ") || line.starts_with("not fetched by design")
    });
    for line in other {
        let redacted = redact(line);
        if !redacted.is_empty() {
            emit(json!({ "kind": "log", "line": redacted }));
        }
    }

    match produced {
        Ok(_) if stop.load(Ordering::SeqCst) => emit(json!({ "kind": "stopped" })),
        Ok(bytes) => {
            if !path_lines.is_empty() {
                emit(json!({
                    "kind": "paths",
                    "lines": path_lines.iter().map(|line| redact(line)).collect::<Vec<_>>(),
                }));
            }
            match write_atomically(Path::new(&config.output), &bytes) {
                Ok(()) => emit(json!({
                    "kind": "written",
                    "path": config.output,
                    "bytes": bytes.len() as u64,
                    "sha256": hex_sha(&bytes),
                })),
                Err(err) => emit(json!({
                    "kind": "failed",
                    "message": format!("could not write the object: {err}"),
                })),
            }
        }
        Err(err) if is_stop(&err) => emit(json!({ "kind": "stopped" })),
        Err(err) => emit(json!({ "kind": "failed", "message": redact(&err.message) })),
    }
}

fn produce_live(
    clock: &hike::HeaderClock,
    book: hike::LivePathBook,
    bits: u32,
    public_key: [u8; 32],
    stop: &AtomicBool,
    emit: &dyn Fn(Value),
) -> Result<Vec<u8>, ProduceFailure> {
    let book = RefCell::new(book);
    let tip = |hash: [u8; 32], height: u64| {
        emit(tip_event(hash, height));
    };
    hike::begin_ops_capture();
    drive(
        public_key,
        hike::Sizes::production(),
        stop,
        emit,
        || {
            let (hash, height) = hike::note_produce_headers(clock, &mut book.borrow_mut())?;
            Ok((hash, height, header_time(clock, hash)))
        },
        |waypoint, beacon_height| {
            emit(json!({ "kind": "posting", "beacon_height": beacon_height }));
            let (now_tip, height) = hike::note_produce_headers(clock, &mut book.borrow_mut())?;
            hike::emit_tip_in_hashcash(&now_tip, height);
            book.borrow_mut().post_observing(
                waypoint,
                &now_tip,
                height,
                beacon_height,
                bits,
                |after| {
                    clock.headers_since(after).map(|ticks| {
                        ticks
                            .into_iter()
                            .map(|tick| {
                                tip(tick.hash, tick.height);
                                (tick.hash, tick.height)
                            })
                            .collect()
                    })
                },
            )?;
            Ok(Vec::new())
        },
        |stages, cut_off| {
            book.borrow_mut().finish(stages, cut_off, |after| {
                if stop.load(Ordering::SeqCst) {
                    return Err(ProduceFailure::new(STOPPED));
                }
                let ticks = clock.wait_tips_after(after)?;
                Ok(ticks
                    .into_iter()
                    .map(|tick| {
                        tip(tick.hash, tick.height);
                        (tick.hash, tick.height)
                    })
                    .collect())
            })
        },
    )
}

/// Temporary sibling, sync, then rename. A failure leaves no partial object.
pub fn write_atomically(dest: &Path, bytes: &[u8]) -> std::io::Result<()> {
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

fn hex_sha(bytes: &[u8]) -> String {
    let digest: [u8; 32] = Sha256::digest(bytes).into();
    hike::encode_hex32(&digest)
}

/// A finite tip script for tests. Stage `g` records `legs[g]` legs.
/// Not a live dial.
pub fn scripted(
    legs: &[u64; 18],
    stop_after: Option<u64>,
) -> (Result<Vec<u8>, ProduceFailure>, Vec<Value>) {
    let tips = script(legs, 1_000);
    let events = RefCell::new(Vec::new());
    let stop = AtomicBool::new(false);
    let emit = |event: Value| {
        if let (Some(limit), Some("leg")) = (stop_after, event.get("kind").and_then(Value::as_str))
        {
            let seen = events
                .borrow()
                .iter()
                .filter(|event: &&Value| event.get("kind").and_then(Value::as_str) == Some("leg"))
                .count() as u64;
            if seen + 1 >= limit {
                stop.store(true, Ordering::SeqCst);
            }
        }
        events.borrow_mut().push(event);
    };
    let mut at = 0usize;
    let result = drive(
        [0x11; 32],
        Sizes::conformance_small(),
        &stop,
        &emit,
        || {
            let tip = tips[at];
            at += 1;
            Ok((tip.0, tip.1, 1_700_000_000))
        },
        |_, _| Ok(Vec::new()),
        |_, _| Ok(()),
    );
    (result, events.into_inner())
}

fn script(legs: &[u64; 18], first_height: u64) -> Vec<([u8; 32], u64)> {
    let mut hash = [0u8; 32];
    hash[0] = 1;
    let mut height = first_height;
    let mut tips = vec![(hash, height)];
    for &count in legs {
        for _ in 1..count {
            tips.push((hash, height));
        }
        hash[0] = hash[0].wrapping_add(1);
        hash[1] = 0xee;
        height += 1;
        tips.push((hash, height));
    }
    tips
}

/// `nTime` of `hash` when that header is the tip. Zero when it was not read.
fn header_time(clock: &hike::HeaderClock, hash: [u8; 32]) -> u32 {
    clock
        .headers_since(Some(hash))
        .ok()
        .and_then(|ticks| {
            ticks
                .iter()
                .rev()
                .find(|tick| tick.hash == hash)
                .map(|tick| tick.n_time)
        })
        .unwrap_or(0)
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

    const LEGS: [u64; 18] = [3, 1, 2, 1, 4, 1, 1, 2, 1, 1, 3, 1, 1, 1, 2, 1, 1, 2];

    #[test]
    fn events_follow_the_reference_stage_boundaries() {
        let (result, events) = scripted(&LEGS, None);
        let bytes = result.expect("scripted produce");
        assert!(!bytes.is_empty());

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
        assert_eq!(
            closed,
            LEGS.iter()
                .enumerate()
                .map(|(stage, legs)| (stage as u64, *legs))
                .collect::<Vec<_>>()
        );
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

        let outcome = crate::verify::verify_object_sized(&bytes, "http://127.0.0.1:9", 2);
        match outcome {
            crate::verify::VerifyOutcome::Record { json, reads, .. } => {
                assert!(json.contains("\"profile\":\"conformance-small\""));
                assert!(json.ends_with('\n'));
                assert!(reads.issued > 0);
                assert_eq!(reads.answered, 0);
            }
            crate::verify::VerifyOutcome::Unreadable => panic!("scripted object did not measure"),
        }
    }

    #[test]
    fn stop_writes_no_object() {
        let (result, events) = scripted(&LEGS, Some(2));
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
