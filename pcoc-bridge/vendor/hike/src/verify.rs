//! Section 14. Records measurements and does not judge them.
//!
//! Order: shape, checkpoints, waypoints, leg rate, paths included, then six
//! recomputed legs in parallel. Section 15 ([`crate::admit`]) is the only
//! admission judgment.

use crate::audit::{draw_distinct, Entropy, OsEntropy, PinnedEntropy};
use crate::bind::successor_waypoint_sized;
use crate::clock::IndexBeacon;
use crate::consts::{bind_threads_from, height_in_anchor_window, Sizes, STAGES};
use crate::object::{earlier_object_kind, read_measured_object_sized, Hike, Stage};
use crate::pathbody::{path_locators, validate_path};
use crate::terrain::{fill_pin, fill_terrain_sized, production_seed};
use crate::walk::leg_result_sized;
use rayon::prelude::*;
use sha2::{Digest, Sha256};

/// How many legs section 14.6 draws. Not the thread-pool size.
const REWALK_COUNT: usize = 6;
/// Pool size for those recomputed legs. Not the draw count, and not the host CPU count.
const REWALK_THREADS: usize = 6;

pub enum AuditDraw {
    OsCsprng,
    PinnedSeed([u8; 32]),
}

/// A measurement that had no usable value, or the value that was recorded.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Recorded<T> {
    Obtained(T),
    NotObtained,
}

/// Section 14.2. `mismatch` is true when any beacon or any digest mismatches.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CheckpointMeasurement {
    /// Stage 0 first. True when that stage's beacon matches the index.
    pub beacon_matches_index: Vec<bool>,
    /// Stage 0 first. Each inner entry is one leg, leg 0 first.
    /// True when the stored checkpoint matches the recomputed digest.
    pub digest_matches: Vec<Vec<bool>>,
    pub mismatch: bool,
}

/// Section 13.1 window record for one of `W_1` … `W_16`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Anchor {
    Anchored,
    NotAnchored,
}

impl Anchor {
    pub fn token(self) -> &'static str {
        match self {
            Anchor::Anchored => "anchored",
            Anchor::NotAnchored => "not anchored",
        }
    }
}

/// Plant read for one present path. `Err(())` from an older callback is [`ChainRead::Mismatch`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ChainRead {
    Match,
    Mismatch,
    NotRead,
}

impl From<Result<(), ()>> for ChainRead {
    fn from(value: Result<(), ()>) -> Self {
        match value {
            Ok(()) => ChainRead::Match,
            Err(()) => ChainRead::Mismatch,
        }
    }
}

/// `Current` is this file's `KIND`. `Earlier` is Rule A5.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ObjectLineage {
    Current,
    Earlier(String),
}

/// Section 14.3.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WaypointMeasurement {
    pub seed_value_matches: bool,
    /// `successor_differs[g]` is the recomputed successor of stage `g` against
    /// `stages[g + 1].waypoint`, for `g` from 0 through 16.
    /// `Some(true)` is `mismatch`, `Some(false)` is `match`, and `None` is
    /// `not defined` (the stage bytes are absent or unusable).
    pub successor_differs: Vec<Option<bool>>,
    pub absent_paths: u64,
    pub present_path_does_not_match_plant: bool,
    /// One token per `W_1` … `W_16`, in order. Section 13.1.
    pub anchors: Vec<Anchor>,
}

/// Section 14.4. The closing header is not stored, so the rate is not read.
///
/// This is not the stored-beacon table. That table is [`StageReport`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LegRate {
    NotRead,
}

/// Miner time of one stored beacon, from `header.time` (Bitcoin `nTime`, unix seconds).
///
/// Absent `header.time` is [`StageClock::NotRead`]. `header.timestamp_unix_ms`
/// is Bari's observation and is not this clock.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StageClock {
    NotRead,
    /// Bitcoin header `nTime`, unix seconds.
    UnixS(u64),
}

/// Gap from one stored beacon's header `nTime` to the next stored beacon's.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StoredGap {
    NotRead,
    /// The stage has no next stored beacon, or the two header times are not in order.
    NotDefined,
    /// Difference of the two `header.time` values, unix seconds.
    Seconds(u64),
}

/// Legs divided by [`StoredGap::Seconds`]. Not a 600 second block.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StoredRate {
    NotRead,
    NotDefined,
    /// Thousandths of a leg per second. 215 is 0.215.
    Thousandths(u64),
}

/// One stage of the stored-beacon table. The last stage's rate is not defined.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StageMeasurement {
    /// Object index. Stage 1 on a report is index 0.
    pub index: u64,
    pub beacon_height: u64,
    /// Bitcoin header `nTime` in unix seconds. Not Bari's observation.
    pub miner_time: StageClock,
    /// Bari's wall-clock observation (`header.timestamp_unix_ms`), unix
    /// milliseconds. `None` when that field was not an integer. Not used as
    /// the miner clock or the gap.
    pub observed_unix_ms: Option<u64>,
    /// Height of the next stored beacon. `None` on the last stage.
    pub next_stored_beacon: Option<u64>,
    pub gap: StoredGap,
    pub leg_count: u64,
    pub leg_rate: StoredRate,
}

impl StoredGap {
    pub fn label(&self) -> String {
        match self {
            StoredGap::NotRead => "not read".to_owned(),
            StoredGap::NotDefined => "not defined".to_owned(),
            StoredGap::Seconds(secs) => secs.to_string(),
        }
    }
}

impl StoredRate {
    pub fn label(&self) -> String {
        match self {
            StoredRate::NotRead => "not read".to_owned(),
            StoredRate::NotDefined => "not defined".to_owned(),
            StoredRate::Thousandths(thousandths) => {
                format!("{}.{:03}", thousandths / 1000, thousandths % 1000)
            }
        }
    }
}

/// Per-stage stored-beacon measurements. Not section 14.4, and not section 15.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StageReport {
    NotRead,
    Read(Vec<StageMeasurement>),
}

/// Section 14.6.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SixLegs {
    /// No usable leg total.
    NotObtained,
    /// Fewer than 6 legs. The draw was not started.
    CouldNotDraw,
    /// The byte source failed before 6 distinct indices were obtained.
    DrawNotObtained,
    Rewalked {
        /// Global indices in the order drawn.
        indices: Vec<u64>,
        /// Same order. True when the recomputed leg result equals the stored leg result.
        matches: Vec<bool>,
    },
}

/// The section 14 record. This struct does not say whether admission passes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Measurements {
    pub version: ObjectLineage,
    pub shape: bool,
    pub checkpoints: Recorded<CheckpointMeasurement>,
    pub waypoints: Recorded<WaypointMeasurement>,
    pub leg_rate: LegRate,
    pub paths_included: Recorded<u64>,
    pub six_legs: SixLegs,
    /// Stored-beacon gaps. Section 14.4 stays [`LegRate::NotRead`].
    pub stages: StageReport,
    /// Section 14 JSON object, without the trailing newline. Empty on a hand-built fixture.
    pub record_body: String,
}

fn total_legs(hike: &Hike) -> Option<u64> {
    let mut n = 0u64;
    for stage in &hike.stages {
        n = n.checked_add(stage.leg_count)?;
    }
    Some(n)
}

/// `None` means fewer than 6 legs: do not draw.
fn draw_count(n: u64) -> Option<usize> {
    if n < REWALK_COUNT as u64 {
        None
    } else {
        Some(REWALK_COUNT)
    }
}

fn locate(hike: &Hike, q: u64) -> (usize, usize) {
    let mut start = 0u64;
    for (g, stage) in hike.stages.iter().enumerate() {
        let len = stage.leg_count;
        if q < start + len {
            return (g, (q - start) as usize);
        }
        start += len;
    }
    unreachable!("global index is inside the hike");
}

fn terrain_of(hike: &Hike, sizes: Sizes) -> Vec<u8> {
    fill_terrain_sized(
        fill_pin(&production_seed(&hike.public_key, &hike.seed_beacon)),
        sizes,
    )
}

fn beacon_records<F>(
    hike: &Hike,
    beacon_at: &mut F,
) -> (
    Vec<bool>,
    Vec<Option<u64>>,
    Vec<Option<u64>>,
    Vec<crate::section14::HeaderFact>,
)
where
    F: FnMut(u64) -> Result<IndexBeacon, ()>,
{
    let mut matches = Vec::with_capacity(hike.stages.len());
    let mut header_times_s = Vec::with_capacity(hike.stages.len());
    let mut observed_unix_ms = Vec::with_capacity(hike.stages.len());
    let mut headers = Vec::with_capacity(19);
    for stage in &hike.stages {
        let (fact, observed, stage_time) =
            read_header(Some(stage.beacon), Some(stage.beacon_height), beacon_at);
        matches.push(matches!(fact.cmp, crate::section14::IndexCmp::Match { .. }));
        header_times_s.push(stage_time);
        observed_unix_ms.push(observed);
        headers.push(fact);
    }
    if headers.len() > 18 {
        headers.truncate(18);
    }
    while headers.len() < 18 {
        headers.push(crate::section14::HeaderFact {
            stored_hash: None,
            stored_height: None,
            cmp: crate::section14::IndexCmp::NotRead,
        });
    }
    let closing_hash = (!hike.last_interrupt_hash_absent).then_some(hike.last_interrupt);
    let closing_height = (!hike.last_interrupt_height_absent).then_some(hike.last_interrupt_height);
    let closing = read_header(closing_hash, closing_height, beacon_at).0;
    headers.push(closing);
    (matches, header_times_s, observed_unix_ms, headers)
}

/// One stored header. The stage table still keeps a successful document's
/// `header.time` when the hash does not agree. The section 14 `T` does not.
fn read_header<F>(
    hash: Option<[u8; 32]>,
    height: Option<u64>,
    beacon_at: &mut F,
) -> (crate::section14::HeaderFact, Option<u64>, Option<u64>)
where
    F: FnMut(u64) -> Result<IndexBeacon, ()>,
{
    use crate::section14::{HeaderFact, IndexCmp};
    let stored_height = height.filter(|height| *height <= crate::consts::JSON_INT_MAX);
    let Some(height) = stored_height else {
        return (
            HeaderFact {
                stored_hash: hash,
                stored_height: None,
                cmp: IndexCmp::NotRead,
            },
            None,
            None,
        );
    };
    let Some(hash) = hash else {
        return (
            HeaderFact {
                stored_hash: None,
                stored_height: Some(height),
                cmp: IndexCmp::NotRead,
            },
            None,
            None,
        );
    };
    match beacon_at(height) {
        Err(()) => (
            HeaderFact {
                stored_hash: Some(hash),
                stored_height: Some(height),
                cmp: IndexCmp::NotRead,
            },
            None,
            None,
        ),
        Ok(beacon) => {
            let time = beacon
                .header_time_s
                .filter(|time| *time <= u64::from(u32::MAX));
            let cmp = if beacon.natural == hash
                && beacon.height == height
                && beacon.height <= crate::consts::JSON_INT_MAX
            {
                IndexCmp::Match {
                    height: beacon.height,
                    time,
                }
            } else {
                IndexCmp::Mismatch
            };
            (
                HeaderFact {
                    stored_hash: Some(hash),
                    stored_height: Some(height),
                    cmp,
                },
                beacon.timestamp_unix_ms,
                beacon.header_time_s,
            )
        }
    }
}

