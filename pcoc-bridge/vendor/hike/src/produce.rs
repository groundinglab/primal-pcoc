//! Produce procedure, section 13.
//! `observe` is the plane tip sequence. Both planes read gossip.
//! The test profile reads the synth. The explicit live profile reads
//! Bitcoin selected-chain gossip. When no peer is named, that start uses
//! Bitcoin Core's mainnet DNS seeds. A lookup returns node addresses. The
//! producer connects and asks those nodes for more peers. A bad address
//! fails and is discarded. The list is Core's own bootstrap. Re-check it
//! against mainnet chainparams when a producer release is cut. The test
//! profile does not use those seeds. Neither profile reads an HTTP tip.
//!
//! Waypoint anchoring stays the Bari post and the Bari path. On the live
//! profile the plant is a Bitcoin transaction, so the anchor is the block.
//! Bari serves that path for the section 13.1 anchor window. This producer does not anchor
//! by talking to Bitcoin and skipping Bari.
//! `open_stage` posts `W_g` for `g ≥ 1` before any leg of that stage. Stage 0
//! is not posted. The callback's height is that stage's beacon height, which
//! is H. Both profiles return a placeholder and [`produce_with_settlement`]
//! writes the path after the anchor-window asks. That settlement does not run a leg.
//!
//! The tip clock is `observe`. This function does not dial Bari for a tip.
//! A skipped path is empty bytes. The leg continues.
//! A Class S clock failure still returns.
//!
//! Produce is a continuous drive. A header interrupts it. The header the stage
//! binds is the Observe after `open_stage` returns. Stage open must not pause
//! the drive to wait for further headers.

use crate::bind::{
    checkpoint, checkpoint_sized, successor_waypoint_sized, terrain_sample, terrain_sample_sized,
};
use crate::consts::{Sizes, STAGES};
use crate::object::{encode_hike, encode_hike_sized, Hike, Stage};
use crate::sha::sha256;
use crate::terrain::{fill_pin, fill_terrain, fill_terrain_sized, production_seed};
use crate::walk::{leg_result, leg_result_sized};

/// One stage record from [`produce_stage`]. The 18-stage driver stores these fields.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StageRun {
    pub beacon: [u8; 32],
    pub beacon_height: u64,
    pub waypoint: [u8; 32],
    pub path: Vec<u8>,
    pub leg_count: u64,
    pub checkpoints: Vec<[u8; 32]>,
    pub leg_results: Vec<[u8; 8]>,
    pub closing_tip: [u8; 32],
    pub closing_height: u64,
}

#[derive(Debug)]
pub struct ProduceFailure {
    pub message: String,
    /// HTTP status when the failure is an HTTP answer. Absent when the call
    /// was not answered.
    pub http_status: Option<u16>,
}

impl ProduceFailure {
    pub fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            http_status: None,
        }
    }

    pub fn with_http_status(mut self, status: u16) -> Self {
        self.http_status = Some(status);
        self
    }
}

impl std::fmt::Display for ProduceFailure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

pub fn produce<O, P>(
    public_key: [u8; 32],
    observe: O,
    open_stage: P,
) -> Result<Vec<u8>, ProduceFailure>
where
    O: FnMut() -> Result<([u8; 32], u64), ProduceFailure>,
    P: FnMut(&[u8; 32], u64) -> Result<Vec<u8>, ProduceFailure>,
{
    produce_with_settlement(public_key, observe, open_stage, |_, _| Ok(()))
}

/// [`produce`], then `settle` may replace stage paths before the object is encoded.
///
/// `settle` runs after the last leg. It may wait for later public ticks. It
/// must not record a leg. The live profile uses it for the path GET.
pub fn produce_with_settlement<O, P, S>(
    public_key: [u8; 32],
    observe: O,
    open_stage: P,
    settle: S,
) -> Result<Vec<u8>, ProduceFailure>
where
    O: FnMut() -> Result<([u8; 32], u64), ProduceFailure>,
    P: FnMut(&[u8; 32], u64) -> Result<Vec<u8>, ProduceFailure>,
    S: FnMut(&mut [Stage], u64) -> Result<(), ProduceFailure>,
{
    // The CLI stays on section 6. Callers that already hold a finite tip script
    // pass the size set they measured with.
    produce_with_settlement_sized(public_key, Sizes::production(), observe, open_stage, settle)
}

