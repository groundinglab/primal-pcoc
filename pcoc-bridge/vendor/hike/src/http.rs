//! Produce and verify HTTP.
//!
//! Index and clock reads are one GET. An unsuccessful read is an error here;
//! verify records that error as `not read`. A SPEC-invalid index body is Class S
//! and returns. The 30s read limit ends one hang. It is not a hike deadline.
//!
//! Bari on the produce plant is a batching convenience. The tip clock is the
//! caller's own gossip. On the live profile the plant is a Bitcoin transaction,
//! so the anchor is the block. Bari serves the waypoint path from H+2 through
//! H+4. This producer does not anchor by talking to Bitcoin and skipping Bari.
//! `POST /waypoint` is fire-and-forget after Hashcash.
//! The test profile's `GET /path` is a short best-effort read and then a skip.
//! The live profile does not use that read. It asks once, four headers after
//! the post, and it reads the 404 body. Neither call holds a connection open
//! waiting for Bari to look healthy.

use std::io::Read;
use std::time::{Duration, Instant};

use crate::clock::{parse_index_beacon, parse_index_document, IndexBeacon};
use crate::hashcash;
use crate::hex::encode_hex32;
use crate::produce::ProduceFailure;

const READ_TIMEOUT: Duration = Duration::from_secs(30);

/// Old pause between path 404s. The produce plant no longer loops on it.
///
/// Kept so a caller that still names the constant compiles. Bari's documented
/// tip-poll interval (`BARI_POLL_INTERVAL_MS`) is not a produce wait.
pub const PATH_404_PAUSE: Duration = Duration::from_secs(5);

/// Retries of one `POST /waypoint` when the request did not connect.
///
/// Not a hike check. Unset, empty, or unparseable `PCOC_PRODUCE_TRANSIENT_WAITS`
/// uses this value. `0` fails on the first connect failure and does not wait.
/// A timeout after connect, a reset after connect, and HTTP 5xx are not retries.
/// This does not apply to index reads or to gossip.
const DEFAULT_PRODUCE_TRANSIENT_WAITS: u32 = 2;

/// Old tip-mismatch budget. The resend is one Observe and one fresh nonce.
///
/// Kept so the test dial still names the field. It is not a retry count.
#[allow(dead_code)]
const DEFAULT_WAYPOINT_HTTP_400_WAITS: u32 = 2;

/// Quick path GETs before the fetch gives up.
///
/// Not a hike check. Unset, empty, or unparseable `PCOC_PATH_ABSENT_ATTEMPTS`
/// uses this value. Two checks, not 1440. `0` skips the quick phase.
/// HTTP 404, HTTP 5xx, and connect failures share this cap.
const DEFAULT_PATH_QUICK_CHECKS: u32 = 2;

/// Wall cap on the quick path phase, in milliseconds.
///
/// Not a hike check. Unset `PCOC_PATH_TRY_BUDGET_MS` uses this. The lead-in
/// jitter and the gossip plant window are not part of this budget.
const DEFAULT_PATH_TRY_BUDGET_MS: u64 = 30_000;

/// One later path GET after the quick phase misses.
///
/// Not a hike check. Unset `PCOC_PATH_STAGGER_MS` uses this. `0` does not
/// schedule that GET. This millisecond dial is not the live inclusion wait.
/// Live produce does not read it.
const DEFAULT_PATH_STAGGER_MS: u64 = 60_000;

/// Last offset in [`crate::consts::ANCHOR_WINDOW_OFFSETS`]. Not a second list.
///
/// Not [`DEFAULT_PATH_STAGGER_MS`]. The live profile does not sleep this.
pub const HEADERS_AFTER_POST: u64 = crate::consts::ANCHOR_WINDOW_OFFSETS[2];

/// Inclusive bounds for the wait after an ask header, before that header's GET.
///
/// Each ask draws its own duration, uniformly from 15 seconds through 45.
/// The draw is not a clock offset, so two producers that see the same header
/// do not wait the same time. It is not the one-minute stagger.
const LIVE_PATH_ASK_WAIT_MIN: Duration = Duration::from_secs(15);
const LIVE_PATH_ASK_WAIT_MAX: Duration = Duration::from_secs(45);

/// Inclusive lead-in before the first path GET, after the plant window.
const DEFAULT_PATH_JITTER_MIN_MS: u64 = 5_000;
const DEFAULT_PATH_JITTER_MAX_MS: u64 = 30_000;

/// Gossip tips after the waypoint POST before the first path GET.
///
/// Not a hike check. Unset `PCOC_PLANT_WINDOW_TIPS` uses this. `0` does not
/// wait. The wait is the producer's own tip clock. It does not dial Bari.
const DEFAULT_PLANT_WINDOW_TIPS: u32 = 3;

/// Produce Bari connect and read cap, in milliseconds.
///
/// Not a hike check. Unset `PCOC_PRODUCE_HTTP_TIMEOUT_MS` uses this. One
/// attempt ends. It does not hold the socket for the index read limit.
const DEFAULT_PRODUCE_HTTP_TIMEOUT_MS: u64 = 1_000;

/// Lab Class T base. Not Lock. 1s is the lab choice in the 1–2s band.
const CLASS_T_BASE_MS: u64 = 1_000;
/// Lab Class T cap. Not Lock.
const CLASS_T_CAP_MS: u64 = 60_000;
/// Added after `min(2^n × base, cap)`. Inclusive range is `0..=CLASS_T_JITTER_MS`.
const CLASS_T_JITTER_MS: u64 = 250;

// `connectivity_wait` is an ops wait line only. It is not an admit or skip counter
// and must never be an input to #41 policy.
#[cfg(test)]
thread_local! {
    static PAUSE_HOOK: std::cell::RefCell<Option<Box<dyn FnMut(Duration)>>> =
        std::cell::RefCell::new(None);
    static CONNECTIVITY_WAIT_LINES: std::cell::RefCell<Vec<String>> =
        std::cell::RefCell::new(Vec::new());
    static RETRY_LINES: std::cell::RefCell<Vec<String>> = std::cell::RefCell::new(Vec::new());
    static PAUSE_LOG: std::cell::RefCell<Vec<Duration>> = std::cell::RefCell::new(Vec::new());
    static LIMIT_OVERRIDE: std::cell::Cell<Option<TestDial>> = std::cell::Cell::new(None);
}

#[cfg(test)]
#[derive(Clone, Copy)]
struct TestDial {
    transient_waits: u32,
    #[allow(dead_code)]
    http_400_waits: u32,
    path_attempts: u32,
    path_budget_ms: u64,
    stagger_ms: u64,
    jitter_min_ms: u64,
    jitter_max_ms: u64,
}

pub fn http_agent() -> ureq::Agent {
    // `index.groundinglab.io` answers 403 when the request carries no User-Agent header.
    ureq::AgentBuilder::new()
        .timeout(READ_TIMEOUT)
        .user_agent("pcoc-hike")
        .redirects(0)
        .build()
}

/// Short agent for `POST /waypoint` and `GET /path`.
///
/// Connect, read, and the whole call share one cap ([`DEFAULT_PRODUCE_HTTP_TIMEOUT_MS`]
/// unless `PCOC_PRODUCE_HTTP_TIMEOUT_MS` is set). Idle pooled sockets are off.
/// Index reads stay on [`http_agent`].
pub fn produce_http_agent() -> ureq::Agent {
    let timeout = produce_http_timeout();
    ureq::AgentBuilder::new()
        .timeout(timeout)
        .timeout_connect(timeout)
        .timeout_read(timeout)
        .redirects(0)
        .max_idle_connections(0)
        .max_idle_connections_per_host(0)
        .build()
}

/// `GET {base_url}/index`. Section 9 clock document. Returns `(natural, height)`.
///
/// Wire `header.hash` is Bitcoin-id display. The returned beacon is natural order.
pub fn read_clock(agent: &ureq::Agent, base_url: &str) -> Result<([u8; 32], u64), ProduceFailure> {
    let url = format!("{base_url}/index");
    let body = clock_document(agent, &url, "read_clock")?;
    parse_index_document(&body).map_err(|_| ProduceFailure::new("clock read failed: index body"))
}

/// `GET {index_url}/index/{height}`. Returns the response body.
///
/// One GET. Success is HTTP 200. Any other outcome is an error. Verify records
/// that error as `not read`. A 200 whose body is SPEC-invalid still returns so
/// the caller can name that body.
pub fn index_document(agent: &ureq::Agent, index_url: &str, height: u64) -> Result<String, String> {
    let url = format!("{index_url}/index/{height}");
    clock_document(agent, &url, "index_document").map_err(|err| err.message)
}

/// `GET {index_url}/index/{height}`. Returns the natural beacon and `header.height`.
pub fn beacon_at_height(
    agent: &ureq::Agent,
    index_url: &str,
    height: u64,
) -> Result<([u8; 32], u64), String> {
    let beacon = read_index_beacon(agent, index_url, height)?;
    Ok((beacon.natural, beacon.height))
}

/// `GET {index_url}/index/{height}`.
///
/// `header.time` is Bitcoin `nTime` in unix seconds when that field is an
/// integer. `header.timestamp_unix_ms` is Bari's wall-clock observation in
/// unix milliseconds when that field is an integer. A missing `header.time`
/// is not filled from the observation.
pub fn read_index_beacon(
    agent: &ureq::Agent,
    index_url: &str,
    height: u64,
) -> Result<IndexBeacon, String> {
    let body = index_document(agent, index_url, height)?;
    parse_index_beacon(&body).map_err(|_| "index body".to_owned())
}

/// Result of one best-effort path GET.
///
/// [`PathFetch::Skipped`] is an absent or unreachable path. The drive continues.
/// The stored stage path is empty. Those empty bytes are the section 13.3 produce fact.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PathFetch {
    Body(Vec<u8>),
    Skipped,
}

/// HTTP-tip helper. No profile selects this path.
///
/// Gossip produce uses [`produce_open_stage`]. It does not wait for newer tips
/// before the leg. [`plant_stage`] still runs a caller-supplied plant window;
/// `satoshi-hike-produce` does not call it.
pub fn open_stage<S: FnMut()>(
    agent: &ureq::Agent,
    tip_url: &str,
    bari_url: &str,
    waypoint: &[u8; 32],
    bits: u32,
    on_absent: S,
) -> Result<PathFetch, ProduceFailure> {
    let (tip_hash, _) = read_clock(agent, tip_url)?;
    open_stage_at_tip(agent, bari_url, waypoint, &tip_hash, bits, on_absent)
}

/// Lab stderr for the gossip produce path. Not a #41 input.
///
/// `tip_hash` is the natural tip `challenge_from_tip` uses. HTTP produce does
/// not call this. The waypoint POST that follows must use this same `tip_hash`.
pub fn emit_tip_in_hashcash(tip_hash: &[u8; 32], height: u64) {
    let _ = hashcash::challenge_from_tip(tip_hash);
    let line = tip_in_hashcash_line(tip_hash, height);
    eprintln!("{line}");
    crate::note_ops_line(&line);
}

fn tip_in_hashcash_line(tip_hash: &[u8; 32], height: u64) -> String {
    format!(
        "tip_in_hashcash call_site=post_waypoint tip_natural={} height={height}",
        encode_hex32(tip_hash)
    )
}

/// Lab stage open: one Observe for Hashcash, then a path GET at the post.
///
/// The produce profiles do not call this. They post through [`LivePathBook`]
/// and ask at the section 13.1 offsets from the stage beacon height. This
/// helper does not pause the drive to wait for a further header. That pause
/// is the failure mode where headers arrive while the leg is stopped, and
/// one leg then closes the stage.
pub fn produce_open_stage<C>(
    agent: &ureq::Agent,
    bari_url: &str,
    waypoint: &[u8; 32],
    bits: u32,
    mut observe: C,
) -> Result<PathFetch, ProduceFailure>
where
    C: FnMut() -> Result<([u8; 32], u64), ProduceFailure>,
{
    let (tip, height) = observe()?;
    emit_tip_in_hashcash(&tip, height);
    open_stage_at_tip(agent, bari_url, waypoint, &tip, bits, || {})
}

/// Waypoint POST and path GET using a tip the caller already observed.
///
/// `tip_hash` is the natural beacon. Hashcash must not see Bitcoin display bytes.
/// This function does not read a tip and does not wait for Bari. It does not
/// insert the gossip plant window. [`plant_stage`] does.
pub fn open_stage_at_tip<S: FnMut()>(
    agent: &ureq::Agent,
    bari_url: &str,
    waypoint: &[u8; 32],
    tip_hash: &[u8; 32],
    bits: u32,
    on_absent: S,
) -> Result<PathFetch, ProduceFailure> {
    post_waypoint(agent, bari_url, waypoint, tip_hash, bits)?;
    fetch_path(agent, bari_url, waypoint, on_absent)
}

/// POST, then the caller's plant-window wait, then a short path GET.
///
/// `satoshi-hike-produce` must not call this. The wait spends the tip advances
/// that bound the leg and forces `leg_count = 1`.
///
/// `wait_plant_window` is the caller's own tip clock. This function does not
/// dial Bari inside that wait. After the wait it sleeps a random 5–30s lead-in
/// (gossip seeing a tip before Bari serves) and then [`fetch_path`].
pub fn plant_stage<W>(
    agent: &ureq::Agent,
    bari_url: &str,
    waypoint: &[u8; 32],
    tip_hash: &[u8; 32],
    bits: u32,
    wait_plant_window: W,
) -> Result<PathFetch, ProduceFailure>
where
    W: FnOnce() -> Result<(), ProduceFailure>,
{
    post_waypoint(agent, bari_url, waypoint, tip_hash, bits)?;
    wait_plant_window()?;
    let sleep_ms = path_jitter_ms();
    emit_ops_line(format!(
        "path_jitter call_site=fetch_path sleep_ms={sleep_ms}"
    ));
    class_t_pause(Duration::from_millis(sleep_ms));
    fetch_path(agent, bari_url, waypoint, || {})
}

/// Waypoint POST.
///
/// The POST is sent once. Status 204 proceeds. A body that says the waypoint
/// is already held proceeds. HTTP 5xx, 408, 429, a timeout after a status
/// line, and HTTP 400 with any body other than Bari's exact tips-differ text
/// fail on that response.
///
/// A request that did not connect — no HTTP status line — may send the same
/// nonce again, up to [`DEFAULT_PRODUCE_TRANSIENT_WAITS`]. This function has
/// no Observe, so the exact tips-differ body fails after the one POST.
pub fn post_waypoint(
    agent: &ureq::Agent,
    bari_url: &str,
    waypoint: &[u8; 32],
    tip_hash: &[u8; 32],
    bits: u32,
) -> Result<(), ProduceFailure> {
    post_waypoint_status(agent, bari_url, waypoint, tip_hash, bits, |_| {
        Err(ProduceFailure::new(
            "waypoint post failed: observe did not succeed",
        ))
    })
    .map(|_| ())
}

/// [`post_waypoint`], plus the HTTP status of the admit.
///
/// `observe` runs once, and only after HTTP 400 whose body is exactly
/// `Hashcash proof invalid for live tip challenge`. On success the next POST
/// uses a nonce searched from 0 against the newest tip in that result. A
/// second exact 400 does not Observe again. An Observe error sends no further
/// POST.
fn post_waypoint_status<F>(
    agent: &ureq::Agent,
    bari_url: &str,
    waypoint: &[u8; 32],
    tip_hash: &[u8; 32],
    bits: u32,
    mut observe: F,
) -> Result<u16, ProduceFailure>
where
    F: FnMut(Option<[u8; 32]>) -> Result<Vec<([u8; 32], u64)>, ProduceFailure>,
{
    let mut tip = *tip_hash;
    let mut nonce = solve_nonce(&tip, waypoint, bits)?;
    let url = format!("{bari_url}/waypoint");
    let mut transient_waits = 0u32;
    let transient_limit = produce_transient_waits();
    let mut tip_observed = false;
    loop {
        let response = agent
            .post(&url)
            .set("Connection", "close")
            .set("Content-Type", "application/octet-stream")
            .set(hashcash::HASHCASH_NONCE_HEADER, &nonce.to_string())
            .send_bytes(waypoint);
        let decision = match response {
            Ok(resp) => waypoint_decision(resp.status(), resp),
            Err(ureq::Error::Status(code, resp)) => waypoint_decision(code, resp),
            Err(err) => {
                // No HTTP status line. The SPEC allows the same nonce again.
                if request_did_not_connect(&err) {
                    capped_transport(
                        "post_waypoint",
                        "waypoint post failed",
                        &mut transient_waits,
                        transient_limit,
                        err,
                    )?;
                    continue;
                }
                return Err(ProduceFailure::new(format!("waypoint post failed: {err}")));
            }
        };
        match decision {
            WaypointDecision::Admit(status) => return Ok(status),
            WaypointDecision::Fail(err) => return Err(err),
            WaypointDecision::TipMismatch => {
                if tip_observed {
                    return Err(tip_mismatch_failure());
                }
                tip_observed = true;
                let ticks = match observe(Some(tip)) {
                    Ok(ticks) => ticks,
                    Err(_) => return Err(tip_mismatch_failure()),
                };
                let Some((new_tip, new_height)) = ticks.last().copied() else {
                    return Err(tip_mismatch_failure());
                };
                emit_tip_in_hashcash(&new_tip, new_height);
                tip = new_tip;
                nonce = solve_nonce(&tip, waypoint, bits)?;
            }
        }
    }
}

fn solve_nonce(tip: &[u8; 32], waypoint: &[u8; 32], bits: u32) -> Result<u64, ProduceFailure> {
    let challenge = hashcash::challenge_from_tip(tip);
    hashcash::solve(&challenge, waypoint, bits)
        .map_err(|_| ProduceFailure::new("waypoint post failed: hashcash search wrapped"))
}