fn bind_pool(threads: usize) -> rayon::ThreadPool {
    rayon::ThreadPoolBuilder::new()
        .num_threads(threads.max(1))
        .build()
        .expect("checkpoint binding pool")
}

fn configured_bind_threads() -> usize {
    bind_threads_from(std::env::var("PCOC_BIND_THREADS").ok().as_deref())
}

/// `SHA-256(public_key ‖ seed_beacon)`. Stage 0’s checkpoint limb and its
/// `j = 0` previous. Not the stored `stages[0].waypoint`.
fn computed_seed(hike: &Hike) -> [u8; 32] {
    crate::sha::sha256(&production_seed(&hike.public_key, &hike.seed_beacon))
}

/// Rule A4.3. `j = 0` on stage 0 is the computed seed. `j = 0` on stage
/// `g ≥ 1` is the stored waypoint. A later leg uses `checkpoints[j − 1]`.
fn binding_previous(stage: &Stage, j: usize, stage_index: usize, seed: &[u8; 32]) -> [u8; 32] {
    if j == 0 {
        if stage_index == 0 {
            *seed
        } else {
            stage.waypoint
        }
    } else {
        stage.checkpoints[j - 1]
    }
}

/// Rule A4.3 stage limb. The computed seed on stage 0, the stored waypoint
/// on stage `g ≥ 1`. It is not `previous` when `j ≥ 1`.
fn stage_limb(stage: &Stage, stage_index: usize, seed: &[u8; 32]) -> [u8; 32] {
    if stage_index == 0 {
        *seed
    } else {
        stage.waypoint
    }
}

/// Rule A4.3 digest. The four limbs are hashed in preimage order from the
/// terrain window. No sample buffer is allocated.
fn binding_digest(
    terrain: &[u8],
    previous: &[u8; 32],
    limb: &[u8; 32],
    leg: &[u8; 8],
    sizes: Sizes,
) -> [u8; 32] {
    let offset =
        (u64::from_le_bytes(previous[..8].try_into().unwrap()) % sizes.offset_modulus()) as usize;
    let mut hasher = Sha256::new();
    hasher.update(previous);
    hasher.update(limb);
    hasher.update(&terrain[offset..offset + sizes.sample_bytes]);
    hasher.update(leg);
    let digest = hasher.finalize();
    let mut out = [0u8; 32];
    out.copy_from_slice(&digest);
    out
}

fn digest_records(
    hike: &Hike,
    terrain: Option<&[u8]>,
    threads: usize,
    sizes: Sizes,
) -> Vec<Vec<bool>> {
    let seed = computed_seed(hike);
    let mut out = Vec::with_capacity(hike.stages.len());
    let pool = bind_pool(threads);
    for (stage_index, stage) in hike.stages.iter().enumerate() {
        if !stage.checkpoint_rows_defined || stage.checkpoints.is_empty() {
            out.push(Vec::new());
            continue;
        }
        let terrain = terrain.expect("a stored checkpoint is checked against terrain");
        let legs = pool.install(|| {
            (0..stage.checkpoints.len())
                .into_par_iter()
                .map(|j| {
                    if stage.leg_result_missing.get(j).copied().unwrap_or(false)
                        || j >= stage.leg_results.len()
                    {
                        return false;
                    }
                    let previous = binding_previous(stage, j, stage_index, &seed);
                    let limb = stage_limb(stage, stage_index, &seed);
                    binding_digest(terrain, &previous, &limb, &stage.leg_results[j], sizes)
                        == stage.checkpoints[j]
                })
                .collect()
        });
        out.push(legs);
    }
    out
}

fn checkpoint_mismatch(beacon_matches_index: &[bool], digest_matches: &[Vec<bool>]) -> bool {
    beacon_matches_index.iter().any(|matches| !matches)
        || digest_matches.iter().flatten().any(|matches| !matches)
}

/// The successor loop reads stages 0 through 17. Fewer than 18 stages leaves
/// that input missing. Nineteen or more stages still have those inputs.
fn successor_inputs_present(hike: &Hike) -> bool {
    hike.stages.len() >= STAGES
}

fn interrupt_header(hike: &Hike, hash: &[u8; 32], height: u64) -> bool {
    let stored = hike
        .stages
        .iter()
        .skip(1)
        .any(|stage| stage.beacon == *hash && stage.beacon_height == height);
    let closing = !hike.last_interrupt_absent
        && hike.last_interrupt == *hash
        && hike.last_interrupt_height == height;
    stored || closing
}

struct BoundPath {
    fact: crate::section14::PathFact,
    anchor: Anchor,
    differs: bool,
}

/// Section 13.1. `W_1` … `W_16` ask `path_chain` only after the window and the
/// stored interrupt header both match. `W_17` is not asked.
fn bound_path<C, R>(hike: &Hike, g: usize, path_chain: &mut C) -> BoundPath
where
    C: FnMut(&crate::pathbody::PathLocators) -> R,
    R: Into<ChainRead>,
{
    use crate::section14::{PathFact, Plant};
    let absent = PathFact {
        stage_present: false,
        empty: true,
        plant_read: false,
        plant: Plant::NotRead,
        block_hash: None,
        block_height: None,
    };
    if hike.stages.len() <= g {
        return BoundPath {
            fact: absent,
            anchor: Anchor::NotAnchored,
            differs: false,
        };
    }
    let stage = &hike.stages[g];
    if stage.path.is_empty() {
        return BoundPath {
            fact: PathFact {
                stage_present: true,
                empty: true,
                plant_read: false,
                plant: Plant::NotApplicable,
                block_hash: None,
                block_height: None,
            },
            anchor: Anchor::NotAnchored,
            differs: false,
        };
    }
    if validate_path(&stage.path, &stage.waypoint).is_err() {
        return BoundPath {
            fact: PathFact {
                stage_present: true,
                empty: false,
                plant_read: false,
                plant: Plant::Mismatch,
                block_hash: None,
                block_height: None,
            },
            anchor: Anchor::NotAnchored,
            differs: true,
        };
    }
    let Ok(locators) = path_locators(&stage.path) else {
        return BoundPath {
            fact: PathFact {
                stage_present: true,
                empty: false,
                plant_read: false,
                plant: Plant::Mismatch,
                block_hash: None,
                block_height: None,
            },
            anchor: Anchor::NotAnchored,
            differs: true,
        };
    };
    let height = u64::from(locators.block_height);
    if g == 17 {
        return BoundPath {
            fact: PathFact {
                stage_present: true,
                empty: false,
                plant_read: false,
                plant: Plant::NotApplicable,
                block_hash: Some(locators.block_hash),
                block_height: Some(height),
            },
            anchor: Anchor::NotAnchored,
            differs: false,
        };
    }
    if !height_in_anchor_window(stage.beacon_height, height)
        || !interrupt_header(hike, &locators.block_hash, height)
    {
        return BoundPath {
            fact: PathFact {
                stage_present: true,
                empty: false,
                plant_read: false,
                plant: Plant::NotRead,
                block_hash: Some(locators.block_hash),
                block_height: Some(height),
            },
            anchor: Anchor::NotAnchored,
            differs: false,
        };
    }
    let (plant, anchor, differs) = match path_chain(&locators).into() {
        ChainRead::Match => (Plant::Match, Anchor::Anchored, false),
        ChainRead::Mismatch => (Plant::Mismatch, Anchor::NotAnchored, true),
        ChainRead::NotRead => (Plant::NotRead, Anchor::NotAnchored, false),
    };
    BoundPath {
        fact: PathFact {
            stage_present: true,
            empty: false,
            plant_read: true,
            plant,
            block_hash: Some(locators.block_hash),
            block_height: Some(height),
        },
        anchor,
        differs,
    }
}

fn bound_paths<C, R>(hike: &Hike, path_chain: &mut C) -> Vec<BoundPath>
where
    C: FnMut(&crate::pathbody::PathLocators) -> R,
    R: Into<ChainRead>,
{
    (1..=17).map(|g| bound_path(hike, g, path_chain)).collect()
}

fn waypoint_records(
    hike: &Hike,
    terrain: &[u8],
    paths: &[BoundPath],
    sizes: Sizes,
) -> WaypointMeasurement {
    // `W_1` uses the stored field. The computed seed is the seed-value comparison.
    let seed_value = computed_seed(hike);
    let seed_value_matches = hike
        .stages
        .first()
        .is_some_and(|stage| stage.waypoint == seed_value);
    let mut successor_differs = Vec::with_capacity(STAGES - 1);
    for g in 0..STAGES - 1 {
        // A missing stage of the pair is `not defined`. The record still has 17 rows.
        if hike.stages.len() <= g + 1 {
            successor_differs.push(None);
            continue;
        }
        let stage = &hike.stages[g];
        // I_g is the next stage's beacon, not the beacon this stage started from.
        let interrupt = hike.stages[g + 1].beacon;
        let waypoint = successor_waypoint_sized(
            terrain,
            &interrupt,
            &stage.waypoint,
            &stage.checkpoints,
            sizes,
        );
        successor_differs.push(Some(waypoint != hike.stages[g + 1].waypoint));
    }
    let mut absent_paths = 0u64;
    let mut present_path_does_not_match_plant = false;
    for stage in &hike.stages {
        if stage.path.is_empty() {
            absent_paths += 1;
        }
    }
    let mut anchors = Vec::with_capacity(16);
    for path in paths.iter().take(16) {
        if path.differs {
            present_path_does_not_match_plant = true;
        }
        anchors.push(path.anchor);
    }
    WaypointMeasurement {
        seed_value_matches,
        successor_differs,
        absent_paths,
        present_path_does_not_match_plant,
        anchors,
    }
}