/// [`produce_with_settlement`] at `sizes`.
///
/// Section 6 is [`Sizes::production`]. The CLI uses that wrapper. A test can
/// pass another size set with a finite tip script so each stage ends on the
/// next scripted header. `settle` still runs after the last leg and must not
/// record one. A missed waypoint POST does not withhold the object.
pub fn produce_with_settlement_sized<O, P, S>(
    public_key: [u8; 32],
    sizes: Sizes,
    mut observe: O,
    mut open_stage: P,
    mut settle: S,
) -> Result<Vec<u8>, ProduceFailure>
where
    O: FnMut() -> Result<([u8; 32], u64), ProduceFailure>,
    P: FnMut(&[u8; 32], u64) -> Result<Vec<u8>, ProduceFailure>,
    S: FnMut(&mut [Stage], u64) -> Result<(), ProduceFailure>,
{
    let (seed_beacon, height_0) = observe()?;
    let seed = production_seed(&public_key, &seed_beacon);
    let pin = fill_pin(&seed);
    let terrain = if sizes.is_production() {
        fill_terrain(pin)
    } else {
        fill_terrain_sized(pin, sizes)
    };
    let seed_value = sha256(&seed);

    let mut tip = seed_beacon;
    let mut tip_height = height_0;
    let mut next_waypoint = seed_value;
    let mut stages = Vec::with_capacity(STAGES);
    let mut last_interrupt = [0u8; 32];
    let mut last_interrupt_height = 0u64;

    for g in 0..STAGES {
        let waypoint = next_waypoint;
        // The beacon is the header the stage starts from. Stage 0 keeps the
        // seed. Later stages keep I_{g-1}. No second Observe replaces it.
        let run = drive_stage(
            &terrain,
            sizes,
            tip,
            tip_height,
            waypoint,
            g == 0,
            &mut observe,
            &mut open_stage,
        )?;
        // W_{g+1} is built from I_g, the interrupt that ended this stage.
        let successor = if g + 1 < STAGES {
            Some(successor_waypoint_sized(
                &terrain,
                &run.closing_tip,
                &run.waypoint,
                &run.checkpoints,
                sizes,
            ))
        } else {
            None
        };
        if g + 1 == STAGES {
            last_interrupt = run.closing_tip;
            last_interrupt_height = run.closing_height;
        }
        tip = run.closing_tip;
        tip_height = run.closing_height;
        let closed = format!(
            "stage_closed call_site=produce stage={} leg_count={} beacon_height={}",
            g + 1,
            run.leg_count,
            run.beacon_height
        );
        eprintln!("{closed}");
        crate::note_ops_line(&closed);
        stages.push(Stage {
            beacon: run.beacon,
            beacon_height: run.beacon_height,
            leg_count: run.leg_count,
            waypoint: run.waypoint,
            path: run.path,
            checkpoints: run.checkpoints,
            leg_results: run.leg_results,
            checkpoint_rows_defined: true,
            leg_result_missing: Vec::new(),
        });
        if let Some(waypoint) = successor {
            next_waypoint = waypoint;
        }
    }

    settle(&mut stages, last_interrupt_height)?;
    let hike = Hike {
        public_key,
        seed_beacon,
        stages,
        last_interrupt,
        last_interrupt_height,
        last_interrupt_absent: false,
        last_interrupt_hash_absent: false,
        last_interrupt_height_absent: false,
    };
    if sizes.is_production() {
        Ok(encode_hike(&hike))
    } else {
        Ok(encode_hike_sized(&hike, sizes))
    }
}