fn tip_mismatch_failure() -> ProduceFailure {
    ProduceFailure::new(
        "waypoint post failed: HTTP 400 body=Hashcash proof invalid for live tip challenge",
    )
    .with_http_status(400)
}

/// Best-effort `GET {bari_url}/path/{leaf}`.
///
/// At most [`DEFAULT_PATH_QUICK_CHECKS`] GETs, inside
/// [`DEFAULT_PATH_TRY_BUDGET_MS`]. Each call uses the agent's own short timeout.
/// There is no Class T sleep between them. HTTP 404, HTTP 5xx, timeout, and
/// connect failures are the same miss. After that budget, one optional GET
/// waits [`DEFAULT_PATH_STAGGER_MS`] and then this returns [`PathFetch::Skipped`].
/// A 200 returns the body. Any other status fails the call.
///
/// `on_absent` runs after each HTTP 404 in the quick phase. It does not run
/// for the stagger, and it is not a license to wait forever.
///
/// Plant-null is a successful index document whose plant is null. This GET is
/// not that document.
pub fn fetch_path<S: FnMut()>(
    agent: &ureq::Agent,
    bari_url: &str,
    waypoint: &[u8; 32],
    mut on_absent: S,
) -> Result<PathFetch, ProduceFailure> {
    let url = format!("{bari_url}/path/{}", encode_hex32(waypoint));
    let checks = path_quick_checks();
    let budget = Duration::from_millis(path_try_budget_ms());
    let started = Instant::now();
    let mut soft = 0u32;
    let mut last_class = "absent";
    while soft < checks {
        if soft > 0 && started.elapsed() >= budget {
            break;
        }
        match path_once(agent, &url) {
            PathOnce::Body(bytes) => return Ok(PathFetch::Body(bytes)),
            PathOnce::Fatal(err) => return Err(err),
            PathOnce::Soft(class) => {
                soft = soft.saturating_add(1);
                last_class = class;
                emit_ops_line(format!(
                    "path_check call_site=fetch_path error_class={class} attempt={soft} limit={checks}"
                ));
                if class == "http_404" {
                    on_absent();
                }
            }
        }
    }
    let stagger_ms = path_stagger_ms();
    if stagger_ms > 0 {
        emit_ops_line(format!(
            "path_stagger call_site=fetch_path sleep_ms={stagger_ms}"
        ));
        class_t_pause(Duration::from_millis(stagger_ms));
        match path_once(agent, &url) {
            PathOnce::Body(bytes) => return Ok(PathFetch::Body(bytes)),
            PathOnce::Fatal(err) => return Err(err),
            PathOnce::Soft(class) => {
                soft = soft.saturating_add(1);
                last_class = class;
                emit_ops_line(format!(
                    "path_check call_site=fetch_path error_class={class} attempt={soft} limit={checks} phase=stagger"
                ));
            }
        }
    }
    emit_ops_line(format!(
        "path_check call_site=fetch_path exhausted=yes reason={last_class} attempts={soft}"
    ));
    Ok(PathFetch::Skipped)
}

/// First height at which a plant posted at `post_height` can be included.
///
/// Section 13.1. The first [`crate::consts::ANCHOR_WINDOW_OFFSETS`] entry.
/// `None` when that offset does not fit in `u64`, the same answer as
/// [`crate::consts::anchor_window`].
pub fn live_on_chain_height(post_height: u64) -> Option<u64> {
    crate::consts::anchor_window(post_height).map(|window| window[0])
}

/// The last live path ask. Four headers after the post.
///
/// This height is not [`DEFAULT_PATH_STAGGER_MS`] and it is not stage open.
/// The first ask is [`live_on_chain_height`]. `None` when the window does not fit.
pub fn live_path_due_height(post_height: u64) -> Option<u64> {
    crate::consts::anchor_window(post_height).map(|window| window[2])
}

fn height_decimal(height: Option<u64>) -> String {
    match height {
        Some(height) => height.to_string(),
        None => "none".to_owned(),
    }
}

/// The inclusion window has reached its last header.
///
/// A plant posted at H can be included at H+2, H+3, and H+4. The path is
/// served through H+4. At H+4 that window has closed for a plant that is not
/// in a block. It is gone at H+5. A window that does not fit in `u64` is closed.
fn window_closed(post_height: u64, observed_height: u64) -> bool {
    match live_path_due_height(post_height) {
        Some(due) => observed_height >= due,
        None => true,
    }
}

/// Live posts, then path asks at H+2, H+3, and H+4.
///
/// `post` does not GET. The leg is not paused for a header and not for
/// [`DEFAULT_PATH_STAGGER_MS`]. `on_tip` asks at H+2 after a random wait of
/// 15 through 45 seconds from that header. A path is stored and later headers
/// are not asked. A miss asks again at H+3, then once more at H+4, each after
/// its own draw. After H+4 the miss is an empty path and the stage does not
/// stay waiting. Nothing is asked before H+2 or after H+4, and nothing is
/// asked before that wait. A later tip does not stand in for an ask header
/// that was jumped.
///
/// An unknown body, an unreadable 404, a failed request, and a jumped ask
/// header do not stop produce. The miss is an empty path, and the final step
/// writes `path_skip`. A skipped plant is not posted again.
pub struct LivePathBook {
    agent: ureq::Agent,
    bari_url: String,
    watches: Vec<LiveWatch>,
    last_tip: Option<[u8; 32]>,
    last_height: Option<u64>,
    /// `last_interrupt_height`. Asks above it are not made.
    cut_off: Option<u64>,
    /// Live event log. Absent on the test profile and in tests that do not open one.
    /// Not the hike object.
    log: Option<crate::producelog::ProduceEventLog>,
}

struct LiveWatch {
    waypoint: [u8; 32],
    post_height: u64,
    state: WatchState,
    asks: u32,
}

#[derive(Debug)]
enum WatchState {
    Pending,
    /// The path was not available at `at`. The next serve header may ask again.
    Waiting {
        at: u64,
    },
    Done(PathFetch),
}

impl LivePathBook {
    pub fn new(agent: ureq::Agent, bari_url: impl Into<String>) -> Self {
        Self {
            agent,
            bari_url: bari_url.into(),
            watches: Vec::new(),
            last_tip: None,
            last_height: None,
            cut_off: None,
            log: None,
        }
    }

    /// Create `{output}.events` for this live run. A failure here does not
    /// change the hike; the caller still produces.
    pub fn open_event_log(&mut self, output: &str) -> std::io::Result<()> {
        self.log = Some(crate::producelog::ProduceEventLog::create(
            &crate::producelog::event_log_path(output),
        )?);
        Ok(())
    }

    pub fn last_tip(&self) -> Option<[u8; 32]> {
        self.last_tip
    }

    /// Hashcash post only. The path is not fetched here.
    ///
    /// One POST for this stage. This caller has no Observe, so an exact
    /// tips-differ body is a miss. A connect failure with no status line is
    /// retried inside [`post_waypoint`]. A skipped plant is not posted again,
    /// and this does not fee-bump, replace, or repair it.
    pub fn post(
        &mut self,
        waypoint: &[u8; 32],
        tip: &[u8; 32],
        hashcash_height: u64,
        beacon_height: u64,
        bits: u32,
    ) -> Result<(), ProduceFailure> {
        self.post_with(waypoint, tip, hashcash_height, beacon_height, bits, |_| {
            Err(ProduceFailure::new(
                "waypoint post failed: observe did not succeed",
            ))
        })
    }

    /// [`post`], and one Observe after Bari's exact tips-differ body.
    ///
    /// The closure must not borrow this book. Returned ticks are applied with
    /// [`Self::on_tip`] after the POST, so a header is not skipped.
    pub fn post_observing<F>(
        &mut self,
        waypoint: &[u8; 32],
        tip: &[u8; 32],
        hashcash_height: u64,
        beacon_height: u64,
        bits: u32,
        observe: F,
    ) -> Result<(), ProduceFailure>
    where
        F: FnMut(Option<[u8; 32]>) -> Result<Vec<([u8; 32], u64)>, ProduceFailure>,
    {
        self.post_with(waypoint, tip, hashcash_height, beacon_height, bits, observe)
    }

    fn post_with<F>(
        &mut self,
        waypoint: &[u8; 32],
        tip: &[u8; 32],
        hashcash_height: u64,
        beacon_height: u64,
        bits: u32,
        mut observe: F,
    ) -> Result<(), ProduceFailure>
    where
        F: FnMut(Option<[u8; 32]>) -> Result<Vec<([u8; 32], u64)>, ProduceFailure>,
    {
        // Stage 0 is not posted. The first watch is W_1.
        let stage = self.watches.len() as u64 + 1;
        self.record_waypoint(stage, waypoint);
        let mut observed = Vec::new();
        let result =
            post_waypoint_status(&self.agent, &self.bari_url, waypoint, tip, bits, |after| {
                let ticks = observe(after)?;
                observed.clone_from(&ticks);
                Ok(ticks)
            });
        match result {
            Ok(status) => {
                self.record_posted(stage, waypoint, true, true, Some(status));
                self.note_tip(*tip, hashcash_height);
                // `W_17` is posted and is not asked, including when a jump would
                // put H+2, H+3, or H+4 at or below the cut-off.
                let not_asked = stage == 17;
                self.watches.push(LiveWatch {
                    waypoint: *waypoint,
                    post_height: crate::consts::anchor_window_origin(beacon_height),
                    state: if not_asked {
                        WatchState::Done(PathFetch::Skipped)
                    } else {
                        WatchState::Pending
                    },
                    asks: 0,
                });
                self.apply_observed(&observed, !not_asked, hashcash_height)
            }
            Err(err) => {
                // A missed POST does not stop the hike. No ask is due for this stage.
                self.record_posted(
                    stage,
                    waypoint,
                    err.http_status.is_some(),
                    false,
                    err.http_status,
                );
                self.watches.push(LiveWatch {
                    waypoint: *waypoint,
                    post_height: crate::consts::anchor_window_origin(beacon_height),
                    state: WatchState::Done(PathFetch::Skipped),
                    asks: 0,
                });
                let line = format!(
                    "waypoint_post call_site=post_waypoint status={} continued=yes",
                    err.http_status.unwrap_or(0)
                );
                eprintln!("{line}");
                crate::note_ops_line(&line);
                self.apply_observed(&observed, false, hashcash_height)
            }
        }
    }

    /// Apply tips from the one Observe. A tip already noted still asks when
    /// `ask` is set, because [`Self::on_tip`] returns before the ask.
    fn apply_observed(
        &mut self,
        observed: &[([u8; 32], u64)],
        ask: bool,
        posted_height: u64,
    ) -> Result<(), ProduceFailure> {
        if observed.is_empty() {
            return if ask {
                self.ask_at(posted_height)
            } else {
                Ok(())
            };
        }
        for &(hash, height) in observed {
            if self.last_tip == Some(hash) && self.last_height == Some(height) {
                if ask {
                    self.ask_at(height)?;
                }
            } else {
                self.on_tip(hash, height)?;
            }
        }
        Ok(())
    }

    /// Note one header. Ask only a plant whose due header is this header.
    ///
    /// A height that jumps over the next ask header is a missed window. The
    /// newest tip is not a substitute.
    pub fn on_tip(&mut self, tip: [u8; 32], height: u64) -> Result<(), ProduceFailure> {
        if self.last_tip == Some(tip) && self.last_height == Some(height) {
            return Ok(());
        }
        if self.last_height.is_some_and(|prev| height < prev) {
            return Ok(());
        }
        if let Some(prev) = self.last_height {
            if height > prev.saturating_add(1) {
                self.reject_skipped_due(prev, height);
            }
        }
        if self.cut_off.is_some_and(|cut_off| height > cut_off) {
            self.note_tip(tip, height);
            self.close_above_cut_off();
            return Ok(());
        }
        self.note_tip(tip, height);
        // The header is accepted. The path GET, when one is due, is later work.
        self.record_block(tip, height);
        self.ask_at(height)
    }

    /// Wait out unsettled plants, then write each stage path.
    ///
    /// `wait_after` blocks until public ticks strictly after `after`. It does
    /// not run a leg. Ticks are applied in order so a due tick is not skipped
    /// when several arrive together. The wait is headers, not seconds.
    pub fn finish<W>(
        &mut self,
        stages: &mut [crate::object::Stage],
        cut_off: u64,
        mut wait_after: W,
    ) -> Result<(), ProduceFailure>
    where
        W: FnMut(Option<[u8; 32]>) -> Result<Vec<([u8; 32], u64)>, ProduceFailure>,
    {
        self.cut_off = Some(cut_off);
        self.close_above_cut_off();
        if let Some(height) = self.last_height {
            if height <= cut_off {
                self.ask_at(height)?;
            }
        }
        while self.watches.iter().any(|watch| watch.open()) {
            if self.last_height.is_some_and(|height| height >= cut_off) {
                self.skip_open_watches();
                break;
            }
            let ticks = wait_after(self.last_tip)?;
            let mut advanced = false;
            for (tip, height) in ticks {
                if height > cut_off {
                    self.skip_open_watches();
                    break;
                }
                if self.last_height.is_some_and(|prev| height < prev) {
                    continue;
                }
                if self.last_tip == Some(tip) {
                    continue;
                }
                advanced = true;
                self.on_tip(tip, height)?;
            }
            self.close_above_cut_off();
            if !advanced {
                self.skip_open_watches();
                break;
            }
        }
        for stage in stages.iter_mut() {
            // Stage 0 is not posted. A stage with no watch keeps the path it has.
            let Some(watch) = self
                .watches
                .iter()
                .find(|watch| watch.waypoint == stage.waypoint)
            else {
                continue;
            };
            match &watch.state {
                WatchState::Done(PathFetch::Body(bytes)) => stage.path = bytes.clone(),
                WatchState::Done(PathFetch::Skipped)
                | WatchState::Pending
                | WatchState::Waiting { .. } => stage.path.clear(),
            }
        }
        // A fetched body is not stored for `W_17`. The path stays empty.
        if stages.len() >= 18 {
            stages[17].path.clear();
            self.emit_final_path_lines(stages);
        }
        Ok(())
    }

    fn close_above_cut_off(&mut self) {
        let Some(cut_off) = self.cut_off else {
            return;
        };
        for watch in &mut self.watches {
            if !watch.open() {
                continue;
            }
            if watch.next_ask_height().is_some_and(|due| due > cut_off) {
                watch.state = WatchState::Done(PathFetch::Skipped);
            }
        }
    }

    fn skip_open_watches(&mut self) {
        for watch in &mut self.watches {
            if watch.open() {
                watch.state = WatchState::Done(PathFetch::Skipped);
            }
        }
    }

    fn emit_final_path_lines(&self, stages: &[crate::object::Stage]) {
        for g in 1..=16 {
            if stages.get(g).is_some_and(|stage| stage.path.is_empty()) {
                emit_ops_line(format!("path_skip {g}"));
            }
        }
        emit_ops_line("not fetched by design 17".to_owned());
    }

    fn reject_skipped_due(&mut self, prev: u64, height: u64) {
        for watch in &mut self.watches {
            let Some(due) = watch.next_ask_height() else {
                continue;
            };
            if prev < due && due < height {
                watch.state = WatchState::Done(PathFetch::Skipped);
            }
        }
    }

    fn ask_at(&mut self, height: u64) -> Result<(), ProduceFailure> {
        let due: Vec<usize> = self
            .watches
            .iter()
            .enumerate()
            .filter(|(_, watch)| watch.ask_at(height))
            .map(|(index, _)| index)
            .collect();
        for index in due {
            let waypoint = self.watches[index].waypoint;
            let post_height = self.watches[index].post_height;
            let asks = self.watches[index].asks.saturating_add(1);
            self.watches[index].asks = asks;
            let ask_wait = live_path_ask_wait();
            emit_ops_line(format!(
                "path_ask_wait call_site=fetch_path sleep_ms={} tip_height={height} waypoint={}",
                ask_wait.as_millis(),
                encode_hex32(&waypoint)
            ));
            class_t_pause(ask_wait);
            let completed = ask_path(
                &self.agent,
                &self.bari_url,
                &waypoint,
                post_height,
                height,
                self.cut_off,
            );
            // After the GET returns. Not the request line above the call.
            self.record_fetch(&waypoint, &completed);
            match completed.disposition {
                Disposition::Done(fetch) => {
                    self.watches[index].state = WatchState::Done(fetch);
                }
                Disposition::Wait if Some(height) == live_path_due_height(post_height) => {
                    // Rule A3.4 writes path_skip only in the step after the cut-off.
                    self.watches[index].state = WatchState::Done(PathFetch::Skipped);
                }
                Disposition::Wait => {
                    self.watches[index].state = WatchState::Waiting { at: height };
                }
                Disposition::Failed => {
                    // A miss does not stop the hike. Ask the next due header,
                    // or store empty bytes when this was the last one.
                    if self.watch_has_later_ask(index, height) {
                        self.watches[index].state = WatchState::Waiting { at: height };
                    } else {
                        self.watches[index].state = WatchState::Done(PathFetch::Skipped);
                    }
                }
            }
        }
        Ok(())
    }

    fn watch_has_later_ask(&self, index: usize, at: u64) -> bool {
        let watch = &self.watches[index];
        let Some(heights) = crate::consts::anchor_window(watch.post_height) else {
            return false;
        };
        heights
            .into_iter()
            .any(|height| height > at && self.cut_off.is_none_or(|cut_off| height <= cut_off))
    }

    fn note_tip(&mut self, tip: [u8; 32], height: u64) {
        if self.last_height.is_some_and(|prev| height < prev) {
            return;
        }
        self.last_tip = Some(tip);
        self.last_height = Some(height);
    }

    fn record_block(&mut self, tip: [u8; 32], height: u64) {
        if let Some(log) = self.log.as_mut() {
            log.block(height, &tip);
        }
    }