fn count_paths_included(hike: &Hike) -> u64 {
    hike.stages
        .iter()
        .filter(|stage| !stage.path.is_empty())
        .count() as u64
}

fn rewalk_pool() -> rayon::ThreadPool {
    rayon::ThreadPoolBuilder::new()
        .num_threads(REWALK_THREADS)
        .build()
        .expect("recomputed-leg pool of 6 threads")
}

fn rewalk(hike: &Hike, terrain: &[u8], indices: &[u64], sizes: Sizes) -> Vec<bool> {
    let seed = computed_seed(hike);
    let pool = rewalk_pool();
    pool.install(|| {
        indices
            .par_iter()
            .map(|&q| {
                let (g, j) = locate(hike, q);
                let stage = &hike.stages[g];
                // Section 14.6. Stage 0’s leg-0 start is the computed seed.
                let previous = binding_previous(stage, j, g, &seed);
                if stage.leg_result_missing.get(j).copied().unwrap_or(false)
                    || j >= stage.leg_results.len()
                {
                    return false;
                }
                let stored = u64::from_le_bytes(stage.leg_results[j]);
                leg_result_sized(terrain, &previous, sizes) == stored
            })
            .collect()
    })
}

fn measure_six(hike: &Hike, terrain: &[u8], source: &mut dyn Entropy, sizes: Sizes) -> SixLegs {
    let Some(n) = total_legs(hike) else {
        return SixLegs::NotObtained;
    };
    let Some(k) = draw_count(n) else {
        return SixLegs::CouldNotDraw;
    };
    let indices = match draw_distinct(n, k, source) {
        Ok(indices) => indices,
        Err(()) => return SixLegs::DrawNotObtained,
    };
    let matches = rewalk(hike, terrain, &indices, sizes);
    SixLegs::Rewalked { indices, matches }
}

fn entropy(draw: AuditDraw) -> Box<dyn Entropy> {
    match draw {
        AuditDraw::OsCsprng => Box::new(OsEntropy),
        AuditDraw::PinnedSeed(seed) => Box::new(PinnedEntropy::new(seed)),
    }
}

/// Record section 14, and the stored-beacon stage table.
///
/// `beacon_at` returns the natural beacon, `header.height`, `header.time`
/// (Bitcoin `nTime`, unix seconds) when that field is an integer, and
/// `header.timestamp_unix_ms` (Bari's wall-clock observation) when that field
/// is an integer, from `GET {index-url}/index/{beacon_height}`. `Err` is an
/// unsuccessful read. A missing `header.time` stays unread. The observation
/// does not fill it.
/// The callback reads each stored header, including `last_interrupt` when it
/// is present. It does not invent a height the object did not store.
///
/// `path_chain` is the chain bind for one present path whose `validate_for`
/// succeeded. An empty path is not passed to it.
///
/// Section 14.4 stays [`LegRate::NotRead`]. The stage table is a separate
/// measurement. It is not section 15.
fn earlier_measurements(kind: String) -> Measurements {
    Measurements {
        version: ObjectLineage::Earlier(kind),
        shape: false,
        checkpoints: Recorded::NotObtained,
        waypoints: Recorded::NotObtained,
        leg_rate: LegRate::NotRead,
        paths_included: Recorded::NotObtained,
        six_legs: SixLegs::NotObtained,
        stages: StageReport::NotRead,
        record_body: String::new(),
    }
}

/// Record section 14. Rule A4.3 uses [`configured_bind_threads`].
pub fn verify<F, C, R>(bytes: &[u8], draw: AuditDraw, beacon_at: F, path_chain: C) -> Measurements
where
    F: FnMut(u64) -> Result<IndexBeacon, ()>,
    C: FnMut(&crate::pathbody::PathLocators) -> R,
    R: Into<ChainRead>,
{
    verify_with_bind_threads_sized(
        bytes,
        draw,
        beacon_at,
        path_chain,
        configured_bind_threads(),
        Sizes::production(),
    )
}

/// Same record as [`verify`], measured with `sizes`.
///
/// Production callers pass [`Sizes::production`]. SPEC §16.1 `conformance-small`
/// passes [`Sizes::conformance_small`]. The produce path does not call this
/// with the small set.
pub fn verify_sized<F, C, R>(
    bytes: &[u8],
    draw: AuditDraw,
    beacon_at: F,
    path_chain: C,
    sizes: Sizes,
) -> Measurements
where
    F: FnMut(u64) -> Result<IndexBeacon, ()>,
    C: FnMut(&crate::pathbody::PathLocators) -> R,
    R: Into<ChainRead>,
{
    verify_with_bind_threads_sized(
        bytes,
        draw,
        beacon_at,
        path_chain,
        configured_bind_threads(),
        sizes,
    )
}

/// Same record as [`verify`], with the Rule A4.3 pool size set by the caller.
pub fn verify_with_bind_threads<F, C, R>(
    bytes: &[u8],
    draw: AuditDraw,
    beacon_at: F,
    path_chain: C,
    bind_threads: usize,
) -> Measurements
where
    F: FnMut(u64) -> Result<IndexBeacon, ()>,
    C: FnMut(&crate::pathbody::PathLocators) -> R,
    R: Into<ChainRead>,
{
    verify_with_bind_threads_sized(
        bytes,
        draw,
        beacon_at,
        path_chain,
        bind_threads,
        Sizes::production(),
    )
}

/// Same record as [`verify_with_bind_threads`], measured with `sizes`.
pub fn verify_with_bind_threads_sized<F, C, R>(
    bytes: &[u8],
    draw: AuditDraw,
    mut beacon_at: F,
    mut path_chain: C,
    bind_threads: usize,
    sizes: Sizes,
) -> Measurements
where
    F: FnMut(u64) -> Result<IndexBeacon, ()>,
    C: FnMut(&crate::pathbody::PathLocators) -> R,
    R: Into<ChainRead>,
{
    if let Some(kind) = earlier_object_kind(bytes) {
        return earlier_measurements(kind);
    }
    let measured = match read_measured_object_sized(bytes, sizes) {
        Ok(item) => item,
        Err(fail) => {
            return Measurements {
                version: ObjectLineage::Current,
                shape: false,
                checkpoints: Recorded::NotObtained,
                waypoints: Recorded::NotObtained,
                leg_rate: LegRate::NotRead,
                paths_included: Recorded::NotObtained,
                six_legs: SixLegs::NotObtained,
                stages: StageReport::NotRead,
                record_body: crate::section14::undecoded_tail(&[fail.0]),
            };
        }
    };
    let shape = measured.shape;
    let hike = &measured.hike;

    let mut terrain: Option<Vec<u8>> = None;
    let (checkpoints, stage_times, observed_times, headers) = {
        let (beacon_matches_index, times, observed, headers) = beacon_records(hike, &mut beacon_at);
        let digests = hike
            .stages
            .iter()
            .any(|stage| !stage.checkpoints.is_empty());
        // Production keeps the historical guard: a short object does not allocate
        // the 256 MB terrain just to label missing waypoint pairs. SPEC §16.1
        // sizes are small, so a short object is measured in full.
        let measure_successors = if sizes.is_production() {
            successor_inputs_present(hike)
        } else {
            !hike.stages.is_empty()
        };
        if digests || measure_successors {
            terrain = Some(terrain_of(hike, sizes));
        }
        let digest_matches = digest_records(hike, terrain.as_deref(), bind_threads, sizes);
        let mismatch = checkpoint_mismatch(&beacon_matches_index, &digest_matches);
        (
            Recorded::Obtained(CheckpointMeasurement {
                beacon_matches_index,
                digest_matches,
                mismatch,
            }),
            Some(times),
            Some(observed),
            headers,
        )
    };

    let bound = bound_paths(hike, &mut path_chain);
    let measure_successors = if sizes.is_production() {
        successor_inputs_present(hike)
    } else {
        !hike.stages.is_empty()
    };
    let waypoints = if measure_successors {
        if terrain.is_none() {
            terrain = Some(terrain_of(hike, sizes));
        }
        let _terrain = terrain
            .as_deref()
            .expect("waypoint measurement recomputes terrain");
        Recorded::Obtained(waypoint_records(hike, _terrain, &bound, sizes))
    } else {
        Recorded::NotObtained
    };

    // Section 14.4 does not apply the leg-rate floor. `last_interrupt` is header 18.
    let leg_rate = LegRate::NotRead;
    let stages = StageReport::Read(stored_beacon_stages(
        hike,
        stage_times.as_deref().unwrap_or(&[]),
        observed_times.as_deref().unwrap_or(&[]),
    ));

    let paths_included = Recorded::Obtained(count_paths_included(hike));

    let six_legs = match total_legs(hike) {
        None => SixLegs::NotObtained,
        Some(n) if draw_count(n).is_none() => SixLegs::CouldNotDraw,
        Some(_) => {
            if terrain.is_none() {
                terrain = Some(terrain_of(hike, sizes));
            }
            let mut source = entropy(draw);
            measure_six(
                hike,
                terrain.as_deref().expect("a recomputed leg reads terrain"),
                source.as_mut(),
                sizes,
            )
        }
    };

    let seed_value = computed_seed(hike);
    let opening_match = hike
        .stages
        .first()
        .map(|stage| stage.waypoint == seed_value);
    let digest_rows = match &checkpoints {
        Recorded::Obtained(found) => Some(found.digest_matches.as_slice()),
        Recorded::NotObtained => None,
    };
    let successor = match &waypoints {
        Recorded::Obtained(found) => Some(found.successor_differs.as_slice()),
        Recorded::NotObtained => None,
    };
    let anchor_rows = match &waypoints {
        Recorded::Obtained(found) => found.anchors.clone(),
        Recorded::NotObtained => bound.iter().take(16).map(|path| path.anchor).collect(),
    };
    let counts: Vec<Option<u64>> = (0..STAGES)
        .map(|index| {
            hike.stages.get(index).and_then(|stage| {
                stage
                    .checkpoint_rows_defined
                    .then_some(stage.checkpoints.len() as u64)
            })
        })
        .collect();
    let rows_defined: Vec<bool> = (0..STAGES)
        .map(|index| {
            hike.stages
                .get(index)
                .is_some_and(|stage| stage.checkpoint_rows_defined)
        })
        .collect();
    let facts: Vec<crate::section14::PathFact> = bound.iter().map(|path| path.fact).collect();
    let notes = measured.shape_notes.as_slice();
    let record_body = crate::section14::record_tail(
        &crate::section14::RecordInput {
            shape,
            shape_notes: notes,
            headers: &headers,
            opening_match,
            digest_matches: digest_rows,
            checkpoint_rows_defined: &rows_defined,
            successor_differs: successor,
            paths: &facts,
            anchors: &anchor_rows,
            checkpoint_counts: &counts,
            six: &six_legs,
        },
        &mut beacon_at,
    );

    Measurements {
        version: ObjectLineage::Current,
        shape,
        checkpoints,
        waypoints,
        leg_rate,
        paths_included,
        six_legs,
        stages,
        record_body,
    }
}