/// One stage of the drive. The stage-loop rig calls this. The 18-stage driver
/// uses the same leg loop and keeps stage 0 on the seed beacon.
///
/// The leg, the terrain sample, and the checkpoint are the production functions.
/// `observe` and `open_stage` are the header and Bari boundaries.
///
/// The beacon is the incoming tip, the header the stage started from.
/// `open_stage` runs first and does not replace that tip. The drive then
/// records legs until a later header interrupts it. A header read inside
/// `open_stage` is not that interrupt. Stage open must not pause the drive
/// to wait for further headers. A newer header is read at the Observe after
/// the first checkpoint and ends the stage at one leg.
///
/// The 18-stage driver keeps stage 0 on the seed beacon and keeps each later
/// stage on `I_{g-1}`. This function does not Observe after open.
pub fn produce_stage<O, P>(
    terrain: &[u8],
    incoming_tip: [u8; 32],
    incoming_height: u64,
    waypoint: [u8; 32],
    observe: &mut O,
    open_stage: &mut P,
) -> Result<StageRun, ProduceFailure>
where
    O: FnMut() -> Result<([u8; 32], u64), ProduceFailure>,
    P: FnMut(&[u8; 32], u64) -> Result<Vec<u8>, ProduceFailure>,
{
    drive_stage(
        terrain,
        Sizes::production(),
        incoming_tip,
        incoming_height,
        waypoint,
        false,
        observe,
        open_stage,
    )
}

