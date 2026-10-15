//! Shared fill, leg, bind, and hike-object code for the reference CLI.
//! Satoshi Hike v2.0 is the construction. CLI v0.1 is the program interface.
//! `verify` records section 14 measurements. `admit` applies section 15.
//! Six recomputed legs run on a pool of exactly 6 threads. That pool size is
//! not the draw count. Rule A4.3 uses `PCOC_BIND_THREADS`, default 6.
#![forbid(unsafe_code)]

mod admit;
mod args;
mod audit;
mod bind;
mod cbor;
mod clock;
mod consts;
mod gossip;
mod hashcash;
mod hex;
mod http;
mod object;
mod pathbody;
pub mod pathchain;
mod plane;
mod produce;
mod producelog;
mod report;
mod section14;
mod sha;
mod subscribe;
mod terrain;
mod verify;
mod walk;

pub use admit::{admit, failure_facts};
pub use args::{
    absolute_http_without_trailing_slash, apply_live_gossip_env, apply_test_index_env,
    apply_test_produce_env, parse_produce, parse_verify, AdmitPolicy, GossipDial, MeasureProfile,
    PlaneProfile, ProduceArgs, ProduceClock, UsageError, VerifyArgs,
};
pub use bind::{
    checkpoint_sized, successor_waypoint, successor_waypoint_sized, terrain_sample_sized,
};
pub use clock::{parse_index_beacon, parse_index_document, IndexBeacon};
pub use consts::{
    anchor_window, height_in_anchor_window, Sizes, ANCHOR_WINDOW_OFFSETS, BEACON_BYTES,
    CHECKPOINT_PREIMAGE_LEN, DEFAULT_BIND_THREADS, DEFAULT_INDEX_URL, EARLIER_KINDS, FILL,
    HASH_BYTES, KIND, LEG_RATE_FLOOR_DEFAULT, LEG_RATE_FLOOR_LENIENT, LEG_RATE_FLOOR_STRICT,
    LEG_RESULT_BYTES, MIX_ADD, MIX_MUL1, MIX_MUL2, N_SLOTS, OFFSET_MODULUS, PARENT_MUL,
    PUBLIC_KEY_BYTES, SAMPLE_BYTES, SEAL_DOMAIN, SEED_DOMAIN, SLOT_BYTES, SLOT_MUL, STAGES,
    STEPS_PER_LEG, TERRAIN_BYTES, WORDS_PER_SLOT, WORD_XOR,
};
pub use gossip::{
    note_produce_headers, serve_synth, GossipNetwork, HeaderClock, TipTick, MAINNET_DNS_PORT,
    MAINNET_DNS_SEEDS,
};
pub use hashcash::effective_bits;
pub use hex::encode_hex32;
pub use http::{
    beacon_at_height, emit_tip_in_hashcash, http_agent, index_document, live_on_chain_height,
    live_path_due_height, open_stage, open_stage_at_tip, plant_stage, produce_http_agent,
    produce_open_stage, read_clock, read_index_beacon, LivePathBook, PathFetch, HEADERS_AFTER_POST,
    PATH_404_PAUSE,
};
pub use object::{
    earlier_object_kind, encode_hike, encode_hike_sized, object_public_key, structural_object,
    yielded_public_key, Hike, Stage, YieldedKey,
};
pub use pathbody::{encode_path_in_block, encode_single_leaf, path_locators, PathError, PathLocators};
pub use pathchain::{direct_facts, direct_matches, proxy_matches, DirectFacts, MAINNET_NETWORK};
pub use plane::live_plane_host;
pub use produce::{
    produce, produce_stage, produce_with_settlement, produce_with_settlement_sized, ProduceFailure,
    StageRun,
};
pub use producelog::event_log_path as produce_event_log_path;
pub use report::{emit_report, parent_dir_exists, Report};
pub use subscribe::{
    publish_verify, verify_events, Found, VerifyEvent, VerifyLog, VerifyObservation,
    VerifySubscriber, CHECK_ORDER,
};
pub use terrain::{fill_pin, fill_terrain, fill_terrain_sized, production_seed};
pub use verify::{
    verify, verify_record_json, verify_record_sha256, verify_sized, verify_with_bind_threads,
    verify_with_bind_threads_sized, Anchor, AuditDraw, ChainRead, CheckpointMeasurement, LegRate,
    Measurements, ObjectLineage, Recorded, SixLegs, StageClock, StageMeasurement, StageReport,
    StoredGap, StoredRate, WaypointMeasurement,
};
pub use walk::{leg_result, leg_result_sized};

/// Stderr lines from gossip, Hashcash, and the path book, for one test at a time.
///
/// Production leaves this off. `note_ops_line` still prints the line.
mod linelog {
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Mutex;

    static ON: AtomicBool = AtomicBool::new(false);
    static LINES: Mutex<Vec<String>> = Mutex::new(Vec::new());

    pub fn begin() {
        LINES.lock().unwrap_or_else(|err| err.into_inner()).clear();
        ON.store(true, Ordering::SeqCst);
    }

    pub fn take() -> String {
        ON.store(false, Ordering::SeqCst);
        let lines = std::mem::take(&mut *LINES.lock().unwrap_or_else(|err| err.into_inner()));
        if lines.is_empty() {
            String::new()
        } else {
            let mut out = lines.join("\n");
            out.push('\n');
            out
        }
    }

    pub(crate) fn note(line: &str) {
        if ON.load(Ordering::Relaxed) {
            LINES
                .lock()
                .unwrap_or_else(|err| err.into_inner())
                .push(line.to_owned());
        }
    }
}

pub(crate) use linelog::note as note_ops_line;
pub use linelog::{begin as begin_ops_capture, take as take_ops_capture};