/// The verify record `satoshi-hike-verify` writes. Rule A5 is the earlier-kind line.
/// A current object is the section 14 record. The profile key is first.
pub fn verify_record_json(profile: &str, measurements: &Measurements) -> String {
    if let ObjectLineage::Earlier(kind) = &measurements.version {
        return format!("{{\"version\":\"earlier version\",\"kind\":\"{kind}\"}}\n");
    }
    let mut line = String::from("{\"profile\":");
    line.push_str(&quoted(profile));
    if profile == "conformance-small" {
        let sizes = Sizes::conformance_small();
        line.push_str(&format!(
            ",\"sample_bytes\":{},\"slot_bytes\":{},\"terrain_bytes\":{},\"steps_per_leg\":{}",
            sizes.sample_bytes, sizes.slot_bytes, sizes.terrain_bytes, sizes.steps_per_leg
        ));
    }
    line.push_str(&measurements.record_body);
    if measurements.record_body.is_empty() {
        line.push('}');
    }
    line.push('\n');
    line
}

/// SHA-256 of the section 14 object under RFC 8785.
///
/// The trailing newline is part of the CLI file. It is not an input to this
/// hash. The hash input is the record without that newline, encoded with JCS.
pub fn verify_record_sha256(record: &str) -> Result<[u8; 32], String> {
    let body = record.strip_suffix('\n').unwrap_or(record);
    let value: serde_json::Value = serde_json::from_str(body).map_err(|err| err.to_string())?;
    let canonical = jcs(&value)?;
    Ok(crate::sha::sha256(&canonical))
}

fn jcs(value: &serde_json::Value) -> Result<Vec<u8>, String> {
    let mut out = String::new();
    write_jcs(value, &mut out)?;
    Ok(out.into_bytes())
}

fn write_jcs(value: &serde_json::Value, out: &mut String) -> Result<(), String> {
    use serde_json::Value;
    match value {
        Value::Null => Err("JSON null is not a canonical value".to_owned()),
        Value::Bool(true) => {
            out.push_str("true");
            Ok(())
        }
        Value::Bool(false) => {
            out.push_str("false");
            Ok(())
        }
        Value::Number(number) => {
            if let Some(value) = number.as_u64() {
                out.push_str(&value.to_string());
                return Ok(());
            }
            if let Some(value) = number.as_i64() {
                if value == 0 {
                    return Err("negative zero is not a canonical number".to_owned());
                }
                out.push_str(&value.to_string());
                return Ok(());
            }
            Err(format!("a fractional number is not canonical: {number}"))
        }
        Value::String(text) => {
            out.push('"');
            for ch in text.chars() {
                match ch {
                    '"' => out.push_str("\\\""),
                    '\\' => out.push_str("\\\\"),
                    c if (c as u32) <= 0x1f => out.push_str(&format!("\\u{:04x}", c as u32)),
                    c => out.push(c),
                }
            }
            out.push('"');
            Ok(())
        }
        Value::Array(items) => {
            out.push('[');
            for (index, item) in items.iter().enumerate() {
                if index > 0 {
                    out.push(',');
                }
                write_jcs(item, out)?;
            }
            out.push(']');
            Ok(())
        }
        Value::Object(map) => {
            let mut keys: Vec<&String> = map.keys().collect();
            keys.sort_by(|left, right| left.encode_utf16().cmp(right.encode_utf16()));
            out.push('{');
            for (index, key) in keys.iter().enumerate() {
                if index > 0 {
                    out.push(',');
                }
                out.push('"');
                for ch in key.chars() {
                    match ch {
                        '"' => out.push_str("\\\""),
                        '\\' => out.push_str("\\\\"),
                        c if (c as u32) <= 0x1f => out.push_str(&format!("\\u{:04x}", c as u32)),
                        c => out.push(c),
                    }
                }
                out.push('"');
                out.push(':');
                write_jcs(&map[*key], out)?;
            }
            out.push('}');
            Ok(())
        }
    }
}

fn quoted(text: &str) -> String {
    let mut out = String::from("\"");
    for ch in text.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            _ => out.push(ch),
        }
    }
    out.push('"');
    out
}

/// Stored-beacon gaps. `header_times_s[i]` is Bitcoin `nTime` of `stages[i]`
/// in unix seconds, or `None` when `header.time` was not read.
/// `observed_unix_ms[i]` is Bari's wall-clock observation. It is copied onto
/// the row and is not the gap. The gap for stage `i` uses stage `i + 1`'s
/// stored beacon. No other height is consulted. The last stage's rate is not defined.
fn stored_beacon_stages(
    hike: &Hike,
    header_times_s: &[Option<u64>],
    observed_unix_ms: &[Option<u64>],
) -> Vec<StageMeasurement> {
    let n = hike.stages.len();
    let mut rows = Vec::with_capacity(n);
    for (i, stage) in hike.stages.iter().enumerate() {
        let miner_time = match header_times_s.get(i).copied().flatten() {
            Some(secs) => StageClock::UnixS(secs),
            None => StageClock::NotRead,
        };
        let observed = observed_unix_ms.get(i).copied().flatten();
        let last = i + 1 == n;
        let (next_stored_beacon, gap, leg_rate) = if last {
            (None, StoredGap::NotDefined, StoredRate::NotDefined)
        } else {
            let next_height = hike.stages[i + 1].beacon_height;
            match (
                header_times_s.get(i).copied().flatten(),
                header_times_s.get(i + 1).copied().flatten(),
            ) {
                (Some(opened), Some(closed)) if closed > opened => {
                    let gap_s = closed - opened;
                    let rate = match rate_thousandths(stage.leg_count, gap_s) {
                        Some(thousandths) => StoredRate::Thousandths(thousandths),
                        None => StoredRate::NotDefined,
                    };
                    (Some(next_height), StoredGap::Seconds(gap_s), rate)
                }
                (Some(_), Some(_)) => (
                    Some(next_height),
                    StoredGap::NotDefined,
                    StoredRate::NotDefined,
                ),
                _ => (Some(next_height), StoredGap::NotRead, StoredRate::NotRead),
            }
        };
        rows.push(StageMeasurement {
            index: i as u64,
            beacon_height: stage.beacon_height,
            miner_time,
            observed_unix_ms: observed,
            next_stored_beacon,
            gap,
            leg_count: stage.leg_count,
            leg_rate,
        });
    }
    rows
}