fn drive_stage<O, P>(
    terrain: &[u8],
    sizes: Sizes,
    incoming_tip: [u8; 32],
    incoming_height: u64,
    waypoint: [u8; 32],
    keep_seed_beacon: bool,
    observe: &mut O,
    open_stage: &mut P,
) -> Result<StageRun, ProduceFailure>
where
    O: FnMut() -> Result<([u8; 32], u64), ProduceFailure>,
    P: FnMut(&[u8; 32], u64) -> Result<Vec<u8>, ProduceFailure>,
{
    // Stage 0 posts nothing. H for later asks is this stage's beacon height.
    let path = if keep_seed_beacon {
        Vec::new()
    } else {
        open_stage(&waypoint, incoming_height)?
    };
    let (beacon, beacon_height) = (incoming_tip, incoming_height);
    // Leg 0 starts from the seed value on stage 0 and from W_g later.
    let mut previous = waypoint;
    let mut checkpoints = Vec::new();
    let mut leg_results = Vec::new();
    let closing_tip;
    let closing_height;
    loop {
        let result = if sizes.is_production() {
            leg_result(terrain, &previous)
        } else {
            leg_result_sized(terrain, &previous, sizes)
        };
        let digest = if sizes.is_production() {
            let sample = terrain_sample(terrain, &previous);
            checkpoint(&previous, &waypoint, &sample, result)
        } else {
            let sample = terrain_sample_sized(terrain, &previous, sizes);
            checkpoint_sized(&previous, &waypoint, &sample, result, sizes)
        };
        checkpoints.push(digest);
        leg_results.push(result.to_le_bytes());
        previous = digest;
        let (tip, tip_height) = observe()?;
        if tip != beacon && tip_height > beacon_height {
            closing_tip = tip;
            closing_height = tip_height;
            break;
        }
    }
    let leg_count = checkpoints.len() as u64;
    Ok(StageRun {
        beacon,
        beacon_height,
        waypoint,
        path,
        leg_count,
        checkpoints,
        leg_results,
        closing_tip,
        closing_height,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// SPEC §16.1 is a verifier input. Produce keeps `fill_terrain`, `leg_result`,
    /// and `encode_hike`, which are the section 6 sizes.
    #[test]
    fn produce_path_stays_on_section_6_sizes() {
        let source = include_str!("produce.rs");
        let hook = ["conformance", "_small"].concat();
        let profile = ["Measure", "Profile"].concat();
        assert!(!source.contains(&hook));
        assert!(!source.contains(&profile));
        assert!(source.contains("fill_terrain("));
        assert!(source.contains("leg_result("));
        assert!(source.contains("encode_hike("));
    }

    /// Rules A1.1, A1.2, and A2.4 on a finite tip script. One stage has two legs.
    /// Stage 0 is not posted. Each later beacon is the interrupt that closed the stage before it.
    #[test]
    fn scripted_tips_chain_interrupts_and_do_not_post_stage_zero() {
        let sizes = Sizes {
            sample_bytes: 32,
            slot_bytes: 64,
            terrain_bytes: 256,
            steps_per_leg: 32,
        };
        let mut hash = [0x10u8; 32];
        let mut height = 1_000u64;
        let mut tips = Vec::new();
        let mut beacons = Vec::new();
        tips.push((hash, height));
        beacons.push((hash, height));
        for g in 0..STAGES {
            if g == 3 {
                tips.push((hash, height));
            }
            hash[0] = hash[0].wrapping_add(1);
            height += 1;
            tips.push((hash, height));
            if g + 1 < STAGES {
                beacons.push((hash, height));
            }
        }
        let closing = *tips.last().unwrap();
        let mut at = 0usize;
        let mut posts = Vec::new();
        let bytes = produce_with_settlement_sized(
            [0x11; 32],
            sizes,
            || {
                let tip = tips[at];
                at += 1;
                Ok(tip)
            },
            |_waypoint, beacon_height| {
                posts.push(beacon_height);
                Ok(Vec::new())
            },
            |_, _| Ok(()),
        )
        .expect("scripted produce");
        assert_eq!(at, tips.len());
        assert_eq!(at, 1 + 19);
        assert_eq!(posts.len(), 17);
        let measured = crate::object::read_measured_object_sized(&bytes, sizes).unwrap();
        assert!(measured.shape);
        let hike = measured.hike;
        assert_eq!(hike.stages[0].path, Vec::<u8>::new());
        assert_eq!(posts[0], hike.stages[1].beacon_height);
        for (g, stage) in hike.stages.iter().enumerate() {
            assert_eq!(stage.beacon, beacons[g].0);
            assert_eq!(stage.beacon_height, beacons[g].1);
            let legs = if g == 3 { 2 } else { 1 };
            assert_eq!(stage.leg_count, legs);
        }
        assert_eq!(hike.stages[1].beacon, beacons[1].0);
        assert_eq!(hike.last_interrupt, closing.0);
        assert_eq!(hike.last_interrupt_height, closing.1);
        assert_ne!(hike.stages[17].beacon, hike.last_interrupt);
    }

    /// Rules A1.1, A1.2 and SPEC section 13 P13.3, on the small sizes.
    /// A header read inside stage open is not the interrupt and does not pause
    /// the drive. The tip is read after each checkpoint. A tip equal to the
    /// beacon does not end the stage; the first later header does.
    #[test]
    fn stage_open_header_is_not_the_interrupt_and_tip_is_read_per_checkpoint() {
        let sizes = Sizes {
            sample_bytes: 32,
            slot_bytes: 64,
            terrain_bytes: 256,
            steps_per_leg: 32,
        };
        let terrain = crate::fill_terrain_sized(crate::fill_pin(&[0x11; 64]), sizes);
        let incoming = ([0x20u8; 32], 100u64);
        let newer = ([0x21u8; 32], 101u64);
        let mut opened = 0;
        let mut observed = Vec::new();
        // Leg 1 and leg 2 still see the beacon. Leg 3 sees the newer header.
        let script = [incoming, incoming, newer];
        let run = drive_stage(
            &terrain,
            sizes,
            incoming.0,
            incoming.1,
            [0x33; 32],
            false,
            &mut || {
                let tip = script[observed.len()];
                observed.push(tip);
                Ok(tip)
            },
            &mut |_wp: &[u8; 32], _h: u64| {
                opened += 1;
                // A newer header arriving during open is not read as the interrupt.
                Ok(Vec::new())
            },
        )
        .expect("small stage");
        assert_eq!(opened, 1);
        assert_eq!(observed.len(), 3, "one Observe per checkpoint");
        assert_eq!(run.leg_count, 3);
        assert_eq!(run.checkpoints.len(), 3);
        assert_eq!(run.leg_results.len(), 3);
        assert_eq!((run.beacon, run.beacon_height), incoming);
        assert_eq!((run.closing_tip, run.closing_height), newer);

        // Newer header at the first Observe ends the stage at one leg (A1.2).
        let mut once = false;
        let one = drive_stage(
            &terrain,
            sizes,
            incoming.0,
            incoming.1,
            [0x33; 32],
            false,
            &mut || {
                once = true;
                Ok(newer)
            },
            &mut |_wp: &[u8; 32], _h: u64| Ok(Vec::new()),
        )
        .expect("one-leg stage");
        assert!(once);
        assert_eq!(one.leg_count, 1);
    }

    #[test]
    fn clock_failure_does_not_open_a_stage() {
        let mut opened = false;
        let err = produce(
            [0x11; 32],
            || Err(ProduceFailure::new("clock")),
            |_, _| {
                opened = true;
                Ok(Vec::new())
            },
        );
        assert!(err.is_err());
        assert!(!opened);
    }

    #[test]
    fn stage_0_is_not_posted_and_the_first_post_is_not_zero() {
        let mut n = 0u32;
        let mut seen = None;
        let mut heights = Vec::new();
        let err = produce(
            [0x11; 32],
            || {
                n += 1;
                if n == 1 {
                    Ok(([0x22; 32], 9))
                } else {
                    Ok(([0x33; 32], 10))
                }
            },
            |waypoint, height| {
                seen = Some(*waypoint);
                heights.push(height);
                Err(ProduceFailure::new("path missing"))
            },
        );
        assert!(err.is_err());
        let posted = seen.expect("W_1 was posted");
        assert_ne!(posted, [0u8; 32]);
        // H for W_1 is I_0's height, the beacon stage 1 stores.
        assert_eq!(heights, vec![10]);
        let public_key = [0x11; 32];
        let seed_beacon = [0x22; 32];
        let terrain = crate::terrain::fill_terrain(crate::terrain::fill_pin(
            &crate::terrain::production_seed(&public_key, &seed_beacon),
        ));
        let seed_value = sha256(&crate::terrain::production_seed(&public_key, &seed_beacon));
        assert_ne!(posted, seed_value);
        let result = crate::walk::leg_result(&terrain, &seed_value);
        let sample = crate::bind::terrain_sample(&terrain, &seed_value);
        let digest = crate::bind::checkpoint(&seed_value, &seed_value, &sample, result);
        let interrupt = [0x33; 32];
        let w1 = crate::bind::successor_waypoint(&terrain, &interrupt, &seed_value, &[digest]);
        assert_eq!(posted, w1);
    }

    /// P9.14. The first tip is already known. Observing it again after the
    /// first leg does not wait for a newer header and does not replace the seed.
    #[test]
    fn stage0_starts_on_the_latest_known_header() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../spec/conformance/v2.0/stage0-start.json");
        let text = std::fs::read_to_string(&path)
            .unwrap_or_else(|err| panic!("read {}: {err}", path.display()));
        let doc: serde_json::Value = serde_json::from_str(&text).unwrap();
        assert_eq!(doc["rule"], "P9.14");
        assert_eq!(doc["profile"], "conformance-small");
        let public_key = crate::hex::decode_hex32(doc["public_key_hex"].as_str().unwrap()).unwrap();
        let seed_height = doc["seed_height"].as_u64().unwrap();
        let stage0_legs = doc["stage0_legs"].as_u64().unwrap();
        let tips: Vec<([u8; 32], u64)> = doc["tips"]
            .as_array()
            .unwrap()
            .iter()
            .map(|tip| {
                (
                    crate::hex::decode_hex32(tip["natural_hex"].as_str().unwrap()).unwrap(),
                    tip["height"].as_u64().unwrap(),
                )
            })
            .collect();
        assert!(tips.len() >= 3);
        assert_eq!(tips[0], tips[1]);
        assert_eq!(tips[0].1, seed_height);
        // Same integers as the conformance-small profile. This file's source
        // guard rejects that profile constructor's name.
        let sizes = Sizes {
            sample_bytes: 32,
            slot_bytes: 64,
            terrain_bytes: 256,
            steps_per_leg: 32,
        };
        let mut at = 0usize;
        let bytes = produce_with_settlement_sized(
            public_key,
            sizes,
            || {
                let tip = tips[at];
                at += 1;
                Ok(tip)
            },
            |_, _| Ok(Vec::new()),
            |_, _| Ok(()),
        )
        .expect("stage 0 start vector");
        assert_eq!(at, tips.len());
        let measured = crate::object::read_measured_object_sized(&bytes, sizes).unwrap();
        assert!(measured.shape);
        let hike = measured.hike;
        assert_eq!(hike.seed_beacon, tips[0].0);
        assert_eq!(hike.stages[0].beacon, tips[0].0);
        assert_eq!(hike.stages[0].beacon_height, seed_height);
        assert_eq!(hike.stages[0].leg_count, stage0_legs);
        assert_eq!(hike.stages[1].beacon, tips[2].0);
        assert_eq!(hike.stages[1].beacon_height, tips[2].1);
        assert_ne!(hike.stages[0].beacon, hike.stages[1].beacon);
    }
}