    fn record_waypoint(&mut self, stage: u64, waypoint: &[u8; 32]) {
        if let Some(log) = self.log.as_mut() {
            log.waypoint(stage, waypoint);
        }
    }

    fn record_posted(
        &mut self,
        stage: u64,
        waypoint: &[u8; 32],
        posted: bool,
        succeeded: bool,
        status: Option<u16>,
    ) {
        if let Some(log) = self.log.as_mut() {
            log.posted(stage, waypoint, posted, succeeded, status);
        }
    }

    fn record_fetch(&mut self, waypoint: &[u8; 32], ask: &CompletedAsk) {
        let Some(log) = self.log.as_mut() else {
            return;
        };
        if let Some(checked) = ask.checked {
            log.checked(waypoint, checked);
        }
        let worked = matches!(ask.disposition, Disposition::Done(PathFetch::Body(_)));
        log.fetch(
            waypoint,
            worked,
            ask.status,
            ask.block_hash.as_ref(),
            ask.block_height,
            ask.miss,
        );
    }
}

impl LiveWatch {
    fn open(&self) -> bool {
        matches!(self.state, WatchState::Pending | WatchState::Waiting { .. })
    }

    fn next_ask_height(&self) -> Option<u64> {
        let heights = crate::consts::anchor_window(self.post_height)?;
        match self.state {
            WatchState::Pending => Some(heights[0]),
            WatchState::Waiting { at } => heights.into_iter().find(|height| *height > at),
            WatchState::Done(_) => None,
        }
    }

    fn ask_at(&self, height: u64) -> bool {
        self.next_ask_height() == Some(height)
    }
}

#[derive(Debug)]
enum Ask {
    Done(PathFetch),
    Wait,
}

enum Disposition {
    Done(PathFetch),
    Wait,
    Failed,
}

/// Facts the producer already has once a path GET has returned.
struct CompletedAsk {
    disposition: Disposition,
    status: Option<u16>,
    /// `Some` only after a 200 body was checked. A miss is not that check.
    checked: Option<bool>,
    block_hash: Option<[u8; 32]>,
    block_height: Option<u32>,
    miss: Option<&'static str>,
}

impl CompletedAsk {
    fn from_ask(
        ask: Ask,
        status: Option<u16>,
        checked: Option<bool>,
        block_hash: Option<[u8; 32]>,
        block_height: Option<u32>,
        miss: Option<&'static str>,
    ) -> Self {
        let disposition = match ask {
            Ask::Done(fetch) => Disposition::Done(fetch),
            Ask::Wait => Disposition::Wait,
        };
        Self {
            disposition,
            status,
            checked,
            block_hash,
            block_height,
            miss,
        }
    }

    fn failed(
        err: ProduceFailure,
        status: Option<u16>,
        checked: Option<bool>,
        block_hash: Option<[u8; 32]>,
        block_height: Option<u32>,
        miss: Option<&'static str>,
    ) -> Self {
        Self {
            disposition: {
                let _ = err;
                Disposition::Failed
            },
            status,
            checked,
            block_hash,
            block_height,
            miss,
        }
    }
}

fn known_miss(text: &str) -> Option<&'static str> {
    match text {
        "still in the pool" => Some("still in the pool"),
        "sealed but not in a block yet" => Some("sealed but not in a block yet"),
        "not included" => Some("not included"),
        "already archived after it was served" => Some("already archived after it was served"),
        "unknown" => Some("unknown"),
        _ => None,
    }
}

/// One GET. The body is read before the answer is stored or skipped.
///
/// A 200 is stored only after it checks as a path for this waypoint, in a
/// block at H+2, H+3, or H+4. An early 404 does not store a path. Empty bytes
/// are returned only for `not included` after the window has closed. Any other
/// 404, any other status, and a failed read fail this call. The caller does
/// not send it again.
///
/// The stderr `path_fetch` line names the request before the GET. The event
/// log is not that line. [`LivePathBook::ask_at`] appends the fetch record
/// after this function returns.
fn ask_path(
    agent: &ureq::Agent,
    bari_url: &str,
    waypoint: &[u8; 32],
    post_height: u64,
    observed_height: u64,
    cut_off: Option<u64>,
) -> CompletedAsk {
    let due = height_decimal(live_path_due_height(post_height));
    let url = format!("{bari_url}/path/{}", encode_hex32(waypoint));
    emit_ops_line(format!(
        "path_fetch call_site=fetch_path due_height={due} tip_height={observed_height} waypoint={}",
        encode_hex32(waypoint)
    ));
    let response = match agent.get(&url).set("Connection", "close").call() {
        Ok(resp) => resp,
        Err(ureq::Error::Status(code, resp)) => {
            return status_ask(code, resp, waypoint, post_height, observed_height, cut_off);
        }
        Err(err) => {
            let detail = match classify_ureq(&err) {
                Fault::ClassT(class) => class.to_owned(),
                Fault::Fatal(msg) => msg,
            };
            return CompletedAsk::failed(
                ProduceFailure::new(format!("path fetch failed: {detail}")),
                None,
                None,
                None,
                None,
                None,
            );
        }
    };
    status_ask(
        response.status(),
        response,
        waypoint,
        post_height,
        observed_height,
        cut_off,
    )
}

fn status_ask(
    status: u16,
    response: ureq::Response,
    waypoint: &[u8; 32],
    post_height: u64,
    observed_height: u64,
    cut_off: Option<u64>,
) -> CompletedAsk {
    match status {
        200 => match take_body(response) {
            Ok(bytes) => {
                let locators = crate::pathbody::path_locators(&bytes).ok();
                let block_hash = locators.as_ref().map(|item| item.block_hash);
                let block_height = locators.as_ref().map(|item| item.block_height);
                match checked_path(bytes, waypoint, post_height, cut_off) {
                    Ok(ask) => CompletedAsk::from_ask(
                        ask,
                        Some(status),
                        Some(true),
                        block_hash,
                        block_height,
                        None,
                    ),
                    Err(err) => CompletedAsk::failed(
                        err.with_http_status(status),
                        Some(status),
                        Some(false),
                        block_hash,
                        block_height,
                        None,
                    ),
                }
            }
            Err(err) => CompletedAsk::failed(
                ProduceFailure::new(format!("path fetch failed: {err}")).with_http_status(status),
                Some(status),
                None,
                None,
                None,
                None,
            ),
        },
        404 => match take_body(response) {
            Ok(bytes) => {
                let text = std::str::from_utf8(&bytes).map(str::trim).unwrap_or("");
                let miss = known_miss(text);
                match miss_ask(&bytes, waypoint, post_height, observed_height) {
                    Ok(ask) => CompletedAsk::from_ask(ask, Some(status), None, None, None, miss),
                    Err(err) => CompletedAsk::failed(
                        err.with_http_status(status),
                        Some(status),
                        None,
                        None,
                        None,
                        miss,
                    ),
                }
            }
            Err(_) => CompletedAsk::failed(
                ProduceFailure::new("path fetch failed: unreadable path miss")
                    .with_http_status(status),
                Some(status),
                None,
                None,
                None,
                None,
            ),
        },
        other => {
            drop(response);
            CompletedAsk::failed(
                ProduceFailure::new(format!("path fetch failed: HTTP {other}"))
                    .with_http_status(other),
                Some(other),
                None,
                None,
                None,
                None,
            )
        }
    }
}

fn checked_path(
    bytes: Vec<u8>,
    waypoint: &[u8; 32],
    post_height: u64,
    cut_off: Option<u64>,
) -> Result<Ask, ProduceFailure> {
    if crate::pathbody::validate_path(&bytes, waypoint).is_err() {
        return Err(ProduceFailure::new(
            "path fetch failed: response is not a path for this waypoint",
        ));
    }
    let locators = crate::pathbody::path_locators(&bytes).map_err(|_| {
        ProduceFailure::new("path fetch failed: response is not a path for this waypoint")
    })?;
    let height = u64::from(locators.block_height);
    let in_window = crate::consts::height_in_anchor_window(post_height, height);
    let above_cut_off = cut_off.is_some_and(|cut_off| height > cut_off);
    if !in_window || above_cut_off {
        return Ok(Ask::Wait);
    }
    Ok(Ask::Done(PathFetch::Body(bytes)))
}

fn miss_ask(
    body: &[u8],
    _waypoint: &[u8; 32],
    post_height: u64,
    observed_height: u64,
) -> Result<Ask, ProduceFailure> {
    let text = std::str::from_utf8(body).map(str::trim).unwrap_or("");
    match text {
        "still in the pool" | "sealed but not in a block yet" => Ok(Ask::Wait),
        "not included" if window_closed(post_height, observed_height) => {
            Ok(Ask::Done(PathFetch::Skipped))
        }
        "not included" => Ok(Ask::Wait),
        "already archived after it was served" | "unknown" => Ok(Ask::Wait),
        _ => Ok(Ask::Wait),
    }
}

/// Gossip tips to wait after a waypoint POST before the path lead-in.
///
/// Unset, empty, or unparseable `PCOC_PLANT_WINDOW_TIPS` is
/// [`DEFAULT_PLANT_WINDOW_TIPS`].
pub(crate) fn plant_window_tips() -> u32 {
    dial_u32("PCOC_PLANT_WINDOW_TIPS", DEFAULT_PLANT_WINDOW_TIPS)
}

fn clock_document(
    agent: &ureq::Agent,
    url: &str,
    _call_site: &'static str,
) -> Result<String, ProduceFailure> {
    match agent.get(url).call() {
        Ok(response) if response.status() == 200 => {
            let bytes = take_body(response)
                .map_err(|err| ProduceFailure::new(format!("clock read failed: {err}")))?;
            String::from_utf8(bytes)
                .map_err(|_| ProduceFailure::new("clock read failed: index body"))
        }
        Ok(response) => Err(ProduceFailure::new(format!(
            "clock read failed: HTTP {}",
            response.status()
        ))),
        Err(ureq::Error::Status(code, _)) => Err(ProduceFailure::new(format!(
            "clock read failed: HTTP {code}"
        ))),
        Err(err) => Err(ProduceFailure::new(format!("clock read failed: {err}"))),
    }
}

fn take_body(response: ureq::Response) -> Result<Vec<u8>, std::io::Error> {
    let mut buf = Vec::new();
    response.into_reader().read_to_end(&mut buf)?;
    Ok(buf)
}

enum PathOnce {
    Body(Vec<u8>),
    Soft(&'static str),
    Fatal(ProduceFailure),
}

fn path_once(agent: &ureq::Agent, url: &str) -> PathOnce {
    match agent.get(url).set("Connection", "close").call() {
        Ok(resp) if resp.status() == 200 => match take_body(resp) {
            Ok(bytes) => PathOnce::Body(bytes),
            Err(err) => match io_error_class(&err) {
                Some(class) => PathOnce::Soft(class),
                None => PathOnce::Fatal(ProduceFailure::new(format!("path fetch failed: {err}"))),
            },
        },
        Ok(resp) if resp.status() == 404 => {
            drop(resp);
            PathOnce::Soft("http_404")
        }
        Ok(resp) if is_http_5xx(resp.status()) || resp.status() == 408 || resp.status() == 429 => {
            let class = soft_status_class(resp.status());
            drop(resp);
            PathOnce::Soft(class)
        }
        Ok(resp) => {
            let status = resp.status();
            let body = body_snippet(resp);
            PathOnce::Fatal(http_fail("path fetch failed", status, &body))
        }
        Err(ureq::Error::Status(code, resp)) => {
            if code == 404 || is_http_5xx(code) || code == 408 || code == 429 {
                let class = soft_status_class(code);
                drop(resp);
                PathOnce::Soft(class)
            } else {
                let body = body_snippet(resp);
                PathOnce::Fatal(http_fail("path fetch failed", code, &body))
            }
        }
        Err(err) => match classify_ureq(&err) {
            Fault::ClassT(class) => PathOnce::Soft(class),
            Fault::Fatal(msg) => {
                PathOnce::Fatal(ProduceFailure::new(format!("path fetch failed: {msg}")))
            }
        },
    }
}

fn soft_status_class(status: u16) -> &'static str {
    if status == 404 {
        "http_404"
    } else if status == 408 {
        "http_408"
    } else if status == 429 {
        "http_429"
    } else {
        "http_5xx"
    }
}

/// Bari's exact tips-differ body, from `services/bari` `submit_waypoint`.
///
/// The match is these ASCII bytes and no others. A trailing newline, a longer
/// page that contains the sentence, or a whitespace change is not a retry.
const TIP_MISMATCH_BODY: &[u8] = b"Hashcash proof invalid for live tip challenge";

enum WaypointDecision {
    Admit(u16),
    /// HTTP 400 and [`TIP_MISMATCH_BODY`] exactly.
    TipMismatch,
    Fail(ProduceFailure),
}

fn body_already_held(body: &str) -> bool {
    body.to_ascii_lowercase().contains("already held")
}

fn response_bytes(response: ureq::Response) -> Vec<u8> {
    let mut buf = Vec::new();
    let mut reader = response.into_reader().take(4096);
    let _ = reader.read_to_end(&mut buf);
    buf
}

fn body_snippet(response: ureq::Response) -> String {
    snippet_of(&response_bytes(response))
}

fn snippet_of(buf: &[u8]) -> String {
    let text = String::from_utf8_lossy(buf);
    let mut collapsed = String::new();
    let mut pending_space = false;
    for ch in text.chars() {
        if ch.is_whitespace() || ch.is_control() {
            if !collapsed.is_empty() {
                pending_space = true;
            }
            continue;
        }
        if pending_space {
            if collapsed.len() + 1 > 160 {
                break;
            }
            collapsed.push(' ');
            pending_space = false;
        }
        if collapsed.len() + ch.len_utf8() > 160 {
            break;
        }
        collapsed.push(ch);
    }
    collapsed
}

fn http_fail(prefix: &str, status: u16, body: &str) -> ProduceFailure {
    let message = if body.is_empty() {
        format!("{prefix}: HTTP {status}")
    } else {
        format!("{prefix}: HTTP {status} body={body}")
    };
    ProduceFailure::new(message).with_http_status(status)
}

fn waypoint_decision(status: u16, resp: ureq::Response) -> WaypointDecision {
    if status == 204 {
        return WaypointDecision::Admit(status);
    }
    if is_http_5xx(status) {
        drop(resp);
        return WaypointDecision::Fail(
            ProduceFailure::new(format!("waypoint post failed: HTTP {status}"))
                .with_http_status(status),
        );
    }
    let raw = response_bytes(resp);
    let body = snippet_of(&raw);
    if body_already_held(&body) {
        emit_ops_line(format!(
            "waypoint_post call_site=post_waypoint result=already_held status={status}"
        ));
        return WaypointDecision::Admit(status);
    }
    if status == 400 && raw == TIP_MISMATCH_BODY {
        return WaypointDecision::TipMismatch;
    }
    WaypointDecision::Fail(http_fail("waypoint post failed", status, &body))
}

fn emit_ops_line(line: String) {
    eprintln!("{line}");
    crate::note_ops_line(&line);
    #[cfg(test)]
    RETRY_LINES.with(|lines| lines.borrow_mut().push(line));
}

fn produce_transient_waits() -> u32 {
    #[cfg(test)]
    if let Some(dial) = LIMIT_OVERRIDE.with(|cell| cell.get()) {
        return dial.transient_waits;
    }
    dial_u32(
        "PCOC_PRODUCE_TRANSIENT_WAITS",
        DEFAULT_PRODUCE_TRANSIENT_WAITS,
    )
}

fn path_quick_checks() -> u32 {
    #[cfg(test)]
    if let Some(dial) = LIMIT_OVERRIDE.with(|cell| cell.get()) {
        return dial.path_attempts;
    }
    dial_u32("PCOC_PATH_ABSENT_ATTEMPTS", DEFAULT_PATH_QUICK_CHECKS)
}

fn path_try_budget_ms() -> u64 {
    #[cfg(test)]
    if let Some(dial) = LIMIT_OVERRIDE.with(|cell| cell.get()) {
        return dial.path_budget_ms;
    }
    dial_u64("PCOC_PATH_TRY_BUDGET_MS", DEFAULT_PATH_TRY_BUDGET_MS)
}

fn path_stagger_ms() -> u64 {
    #[cfg(test)]
    if let Some(dial) = LIMIT_OVERRIDE.with(|cell| cell.get()) {
        return dial.stagger_ms;
    }
    dial_u64("PCOC_PATH_STAGGER_MS", DEFAULT_PATH_STAGGER_MS)
}

fn path_jitter_ms() -> u64 {
    let (min_ms, max_ms) = path_jitter_bounds();
    if max_ms <= min_ms {
        return min_ms;
    }
    let span = max_ms - min_ms;
    let Some(span) = span.checked_add(1) else {
        return min_ms;
    };
    let mut bytes = [0u8; 8];
    if getrandom::getrandom(&mut bytes).is_err() {
        return min_ms;
    }
    min_ms + (u64::from_le_bytes(bytes) % span)
}

fn path_jitter_bounds() -> (u64, u64) {
    #[cfg(test)]
    if let Some(dial) = LIMIT_OVERRIDE.with(|cell| cell.get()) {
        return (dial.jitter_min_ms, dial.jitter_max_ms);
    }
    (
        dial_u64("PCOC_PATH_JITTER_MIN_MS", DEFAULT_PATH_JITTER_MIN_MS),
        dial_u64("PCOC_PATH_JITTER_MAX_MS", DEFAULT_PATH_JITTER_MAX_MS),
    )
}

fn produce_http_timeout() -> Duration {
    let ms = dial_u64(
        "PCOC_PRODUCE_HTTP_TIMEOUT_MS",
        DEFAULT_PRODUCE_HTTP_TIMEOUT_MS,
    );
    Duration::from_millis(ms.max(1))
}