/// Half-up thousandths of `legs / gap_s` (legs per second). `None` when the
/// quotient does not fit. A 600 second block is not an input. `gap_s` is the
/// difference of two Bitcoin header `nTime` values, not an observation gap.
fn rate_thousandths(legs: u64, gap_s: u64) -> Option<u64> {
    if gap_s == 0 {
        return None;
    }
    let numer = legs.checked_mul(1_000)?;
    let quotient = numer / gap_s;
    let remainder = numer % gap_s;
    let twice = remainder.checked_mul(2)?;
    if twice >= gap_s {
        quotient.checked_add(1)
    } else {
        Some(quotient)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::audit::Entropy;
    use crate::bind::successor_waypoint;
    use crate::object::{encode_hike, structural_object};
    use crate::pathbody::encode_single_leaf;

    struct Boom;

    impl Entropy for Boom {
        fn fill(&mut self, _dest: &mut [u8]) -> Result<(), ()> {
            panic!("drew");
        }
    }

    struct Empty;

    impl Entropy for Empty {
        fn fill(&mut self, _dest: &mut [u8]) -> Result<(), ()> {
            Err(())
        }
    }

    fn obtained<T>(value: &Recorded<T>) -> &T {
        match value {
            Recorded::Obtained(inner) => inner,
            Recorded::NotObtained => panic!("measurement was not obtained"),
        }
    }

    fn beacon(natural: [u8; 32], height: u64) -> Result<IndexBeacon, ()> {
        Ok(IndexBeacon {
            natural,
            height,
            header_time_s: None,
            timestamp_unix_ms: None,
        })
    }

    fn beacon_at(natural: [u8; 32], height: u64, header_time_s: u64) -> Result<IndexBeacon, ()> {
        Ok(IndexBeacon {
            natural,
            height,
            header_time_s: Some(header_time_s),
            timestamp_unix_ms: Some(9),
        })
    }

    fn zero_leg_hike() -> crate::object::Hike {
        let mut hike = structural_object();
        for stage in &mut hike.stages {
            stage.leg_count = 0;
            stage.checkpoints.clear();
            stage.leg_results.clear();
            stage.path.clear();
        }
        hike.stages[0].waypoint = [1u8; 32];
        hike.stages[2].path = encode_single_leaf(&hike.stages[2].waypoint);
        hike.stages[3].path = encode_single_leaf(&hike.stages[3].waypoint);
        hike
    }

    #[test]
    fn malformed_cbor_is_a_false_shape_and_later_measurements_are_not_obtained() {
        let mut beacon_calls = 0;
        let mut path_calls = 0;
        let measurements = verify(
            &[],
            AuditDraw::PinnedSeed([0u8; 32]),
            |_| {
                beacon_calls += 1;
                beacon([0u8; 32], 0)
            },
            |_| {
                path_calls += 1;
                Ok(())
            },
        );
        assert!(!measurements.shape);
        assert_eq!(measurements.checkpoints, Recorded::NotObtained);
        assert_eq!(measurements.waypoints, Recorded::NotObtained);
        assert_eq!(measurements.leg_rate, LegRate::NotRead);
        assert_eq!(measurements.paths_included, Recorded::NotObtained);
        assert_eq!(measurements.six_legs, SixLegs::NotObtained);
        assert_eq!(measurements.stages, StageReport::NotRead);
        assert_eq!(beacon_calls, 0);
        assert_eq!(path_calls, 0);
        assert!(!crate::admit::admit(&measurements));
    }

    #[test]
    fn malformed_cbor_shape_item_is_exactly_that_string() {
        // `b"not-cbor"` is a truncated text item. `0xff` is a simple value.
        // `0xc0 0x00` is a tag. An empty input is not a data item.
        let cases: &[&[u8]] = &[b"not-cbor", b"", &[0xff], &[0xc0, 0x00]];
        for bytes in cases {
            let measurements = verify(
                bytes,
                AuditDraw::PinnedSeed([0u8; 32]),
                |_| Err(()),
                |_| Ok(()),
            );
            let record = record_json(&measurements);
            assert_eq!(record["shape"], "mismatch");
            assert_eq!(record["shape_item"], serde_json::json!(["malformed cbor"]));
            assert_eq!(record["seed_value"], "not defined");
            assert_eq!(measurements.checkpoints, Recorded::NotObtained);
        }
    }

    /// PORTS-219 item 17. `0xa000` is one empty map and a trailing byte.
    /// Section 14: that byte is exactly `["malformed cbor"]`, not `field set`.
    #[test]
    fn a_trailing_byte_after_an_empty_map_is_exactly_malformed_cbor() {
        let trailing = verify_record_json(
            "production",
            &verify(
                &[0xa0, 0x00],
                AuditDraw::PinnedSeed([0u8; 32]),
                |_| Err(()),
                |_| Ok(()),
            ),
        );
        let empty = verify_record_json(
            "production",
            &verify(
                &[],
                AuditDraw::PinnedSeed([0u8; 32]),
                |_| Err(()),
                |_| Ok(()),
            ),
        );
        assert!(trailing.ends_with('\n'));
        assert_eq!(trailing.as_bytes(), empty.as_bytes());
        assert!(trailing.contains("\"shape_item\":[\"malformed cbor\"]"));
        assert!(!trailing.contains("field set"));
        assert!(trailing.contains("\"seed_value\":\"not defined\""));
        assert_eq!(trailing.len(), 7613);
        assert_eq!(
            crate::hex::encode_hex32(&crate::sha::sha256(trailing.as_bytes())),
            "c9bea178ac50f3dcf786f5aa944953682b7d4ffa673a9bfddfe9757a9c29925e"
        );
    }

    /// PORTS-219 item 19. A quiet object plus one trailing byte is still measured.
    /// `shape_item` stays exactly `["malformed cbor"]`.
    #[test]
    fn a_trailing_byte_on_a_quiet_object_is_measured() {
        let mut bytes = encode_hike(&quiet_hike());
        bytes.push(0x00);
        let measurements = verify(
            &bytes,
            AuditDraw::PinnedSeed([0u8; 32]),
            |_| Err(()),
            |_| Ok(()),
        );
        assert!(!measurements.shape);
        assert!(matches!(measurements.checkpoints, Recorded::Obtained(_)));
        assert!(matches!(measurements.waypoints, Recorded::Obtained(_)));
        let record = record_json(&measurements);
        assert_eq!(record["shape"], "mismatch");
        assert_eq!(record["shape_item"], serde_json::json!(["malformed cbor"]));
        assert_eq!(record["seed_value"], "match");
        assert_eq!(record["paths"][16]["w17_path"], "not fetched by design");
        let skip = record["skip_list"].as_array().expect("skip_list");
        assert_eq!(skip.len(), 16);
        let raw = verify_record_json("production", &measurements);
        assert!(raw.ends_with('\n'));
        assert!(raw.contains("\"shape_item\":[\"malformed cbor\"]"));
        assert!(!raw.contains("field set"));
        assert_eq!(raw.len(), 7722);
        assert_eq!(
            crate::hex::encode_hex32(&crate::sha::sha256(raw.as_bytes())),
            "fe46e35a55d2c43f82e86c062cab25026103ffa8e206e3028d752674aad3fa03"
        );
    }

    #[test]
    fn a_missing_successor_stage_is_not_defined() {
        let mut hike = quiet_hike();
        hike.stages.truncate(3);
        let sizes = crate::consts::Sizes::conformance_small();
        let terrain = vec![0u8; sizes.terrain_bytes];
        let fact = crate::section14::PathFact {
            stage_present: false,
            empty: true,
            plant_read: false,
            plant: crate::section14::Plant::NotRead,
            block_hash: None,
            block_height: None,
        };
        let paths: Vec<BoundPath> = (0..17)
            .map(|_| BoundPath {
                fact,
                anchor: Anchor::NotAnchored,
                differs: false,
            })
            .collect();
        let found = waypoint_records(&hike, &terrain, &paths, sizes);
        assert_eq!(found.successor_differs.len(), 17);
        assert!(found.successor_differs[0].is_some());
        assert!(found.successor_differs[1].is_some());
        assert!(found.successor_differs[2..].iter().all(|row| row.is_none()));
    }

    #[test]
    fn verify_record_sha256_excludes_the_trailing_newline() {
        let file = "{\"b\":1,\"a\":2}\n";
        let hash = verify_record_sha256(file).unwrap();
        assert_ne!(crate::sha::sha256(file.as_bytes()), hash);
        assert_ne!(crate::sha::sha256(br#"{"b":1,"a":2}"#), hash);
        assert_eq!(
            crate::hex::encode_hex32(&hash),
            "d3626ac30a87e6f7a6428233b3c68299976865fa5508e4267c5415c76af7a772"
        );
    }

    /// PORTS-219 items 21 and 22. Stage 0's `path` key is `field set` and
    /// `stage-0 path key`, then `leg_count`, in section 14 table order.
    #[test]
    fn a_stage0_path_key_records_field_set_then_that_key_then_leg_count() {
        let bytes = stage0_path_with_zero_leg_counts();
        let measurements = verify(
            &bytes,
            AuditDraw::PinnedSeed([0u8; 32]),
            |_| Err(()),
            |_| Ok(()),
        );
        let raw = verify_record_json("production", &measurements);
        assert!(raw.ends_with('\n'));
        assert!(raw.contains("\"shape_item\":[\"field set\",\"stage-0 path key\",\"leg_count\"]"));
        let record = record_json(&measurements);
        assert_eq!(
            record["shape_item"],
            serde_json::json!(["field set", "stage-0 path key", "leg_count"])
        );
        assert_eq!(record["seed_value"], "match");
        assert_eq!(raw.len(), 7748);
        assert_eq!(
            crate::hex::encode_hex32(&crate::sha::sha256(raw.as_bytes())),
            "d8f75d27a34a9309ac6a862aa7e775f975092531b58431fe83a8c2b35b939ec3"
        );
    }

    fn stage0_path_with_zero_leg_counts() -> Vec<u8> {
        use crate::cbor::{decode_one, encode, Cbor};
        let mut value = decode_one(&encode_hike(&quiet_hike())).unwrap();
        let Cbor::Map(pairs) = &mut value else {
            panic!("object");
        };
        let stages = &mut pairs.iter_mut().find(|(key, _)| key == "stages").unwrap().1;
        let Cbor::Array(stages) = stages else {
            panic!("stages");
        };
        let Cbor::Map(stage) = &mut stages[0] else {
            panic!("stage 0");
        };
        stage.insert(0, ("path".to_owned(), Cbor::Bytes(Vec::new())));
        encode(&value)
    }

    fn quiet_hike() -> crate::object::Hike {
        let mut hike = structural_object();
        for stage in &mut hike.stages {
            stage.leg_count = 0;
            stage.checkpoints.clear();
            stage.leg_results.clear();
            stage.path.clear();
        }
        hike
    }

    fn assert_decoded_values_stay_recorded(bytes: &[u8], stages: usize) {
        let mut beacon_calls = 0;
        let measurements = verify(
            bytes,
            AuditDraw::PinnedSeed([0u8; 32]),
            |height| {
                beacon_calls += 1;
                beacon([1u8; 32], height)
            },
            |_| -> ChainRead { panic!("an absent path is not bound") },
        );
        assert!(!measurements.shape);
        assert_eq!(beacon_calls, stages + 1);
        assert!(obtained(&measurements.checkpoints).mismatch);
        let waypoints = obtained(&measurements.waypoints);
        assert_eq!(waypoints.successor_differs.len(), STAGES - 1);
        assert_eq!(waypoints.absent_paths, stages as u64);
        assert_eq!(measurements.leg_rate, LegRate::NotRead);
        assert_eq!(measurements.paths_included, Recorded::Obtained(0));
        assert_eq!(measurements.six_legs, SixLegs::CouldNotDraw);
        assert!(!crate::admit::admit(&measurements));
    }

    #[test]
    fn trailing_byte_and_extra_top_level_key_keep_decoded_measurements() {
        let hike = quiet_hike();
        let mut trailing = encode_hike(&hike);
        trailing.push(0x00);
        assert_decoded_values_stay_recorded(&trailing, STAGES);

        let mut value = crate::cbor::decode_one(&encode_hike(&hike)).unwrap();
        if let crate::cbor::Cbor::Map(pairs) = &mut value {
            pairs.push(("note".to_owned(), crate::cbor::Cbor::Uint(1)));
        }
        assert_decoded_values_stay_recorded(&crate::cbor::encode(&value), STAGES);
    }

    #[test]
    fn fewer_than_six_legs_does_not_draw() {
        let mut hike = structural_object();
        for stage in &mut hike.stages {
            stage.leg_count = 0;
            stage.checkpoints.clear();
            stage.leg_results.clear();
        }
        assert_eq!(
            measure_six(&hike, &[], &mut Boom, Sizes::production()),
            SixLegs::CouldNotDraw
        );
        assert_eq!(draw_count(0), None);
        assert_eq!(draw_count(5), None);
        assert_eq!(draw_count(6), Some(6));
        assert_eq!(draw_count(18), Some(6));
    }

    #[test]
    fn a_failed_byte_source_records_that_the_draw_was_not_obtained() {
        let hike = structural_object();
        assert_eq!(
            measure_six(&hike, &[], &mut Empty, Sizes::production()),
            SixLegs::DrawNotObtained
        );
    }

    #[test]
    fn fewer_than_18_stages_leaves_waypoints_not_obtained() {
        let mut hike = structural_object();
        hike.stages.pop();
        for stage in &mut hike.stages {
            stage.leg_count = 0;
            stage.checkpoints.clear();
            stage.leg_results.clear();
            stage.path.clear();
        }
        let mut calls = 0;
        let measurements = verify(
            &encode_hike(&hike),
            AuditDraw::PinnedSeed([0u8; 32]),
            |height| {
                calls += 1;
                beacon([1u8; 32], height)
            },
            |_| -> ChainRead { panic!("an absent path is not bound") },
        );
        assert!(!measurements.shape);
        assert_eq!(calls, 18);
        assert!(obtained(&measurements.checkpoints).mismatch);
        assert_eq!(measurements.waypoints, Recorded::NotObtained);
        assert_eq!(measurements.leg_rate, LegRate::NotRead);
        assert_eq!(measurements.paths_included, Recorded::Obtained(0));
        assert_eq!(measurements.six_legs, SixLegs::CouldNotDraw);
        assert!(!crate::admit::admit(&measurements));
    }

    #[test]
    fn nineteen_stages_still_record_the_waypoint_measurement() {
        let mut hike = quiet_hike();
        let mut extra = hike.stages[STAGES - 1].clone();
        extra.beacon[0] = 99;
        extra.beacon_height = 100;
        hike.stages.push(extra);
        assert_decoded_values_stay_recorded(&encode_hike(&hike), STAGES + 1);
    }

    #[test]
    fn rewalk_pool_is_six_threads() {
        assert_eq!(REWALK_THREADS, 6);
        assert_eq!(REWALK_COUNT, 6);
        let pool = rewalk_pool();
        let n = pool.install(rayon::current_num_threads);
        assert_eq!(n, 6);
    }

    #[test]
    fn false_shape_still_records_every_later_measurement() {
        let hike = zero_leg_hike();
        let bytes = encode_hike(&hike);
        let trace = std::cell::RefCell::new(Vec::new());
        let mut heights = Vec::new();
        let mut bound = Vec::new();
        let measurements = verify(
            &bytes,
            AuditDraw::PinnedSeed([0u8; 32]),
            |height| {
                trace.borrow_mut().push("beacon");
                heights.push(height);
                if height == hike.last_interrupt_height {
                    return beacon_at(hike.last_interrupt, height, height * 1_000);
                }
                let stage = hike
                    .stages
                    .iter()
                    .find(|stage| stage.beacon_height == height)
                    .expect("stored height");
                if height == 1 {
                    beacon_at(stage.beacon, height + 1, height * 1_000)
                } else if height == 2 {
                    beacon_at([0x9; 32], height, height * 1_000)
                } else if height == 3 {
                    Err(())
                } else {
                    beacon_at(stage.beacon, height, height * 1_000)
                }
            },
            |loc| {
                trace.borrow_mut().push("path");
                bound.push(loc.block_height);
                if bound.len() == 1 {
                    Err(())
                } else {
                    Ok(())
                }
            },
        );

        assert!(!measurements.shape);
        let mut expected_heights: Vec<u64> = (1..=18).collect();
        expected_heights.push(hike.last_interrupt_height);
        assert_eq!(heights, expected_heights);
        let trace = trace.borrow();
        assert!(trace.iter().all(|event| *event == "beacon"));
        assert_eq!(trace.len(), 19);
        let checkpoints = obtained(&measurements.checkpoints);
        assert_eq!(
            &checkpoints.beacon_matches_index[..3],
            &[false, false, false]
        );
        assert!(checkpoints.beacon_matches_index[3..].iter().all(|m| *m));
        assert!(checkpoints
            .digest_matches
            .iter()
            .all(|legs| legs.is_empty()));
        assert!(checkpoints.mismatch);

        let waypoints = obtained(&measurements.waypoints);
        assert!(!waypoints.seed_value_matches);
        assert_eq!(waypoints.successor_differs.len(), 17);
        assert_eq!(waypoints.absent_paths, 16);
        assert!(!waypoints.present_path_does_not_match_plant);
        assert!(bound.is_empty(), "a path outside the window is not read");
        assert_eq!(waypoints.anchors.len(), 16);
        assert!(waypoints
            .anchors
            .iter()
            .all(|anchor| *anchor == Anchor::NotAnchored));

        assert_eq!(measurements.leg_rate, LegRate::NotRead);
        assert_eq!(measurements.paths_included, Recorded::Obtained(2));
        assert_eq!(measurements.six_legs, SixLegs::CouldNotDraw);
        assert!(!crate::admit::admit(&measurements));

        let stages = match &measurements.stages {
            StageReport::Read(rows) => rows,
            StageReport::NotRead => panic!("stages were not read"),
        };
        assert_eq!(stages.len(), 18);
        assert_eq!(&heights[..18], &(1..=18).collect::<Vec<_>>()[..]);
        assert_eq!(*heights.last().unwrap(), hike.last_interrupt_height);
        assert!(
            !heights.contains(&19),
            "the stage table does not invent the next height"
        );
        assert_eq!(stages[0].miner_time, StageClock::UnixS(1_000));
        assert_eq!(stages[0].observed_unix_ms, Some(9));
        assert_eq!(stages[0].gap, StoredGap::Seconds(1_000));
        assert_eq!(stages[0].leg_rate.label(), "0.000");
        assert_eq!(stages[1].miner_time, StageClock::UnixS(2_000));
        assert_eq!(stages[1].observed_unix_ms, Some(9));
        assert_eq!(stages[1].gap, StoredGap::NotRead);
        assert_eq!(stages[1].leg_rate, StoredRate::NotRead);
        assert_eq!(stages[2].miner_time, StageClock::NotRead);
        assert_eq!(stages[2].observed_unix_ms, None);
        assert_eq!(stages[2].next_stored_beacon, Some(4));
        assert_eq!(stages[17].next_stored_beacon, None);
        assert_eq!(stages[17].gap, StoredGap::NotDefined);
        assert_eq!(stages[17].leg_rate, StoredRate::NotDefined);
        assert_eq!(stages[17].leg_rate.label(), "not defined");
    }

    /// The gap is the difference of the header times at heights
    /// 969764 and 969766, 227 seconds.
    /// The next stored beacon is 969766. Height 969765 is not consulted.
    /// The millisecond stamp is an observation and is not the gap.
    #[test]
    fn a_stored_gap_is_not_a_600_second_block_and_does_not_borrow_the_next_height() {
        let mut hike = structural_object();
        hike.stages[16].beacon_height = 969764;
        hike.stages[16].leg_count = 79;
        hike.stages[17].beacon_height = 969766;
        hike.stages[17].leg_count = 189;
        let mut times = vec![Some(1_000_u64); 18];
        times[16] = Some(1_791_063_129);
        times[17] = Some(1_791_063_356);
        let observed = vec![Some(1_800_000_000_000_u64); 18];
        let rows = super::stored_beacon_stages(&hike, &times, &observed);
        assert_eq!(rows[16].miner_time, StageClock::UnixS(1_791_063_129));
        assert_eq!(rows[16].observed_unix_ms, Some(1_800_000_000_000));
        assert_eq!(rows[16].next_stored_beacon, Some(969766));
        assert_eq!(rows[16].gap, StoredGap::Seconds(227));
        assert_ne!(rows[16].gap, StoredGap::Seconds(600));
        assert_eq!(rows[16].leg_rate.label(), "0.348");
        assert_eq!(rows[17].leg_count, 189);
        assert_eq!(rows[17].leg_rate, StoredRate::NotDefined);
        assert_ne!(
            rows[17].leg_rate,
            StoredRate::Thousandths(super::rate_thousandths(189, 53_948).unwrap())
        );
    }

    #[test]
    fn missing_header_time_is_not_read_even_when_observation_is_present() {
        let hike = zero_leg_hike();
        let bytes = encode_hike(&hike);
        let measurements = verify(
            &bytes,
            AuditDraw::PinnedSeed([0u8; 32]),
            |height| {
                if height == hike.last_interrupt_height {
                    return Ok(IndexBeacon {
                        natural: hike.last_interrupt,
                        height,
                        header_time_s: None,
                        timestamp_unix_ms: Some(1_800_000_000_000 + height),
                    });
                }
                let stage = hike
                    .stages
                    .iter()
                    .find(|stage| stage.beacon_height == height)
                    .expect("stored height");
                if height == 1 {
                    Ok(IndexBeacon {
                        natural: stage.beacon,
                        height,
                        header_time_s: Some(1_700_000_000),
                        timestamp_unix_ms: Some(1_800_000_000_000),
                    })
                } else {
                    Ok(IndexBeacon {
                        natural: stage.beacon,
                        height,
                        header_time_s: None,
                        timestamp_unix_ms: Some(1_800_000_000_000 + height),
                    })
                }
            },
            |_| Ok(()),
        );
        let stages = match &measurements.stages {
            StageReport::Read(rows) => rows,
            StageReport::NotRead => panic!("stages were not read"),
        };
        assert_eq!(stages[0].miner_time, StageClock::UnixS(1_700_000_000));
        assert_eq!(stages[0].observed_unix_ms, Some(1_800_000_000_000));
        assert_eq!(stages[0].gap, StoredGap::NotRead);
        assert_eq!(stages[0].leg_rate, StoredRate::NotRead);
        assert_eq!(stages[1].miner_time, StageClock::NotRead);
        assert_eq!(stages[1].observed_unix_ms, Some(1_800_000_000_002));
        assert_eq!(stages[1].gap, StoredGap::NotRead);
        assert!(stages[2..]
            .iter()
            .all(|row| row.miner_time == StageClock::NotRead && row.observed_unix_ms.is_some()));
    }

    /// In-place binding digest equals the joined preimage. A mismatch in the
    /// middle leaves every later binding recorded, in leg order, per stage.
    #[test]
    fn checkpoint_bindings_match_the_serial_preimage() {
        use crate::bind::{checkpoint_preimage, terrain_sample};
        use crate::consts::TERRAIN_BYTES;
        use crate::sha::sha256;

        let terrain = vec![0xA5u8; TERRAIN_BYTES];
        let limb = [0x11u8; 32];
        let leg = 7u64.to_le_bytes();
        let previous = [0u8; 32];
        let sample = terrain_sample(&terrain, &previous);
        let joined = sha256(&checkpoint_preimage(&previous, &limb, &sample, &leg));
        assert_eq!(
            binding_digest(&terrain, &previous, &limb, &leg, Sizes::production()),
            joined
        );

        let shifted = {
            let mut previous = [0u8; 32];
            previous[..8].copy_from_slice(&1_000u64.to_le_bytes());
            previous
        };
        let sample = terrain_sample(&terrain, &shifted);
        let joined = sha256(&checkpoint_preimage(&shifted, &limb, &sample, &leg));
        assert_eq!(
            binding_digest(&terrain, &shifted, &limb, &leg, Sizes::production()),
            joined
        );

        let waypoint = [0x22u8; 32];
        let public_key = [0x33u8; 32];
        let seed_beacon = [0x44u8; 32];
        let seed = sha256(&production_seed(&public_key, &seed_beacon));
        assert_ne!(seed, waypoint);
        // Stage 0 uses the computed seed as `j = 0` previous and as the limb.
        // Stage `g ≥ 1` uses the stored waypoint for both.
        let series = |limb: [u8; 32]| {
            let mut checkpoints = Vec::new();
            let mut leg_results = Vec::new();
            for j in 0..8 {
                let previous = if j == 0 { limb } else { checkpoints[j - 1] };
                let leg = (j as u64).to_le_bytes();
                let sample = terrain_sample(&terrain, &previous);
                let digest = sha256(&checkpoint_preimage(&previous, &limb, &sample, &leg));
                checkpoints.push(if j == 3 { [0xEEu8; 32] } else { digest });
                leg_results.push(leg);
            }
            let serial: Vec<bool> = (0..checkpoints.len())
                .map(|j| {
                    let previous = if j == 0 { limb } else { checkpoints[j - 1] };
                    let sample = terrain_sample(&terrain, &previous);
                    let digest = sha256(&checkpoint_preimage(
                        &previous,
                        &limb,
                        &sample,
                        &leg_results[j],
                    ));
                    digest == checkpoints[j]
                })
                .collect();
            (checkpoints, leg_results, serial)
        };
        let (seed_cps, seed_legs, seed_serial) = series(seed);
        let (stored_cps, stored_legs, stored_serial) = series(waypoint);
        assert!(!seed_serial[3]);
        assert_eq!(seed_serial.iter().filter(|matches| **matches).count(), 7);
        assert!(stored_serial[..2].iter().all(|matches| *matches));

        let hike = Hike {
            public_key,
            seed_beacon,
            last_interrupt: [0x99; 32],
            last_interrupt_height: 4,
            last_interrupt_absent: false,
            last_interrupt_hash_absent: false,
            last_interrupt_height_absent: false,
            stages: vec![
                Stage {
                    beacon: [0x55; 32],
                    beacon_height: 1,
                    leg_count: 8,
                    waypoint,
                    path: Vec::new(),
                    checkpoints: seed_cps,
                    leg_results: seed_legs,
                    checkpoint_rows_defined: true,
                    leg_result_missing: Vec::new(),
                },
                Stage {
                    beacon: [0x66; 32],
                    beacon_height: 2,
                    leg_count: 0,
                    waypoint: [0x77; 32],
                    path: Vec::new(),
                    checkpoints: Vec::new(),
                    leg_results: Vec::new(),
                    checkpoint_rows_defined: true,
                    leg_result_missing: Vec::new(),
                },
                Stage {
                    beacon: [0x88; 32],
                    beacon_height: 3,
                    leg_count: 2,
                    waypoint,
                    path: Vec::new(),
                    checkpoints: stored_cps[..2].to_vec(),
                    leg_results: stored_legs[..2].to_vec(),
                    checkpoint_rows_defined: true,
                    leg_result_missing: Vec::new(),
                },
            ],
        };
        let rows = digest_records(
            &hike,
            Some(&terrain),
            crate::consts::DEFAULT_BIND_THREADS,
            Sizes::production(),
        );
        assert_eq!(rows.len(), 3);
        assert_eq!(rows[0], seed_serial);
        assert!(rows[1].is_empty());
        assert_eq!(rows[2], stored_serial[..2]);
        let one = digest_records(&hike, Some(&terrain), 1, Sizes::production());
        let many = digest_records(&hike, Some(&terrain), 6, Sizes::production());
        assert_eq!(one, many);
    }

    #[test]
    fn sequential_and_parallel_checkpoint_binding_records_are_byte_identical() {
        let mut hike = quiet_hike();
        let mut checkpoints = Vec::new();
        let mut leg_results = Vec::new();
        for j in 0..4u64 {
            checkpoints.push([j as u8; 32]);
            leg_results.push(j.to_le_bytes());
        }
        hike.stages[3].leg_count = 4;
        hike.stages[3].checkpoints = checkpoints;
        hike.stages[3].leg_results = leg_results;
        let bytes = encode_hike(&hike);
        let draw = AuditDraw::PinnedSeed([9u8; 32]);
        let one = verify_with_bind_threads(&bytes, draw, |_| Err(()), |_| Ok(()), 1);
        let draw = AuditDraw::PinnedSeed([9u8; 32]);
        let many = verify_with_bind_threads(&bytes, draw, |_| Err(()), |_| Ok(()), 6);
        let left = verify_record_json("production", &one);
        let right = verify_record_json("production", &many);
        assert_eq!(left, right);
        assert!(left.contains("\"anchors\":"));
        assert_eq!(one, many);
    }

    #[test]
    fn anchor_window_records_the_four_path_cases_and_reads_one_header() {
        use crate::pathbody::encode_path_in_block;
        let mut hike = quiet_hike();
        hike.stages[3].beacon = [0x55; 32];
        let inside = hike.stages[1].waypoint;
        hike.stages[1].path = encode_path_in_block(&inside, 4);
        let outside = hike.stages[2].waypoint;
        hike.stages[2].path = encode_path_in_block(&outside, 100);
        let missing_header = hike.stages[4].waypoint;
        hike.stages[4].path = encode_path_in_block(&missing_header, 7);
        let bytes = encode_hike(&hike);
        let mut reads = Vec::new();
        let trace = std::cell::RefCell::new(Vec::new());
        let measurements = verify(
            &bytes,
            AuditDraw::PinnedSeed([0u8; 32]),
            |_| {
                trace.borrow_mut().push("beacon");
                Err(())
            },
            |loc| {
                trace.borrow_mut().push("path");
                reads.push(loc.block_height);
                Ok(())
            },
        );
        let trace = trace.borrow();
        let first_path = trace.iter().position(|event| *event == "path").unwrap();
        assert!(trace[..first_path].iter().all(|event| *event == "beacon"));
        assert_eq!(trace.iter().filter(|event| **event == "path").count(), 1);
        let anchors = &obtained(&measurements.waypoints).anchors;
        assert_eq!(anchors[0], Anchor::Anchored, "inside the window");
        assert_eq!(anchors[1], Anchor::NotAnchored, "outside the window");
        assert_eq!(anchors[3], Anchor::NotAnchored, "header not in the object");
        assert_eq!(anchors[4], Anchor::NotAnchored, "no path");
        assert_eq!(reads, vec![4], "one header, no scan");
    }

    #[test]
    fn a_path_included_in_last_interrupt_is_anchored_for_w16() {
        use crate::pathbody::encode_path_in_block;
        let mut hike = quiet_hike();
        hike.last_interrupt = [0x55; 32];
        hike.last_interrupt_height = 19;
        hike.last_interrupt_absent = false;
        let waypoint = hike.stages[16].waypoint;
        hike.stages[16].path = encode_path_in_block(&waypoint, 19);
        let bytes = encode_hike(&hike);
        let mut reads = Vec::new();
        let measurements = verify(
            &bytes,
            AuditDraw::PinnedSeed([0u8; 32]),
            |_| Err(()),
            |loc| {
                reads.push(loc.block_height);
                assert_eq!(loc.block_hash, [0x55; 32]);
                Ok(())
            },
        );
        let anchors = &obtained(&measurements.waypoints).anchors;
        assert_eq!(anchors.len(), 16);
        assert_eq!(anchors[15], Anchor::Anchored);
        assert!(anchors[..15]
            .iter()
            .all(|anchor| *anchor == Anchor::NotAnchored));
        assert_eq!(reads, vec![19]);
    }

    #[test]
    fn a_seed_value_mismatch_is_named_on_seed_value_and_shape_stays_match() {
        let mut hike = structural_object();
        hike.stages[0].waypoint = [0x22; 32];
        let bytes = encode_hike(&hike);
        assert!(crate::object::decode_hike(&bytes).is_ok());
        let measurements = verify(
            &bytes,
            AuditDraw::PinnedSeed([0u8; 32]),
            |_| Err(()),
            |_| Ok(()),
        );
        assert!(measurements.shape);
        let record = verify_record_json("production", &measurements);
        let value: serde_json::Value = serde_json::from_str(record.trim()).unwrap();
        assert_eq!(value["shape"], "match");
        assert_eq!(value["shape_item"], serde_json::json!([]));
        assert_eq!(value["seed_value"], "mismatch");
        assert!(!crate::admit::admit(&measurements));
    }

    /// Rule A4.3. Tampering only the stored stage-0 waypoint leaves the
    /// checkpoint comparison on the computed seed, and moves `W_1`.
    #[test]
    fn stage0_checkpoint_uses_the_computed_seed_and_w1_uses_the_stored_field() {
        let mut hike = structural_object();
        let seed = computed_seed(&hike);
        assert_eq!(hike.stages[0].waypoint, seed);
        let terrain = terrain_of(&hike, Sizes::production());
        let leg = 7u64.to_le_bytes();
        let digest = binding_digest(&terrain, &seed, &seed, &leg, Sizes::production());
        hike.stages[0].checkpoints = vec![digest];
        hike.stages[0].leg_results = vec![leg];
        hike.stages[1].waypoint = successor_waypoint(
            &terrain,
            &hike.stages[1].beacon,
            &seed,
            &hike.stages[0].checkpoints,
        );
        let control = verify(
            &encode_hike(&hike),
            AuditDraw::PinnedSeed([0u8; 32]),
            |_| Err(()),
            |_| Ok(()),
        );
        let control_record = record_json(&control);
        assert_eq!(control_record["seed_value"], "match");
        assert_eq!(
            control_record["checkpoints"][0]["legs"][0]["result"],
            "match"
        );
        assert_eq!(control_record["waypoints"][0]["result"], "match");

        hike.stages[0].waypoint = [0x22; 32];
        assert_eq!(hike.stages[0].checkpoints, vec![digest]);
        let tampered = verify(
            &encode_hike(&hike),
            AuditDraw::PinnedSeed([0u8; 32]),
            |_| Err(()),
            |_| Ok(()),
        );
        assert!(tampered.shape);
        let record = record_json(&tampered);
        assert_eq!(record["shape"], "match");
        assert_eq!(record["shape_item"], serde_json::json!([]));
        assert_eq!(record["seed_value"], "mismatch");
        assert_eq!(record["checkpoints"][0]["legs"][0]["result"], "match");
        assert_eq!(record["waypoints"][0]["result"], "mismatch");
    }

    /// Section 14.6. A drawn index on stage 0 starts from the computed seed
    /// after the stored waypoint has been replaced.
    #[test]
    fn a_drawn_index_on_stage_0_starts_from_the_computed_seed() {
        use crate::consts::TERRAIN_BYTES;
        let mut hike = structural_object();
        let seed = computed_seed(&hike);
        let terrain = vec![0xA5u8; TERRAIN_BYTES];
        let walked = crate::leg_result(&terrain, &seed);
        hike.stages[0].leg_results = vec![walked.to_le_bytes()];
        hike.stages[0].waypoint = [0x22; 32];
        assert_ne!(seed[..8], hike.stages[0].waypoint[..8]);
        assert_eq!(
            rewalk(&hike, &terrain, &[0], Sizes::production()),
            vec![true]
        );
        assert_ne!(
            crate::leg_result(&terrain, &hike.stages[0].waypoint),
            walked
        );
    }

    fn record_json(measurements: &Measurements) -> serde_json::Value {
        let record = verify_record_json("production", measurements);
        assert!(record.ends_with('\n'));
        serde_json::from_str(record.trim_end()).unwrap()
    }

    #[test]
    fn section_14_record_reads_last_interrupt_and_states_bookends() {
        use crate::pathbody::encode_path_in_block;
        let mut hike = quiet_hike();
        hike.stages[4].beacon = [0x55; 32];
        hike.stages[1].leg_count = 3;
        hike.stages[1].checkpoints = vec![[0xAB; 32]; 3];
        hike.stages[1].leg_results = vec![[1, 0, 0, 0, 0, 0, 0, 0]; 3];
        let first = hike.stages[1].waypoint;
        let second = hike.stages[2].waypoint;
        hike.stages[1].path = encode_path_in_block(&first, 5);
        hike.stages[2].path = encode_path_in_block(&second, 5);
        let bytes = encode_hike(&hike);
        let mut heights = Vec::new();
        let measurements = verify(
            &bytes,
            AuditDraw::PinnedSeed([0u8; 32]),
            |height| {
                heights.push(height);
                if height == hike.last_interrupt_height {
                    return Ok(IndexBeacon {
                        natural: hike.last_interrupt,
                        height,
                        header_time_s: Some(height * 1_000),
                        timestamp_unix_ms: None,
                    });
                }
                let stage = hike
                    .stages
                    .iter()
                    .find(|stage| stage.beacon_height == height)
                    .expect("stored header");
                Ok(IndexBeacon {
                    natural: stage.beacon,
                    height,
                    header_time_s: Some(height * 1_000),
                    timestamp_unix_ms: None,
                })
            },
            |_| Ok(()),
        );
        assert!(heights.contains(&hike.last_interrupt_height));
        assert_eq!(heights.iter().filter(|height| **height == 5).count(), 2);
        let record = verify_record_json("production", &measurements);
        let value: serde_json::Value = serde_json::from_str(record.trim()).unwrap();
        assert_eq!(value["headers"].as_array().unwrap().len(), 19);
        assert_eq!(value["headers"][18]["stored_height"], 20);
        assert_eq!(value["headers"][18]["index_comparison"], "match");
        assert_eq!(value["headers"][18]["T"], 20_000);
        assert_eq!(value["anchors"][0], "anchored");
        assert_eq!(value["anchors"][1], "anchored");
        assert_eq!(value["a"], 1);
        assert_eq!(value["b"], 2);
        assert_eq!(value["N"], 3);
        assert_eq!(value["S"], 3_000);
        assert_eq!(value["delta_h"], 3);
        assert_eq!(value["height_P_b"], 5);
        assert_eq!(value["index_comparison_P_b"], "match");
        assert_eq!(value["T_P_b"], 5_000);
        assert_eq!(value["height_I_a_minus_1"], 2);
        assert_eq!(value["T_I_a_minus_1"], 2_000);
        assert_eq!(value["fixed_start"]["N"], 3);
        assert_eq!(value["fixed_start"]["S"], 3_000);
        assert_eq!(value["fixed_start"]["delta_h"], 3);
        assert_eq!(value["paths"][0]["d_g"], 3);
        assert_eq!(value["paths"][1]["d_g"], 2);
        assert_eq!(value["paths"][16]["w17_path"], "not fetched by design");
        assert_eq!(value["analysis"]["whole_hike_legs"], 3);
        assert_eq!(value["analysis"]["whole_hike_span"], 18_000);
        assert_eq!(value["analysis"]["legs_outside"], 0);
        assert_eq!(value["six_leg"], "not defined");
        assert_eq!(value["six_leg_runs"], serde_json::json!([]));
        assert!(value.get("leg_rate").is_none());
        assert_eq!(top_level_keys(record.trim()), SECTION_14_KEYS);
        let small = verify_record_json("conformance-small", &measurements);
        let small_sizes = Sizes::conformance_small();
        assert!(small.contains(&format!("\"sample_bytes\":{}", small_sizes.sample_bytes)));
        assert!(small.contains(&format!("\"slot_bytes\":{}", small_sizes.slot_bytes)));
        assert!(small.contains(&format!("\"terrain_bytes\":{}", small_sizes.terrain_bytes)));
        assert!(small.contains(&format!("\"steps_per_leg\":{}", small_sizes.steps_per_leg)));
        assert!(small.contains("\"sample_bytes\":32"));
        assert!(small.contains("\"steps_per_leg\":32"));
        assert!(!record.contains("\"sample_bytes\""));
    }

    fn top_level_keys(record: &str) -> Vec<String> {
        let mut keys = Vec::new();
        let mut depth = 0i32;
        let bytes = record.as_bytes();
        let mut index = 0;
        while index < bytes.len() {
            match bytes[index] {
                b'{' | b'[' => depth += 1,
                b'}' | b']' => depth -= 1,
                b'"' if depth == 1 => {
                    let start = index + 1;
                    index += 1;
                    while index < bytes.len() && bytes[index] != b'"' {
                        index += 1;
                    }
                    let key = record[start..index].to_owned();
                    if index + 1 < bytes.len() && bytes[index + 1] == b':' {
                        keys.push(key);
                    }
                }
                _ => {}
            }
            index += 1;
        }
        keys
    }

    const SECTION_14_KEYS: &[&str] = &[
        "profile",
        "shape",
        "shape_item",
        "headers",
        "seed_value",
        "checkpoints",
        "waypoints",
        "paths",
        "anchors",
        "anchored_total",
        "skip_list",
        "a",
        "b",
        "N",
        "S",
        "delta_h",
        "fixed_start",
        "P_b",
        "height_P_b",
        "index_comparison_P_b",
        "T_P_b",
        "height_I_a_minus_1",
        "T_I_a_minus_1",
        "six_leg",
        "six_leg_runs",
        "analysis",
    ];

    /// SPEC §9. `P_g`, `P_b`, and `claimed_block` `block_hash` are Bitcoin
    /// display order. The pinned header is Bitcoin mainnet genesis. Inclusion
    /// height 5 puts the path in the quiet object's anchor window. That height
    /// is not the Bitcoin height. `stored_hash` stays byte 0 first.
    #[test]
    fn block_hash_on_p_b_p_g_and_claimed_block_is_display_order() {
        let pin: serde_json::Value = serde_json::from_str(
            &std::fs::read_to_string(format!(
                "{}/../../../spec/conformance/blockhash-display.json",
                env!("CARGO_MANIFEST_DIR")
            ))
            .unwrap(),
        )
        .unwrap();
        let header_hex = pin["header_hex"].as_str().unwrap();
        let natural_hex = pin["natural_block_hash"].as_str().unwrap();
        let display_hex = pin["display_block_hash"].as_str().unwrap();
        let header = unhex(header_hex);
        assert_eq!(header.len(), 80);
        assert_eq!(natural_hex.len(), 64);
        assert_eq!(display_hex.len(), 64);
        let natural = crate::sha::sha256(&crate::sha::sha256(&header));
        assert_eq!(crate::hex::encode_hex32(&natural), natural_hex);
        let mut reversed = natural;
        reversed.reverse();
        assert_eq!(crate::hex::encode_hex32(&reversed), display_hex);
        assert_ne!(natural_hex, display_hex);

        let mut hike = quiet_hike();
        hike.stages[4].beacon = natural;
        let first = hike.stages[1].waypoint;
        let second = hike.stages[2].waypoint;
        hike.stages[1].path = crate::pathbody::encode_path_for_block(&first, &natural, 5);
        hike.stages[2].path = crate::pathbody::encode_path_for_block(&second, &natural, 5);
        let measurements = verify(
            &encode_hike(&hike),
            AuditDraw::PinnedSeed([0u8; 32]),
            |height| {
                if height == hike.last_interrupt_height {
                    return Ok(IndexBeacon {
                        natural: hike.last_interrupt,
                        height,
                        header_time_s: Some(height * 1_000),
                        timestamp_unix_ms: None,
                    });
                }
                let stage = hike
                    .stages
                    .iter()
                    .find(|stage| stage.beacon_height == height)
                    .expect("stored header");
                Ok(IndexBeacon {
                    natural: stage.beacon,
                    height,
                    header_time_s: Some(height * 1_000),
                    timestamp_unix_ms: None,
                })
            },
            |_| Ok(()),
        );
        let value = record_json(&measurements);
        assert_eq!(value["headers"][4]["stored_hash"], natural_hex);
        assert_eq!(value["index_comparison_P_b"], "match");
        assert_eq!(value["anchors"][0], "anchored");
        assert_eq!(value["anchors"][1], "anchored");
        for index in [0, 1] {
            assert_eq!(value["paths"][index]["plant"], "match");
            for key in ["P_g", "claimed_block"] {
                assert_eq!(value["paths"][index][key]["block_hash"], display_hex);
                assert_ne!(value["paths"][index][key]["block_hash"], natural_hex);
                assert_eq!(value["paths"][index][key]["block_height"], 5);
            }
        }
        assert_eq!(value["P_b"]["block_hash"], display_hex);
        assert_ne!(value["P_b"]["block_hash"], natural_hex);
        assert_eq!(value["P_b"]["block_height"], 5);
    }

    fn unhex(spelling: &str) -> Vec<u8> {
        assert!(spelling.len() % 2 == 0, "hex");
        (0..spelling.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&spelling[i..i + 2], 16).expect("hex digit"))
            .collect()
    }

    #[test]
    fn bind_thread_setting_uses_six_when_unset_or_blank_or_zero() {
        assert_eq!(
            crate::consts::bind_threads_from(None),
            crate::consts::DEFAULT_BIND_THREADS
        );
        assert_eq!(crate::consts::bind_threads_from(Some("")), 6);
        assert_eq!(crate::consts::bind_threads_from(Some("0")), 6);
        assert_eq!(crate::consts::bind_threads_from(Some("nope")), 6);
        assert_eq!(crate::consts::bind_threads_from(Some("1")), 1);
        assert_eq!(crate::consts::bind_threads_from(Some(" 4 ")), 4);
    }
}