fn dial_u32(name: &str, default: u32) -> u32 {
    match std::env::var(name) {
        Ok(raw) => parse_u32_dial(Some(raw.trim()), default),
        Err(_) => default,
    }
}

fn dial_u64(name: &str, default: u64) -> u64 {
    match std::env::var(name) {
        Ok(raw) => {
            let raw = raw.trim();
            if raw.is_empty() {
                default
            } else {
                raw.parse::<u64>().unwrap_or(default)
            }
        }
        Err(_) => default,
    }
}

fn parse_u32_dial(raw: Option<&str>, default: u32) -> u32 {
    let Some(raw) = raw.map(str::trim).filter(|value| !value.is_empty()) else {
        return default;
    };
    raw.parse::<u32>().unwrap_or(default)
}

fn capped_class_t(
    call_site: &'static str,
    prefix: &str,
    error_class: &'static str,
    detail: &str,
    waits: &mut u32,
    limit: u32,
) -> Result<(), ProduceFailure> {
    if *waits >= limit {
        return Err(ProduceFailure::new(format!(
            "{prefix}: {detail} waits={waits} limit={limit}"
        )));
    }
    class_t_wait(call_site, error_class, waits);
    Ok(())
}

fn capped_transport(
    call_site: &'static str,
    prefix: &str,
    waits: &mut u32,
    limit: u32,
    err: ureq::Error,
) -> Result<(), ProduceFailure> {
    match classify_ureq(&err) {
        Fault::ClassT(class) => capped_class_t(call_site, prefix, class, class, waits, limit),
        Fault::Fatal(msg) => Err(ProduceFailure::new(format!("{prefix}: {msg}"))),
    }
}

/// Lab Class T wait. Not Lock.
///
/// `sleep = min(2^n × base, cap) + jitter`. `attempt` in the log is 1-based, and
/// `n` is `attempt - 1`, so the first wait is the base. Base is 1s. Cap is 60s.
/// Jitter is `0..=250` ms. There is no max-attempt abort.
///
/// DNS NXDOMAIN, SERVFAIL, and resolution timeout are all error class `dns`.
/// ureq reports each as `ErrorKind::Dns`. A TCP connect or read timeout is
/// `timeout`. Missing HTTP headers because the peer reset or closed is `tcp_reset`,
/// not Class S. Class S is a body that arrived and is SPEC-invalid, handled by
/// the caller after this function returns the bytes.
fn class_t_wait(call_site: &'static str, error_class: &'static str, attempt: &mut u32) {
    let _ = connectivity_wait(call_site, error_class, attempt, true);
}

/// Log one Class T wait and optionally sleep. `pause` false still records `sleep_ms`.
/// The returned line is the stderr line. Gossip uses it from its own thread.
pub(crate) fn connectivity_wait(
    call_site: &'static str,
    error_class: &'static str,
    attempt: &mut u32,
    pause: bool,
) -> String {
    *attempt = attempt.saturating_add(1);
    let sleep_ms = class_t_sleep_ms(*attempt);
    let line = format!(
        "connectivity_wait call_site={call_site} error_class={error_class} attempt={attempt} sleep_ms={sleep_ms}"
    );
    eprintln!("{line}");
    #[cfg(test)]
    CONNECTIVITY_WAIT_LINES.with(|lines| lines.borrow_mut().push(line.clone()));
    if pause {
        class_t_pause(Duration::from_millis(sleep_ms));
    }
    line
}

fn class_t_sleep_ms(attempt: u32) -> u64 {
    class_t_backoff_ms(attempt).saturating_add(class_t_jitter_ms())
}

fn class_t_backoff_ms(attempt: u32) -> u64 {
    let n = attempt.saturating_sub(1).min(16);
    let scaled = CLASS_T_BASE_MS.saturating_mul(1u64 << n);
    scaled.min(CLASS_T_CAP_MS)
}

fn class_t_jitter_ms() -> u64 {
    let mut bytes = [0u8; 2];
    if getrandom::getrandom(&mut bytes).is_err() {
        return 0;
    }
    u16::from_le_bytes(bytes) as u64 % (CLASS_T_JITTER_MS + 1)
}

/// One draw for one ask. Inclusive of 15 seconds and 45 seconds.
///
/// The bytes come from the process RNG. They are not the header time, so
/// producers that observe the same header do not share a wait.
fn live_path_ask_wait() -> Duration {
    let min_ms = u64::try_from(LIVE_PATH_ASK_WAIT_MIN.as_millis()).expect("15s fits u64 millis");
    let max_ms = u64::try_from(LIVE_PATH_ASK_WAIT_MAX.as_millis()).expect("45s fits u64 millis");
    let span = max_ms - min_ms + 1;
    let mut bytes = [0u8; 8];
    let offset = if getrandom::getrandom(&mut bytes).is_ok() {
        u64::from_le_bytes(bytes) % span
    } else {
        0
    };
    Duration::from_millis(min_ms + offset)
}

fn class_t_pause(duration: Duration) {
    #[cfg(test)]
    {
        PAUSE_LOG.with(|log| log.borrow_mut().push(duration));
        let hooked = PAUSE_HOOK.with(|hook| {
            let mut slot = hook.borrow_mut();
            if let Some(callback) = slot.as_mut() {
                callback(duration);
                true
            } else {
                false
            }
        });
        if hooked {
            return;
        }
    }
    // Integration tests compile this crate as a normal library, so `cfg(test)`
    // is off there. Unset keeps the real sleep. `0` returns at once. The
    // drawn duration stays on the caller's `path_ask_wait` line. This does
    // not change `live_path_ask_wait`.
    if std::env::var("PCOC_TEST_PAUSE_MS")
        .ok()
        .as_deref()
        .map(str::trim)
        == Some("0")
    {
        return;
    }
    std::thread::sleep(duration);
}

enum Fault {
    ClassT(&'static str),
    Fatal(String),
}

/// DNS failure and a TCP refusal happen before a connection exists.
/// A timeout or a reset after the peer accepted the connection does not.
fn request_did_not_connect(err: &ureq::Error) -> bool {
    if err.kind() == ureq::ErrorKind::Dns || err.kind() == ureq::ErrorKind::ConnectionFailed {
        return true;
    }
    matches!(first_io_class(err), Some("tcp_refuse"))
}

fn classify_ureq(err: &ureq::Error) -> Fault {
    if let ureq::Error::Status(code, _) = err {
        if is_http_5xx(*code) {
            return Fault::ClassT("http_5xx");
        }
        return Fault::Fatal(format!("HTTP {code}"));
    }
    if err.kind() == ureq::ErrorKind::Dns {
        return Fault::ClassT("dns");
    }
    if let Some(class) = first_io_class(err) {
        return Fault::ClassT(class);
    }
    if err.kind() == ureq::ErrorKind::ConnectionFailed {
        return Fault::ClassT("tcp_refuse");
    }
    Fault::Fatal(err.to_string())
}

fn is_http_5xx(status: u16) -> bool {
    (500..600).contains(&status)
}

fn first_io_class(err: &ureq::Error) -> Option<&'static str> {
    let mut source = std::error::Error::source(err);
    while let Some(inner) = source {
        if let Some(ioe) = inner.downcast_ref::<std::io::Error>() {
            if let Some(class) = io_kind_class(ioe.kind()) {
                return Some(class);
            }
        }
        source = std::error::Error::source(inner);
    }
    None
}

fn io_error_class(err: &std::io::Error) -> Option<&'static str> {
    if let Some(class) = io_kind_class(err.kind()) {
        return Some(class);
    }
    let mut source = std::error::Error::source(err);
    while let Some(inner) = source {
        if let Some(ioe) = inner.downcast_ref::<std::io::Error>() {
            if let Some(class) = io_kind_class(ioe.kind()) {
                return Some(class);
            }
        }
        source = std::error::Error::source(inner);
    }
    None
}

pub(crate) fn io_kind_class(kind: std::io::ErrorKind) -> Option<&'static str> {
    match kind {
        std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock => Some("timeout"),
        std::io::ErrorKind::ConnectionRefused
        | std::io::ErrorKind::NetworkUnreachable
        | std::io::ErrorKind::HostUnreachable => Some("tcp_refuse"),
        std::io::ErrorKind::ConnectionReset
        | std::io::ErrorKind::ConnectionAborted
        | std::io::ErrorKind::BrokenPipe
        | std::io::ErrorKind::UnexpectedEof => Some("tcp_reset"),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hashcash;
    use crate::hex::encode_hex32;

    #[test]
    fn tip_in_hashcash_line_uses_natural_order() {
        let mut tip = [0u8; 32];
        tip[0] = 0xab;
        tip[31] = 0xcd;
        let line = tip_in_hashcash_line(&tip, 7);
        assert_eq!(
            line,
            format!(
                "tip_in_hashcash call_site=post_waypoint tip_natural={} height=7",
                encode_hex32(&tip)
            )
        );
        let mut display = tip;
        display.reverse();
        assert_ne!(encode_hex32(&tip), encode_hex32(&display));
        assert!(!line.contains(&encode_hex32(&display)));
    }
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::{Arc, Mutex};
    use std::thread::{self, JoinHandle};

    struct Hit {
        method: String,
        path: String,
        content_type: String,
        nonce: String,
        body: Vec<u8>,
    }

    struct Script {
        hits: Vec<Hit>,
        path_gets: usize,
    }

    fn with_server<F>(test: F)
    where
        F: FnOnce(&str, Arc<Mutex<Script>>),
    {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let base = format!("http://{addr}");
        let script = Arc::new(Mutex::new(Script {
            hits: Vec::new(),
            path_gets: 0,
        }));
        let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let script_thread = Arc::clone(&script);
        let stop_thread = Arc::clone(&stop);
        let join: JoinHandle<()> = thread::spawn(move || loop {
            if stop_thread.load(std::sync::atomic::Ordering::SeqCst) {
                break;
            }
            let Ok((mut stream, _)) = listener.accept() else {
                break;
            };
            if stop_thread.load(std::sync::atomic::Ordering::SeqCst) {
                break;
            }
            let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
            let Some(req) = read_http(&mut stream) else {
                continue;
            };
            let response = respond(&script_thread, &req);
            let _ = write_http(&mut stream, &response);
        });
        test(&base, Arc::clone(&script));
        stop.store(true, std::sync::atomic::Ordering::SeqCst);
        let _ = std::net::TcpStream::connect(addr);
        join.join().unwrap();
    }

    struct RawReq {
        method: String,
        path: String,
        headers: Vec<(String, String)>,
        body: Vec<u8>,
    }

    fn header<'a>(req: &'a RawReq, name: &str) -> &'a str {
        req.headers
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
            .unwrap_or("")
    }

    fn respond(script: &Mutex<Script>, req: &RawReq) -> (u16, &'static str, Vec<u8>) {
        let mut state = script.lock().unwrap();
        state.hits.push(Hit {
            method: req.method.clone(),
            path: req.path.clone(),
            content_type: header(req, "content-type").to_owned(),
            nonce: header(req, "x-bari-hashcash-nonce").to_owned(),
            body: req.body.clone(),
        });
        if req.method == "GET" && req.path == "/index" {
            return (200, "OK", tip_json());
        }
        if req.method == "GET" && req.path == "/redirect-index" {
            return (200, "OK", tip_json());
        }
        if req.method == "POST" && req.path == "/waypoint" {
            if header(req, "content-type") != "application/octet-stream" || req.body.len() != 32 {
                return (400, "Bad Request", Vec::new());
            }
            let Ok(waypoint) = <[u8; 32]>::try_from(req.body.as_slice()) else {
                return (400, "Bad Request", Vec::new());
            };
            let Ok(nonce) = header(req, "x-bari-hashcash-nonce").trim().parse::<u64>() else {
                return (400, "Bad Request", Vec::new());
            };
            let tip = natural_tip();
            let challenge = hashcash::challenge_from_tip(&tip);
            if !hashcash::verify(&challenge, &waypoint, nonce, 8) {
                return (400, "Bad Request", Vec::new());
            }
            return (204, "No Content", Vec::new());
        }
        if req.method == "GET" && req.path.starts_with("/path/") {
            state.path_gets += 1;
            let n = state.path_gets;
            drop(state);
            if n < 3 {
                return (404, "Not Found", Vec::new());
            }
            return (200, "OK", b"path-body".to_vec());
        }
        if req.method == "GET" && req.path == "/index/42" {
            return (200, "OK", tip_json());
        }
        (404, "Not Found", Vec::new())
    }

    fn natural_tip() -> [u8; 32] {
        std::array::from_fn(|i| (i as u8).wrapping_add(1))
    }

    fn tip_json() -> Vec<u8> {
        let mut display = natural_tip();
        display.reverse();
        let hash = encode_hex32(&display);
        format!(
            r#"{{"version":2,"network":"synthetic","header":{{"hash":"{hash}","height":42}},"plant":null}}"#
        )
        .into_bytes()
    }

    fn read_http(stream: &mut std::net::TcpStream) -> Option<RawReq> {
        let mut buf = Vec::new();
        let mut tmp = [0u8; 2048];
        let header_end = loop {
            let n = stream.read(&mut tmp).ok()?;
            if n == 0 {
                return None;
            }
            buf.extend_from_slice(&tmp[..n]);
            if let Some(pos) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
                break pos;
            }
            if buf.len() > 65_536 {
                return None;
            }
        };
        let head = std::str::from_utf8(&buf[..header_end]).ok()?;
        let mut lines = head.split("\r\n");
        let request_line = lines.next()?;
        let mut parts = request_line.split_whitespace();
        let method = parts.next()?.to_owned();
        let path = parts.next()?.to_owned();
        let mut headers = Vec::new();
        let mut content_length = 0usize;
        for line in lines {
            let Some((name, value)) = line.split_once(':') else {
                continue;
            };
            let value = value.trim();
            if name.eq_ignore_ascii_case("content-length") {
                content_length = value.parse().unwrap_or(0);
            }
            headers.push((name.to_owned(), value.to_owned()));
        }
        let mut body = buf[header_end + 4..].to_vec();
        while body.len() < content_length {
            let n = stream.read(&mut tmp).ok()?;
            if n == 0 {
                break;
            }
            body.extend_from_slice(&tmp[..n]);
        }
        body.truncate(content_length);
        Some(RawReq {
            method,
            path,
            headers,
            body,
        })
    }

    fn write_http(
        stream: &mut std::net::TcpStream,
        response: &(u16, &str, Vec<u8>),
    ) -> std::io::Result<()> {
        let (status, reason, body) = response;
        let head = format!(
            "HTTP/1.1 {status} {reason}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            body.len()
        );
        stream.write_all(head.as_bytes())?;
        stream.write_all(body)?;
        Ok(())
    }

    #[test]
    fn open_stage_posts_hashcash_and_waits_through_404() {
        with_server(|base, script| {
            let _wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let agent = http_agent();
            let waypoint = [0x42; 32];
            let mut absent = 0;
            let body =
                expect_body(open_stage(&agent, base, base, &waypoint, 8, || absent += 1).unwrap());
            assert_eq!(body, b"path-body");
            assert_eq!(absent, 2);
            let state = script.lock().unwrap();
            assert_eq!(state.path_gets, 3);
            let posts: Vec<_> = state
                .hits
                .iter()
                .filter(|hit| hit.method == "POST")
                .collect();
            assert_eq!(posts.len(), 1);
            assert_eq!(posts[0].path, "/waypoint");
            assert_eq!(posts[0].content_type, "application/octet-stream");
            assert_eq!(posts[0].body, waypoint);
            let nonce: u64 = posts[0].nonce.parse().unwrap();
            let challenge = hashcash::challenge_from_tip(&natural_tip());
            assert!(hashcash::verify(&challenge, &waypoint, nonce, 8));
            let gets: Vec<_> = state
                .hits
                .iter()
                .filter(|hit| hit.method == "GET")
                .map(|hit| hit.path.as_str())
                .collect();
            assert_eq!(gets[0], "/index");
            assert!(gets.iter().all(|path| !path.starts_with("/index/")));
            let leaf = format!("/path/{}", encode_hex32(&waypoint));
            assert_eq!(gets.iter().filter(|path| **path == leaf).count(), 3);
        });
    }

    #[test]
    fn rejected_post_fails_without_a_path_get() {
        with_server(|base, script| {
            let agent = http_agent();
            let err = post_waypoint(&agent, base, &[0x7; 32], &[9; 32], 8).unwrap_err();
            assert!(err.message.contains("HTTP 400"));
            let state = script.lock().unwrap();
            assert_eq!(state.path_gets, 0);
        });
    }

    #[test]
    fn beacon_at_height_reads_that_decimal_height() {
        with_server(|base, script| {
            let agent = http_agent();
            let (natural, height) = beacon_at_height(&agent, base, 42).unwrap();
            assert_eq!(natural, natural_tip());
            assert_eq!(height, 42);
            assert!(beacon_at_height(&agent, base, 7).is_err());
            let state = script.lock().unwrap();
            let paths: Vec<_> = state.hits.iter().map(|hit| hit.path.as_str()).collect();
            assert!(paths.contains(&"/index/42"));
            assert!(paths.contains(&"/index/7"));
        });
    }

    #[test]
    fn clock_redirect_is_not_a_successful_read() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let base = format!("http://{addr}");
        let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let stop_thread = Arc::clone(&stop);
        let location = format!("{base}/redirect-index");
        let join = thread::spawn(move || loop {
            if stop_thread.load(std::sync::atomic::Ordering::SeqCst) {
                break;
            }
            let Ok((mut stream, _)) = listener.accept() else {
                break;
            };
            let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
            let Some(req) = read_http(&mut stream) else {
                continue;
            };
            if req.path == "/index" {
                let head = format!(
                        "HTTP/1.1 302 Found\r\nLocation: {location}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    );
                let _ = stream.write_all(head.as_bytes());
            }
        });
        let agent = http_agent();
        assert!(read_clock(&agent, &base).is_err());
        stop.store(true, std::sync::atomic::Ordering::SeqCst);
        let _ = std::net::TcpStream::connect(addr);
        join.join().unwrap();
    }

    struct FastWait;

    impl FastWait {
        fn install() -> Self {
            PAUSE_HOOK.with(|hook| {
                *hook.borrow_mut() = Some(Box::new(|_| {}));
            });
            CONNECTIVITY_WAIT_LINES.with(|lines| lines.borrow_mut().clear());
            RETRY_LINES.with(|lines| lines.borrow_mut().clear());
            PAUSE_LOG.with(|log| log.borrow_mut().clear());
            Self
        }

        fn lines(&self) -> Vec<String> {
            CONNECTIVITY_WAIT_LINES.with(|lines| lines.borrow().clone())
        }

        fn retry_lines(&self) -> Vec<String> {
            RETRY_LINES.with(|lines| lines.borrow().clone())
        }

        fn pauses(&self) -> Vec<Duration> {
            PAUSE_LOG.with(|log| log.borrow().clone())
        }
    }

    impl Drop for FastWait {
        fn drop(&mut self) {
            PAUSE_HOOK.with(|hook| *hook.borrow_mut() = None);
        }
    }

    struct DialGuard;

    impl DialGuard {
        fn install(
            transient_waits: u32,
            http_400_waits: u32,
            path_attempts: u32,
            path_budget_ms: u64,
            stagger_ms: u64,
        ) -> Self {
            Self::install_jitter(
                transient_waits,
                http_400_waits,
                path_attempts,
                path_budget_ms,
                stagger_ms,
                0,
                0,
            )
        }

        fn install_jitter(
            transient_waits: u32,
            http_400_waits: u32,
            path_attempts: u32,
            path_budget_ms: u64,
            stagger_ms: u64,
            jitter_min_ms: u64,
            jitter_max_ms: u64,
        ) -> Self {
            LIMIT_OVERRIDE.with(|cell| {
                cell.set(Some(TestDial {
                    transient_waits,
                    http_400_waits,
                    path_attempts,
                    path_budget_ms,
                    stagger_ms,
                    jitter_min_ms,
                    jitter_max_ms,
                }))
            });
            Self
        }
    }

    impl Drop for DialGuard {
        fn drop(&mut self) {
            LIMIT_OVERRIDE.with(|cell| cell.set(None));
        }
    }

    fn expect_body(fetch: PathFetch) -> Vec<u8> {
        match fetch {
            PathFetch::Body(bytes) => bytes,
            PathFetch::Skipped => panic!("path was skipped"),
        }
    }

    fn consume_one(counter: &std::sync::atomic::AtomicUsize) -> bool {
        use std::sync::atomic::Ordering::SeqCst;
        let mut n = counter.load(SeqCst);
        loop {
            let Some(next) = n.checked_sub(1) else {
                return false;
            };
            match counter.compare_exchange(n, next, SeqCst, SeqCst) {
                Ok(_) => return true,
                Err(seen) => n = seen,
            }
        }
    }

    enum Action {
        Respond(u16, Vec<u8>),
        Reset,
        Hang,
    }

    fn with_actions<F>(mut action: F, test: impl FnOnce(&str))
    where
        F: FnMut(&RawReq) -> Action + Send + 'static,
    {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let base = format!("http://{addr}");
        let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let stop_thread = Arc::clone(&stop);
        let join = thread::spawn(move || {
            while !stop_thread.load(std::sync::atomic::Ordering::SeqCst) {
                let Ok((mut stream, _)) = listener.accept() else {
                    break;
                };
                if stop_thread.load(std::sync::atomic::Ordering::SeqCst) {
                    break;
                }
                let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
                let Some(req) = read_http(&mut stream) else {
                    continue;
                };
                match action(&req) {
                    Action::Respond(status, body) => {
                        let reason = match status {
                            200 => "OK",
                            204 => "No Content",
                            400 => "Bad Request",
                            404 => "Not Found",
                            503 => "Service Unavailable",
                            _ => "Status",
                        };
                        let _ = write_http(&mut stream, &(status, reason, body));
                    }
                    Action::Reset => drop(stream),
                    Action::Hang => {
                        let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
                        let mut tmp = [0u8; 64];
                        loop {
                            match stream.read(&mut tmp) {
                                Ok(0) | Err(_) => break,
                                Ok(_) => {}
                            }
                        }
                    }
                }
            }
        });
        test(&base);
        stop.store(true, std::sync::atomic::Ordering::SeqCst);
        let _ = std::net::TcpStream::connect(addr);
        join.join().unwrap();
    }

    #[test]
    fn class_t_backoff_doubles_until_the_lab_cap() {
        assert_eq!(class_t_backoff_ms(1), 1_000);
        assert_eq!(class_t_backoff_ms(2), 2_000);
        assert_eq!(class_t_backoff_ms(3), 4_000);
        assert_eq!(class_t_backoff_ms(4), 8_000);
        assert_eq!(class_t_backoff_ms(5), 16_000);
        assert_eq!(class_t_backoff_ms(6), 32_000);
        assert_eq!(class_t_backoff_ms(7), 60_000);
        assert_eq!(class_t_backoff_ms(40), 60_000);
    }

    #[test]
    fn tip_5xx_is_one_get_and_an_error() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |req| {
                assert_eq!(req.path, "/index");
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Respond(503, Vec::new())
            },
            |base| {
                let wait = FastWait::install();
                let agent = http_agent();
                let err = read_clock(&agent, base).unwrap_err();
                assert!(err.message.contains("HTTP 503"), "{}", err.message);
                assert!(wait.lines().is_empty(), "{:?}", wait.lines());
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
            },
        );
    }

    #[test]
    fn spec_invalid_tip_body_fails_without_connectivity_wait() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |req| {
                assert_eq!(req.path, "/index");
                let n = hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                if n == 0 {
                    return Action::Respond(
                        200,
                        br#"{"header":{"hash":"aa","height":1}}"#.to_vec(),
                    );
                }
                Action::Respond(400, Vec::new())
            },
            |base| {
                let wait = FastWait::install();
                let agent = http_agent();
                let err = read_clock(&agent, base).unwrap_err();
                assert!(err.message.contains("index body"), "{}", err.message);
                assert!(wait.lines().is_empty());
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
            },
        );
    }

    #[test]
    fn tip_404_is_not_class_t() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |_req| {
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Respond(404, Vec::new())
            },
            |base| {
                let wait = FastWait::install();
                let agent = http_agent();
                let err = read_clock(&agent, base).unwrap_err();
                assert!(err.message.contains("HTTP 404"), "{}", err.message);
                assert!(wait.lines().is_empty());
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
            },
        );
    }

    #[test]
    fn index_5xx_is_one_get_and_an_error() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |req| {
                assert_eq!(req.path, "/index/42");
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Respond(503, Vec::new())
            },
            |base| {
                let wait = FastWait::install();
                let agent = http_agent();
                let err = index_document(&agent, base, 42).unwrap_err();
                assert!(err.contains("HTTP 503"), "{err}");
                assert!(wait.lines().is_empty(), "{:?}", wait.lines());
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
            },
        );
    }

    #[test]
    fn plant_null_document_is_a_successful_read() {
        let body = br#"{"network":"main","plant":null}"#.to_vec();
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |_req| {
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Respond(200, body.clone())
            },
            |base| {
                let wait = FastWait::install();
                let agent = http_agent();
                let got = index_document(&agent, base, 7).unwrap();
                assert!(got.contains("\"plant\":null"));
                assert!(wait.lines().is_empty());
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
            },
        );
    }

    #[test]
    fn waypoint_5xx_is_one_post() {
        let posts = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let posts_srv = Arc::clone(&posts);
        with_actions(
            move |_req| {
                posts_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Respond(503, Vec::new())
            },
            |base| {
                let wait = FastWait::install();
                let agent = http_agent();
                let err = post_waypoint(&agent, base, &[0x42; 32], &natural_tip(), 8).unwrap_err();
                assert!(err.message.contains("HTTP 503"), "{}", err.message);
                assert!(!err.message.contains("waits="), "{}", err.message);
                assert!(wait.lines().is_empty(), "{:?}", wait.lines());
                assert!(wait.retry_lines().is_empty(), "{:?}", wait.retry_lines());
                assert_eq!(posts.load(std::sync::atomic::Ordering::SeqCst), 1);
            },
        );
    }

    #[test]
    fn path_404_then_200_is_not_class_t() {
        let gets = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let gets_srv = Arc::clone(&gets);
        with_actions(
            move |req| {
                assert!(req.path.starts_with("/path/"));
                let n = gets_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                if n == 0 {
                    Action::Respond(404, Vec::new())
                } else {
                    Action::Respond(200, b"path-body".to_vec())
                }
            },
            |base| {
                let wait = FastWait::install();
                let _caps = DialGuard::install(2, 2, 2, 30_000, 0);
                let agent = http_agent();
                let mut absent = 0;
                let body =
                    expect_body(fetch_path(&agent, base, &[0x42; 32], || absent += 1).unwrap());
                assert_eq!(body, b"path-body");
                assert_eq!(absent, 1);
                assert!(wait.lines().is_empty(), "{:?}", wait.lines());
                let lines = wait.retry_lines();
                assert_eq!(lines.len(), 1, "{lines:?}");
                assert_eq!(
                    lines[0],
                    "path_check call_site=fetch_path error_class=http_404 attempt=1 limit=2"
                );
                assert_eq!(gets.load(std::sync::atomic::Ordering::SeqCst), 2);
            },
        );
    }

    #[test]
    fn path_400_fails_without_a_second_get() {
        let gets = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let gets_srv = Arc::clone(&gets);
        with_actions(
            move |_req| {
                gets_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Respond(400, Vec::new())
            },
            |base| {
                let wait = FastWait::install();
                let agent = http_agent();
                let err = fetch_path(&agent, base, &[0x42; 32], || panic!("404 is not Class T"))
                    .unwrap_err();
                assert!(err.message.contains("HTTP 400"), "{}", err.message);
                assert!(wait.lines().is_empty());
                assert_eq!(gets.load(std::sync::atomic::Ordering::SeqCst), 1);
            },
        );
    }

    #[test]
    fn tcp_reset_on_a_clock_read_is_not_retried() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |_req| {
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Reset
            },
            |base| {
                let wait = FastWait::install();
                let agent = http_agent();
                assert!(read_clock(&agent, base).is_err());
                assert!(wait.lines().is_empty(), "{:?}", wait.lines());
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
            },
        );
    }

    #[test]
    fn a_clock_read_timeout_is_not_retried() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |_req| {
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Hang
            },
            |base| {
                let wait = FastWait::install();
                let agent = ureq::AgentBuilder::new()
                    .timeout(Duration::from_millis(200))
                    .redirects(0)
                    .build();
                assert!(read_clock(&agent, base).is_err());
                assert!(wait.lines().is_empty(), "{:?}", wait.lines());
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
            },
        );
    }

    #[test]
    fn a_refused_clock_connect_is_not_retried() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let stop_thread = Arc::clone(&stop);
        let join = thread::spawn(move || loop {
            if stop_thread.load(std::sync::atomic::Ordering::SeqCst) {
                break;
            }
            let Ok((mut stream, _)) = listener.accept() else {
                break;
            };
            if stop_thread.load(std::sync::atomic::Ordering::SeqCst) {
                break;
            }
            let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
            if read_http(&mut stream).is_none() {
                continue;
            }
            let _ = write_http(&mut stream, &(200, "OK", tip_json()));
        });
        let step = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let step_resolver = Arc::clone(&step);
        let refused = std::net::SocketAddr::from(([127, 0, 0, 1], 1));
        let agent = ureq::AgentBuilder::new()
            .timeout(Duration::from_secs(2))
            .redirects(0)
            .resolver(move |_netloc: &str| {
                if step_resolver.fetch_add(1, std::sync::atomic::Ordering::SeqCst) == 0 {
                    return Ok(vec![refused]);
                }
                Ok(vec![addr])
            })
            .build();
        let wait = FastWait::install();
        assert!(read_clock(&agent, "http://tip.lab").is_err());
        assert!(wait.lines().is_empty(), "{:?}", wait.lines());
        assert_eq!(step.load(std::sync::atomic::Ordering::SeqCst), 1);
        stop.store(true, std::sync::atomic::Ordering::SeqCst);
        let _ = std::net::TcpStream::connect(addr);
        join.join().unwrap();
    }

    #[test]
    fn a_dns_failure_on_a_clock_read_is_not_retried() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let stop_thread = Arc::clone(&stop);
        let join = thread::spawn(move || loop {
            if stop_thread.load(std::sync::atomic::Ordering::SeqCst) {
                break;
            }
            let Ok((mut stream, _)) = listener.accept() else {
                break;
            };
            if stop_thread.load(std::sync::atomic::Ordering::SeqCst) {
                break;
            }
            let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
            if read_http(&mut stream).is_none() {
                continue;
            }
            let _ = write_http(&mut stream, &(200, "OK", tip_json()));
        });
        let step = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let step_resolver = Arc::clone(&step);
        let agent = ureq::AgentBuilder::new()
            .timeout(Duration::from_secs(2))
            .redirects(0)
            .resolver(move |_netloc: &str| {
                match step_resolver.fetch_add(1, std::sync::atomic::Ordering::SeqCst) {
                    0 => Err(std::io::Error::new(
                        std::io::ErrorKind::NotFound,
                        "NXDOMAIN",
                    )),
                    1 => Err(std::io::Error::new(std::io::ErrorKind::Other, "SERVFAIL")),
                    2 => Err(std::io::Error::new(
                        std::io::ErrorKind::TimedOut,
                        "resolution timeout",
                    )),
                    _ => Ok(vec![addr]),
                }
            })
            .build();
        let wait = FastWait::install();
        let err = read_clock(&agent, "http://tip.lab").unwrap_err();
        assert!(
            err.message.contains("NXDOMAIN")
                || err.message.contains("dns")
                || err.message.contains("NotFound"),
            "{}",
            err.message
        );
        assert!(wait.lines().is_empty(), "{:?}", wait.lines());
        assert_eq!(step.load(std::sync::atomic::Ordering::SeqCst), 1);
        stop.store(true, std::sync::atomic::Ordering::SeqCst);
        let _ = std::net::TcpStream::connect(addr);
        join.join().unwrap();
    }

    #[test]
    fn live_path_book_post_does_not_resend_a_500() {
        let posts = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let posts_srv = Arc::clone(&posts);
        with_actions(
            move |req| {
                assert_eq!(req.method, "POST");
                assert_eq!(req.path, "/waypoint");
                posts_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Respond(500, Vec::new())
            },
            |base| {
                let wait = FastWait::install();
                let mut book = LivePathBook::new(produce_http_agent(), base);
                book.post(&LIVE_WAYPOINT, &tip_at(100), 100, 100, 8)
                    .unwrap();
                assert_eq!(posts.load(std::sync::atomic::Ordering::SeqCst), 1);
                assert!(wait.lines().is_empty(), "{:?}", wait.lines());
                assert!(
                    wait.retry_lines()
                        .iter()
                        .all(|line| !line.contains("http_5xx")),
                    "{:?}",
                    wait.retry_lines()
                );
            },
        );
    }

    #[test]
    fn waypoint_post_does_not_resend_408_429_or_an_unrecognized_400() {
        for (status, body) in [
            (408u16, Vec::new()),
            (429, Vec::new()),
            (400, b"edge page".to_vec()),
        ] {
            let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
            let hits_srv = Arc::clone(&hits);
            let payload = body.clone();
            with_actions(
                move |_req| {
                    hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                    Action::Respond(status, payload.clone())
                },
                |base| {
                    let wait = FastWait::install();
                    let agent = http_agent();
                    let err =
                        post_waypoint(&agent, base, &[0x42; 32], &natural_tip(), 8).unwrap_err();
                    assert!(
                        err.message.contains(&format!("HTTP {status}")),
                        "{}",
                        err.message
                    );
                    assert!(wait.retry_lines().is_empty(), "{:?}", wait.retry_lines());
                    assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
                },
            );
        }
    }

    fn short_dial() -> DialGuard {
        DialGuard::install(2, 2, 2, 30_000, 0)
    }

    #[test]
    fn path_404_skips_after_two_checks_and_one_stagger() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |_req| {
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Respond(404, Vec::new())
            },
            |base| {
                let started = std::time::Instant::now();
                let wait = FastWait::install();
                let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
                let agent = http_agent();
                let mut absent = 0;
                let fetch = fetch_path(&agent, base, &[0x42; 32], || absent += 1).unwrap();
                assert_eq!(fetch, PathFetch::Skipped);
                assert_eq!(absent, 2);
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 3);
                assert!(wait.lines().is_empty(), "{:?}", wait.lines());
                let lines = wait.retry_lines();
                assert!(
                    lines.iter().any(|line| {
                        line == "path_check call_site=fetch_path exhausted=yes reason=http_404 attempts=3"
                    }),
                    "{lines:?}"
                );
                assert!(
                    lines.iter().all(|line| !line.starts_with("path_skip ")),
                    "{lines:?}"
                );
                assert!(
                    wait.pauses().contains(&Duration::from_millis(60_000)),
                    "{:?}",
                    wait.pauses()
                );
                assert!(
                    started.elapsed() < Duration::from_secs(2),
                    "404 path fetch hung for {:?}",
                    started.elapsed()
                );
            },
        );
    }

    #[test]
    fn path_5xx_is_capped_then_skipped() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |_req| {
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Respond(503, Vec::new())
            },
            |base| {
                let started = std::time::Instant::now();
                let wait = FastWait::install();
                let _caps = DialGuard::install(30, 6, 2, 30_000, 0);
                let agent = http_agent();
                let fetch =
                    fetch_path(&agent, base, &[0x42; 32], || panic!("503 is not a 404")).unwrap();
                assert_eq!(fetch, PathFetch::Skipped);
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 2);
                assert!(wait.lines().is_empty(), "{:?}", wait.lines());
                let lines = wait.retry_lines();
                assert!(
                    lines
                        .iter()
                        .any(|line| line.contains("error_class=http_5xx")),
                    "{lines:?}"
                );
                assert!(
                    lines.iter().any(|line| {
                        line == "path_check call_site=fetch_path exhausted=yes reason=http_5xx attempts=2"
                    }),
                    "{lines:?}"
                );
                assert!(
                    lines.iter().all(|line| !line.starts_with("path_skip ")),
                    "{lines:?}"
                );
                assert!(
                    started.elapsed() < Duration::from_secs(2),
                    "5xx path fetch hung for {:?}",
                    started.elapsed()
                );
            },
        );
    }

    #[test]
    fn path_try_budget_stops_a_second_quick_check() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |_req| {
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Respond(404, Vec::new())
            },
            |base| {
                let wait = FastWait::install();
                let _caps = DialGuard::install(2, 2, 8, 0, 0);
                let agent = http_agent();
                let fetch = fetch_path(&agent, base, &[0x42; 32], || {}).unwrap();
                assert_eq!(fetch, PathFetch::Skipped);
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
                assert!(
                    wait.retry_lines()
                        .iter()
                        .any(|line| line.contains("reason=http_404 attempts=1")),
                    "{:?}",
                    wait.retry_lines()
                );
            },
        );
    }

    #[test]
    fn path_hang_uses_the_short_produce_timeout_then_skips() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |_req| {
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Hang
            },
            |base| {
                let started = std::time::Instant::now();
                let _wait = FastWait::install();
                let _caps = DialGuard::install(2, 2, 2, 30_000, 0);
                let agent = ureq::AgentBuilder::new()
                    .timeout(Duration::from_millis(200))
                    .timeout_connect(Duration::from_millis(200))
                    .timeout_read(Duration::from_millis(200))
                    .redirects(0)
                    .build();
                let fetch = fetch_path(&agent, base, &[0x11; 32], || {}).unwrap();
                assert_eq!(fetch, PathFetch::Skipped);
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 2);
                assert!(
                    started.elapsed() < Duration::from_secs(5),
                    "hung path fetch held for {:?}",
                    started.elapsed()
                );
            },
        );
    }

    #[test]
    fn waypoint_204_proceeds_without_a_path_get_or_a_wait() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |req| {
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                assert_eq!(req.method, "POST");
                assert_eq!(req.path, "/waypoint");
                Action::Respond(204, Vec::new())
            },
            |base| {
                let wait = FastWait::install();
                let agent = http_agent();
                post_waypoint(&agent, base, &[0x42; 32], &natural_tip(), 8).unwrap();
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
                assert!(wait.lines().is_empty(), "{:?}", wait.lines());
                assert!(wait.retry_lines().is_empty(), "{:?}", wait.retry_lines());
            },
        );
    }

    #[test]
    fn waypoint_502_is_one_post_and_does_not_wait() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |_req| {
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Respond(502, Vec::new())
            },
            |base| {
                let started = std::time::Instant::now();
                let wait = FastWait::install();
                let agent = http_agent();
                let err = post_waypoint(&agent, base, &[0x42; 32], &natural_tip(), 8).unwrap_err();
                assert!(err.message.contains("HTTP 502"), "{}", err.message);
                assert!(!err.message.contains("waits="), "{}", err.message);
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
                assert!(wait.lines().is_empty(), "{:?}", wait.lines());
                assert!(
                    started.elapsed() < Duration::from_secs(2),
                    "waypoint 5xx held for {:?}",
                    started.elapsed()
                );
            },
        );
    }

    #[test]
    fn waypoint_exact_tip_mismatch_observes_once_and_posts_a_fresh_nonce() {
        let posts = Arc::new(Mutex::new(Vec::new()));
        let posts_srv = Arc::clone(&posts);
        let left = Arc::new(std::sync::atomic::AtomicUsize::new(1));
        let left_srv = Arc::clone(&left);
        with_actions(
            move |req| {
                let nonce = header(req, "x-bari-hashcash-nonce").to_owned();
                posts_srv.lock().unwrap().push(nonce);
                if consume_one(&left_srv) {
                    return Action::Respond(
                        400,
                        b"Hashcash proof invalid for live tip challenge".to_vec(),
                    );
                }
                Action::Respond(204, Vec::new())
            },
            |base| {
                let wait = FastWait::install();
                let agent = http_agent();
                let fresh = [0xAB; 32];
                let mut observed = 0;
                post_waypoint_status(&agent, base, &[0x42; 32], &natural_tip(), 8, |_| {
                    observed += 1;
                    Ok(vec![(fresh, 99)])
                })
                .unwrap();
                assert_eq!(observed, 1);
                assert!(wait.lines().is_empty(), "{:?}", wait.lines());
                assert!(
                    wait.retry_lines()
                        .iter()
                        .all(|line| !line.contains("tip_mismatch")),
                    "{:?}",
                    wait.retry_lines()
                );
                let nonces = posts.lock().unwrap();
                assert_eq!(nonces.len(), 2);
                assert_ne!(nonces[0], nonces[1]);
                let nonce: u64 = nonces[1].parse().unwrap();
                let challenge = hashcash::challenge_from_tip(&fresh);
                assert_eq!(nonce, hashcash::solve(&challenge, &[0x42; 32], 8).unwrap());
            },
        );
    }

    #[test]
    fn waypoint_tip_mismatch_does_not_post_again_when_observe_fails() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |_req| {
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Respond(
                    400,
                    b"Hashcash proof invalid for live tip challenge".to_vec(),
                )
            },
            |base| {
                let agent = http_agent();
                let err =
                    post_waypoint_status(&agent, base, &[0x42; 32], &natural_tip(), 8, |_| {
                        Err(ProduceFailure::new("clock read failed: reorg"))
                    })
                    .unwrap_err();
                assert_eq!(err.http_status, Some(400));
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
            },
        );
    }

    #[test]
    fn waypoint_400_with_any_other_body_is_one_post() {
        for body in [
            b"Hashcash proof invalid for live tip challenge\n".to_vec(),
            b"Hashcash proof invalid for live tip challenge ".to_vec(),
            b"prefix Hashcash proof invalid for live tip challenge".to_vec(),
        ] {
            let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
            let hits_srv = Arc::clone(&hits);
            let payload = body.clone();
            let mut observed = 0;
            with_actions(
                move |_req| {
                    hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                    Action::Respond(400, payload.clone())
                },
                |base| {
                    let agent = http_agent();
                    let err =
                        post_waypoint_status(&agent, base, &[0x42; 32], &natural_tip(), 8, |_| {
                            observed += 1;
                            Ok(vec![([1; 32], 2)])
                        })
                        .unwrap_err();
                    assert_eq!(err.http_status, Some(400), "{}", err.message);
                    assert_eq!(observed, 0);
                    assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
                },
            );
        }
    }

    #[test]
    fn a_second_exact_tip_mismatch_does_not_observe_again() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |_req| {
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Respond(
                    400,
                    b"Hashcash proof invalid for live tip challenge".to_vec(),
                )
            },
            |base| {
                let agent = http_agent();
                let mut observed = 0;
                let err =
                    post_waypoint_status(&agent, base, &[0x42; 32], &natural_tip(), 8, |_| {
                        observed += 1;
                        Ok(vec![([0xCD; 32], 7)])
                    })
                    .unwrap_err();
                assert_eq!(observed, 1);
                assert_eq!(err.http_status, Some(400));
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 2);
            },
        );
    }

    #[test]
    fn waypoint_durable_400_fails_without_a_retry() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |_req| {
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Respond(400, b"body must be exactly 32 raw bytes".to_vec())
            },
            |base| {
                let wait = FastWait::install();
                let _caps = short_dial();
                let agent = http_agent();
                let err = post_waypoint(&agent, base, &[0x11; 32], &natural_tip(), 8).unwrap_err();
                assert_eq!(
                    err.message,
                    "waypoint post failed: HTTP 400 body=body must be exactly 32 raw bytes"
                );
                assert!(wait.lines().is_empty());
                assert!(wait.retry_lines().is_empty());
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
            },
        );
    }

    #[test]
    fn waypoint_empty_400_fails_without_a_retry() {
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hits_srv = Arc::clone(&hits);
        with_actions(
            move |_req| {
                hits_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Action::Respond(400, Vec::new())
            },
            |base| {
                let wait = FastWait::install();
                let _caps = short_dial();
                let agent = http_agent();
                let err = post_waypoint(&agent, base, &[0x11; 32], &natural_tip(), 8).unwrap_err();
                assert_eq!(err.message, "waypoint post failed: HTTP 400");
                assert!(wait.retry_lines().is_empty(), "{:?}", wait.retry_lines());
                assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 1);
            },
        );
    }

    #[test]
    fn waypoint_already_held_body_proceeds() {
        with_actions(
            move |_req| Action::Respond(409, b"waypoint already held in the open pool".to_vec()),
            |base| {
                let wait = FastWait::install();
                let agent = http_agent();
                post_waypoint(&agent, base, &[0x42; 32], &natural_tip(), 8).unwrap();
                let lines = wait.retry_lines();
                assert_eq!(lines.len(), 1, "{lines:?}");
                assert!(
                    lines[0].contains("result=already_held status=409"),
                    "{}",
                    lines[0]
                );
            },
        );
    }

    #[test]
    fn plant_stage_posts_before_the_window_then_skips_a_missing_path() {
        let order = Arc::new(Mutex::new(Vec::new()));
        let order_srv = Arc::clone(&order);
        with_actions(
            move |req| {
                order_srv.lock().unwrap().push(req.method.clone());
                if req.method == "POST" {
                    Action::Respond(204, Vec::new())
                } else {
                    Action::Respond(404, Vec::new())
                }
            },
            |base| {
                let wait = FastWait::install();
                let _caps = DialGuard::install_jitter(2, 2, 2, 30_000, 0, 5_000, 30_000);
                let agent = http_agent();
                let mut window = false;
                let fetch = plant_stage(&agent, base, &[0x42; 32], &natural_tip(), 8, || {
                    let seen = order.lock().unwrap().clone();
                    assert_eq!(seen, vec!["POST".to_owned()], "{seen:?}");
                    window = true;
                    Ok(())
                })
                .unwrap();
                assert!(window);
                assert_eq!(fetch, PathFetch::Skipped);
                let seen = order.lock().unwrap().clone();
                assert_eq!(
                    seen,
                    vec!["POST".to_owned(), "GET".to_owned(), "GET".to_owned()]
                );
                let jitter = wait
                    .retry_lines()
                    .into_iter()
                    .find(|line| line.starts_with("path_jitter "))
                    .expect("jitter");
                let ms: u64 = jitter.rsplit_once("sleep_ms=").unwrap().1.parse().unwrap();
                assert!((5_000..=30_000).contains(&ms), "{jitter}");
                assert!(
                    wait.retry_lines().iter().any(|line| {
                        line == "path_check call_site=fetch_path exhausted=yes reason=http_404 attempts=2"
                    }),
                    "{:?}",
                    wait.retry_lines()
                );
                assert!(
                    wait.retry_lines()
                        .iter()
                        .all(|line| !line.starts_with("path_skip ")),
                    "{:?}",
                    wait.retry_lines()
                );
            },
        );
    }

    fn tip_at(height: u64) -> [u8; 32] {
        let mut tip = [0u8; 32];
        tip[..8].copy_from_slice(&height.to_le_bytes());
        tip
    }

    const LIVE_WAYPOINT: [u8; 32] = [0x42; 32];

    enum LiveGet {
        Http { status: u16, body: Vec<u8> },
        Reset,
    }

    struct LiveScript {
        posts: Arc<std::sync::atomic::AtomicUsize>,
        gets: Arc<std::sync::atomic::AtomicUsize>,
        reply: Arc<Mutex<LiveGet>>,
    }

    fn script_live(reply: LiveGet) -> (LiveScript, impl FnMut(&RawReq) -> Action) {
        let posts = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let gets = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let reply = Arc::new(Mutex::new(reply));
        let posts_srv = Arc::clone(&posts);
        let gets_srv = Arc::clone(&gets);
        let reply_srv = Arc::clone(&reply);
        let action = move |req: &RawReq| {
            if req.method == "POST" {
                posts_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                return Action::Respond(204, Vec::new());
            }
            gets_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            let reply = reply_srv.lock().unwrap();
            match &*reply {
                LiveGet::Reset => Action::Reset,
                LiveGet::Http { status, body } => Action::Respond(*status, body.clone()),
            }
        };
        (LiveScript { posts, gets, reply }, action)
    }

    fn set_reply(script: &LiveScript, status: u16, body: impl Into<Vec<u8>>) {
        *script.reply.lock().unwrap() = LiveGet::Http {
            status,
            body: body.into(),
        };
    }

    fn path_at(block_height: u32) -> Vec<u8> {
        crate::pathbody::encode_path_in_block(&LIVE_WAYPOINT, block_height)
    }

    fn gets(script: &LiveScript) -> usize {
        script.gets.load(std::sync::atomic::Ordering::SeqCst)
    }

    fn posts(script: &LiveScript) -> usize {
        script.posts.load(std::sync::atomic::Ordering::SeqCst)
    }

    fn fresh_book(base: &str) -> LivePathBook {
        let mut book = LivePathBook::new(produce_http_agent(), base);
        book.post(&LIVE_WAYPOINT, &tip_at(100), 100, 100, 8)
            .unwrap();
        book
    }

    fn stage_for_live() -> crate::object::Stage {
        crate::object::Stage {
            beacon: [1; 32],
            beacon_height: 100,
            leg_count: 3,
            waypoint: LIVE_WAYPOINT,
            path: b"sentinel".to_vec(),
            checkpoints: vec![[2; 32]],
            leg_results: vec![[3; 8]],
            checkpoint_rows_defined: true,
            leg_result_missing: Vec::new(),
        }
    }

    fn no_stagger(wait: &FastWait) {
        assert!(
            wait.retry_lines()
                .iter()
                .all(|line| !line.contains("path_stagger")),
            "{:?}",
            wait.retry_lines()
        );
        assert!(
            !wait.pauses().contains(&Duration::from_millis(60_000)),
            "the one-minute stagger is not the live path wait: {:?}",
            wait.pauses()
        );
    }

    #[test]
    fn due_height_is_four_headers_after_the_post() {
        assert_eq!(HEADERS_AFTER_POST, 4);
        assert_eq!(DEFAULT_PATH_STAGGER_MS, 60_000);
        assert_eq!(live_on_chain_height(100), Some(102));
        assert_eq!(live_path_due_height(100), Some(104));
        assert_eq!(live_path_due_height(100), Some(100 + HEADERS_AFTER_POST));
        assert_ne!(
            live_path_due_height(100).unwrap() - 100,
            DEFAULT_PATH_STAGGER_MS
        );
        assert_eq!(live_on_chain_height(u64::MAX - 3), None);
        assert_eq!(live_path_due_height(u64::MAX - 3), None);
        assert_eq!(crate::consts::anchor_window(u64::MAX - 3), None);
        assert_eq!(live_path_due_height(u64::MAX - 4), Some(u64::MAX));
    }

    #[test]
    fn empty_path_requires_a_closed_window_and_a_not_included_body() {
        let early = miss_ask(b"still in the pool", &LIVE_WAYPOINT, 100, 104).unwrap();
        assert!(matches!(early, Ask::Wait));
        let sealed = miss_ask(b"sealed but not in a block yet", &LIVE_WAYPOINT, 100, 104).unwrap();
        assert!(matches!(sealed, Ask::Wait));
        let too_soon = miss_ask(b"not included", &LIVE_WAYPOINT, 100, 103).unwrap();
        assert!(
            matches!(too_soon, Ask::Wait),
            "not included before the window closes does not store an empty path"
        );
        let closed = miss_ask(b"not included\n", &LIVE_WAYPOINT, 100, 104).unwrap();
        assert!(matches!(closed, Ask::Done(PathFetch::Skipped)));
        for body in [
            &b"already archived after it was served"[..],
            b"unknown",
            b"no such leaf",
            b"",
        ] {
            let miss = miss_ask(body, &LIVE_WAYPOINT, 100, 104).unwrap();
            assert!(
                matches!(miss, Ask::Wait),
                "a named or unreadable miss does not stop the hike: {body:?}"
            );
        }
    }

    fn miss_reply() -> LiveGet {
        LiveGet::Http {
            status: 404,
            body: b"still in the pool".to_vec(),
        }
    }

    fn drive(book: &mut LivePathBook, heights: impl IntoIterator<Item = u64>) {
        for height in heights {
            book.on_tip(tip_at(height), height).unwrap();
        }
    }

    #[test]
    fn live_path_ask_wait_is_drawn_from_fifteen_through_forty_five_seconds() {
        let mut seen = std::collections::BTreeSet::new();
        for _ in 0..64 {
            let wait = live_path_ask_wait();
            assert!(wait >= Duration::from_secs(15), "{wait:?}");
            assert!(wait <= Duration::from_secs(45), "{wait:?}");
            seen.insert(wait);
        }
        assert!(
            seen.len() > 1,
            "draws must not share one fixed offset: {seen:?}"
        );
        let previous = std::env::var("PCOC_LIVE_PATH_ASK_WAIT_MS").ok();
        std::env::set_var("PCOC_LIVE_PATH_ASK_WAIT_MS", "0");
        let drawn = live_path_ask_wait();
        assert!(drawn >= Duration::from_secs(15), "{drawn:?}");
        assert!(drawn <= Duration::from_secs(45), "{drawn:?}");
        match previous {
            Some(value) => std::env::set_var("PCOC_LIVE_PATH_ASK_WAIT_MS", value),
            None => std::env::remove_var("PCOC_LIVE_PATH_ASK_WAIT_MS"),
        }
        // The test pause returns at once. It does not change the draw.
        let previous_pause = std::env::var("PCOC_TEST_PAUSE_MS").ok();
        std::env::set_var("PCOC_TEST_PAUSE_MS", "0");
        let paused = live_path_ask_wait();
        assert!(paused >= Duration::from_secs(15), "{paused:?}");
        assert!(paused <= Duration::from_secs(45), "{paused:?}");
        match previous_pause {
            Some(value) => std::env::set_var("PCOC_TEST_PAUSE_MS", value),
            None => std::env::remove_var("PCOC_TEST_PAUSE_MS"),
        }
    }

    #[test]
    fn each_live_ask_waits_after_its_header_before_the_get() {
        let (script, action) = script_live(miss_reply());
        with_actions(action, |base| {
            let gets_at_wait = Arc::new(Mutex::new(Vec::new()));
            let record = Arc::clone(&gets_at_wait);
            let script_gets = Arc::clone(&script.gets);
            PAUSE_HOOK.with(|hook| {
                *hook.borrow_mut() = Some(Box::new(move |duration| {
                    assert!(
                        (Duration::from_secs(15)..=Duration::from_secs(45)).contains(&duration),
                        "{duration:?}"
                    );
                    record
                        .lock()
                        .unwrap()
                        .push(script_gets.load(std::sync::atomic::Ordering::SeqCst));
                }));
            });
            let _clear = ClearPauseHook;
            PAUSE_LOG.with(|log| log.borrow_mut().clear());
            let mut book = fresh_book(base);
            book.on_tip(tip_at(101), 101).unwrap();
            assert_eq!(gets(&script), 0, "H+1 is not an ask header");
            assert!(gets_at_wait.lock().unwrap().is_empty());
            drive(&mut book, 102..=104);
            assert_eq!(
                gets_at_wait.lock().unwrap().as_slice(),
                &[0, 1, 2],
                "each GET is sent after its wait, not when the header is observed"
            );
            assert_eq!(gets(&script), 3);
            let pauses = PAUSE_LOG.with(|log| log.borrow().clone());
            assert_eq!(pauses.len(), 3);
            assert!(pauses.iter().all(|pause| {
                (Duration::from_secs(15)..=Duration::from_secs(45)).contains(pause)
            }));
            book.on_tip(tip_at(105), 105).unwrap();
            assert_eq!(gets(&script), 3, "nothing is asked after H+4");
            assert_eq!(posts(&script), 1);
        });
    }

    struct ClearPauseHook;

    impl Drop for ClearPauseHook {
        fn drop(&mut self) {
            PAUSE_HOOK.with(|hook| *hook.borrow_mut() = None);
        }
    }

    #[test]
    fn a_path_at_h2_is_stored_and_h3_and_h4_are_not_asked() {
        let body = path_at(102);
        let (script, action) = script_live(LiveGet::Http {
            status: 200,
            body: body.clone(),
        });
        with_actions(action, |base| {
            let wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = fresh_book(base);
            assert_eq!(posts(&script), 1);
            assert_eq!(gets(&script), 0, "stage open must not fetch the path");
            drive(&mut book, 101..=105);
            assert_eq!(gets(&script), 1);
            match &book.watches[0].state {
                WatchState::Done(PathFetch::Body(stored)) => assert_eq!(stored, &body),
                other => panic!("expected the checked path, got {other:?}"),
            }
            let lines = wait.retry_lines();
            assert!(
                lines.iter().any(|line| line.contains("tip_height=102")),
                "{lines:?}"
            );
            assert!(
                lines.iter().all(
                    |line| !line.contains("tip_height=103") && !line.contains("tip_height=104")
                ),
                "{lines:?}"
            );
            no_stagger(&wait);
        });
    }

    /// The event log records a stored 200 after the GET returns.
    ///
    /// The stderr `path_fetch` line is the request. This test fails if the
    /// success is missing, if only a miss was written, or if the success was
    /// already in the file while the GET was still in flight.
    #[test]
    fn successful_fetch_is_recorded_after_the_get() {
        let body = path_at(102);
        let output = temp_output("fetch-ok");
        let output = output.to_str().expect("temp path").to_owned();
        let log_path = crate::producelog::event_log_path(&output);
        let _remove = RemoveFile(log_path.clone());
        let during = Arc::new(Mutex::new(String::new()));
        let during_get = Arc::clone(&during);
        let log_for_get = log_path.clone();
        let (script, mut inner) = script_live(LiveGet::Http {
            status: 200,
            body: body.clone(),
        });
        let action = move |req: &RawReq| {
            if req.method == "GET" {
                let text = std::fs::read_to_string(&log_for_get).unwrap_or_default();
                *during_get.lock().unwrap() = text;
            }
            inner(req)
        };
        with_actions(action, |base| {
            let wait = FastWait::install();
            let mut book = LivePathBook::new(produce_http_agent(), base);
            book.open_event_log(&output).unwrap();
            book.post(&LIVE_WAYPOINT, &tip_at(100), 100, 100, 8)
                .unwrap();
            drive(&mut book, 101..=102);
            assert_eq!(gets(&script), 1);
            assert_eq!(posts(&script), 1);
            match &book.watches[0].state {
                WatchState::Done(PathFetch::Body(stored)) => assert_eq!(stored, &body),
                other => panic!("expected the checked path, got {other:?}"),
            }
            let during = during.lock().unwrap().clone();
            let after = std::fs::read_to_string(&log_path).unwrap();
            let waypoint = encode_hex32(&LIVE_WAYPOINT);
            let block = encode_hex32(&[0x55; 32]);
            let success = format!(
                "{{\"event\":\"hike.produce.fetch\",\"waypoint\":\"{waypoint}\",\"worked\":true,\"status\":200,\"block\":\"{block}\",\"block_height\":102}}"
            );
            assert!(
                during.contains(&format!(
                    "{{\"event\":\"hike.produce.block\",\"height\":102,\"tip\":\"{}\"}}",
                    encode_hex32(&tip_at(102))
                )),
                "the header is logged before the GET returns\n{during}"
            );
            assert!(
                !during.contains("hike.produce.fetch"),
                "a fetch record during the GET is the request, not the result\n{during}"
            );
            assert!(
                !during.contains("\"worked\":true"),
                "success must not be recorded before the GET returns\n{during}"
            );
            assert!(
                after.starts_with(&during),
                "the result is appended after the in-flight GET\nduring:\n{during}\nafter:\n{after}"
            );
            assert!(
                after.lines().any(|line| line == success),
                "successful fetch missing\n{after}"
            );
            assert!(
                after.lines().any(|line| {
                    line == format!(
                        "{{\"event\":\"hike.produce.checked\",\"waypoint\":\"{waypoint}\",\"checked\":true}}"
                    )
                }),
                "the waypoint check is missing\n{after}"
            );
            assert!(
                after.lines().any(|line| {
                    line == format!(
                        "{{\"event\":\"hike.produce.waypoint\",\"stage\":1,\"waypoint\":\"{waypoint}\"}}"
                    )
                }),
                "{after}"
            );
            assert!(
                after.lines().any(|line| {
                    line == format!(
                        "{{\"event\":\"hike.produce.posted\",\"stage\":1,\"waypoint\":\"{waypoint}\",\"posted\":true,\"succeeded\":true,\"status\":204}}"
                    )
                }),
                "{after}"
            );
            assert!(
                wait.retry_lines()
                    .iter()
                    .any(|line| line.starts_with("path_fetch ")),
                "stderr still names the request; the event log is the result"
            );
            assert!(
                after.lines().filter(|line| *line == success).count() == 1,
                "{after}"
            );
        });
    }

    #[test]
    fn a_miss_stays_a_miss_in_the_event_log() {
        let output = temp_output("fetch-miss");
        let output = output.to_str().expect("temp path").to_owned();
        let log_path = crate::producelog::event_log_path(&output);
        let _remove = RemoveFile(log_path.clone());
        let (script, action) = script_live(LiveGet::Http {
            status: 404,
            body: b"not included".to_vec(),
        });
        with_actions(action, |base| {
            let wait = FastWait::install();
            let mut book = LivePathBook::new(produce_http_agent(), base);
            book.open_event_log(&output).unwrap();
            book.post(&LIVE_WAYPOINT, &tip_at(100), 100, 100, 8)
                .unwrap();
            let mut stage = stage_for_live();
            drive(&mut book, 101..=104);
            book.finish(std::slice::from_mut(&mut stage), u64::MAX, |_| {
                Ok(Vec::new())
            })
            .unwrap();
            assert_eq!(stage.path, b"");
            assert_eq!(gets(&script), 3);
            assert_eq!(posts(&script), 1, "a missed plant is not posted again");
            let waypoint = encode_hex32(&LIVE_WAYPOINT);
            let miss = format!(
                "{{\"event\":\"hike.produce.fetch\",\"waypoint\":\"{waypoint}\",\"worked\":false,\"status\":404,\"miss\":\"not included\"}}"
            );
            let text = std::fs::read_to_string(&log_path).unwrap();
            assert_eq!(
                text.lines().filter(|line| *line == miss).count(),
                3,
                "{text}"
            );
            assert!(
                text.lines().all(|line| !line.contains("\"worked\":true")),
                "a miss is not recorded as a successful fetch\n{text}"
            );
            assert!(
                !text.contains("hike.produce.checked"),
                "a miss is not the waypoint check\n{text}"
            );
            assert!(wait
                .retry_lines()
                .iter()
                .all(|line| !line.starts_with("path_skip ")));
        });
    }

    fn temp_output(label: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!(
            "pcoc-{label}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("clock")
                .as_nanos()
        ))
    }

    struct RemoveFile(std::path::PathBuf);

    impl Drop for RemoveFile {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    #[test]
    fn a_miss_at_h2_then_a_path_at_h3_does_not_ask_h4() {
        let body = path_at(103);
        let (script, action) = script_live(miss_reply());
        with_actions(action, |base| {
            let _wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = fresh_book(base);
            drive(&mut book, [101, 102]);
            assert_eq!(gets(&script), 1);
            set_reply(&script, 200, body.clone());
            drive(&mut book, 103..=105);
            assert_eq!(gets(&script), 2);
            match &book.watches[0].state {
                WatchState::Done(PathFetch::Body(stored)) => assert_eq!(stored, &body),
                other => panic!("expected the path from H+3, got {other:?}"),
            }
            assert_eq!(posts(&script), 1);
        });
    }

    #[test]
    fn a_miss_at_h2_and_h3_then_a_path_at_h4_is_stored() {
        let body = path_at(104);
        let (script, action) = script_live(miss_reply());
        with_actions(action, |base| {
            let _wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = fresh_book(base);
            drive(&mut book, 101..=103);
            assert_eq!(gets(&script), 2);
            set_reply(&script, 200, body.clone());
            drive(&mut book, 104..=105);
            assert_eq!(gets(&script), 3);
            match &book.watches[0].state {
                WatchState::Done(PathFetch::Body(stored)) => assert_eq!(stored, &body),
                other => panic!("expected the path from H+4, got {other:?}"),
            }
            assert_eq!(posts(&script), 1);
        });
    }

    #[test]
    fn a_miss_at_h2_h3_and_h4_stores_the_empty_skip() {
        let (script, action) = script_live(miss_reply());
        with_actions(action, |base| {
            let wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = fresh_book(base);
            let mut stage = stage_for_live();
            drive(&mut book, 101..=104);
            book.finish(std::slice::from_mut(&mut stage), u64::MAX, |_| {
                Ok(Vec::new())
            })
            .unwrap();
            assert_eq!(stage.path, b"");
            assert_eq!(gets(&script), 3);
            drive(&mut book, [105]);
            assert_eq!(gets(&script), 3, "a skip is not asked again");
            assert_eq!(posts(&script), 1, "a skipped plant is not posted again");
            assert!(matches!(
                book.watches[0].state,
                WatchState::Done(PathFetch::Skipped)
            ));
            assert!(wait
                .retry_lines()
                .iter()
                .all(|line| !line.starts_with("path_skip ")));
            no_stagger(&wait);
        });
    }

    #[test]
    fn batched_headers_ask_on_the_due_header_not_the_newest() {
        let body = path_at(104);
        let (script, action) = script_live(LiveGet::Http {
            status: 200,
            body: body.clone(),
        });
        with_actions(action, |base| {
            let wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = fresh_book(base);
            let mut stage = stage_for_live();
            stage.leg_count = 4;
            let armed = std::sync::atomic::AtomicBool::new(false);
            book.finish(std::slice::from_mut(&mut stage), u64::MAX, |_| {
                if armed.swap(true, std::sync::atomic::Ordering::SeqCst) {
                    return Ok(Vec::new());
                }
                Ok((101..=106).map(|height| (tip_at(height), height)).collect())
            })
            .unwrap();
            assert_eq!(stage.path, body);
            assert_eq!(stage.leg_count, 4);
            assert_eq!(gets(&script), 1);
            assert_eq!(posts(&script), 1);
            let lines = wait.retry_lines();
            assert!(
                lines
                    .iter()
                    .any(|line| line.contains("tip_height=102") && line.contains("due_height=104")),
                "{lines:?}"
            );
            assert!(
                lines.iter().all(|line| !line.contains("tip_height=103")
                    && !line.contains("tip_height=104")
                    && !line.contains("tip_height=106")),
                "{lines:?}"
            );
            no_stagger(&wait);
        });
    }

    #[test]
    fn the_clock_does_not_skip_the_header_where_the_ask_is_due() {
        let (clock, start, start_height) = crate::gossip::regtest_clock_past_genesis(6);
        assert_eq!(start_height, 0);
        let body = path_at(2);
        let (script, action) = script_live(LiveGet::Http {
            status: 200,
            body: body.clone(),
        });
        with_actions(action, |base| {
            let _wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = LivePathBook::new(produce_http_agent(), base);
            book.post(&LIVE_WAYPOINT, &start, start_height, start_height, 8)
                .unwrap();
            assert_eq!(gets(&script), 0, "the post is not the path ask");
            let (newest, newest_height) =
                crate::gossip::note_produce_headers(&clock, &mut book).unwrap();
            assert_eq!(newest_height, start_height + 6);
            assert_ne!(newest, start);
            assert_eq!(gets(&script), 1, "the ask is H+2, not the newest tip");
            match &book.watches[0].state {
                WatchState::Done(PathFetch::Body(stored)) => assert_eq!(stored, &body),
                other => panic!("expected the path from the due header, got {other:?}"),
            }
        });
    }

    #[test]
    fn w17_is_posted_and_not_asked_when_an_offset_is_due() {
        let (script, action) = script_live(LiveGet::Http {
            status: 200,
            body: path_at(102),
        });
        with_actions(action, |base| {
            let wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = LivePathBook::new(produce_http_agent(), base);
            for g in 1..=16u8 {
                book.post(&[g; 32], &tip_at(100), 100, 1_000, 1).unwrap();
            }
            assert_eq!(posts(&script), 16);
            assert_eq!(gets(&script), 0, "those windows are above the noted tip");
            let w17 = [17u8; 32];
            book.post(&w17, &tip_at(102), 102, 100, 1).unwrap();
            book.on_tip(tip_at(103), 103).unwrap();
            book.on_tip(tip_at(104), 104).unwrap();
            assert_eq!(posts(&script), 17, "W_17 is posted");
            assert_eq!(gets(&script), 0, "W_17 is not asked");
            let mut stages = vec![stage_for_live(); 18];
            stages[17].waypoint = w17;
            stages[17].beacon_height = 100;
            stages[17].path = b"sentinel".to_vec();
            book.finish(&mut stages, 104, |_| Ok(Vec::new())).unwrap();
            assert_eq!(stages[17].path, b"");
            assert_eq!(gets(&script), 0);
            let lines = wait.retry_lines();
            assert!(lines.iter().any(|line| line == "not fetched by design 17"));
            assert!(lines.iter().all(|line| !line.starts_with("path_skip 17")));
        });
    }

    #[test]
    fn a_due_header_already_noted_at_the_cut_off_is_asked() {
        let (script, action) = script_live(LiveGet::Http {
            status: 404,
            body: b"unknown".to_vec(),
        });
        with_actions(action, |base| {
            let _wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = LivePathBook::new(produce_http_agent(), base);
            book.post(&LIVE_WAYPOINT, &tip_at(102), 102, 100, 8)
                .unwrap();
            let mut stage = stage_for_live();
            book.finish(std::slice::from_mut(&mut stage), 102, |_| Ok(Vec::new()))
                .unwrap();
            assert_eq!(
                gets(&script),
                1,
                "H+2 was noted before the watch and is the cut-off"
            );
            assert_eq!(stage.path, b"");
            assert!(wait_lines_have_no_early_path_skip(&_wait));
        });
    }

    fn wait_lines_have_no_early_path_skip(wait: &FastWait) -> bool {
        wait.retry_lines()
            .iter()
            .all(|line| !line.contains("path_skip call_site"))
    }

    #[test]
    fn a_tip_jump_stores_an_empty_path_and_does_not_get() {
        let (script, action) = script_live(LiveGet::Http {
            status: 404,
            body: b"not included".to_vec(),
        });
        with_actions(action, |base| {
            let _wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = LivePathBook::new(produce_http_agent(), base);
            book.post(&LIVE_WAYPOINT, &tip_at(101), 101, 100, 8)
                .unwrap();
            book.on_tip(tip_at(105), 105).unwrap();
            assert_eq!(gets(&script), 0, "a jumped window is not asked");
            assert!(matches!(
                book.watches[0].state,
                WatchState::Done(PathFetch::Skipped)
            ));
            book.on_tip(tip_at(106), 106).unwrap();
            assert_eq!(gets(&script), 0, "a missed window is not asked later");
            let mut stage = stage_for_live();
            book.finish(std::slice::from_mut(&mut stage), 105, |_| Ok(Vec::new()))
                .unwrap();
            assert_eq!(stage.path, b"");
        });
    }

    #[test]
    fn beacon_100_is_asked_at_102_103_and_104() {
        let (script, action) = script_live(LiveGet::Http {
            status: 404,
            body: b"not included".to_vec(),
        });
        with_actions(action, |base| {
            let _wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = LivePathBook::new(produce_http_agent(), base);
            book.post(&LIVE_WAYPOINT, &tip_at(101), 101, 100, 8)
                .unwrap();
            let mut stage = stage_for_live();
            book.finish(std::slice::from_mut(&mut stage), 200, |_| {
                Ok((102..=104).map(|height| (tip_at(height), height)).collect())
            })
            .unwrap();
            assert_eq!(gets(&script), 3);
            assert_eq!(stage.path, b"");
        });
    }

    #[test]
    fn a_200_above_the_cut_off_is_not_stored() {
        let (script, action) = script_live(LiveGet::Http {
            status: 200,
            body: path_at(104),
        });
        with_actions(action, |base| {
            let _wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = LivePathBook::new(produce_http_agent(), base);
            book.post(&LIVE_WAYPOINT, &tip_at(101), 101, 100, 8)
                .unwrap();
            let mut stage = stage_for_live();
            book.finish(std::slice::from_mut(&mut stage), 102, |_| {
                Ok((102..=104).map(|height| (tip_at(height), height)).collect())
            })
            .unwrap();
            assert_eq!(gets(&script), 1, "103 and 104 are above the cut-off");
            assert_eq!(stage.path, b"");
        });
    }

    #[test]
    fn an_out_of_window_200_stores_empty_and_continues() {
        let (script, action) = script_live(LiveGet::Http {
            status: 200,
            body: path_at(101),
        });
        with_actions(action, |base| {
            let _wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = LivePathBook::new(produce_http_agent(), base);
            book.post(&LIVE_WAYPOINT, &tip_at(101), 101, 100, 8)
                .unwrap();
            let mut stage = stage_for_live();
            book.finish(std::slice::from_mut(&mut stage), u64::MAX, |_| {
                Ok((102..=104).map(|height| (tip_at(height), height)).collect())
            })
            .unwrap();
            assert_eq!(gets(&script), 3);
            assert_eq!(stage.path, b"");
        });
    }

    #[test]
    fn the_final_stderr_step_writes_path_skip_then_w17_once() {
        let (script, action) = script_live(LiveGet::Http {
            status: 404,
            body: b"not included".to_vec(),
        });
        with_actions(action, |base| {
            let wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = LivePathBook::new(produce_http_agent(), base);
            book.post(&LIVE_WAYPOINT, &tip_at(101), 101, 100, 8)
                .unwrap();
            let mut stages = vec![stage_for_live(); 18];
            stages[1].waypoint = LIVE_WAYPOINT;
            for (g, stage) in stages.iter_mut().enumerate() {
                if g != 1 {
                    stage.waypoint = [g as u8; 32];
                    stage.path.clear();
                }
            }
            book.finish(&mut stages, 104, |_| {
                Ok((102..=104).map(|height| (tip_at(height), height)).collect())
            })
            .unwrap();
            assert_eq!(stages[1].path, b"");
            assert_eq!(stages[17].path, b"");
            let lines = wait.retry_lines();
            assert!(
                lines
                    .iter()
                    .all(|line| !line.contains("path_skip call_site")),
                "{lines:?}"
            );
            assert!(
                lines
                    .iter()
                    .all(|line| !line.contains("reason=not_included")),
                "{lines:?}"
            );
            let first_skip = lines
                .iter()
                .position(|line| line.starts_with("path_skip "))
                .expect("final path_skip step");
            assert!(
                lines[..first_skip]
                    .iter()
                    .all(|line| !line.contains("path_skip")),
                "{lines:?}"
            );
            let skips: Vec<_> = lines
                .iter()
                .filter(|line| line.starts_with("path_skip "))
                .cloned()
                .collect();
            assert_eq!(
                skips.iter().filter(|line| *line == "path_skip 1").count(),
                1
            );
            assert!(skips.windows(2).all(|pair| {
                let left: u32 = pair[0].trim_start_matches("path_skip ").parse().unwrap();
                let right: u32 = pair[1].trim_start_matches("path_skip ").parse().unwrap();
                left < right
            }));
            assert!(!lines.iter().any(|line| line == "path_skip 0"));
            let final_at = lines
                .iter()
                .rposition(|line| line == "not fetched by design 17")
                .expect("w17 line");
            assert_eq!(
                lines
                    .iter()
                    .filter(|line| *line == "not fetched by design 17")
                    .count(),
                1
            );
            assert!(lines[final_at + 1..]
                .iter()
                .all(|line| !line.starts_with("path_skip ")));
            assert_eq!(gets(&script), 3);
        });
    }

    #[test]
    fn an_unknown_body_does_not_write_path_skip_until_the_final_step() {
        let (script, action) = script_live(LiveGet::Http {
            status: 404,
            body: b"unknown".to_vec(),
        });
        with_actions(action, |base| {
            let wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = LivePathBook::new(produce_http_agent(), base);
            book.post(&LIVE_WAYPOINT, &tip_at(101), 101, 100, 8)
                .unwrap();
            let mut stages = vec![stage_for_live(); 18];
            stages[1].waypoint = LIVE_WAYPOINT;
            for (g, stage) in stages.iter_mut().enumerate() {
                if g != 1 {
                    stage.waypoint = [g as u8; 32];
                    stage.path.clear();
                }
            }
            book.finish(&mut stages, 104, |_| {
                Ok((102..=104).map(|height| (tip_at(height), height)).collect())
            })
            .unwrap();
            assert_eq!(gets(&script), 3, "unknown is asked at 102, 103, and 104");
            assert_eq!(stages[1].path, b"");
            let lines = wait.retry_lines();
            assert!(
                lines
                    .iter()
                    .all(|line| !line.contains("path_skip call_site")),
                "{lines:?}"
            );
            assert!(
                lines
                    .iter()
                    .all(|line| !line.contains("reason=not_included")),
                "{lines:?}"
            );
            let first_skip = lines
                .iter()
                .position(|line| line.starts_with("path_skip "))
                .expect("final path_skip step");
            assert!(lines[..first_skip]
                .iter()
                .all(|line| !line.contains("path_skip")));
            assert_eq!(
                lines.iter().filter(|line| *line == "path_skip 1").count(),
                1
            );
            let final_at = lines
                .iter()
                .position(|line| line == "not fetched by design 17")
                .expect("w17 line");
            assert!(final_at > first_skip);
            assert_eq!(
                lines
                    .iter()
                    .filter(|line| *line == "not fetched by design 17")
                    .count(),
                1
            );
        });
    }

    #[test]
    fn the_five_path_miss_bodies_are_not_one_soft_class() {
        let (script, action) = script_live(LiveGet::Http {
            status: 404,
            body: Vec::new(),
        });
        with_actions(action, |base| {
            let _wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let cases = [
                ("still in the pool", false),
                ("sealed but not in a block yet", false),
                ("not included", true),
                ("already archived after it was served", false),
                ("unknown", false),
            ];
            for (body, stores_empty) in cases {
                let posts_before = posts(&script);
                let gets_before = gets(&script);
                set_reply(&script, 404, body.as_bytes());
                let mut book = fresh_book(base);
                book.on_tip(tip_at(101), 101).unwrap();
                assert_eq!(gets(&script), gets_before, "{body} asked before H+2");
                if stores_empty || body == "unknown" || body.contains("archived") {
                    drive(&mut book, 102..=105);
                    assert_eq!(gets(&script), gets_before + 3, "{body}");
                    assert!(matches!(
                        book.watches[0].state,
                        WatchState::Done(PathFetch::Skipped)
                    ));
                } else {
                    book.on_tip(tip_at(102), 102).unwrap();
                    assert!(matches!(
                        book.watches[0].state,
                        WatchState::Waiting { at: 102 }
                    ));
                    book.on_tip(tip_at(103), 103).unwrap();
                    book.on_tip(tip_at(104), 104).unwrap();
                    book.on_tip(tip_at(105), 105).unwrap();
                    assert_eq!(gets(&script), gets_before + 3, "{body}");
                    assert!(
                        matches!(book.watches[0].state, WatchState::Done(PathFetch::Skipped)),
                        "{body} left {:?}",
                        book.watches[0].state
                    );
                }
                assert_eq!(posts(&script), posts_before + 1, "{body}");
            }
        });
    }

    #[test]
    fn the_ask_window_follows_the_stage_beacon_not_the_hashcash_tip() {
        let body = path_at(102);
        let (script, action) = script_live(LiveGet::Http {
            status: 200,
            body: body.clone(),
        });
        with_actions(action, |base| {
            let _wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = LivePathBook::new(produce_http_agent(), base);
            book.post(&LIVE_WAYPOINT, &tip_at(101), 101, 100, 8)
                .unwrap();
            assert_eq!(
                book.watches[0].post_height,
                crate::consts::anchor_window_origin(100)
            );
            assert_ne!(book.watches[0].post_height, 101);
            book.on_tip(tip_at(102), 102).unwrap();
            assert_eq!(
                gets(&script),
                1,
                "the first ask is the beacon height plus 2"
            );
            match &book.watches[0].state {
                WatchState::Done(PathFetch::Body(stored)) => assert_eq!(stored, &body),
                _ => panic!("expected the path from the beacon window"),
            }
        });
    }

    #[test]
    fn an_unposted_stage_is_not_a_missing_plant() {
        let (script, action) = script_live(LiveGet::Http {
            status: 404,
            body: b"not included".to_vec(),
        });
        with_actions(action, |base| {
            let _wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = fresh_book(base);
            let mut stages = [
                crate::object::Stage {
                    beacon: [9; 32],
                    beacon_height: 1,
                    leg_count: 1,
                    waypoint: [0x11; 32],
                    path: b"keep".to_vec(),
                    checkpoints: Vec::new(),
                    leg_results: Vec::new(),
                    checkpoint_rows_defined: true,
                    leg_result_missing: Vec::new(),
                },
                stage_for_live(),
            ];
            drive(&mut book, 101..=103);
            book.finish(&mut stages, u64::MAX, |_| Ok(vec![(tip_at(104), 104)]))
                .unwrap();
            assert_eq!(stages[0].path, b"keep");
            assert_eq!(stages[1].path, b"");
            assert_eq!(gets(&script), 3);
        });
    }

    #[test]
    fn not_included_at_the_due_header_stores_an_empty_path() {
        let (script, action) = script_live(LiveGet::Http {
            status: 404,
            body: b"not included".to_vec(),
        });
        with_actions(action, |base| {
            let wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = fresh_book(base);
            let mut stage = stage_for_live();
            drive(&mut book, 101..=103);
            book.finish(std::slice::from_mut(&mut stage), u64::MAX, |_| {
                Ok(vec![(tip_at(104), 104)])
            })
            .unwrap();
            drive(&mut book, [105]);
            assert_eq!(stage.path, b"");
            assert_eq!(stage.leg_count, 3);
            assert_eq!(gets(&script), 3);
            assert_eq!(posts(&script), 1);
            assert!(wait.retry_lines().iter().any(|line| {
                line.contains("path_fetch call_site=fetch_path")
                    && line.contains("due_height=104")
                    && line.contains("tip_height=104")
            }));
            assert!(wait.retry_lines().iter().all(
                |line| !line.starts_with("path_skip ") && !line.contains("reason=not_included")
            ));
            no_stagger(&wait);
        });
    }

    #[test]
    fn an_archived_miss_is_asked_through_the_window_and_stores_empty() {
        let (script, action) = script_live(LiveGet::Http {
            status: 404,
            body: b"already archived after it was served".to_vec(),
        });
        with_actions(action, |base| {
            let _wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = fresh_book(base);
            let mut stage = stage_for_live();
            drive(&mut book, 101..=104);
            book.finish(std::slice::from_mut(&mut stage), u64::MAX, |_| {
                Ok(Vec::new())
            })
            .unwrap();
            assert_eq!(stage.path, b"");
            assert_eq!(gets(&script), 3);
            assert!(matches!(
                book.watches[0].state,
                WatchState::Done(PathFetch::Skipped)
            ));
        });
    }

    #[test]
    fn an_unknown_404_is_asked_through_the_window_and_stores_empty() {
        let (script, action) = script_live(LiveGet::Http {
            status: 404,
            body: b"unknown".to_vec(),
        });
        with_actions(action, |base| {
            let _wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 0);
            let mut book = LivePathBook::new(produce_http_agent(), base);
            book.post(&LIVE_WAYPOINT, &tip_at(101), 101, 100, 8)
                .unwrap();
            let mut stage = stage_for_live();
            book.finish(std::slice::from_mut(&mut stage), u64::MAX, |_| {
                Ok((102..=104).map(|height| (tip_at(height), height)).collect())
            })
            .unwrap();
            assert_eq!(gets(&script), 3, "unknown is asked at 102, 103, and 104");
            assert_eq!(stage.path, b"");
        });
    }

    #[test]
    fn a_failed_request_is_not_an_empty_path_and_is_not_repeated() {
        let (script, action) = script_live(LiveGet::Http {
            status: 503,
            body: Vec::new(),
        });
        with_actions(action, |base| {
            let wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 60_000);
            let mut book = fresh_book(base);
            book.on_tip(tip_at(101), 101).unwrap();
            book.on_tip(tip_at(102), 102).unwrap();
            assert_eq!(gets(&script), 1);
            book.on_tip(tip_at(103), 103).unwrap();
            assert_eq!(
                gets(&script),
                2,
                "a failed ask does not stop the later header"
            );
            set_reply(&script, 200, path_at(102));
            let mut reset_book = fresh_book(base);
            *script.reply.lock().unwrap() = LiveGet::Reset;
            reset_book.on_tip(tip_at(101), 101).unwrap();
            let gets_before = gets(&script);
            reset_book.on_tip(tip_at(102), 102).unwrap();
            assert_eq!(gets(&script), gets_before + 1);
            reset_book.on_tip(tip_at(103), 103).unwrap();
            assert_eq!(gets(&script), gets_before + 2);
            assert_eq!(posts(&script), 2);
            assert!(wait
                .retry_lines()
                .iter()
                .all(|line| !line.starts_with("path_skip ")));
            no_stagger(&wait);
        });
    }

    #[test]
    fn a_200_is_stored_only_after_it_checks_as_a_path_for_that_waypoint() {
        let (script, action) = script_live(LiveGet::Http {
            status: 200,
            body: b"not-a-path".to_vec(),
        });
        with_actions(action, |base| {
            let _wait = FastWait::install();
            let _caps = DialGuard::install(2, 2, 2, 30_000, 0);
            let bad = [
                (b"not-a-path".to_vec(), "not a path"),
                (
                    crate::pathbody::encode_path_in_block(&[0x99; 32], 102),
                    "not a path",
                ),
                (path_at(101), "outside the anchor window"),
                (path_at(105), "outside the anchor window"),
                (path_at(123_456), "outside the anchor window"),
            ];
            for (body, needle) in bad {
                set_reply(&script, 200, body);
                let _ = needle;
                let mut book = fresh_book(base);
                let mut stage = stage_for_live();
                book.on_tip(tip_at(101), 101).unwrap();
                book.on_tip(tip_at(102), 102).unwrap();
                book.finish(std::slice::from_mut(&mut stage), u64::MAX, |_| {
                    Ok((103..=104).map(|height| (tip_at(height), height)).collect())
                })
                .unwrap();
                assert_eq!(stage.path, b"", "{needle} is not stored");
            }
            for height in [102u32, 103, 104] {
                let body = path_at(height);
                set_reply(&script, 200, body.clone());
                let gets_before = gets(&script);
                let mut book = fresh_book(base);
                let mut stage = stage_for_live();
                drive(&mut book, 101..=104);
                book.finish(std::slice::from_mut(&mut stage), u64::MAX, |_| {
                    Ok(Vec::new())
                })
                .unwrap();
                assert_eq!(stage.path, body);
                assert_eq!(
                    gets(&script),
                    gets_before + 1,
                    "a path at H+2 is not asked again"
                );
            }
        });
    }

    #[test]
    fn unset_produce_dials_match_the_short_lock() {
        assert_eq!(DEFAULT_PATH_QUICK_CHECKS, 2);
        assert_ne!(DEFAULT_PATH_QUICK_CHECKS, 1_440);
        assert_eq!(DEFAULT_PATH_TRY_BUDGET_MS, 30_000);
        assert_eq!(DEFAULT_PATH_STAGGER_MS, 60_000);
        assert_eq!(DEFAULT_PATH_JITTER_MIN_MS, 5_000);
        assert_eq!(DEFAULT_PATH_JITTER_MAX_MS, 30_000);
        assert_eq!(DEFAULT_PLANT_WINDOW_TIPS, 3);
        assert_eq!(DEFAULT_PRODUCE_TRANSIENT_WAITS, 2);
        assert_eq!(DEFAULT_WAYPOINT_HTTP_400_WAITS, 2);
        assert_eq!(DEFAULT_PRODUCE_HTTP_TIMEOUT_MS, 1_000);
        assert_eq!(parse_u32_dial(None, 2), 2);
        assert_eq!(parse_u32_dial(Some(""), 2), 2);
        assert_eq!(parse_u32_dial(Some("nope"), 2), 2);
        assert_eq!(parse_u32_dial(Some("0"), 2), 0);
    }
}
