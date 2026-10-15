//! Hike object, section 8. Encoding follows section 7.3.

use crate::cbor::{self, Cbor, Fail};
use crate::consts::*;
use crate::pathbody;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Stage {
    pub beacon: [u8; 32],
    pub beacon_height: u64,
    pub leg_count: u64,
    pub waypoint: [u8; 32],
    pub path: Vec<u8>,
    pub checkpoints: Vec<[u8; 32]>,
    pub leg_results: Vec<[u8; 8]>,
    /// False when a checkpoint element is not 32 bytes. That stage's `legs` is `not defined`.
    pub checkpoint_rows_defined: bool,
    /// `true` at index `j` when that checkpoint has no 8-byte `leg_results[j]`.
    /// Empty means every stored limb is present. Ignored when rows are not defined.
    pub leg_result_missing: Vec<bool>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Hike {
    pub public_key: [u8; 32],
    pub seed_beacon: [u8; 32],
    pub stages: Vec<Stage>,
    /// `I_17`. Absent on the wire is a shape measurement, stored here as zeros.
    pub last_interrupt: [u8; 32],
    pub last_interrupt_height: u64,
    /// True when the wire omitted `last_interrupt` or its height.
    pub last_interrupt_absent: bool,
    /// The wire omitted the 32-byte `last_interrupt` hash.
    pub last_interrupt_hash_absent: bool,
    /// The wire omitted `last_interrupt_height`.
    pub last_interrupt_height_absent: bool,
}

const TOP_KEYS: &[&str] = &[
    "fill",
    "kind",
    "stages",
    "n_slots",
    "public_key",
    "slot_bytes",
    "seed_beacon",
    "sample_bytes",
    "steps_per_leg",
    "last_interrupt",
    "last_interrupt_height",
];

const STAGE0_KEYS: &[&str] = &[
    "beacon",
    "waypoint",
    "leg_count",
    "checkpoints",
    "leg_results",
    "beacon_height",
];

const STAGE_KEYS: &[&str] = &[
    "path",
    "beacon",
    "waypoint",
    "leg_count",
    "checkpoints",
    "leg_results",
    "beacon_height",
];

fn bytes32(value: &[u8; 32]) -> Cbor {
    Cbor::Bytes(value.to_vec())
}

fn stage_cbor(stage: &Stage, include_path: bool) -> Cbor {
    assert!(stage.checkpoint_rows_defined);
    assert!(stage.leg_result_missing.iter().all(|missing| !missing));
    assert_eq!(stage.leg_count as usize, stage.checkpoints.len());
    assert_eq!(stage.checkpoints.len(), stage.leg_results.len());
    let checkpoints = Cbor::Array(
        stage
            .checkpoints
            .iter()
            .map(|c| Cbor::Bytes(c.to_vec()))
            .collect(),
    );
    let leg_results = Cbor::Array(
        stage
            .leg_results
            .iter()
            .map(|c| Cbor::Bytes(c.to_vec()))
            .collect(),
    );
    let mut pairs = Vec::new();
    if include_path {
        pairs.push(("path".to_owned(), Cbor::Bytes(stage.path.clone())));
    }
    pairs.extend([
        ("beacon".to_owned(), bytes32(&stage.beacon)),
        ("waypoint".to_owned(), bytes32(&stage.waypoint)),
        ("leg_count".to_owned(), Cbor::Uint(stage.leg_count)),
        ("checkpoints".to_owned(), checkpoints),
        ("leg_results".to_owned(), leg_results),
        ("beacon_height".to_owned(), Cbor::Uint(stage.beacon_height)),
    ]);
    Cbor::Map(pairs)
}

fn hike_cbor(hike: &Hike, sizes: Sizes) -> Cbor {
    let stages = Cbor::Array(
        hike.stages
            .iter()
            .enumerate()
            .map(|(g, stage)| stage_cbor(stage, g != 0))
            .collect(),
    );
    Cbor::Map(vec![
        ("fill".to_owned(), Cbor::Text(FILL.to_owned())),
        ("kind".to_owned(), Cbor::Text(KIND.to_owned())),
        ("stages".to_owned(), stages),
        ("n_slots".to_owned(), Cbor::Uint(sizes.n_slots() as u64)),
        ("public_key".to_owned(), bytes32(&hike.public_key)),
        ("slot_bytes".to_owned(), Cbor::Uint(sizes.slot_bytes as u64)),
        ("seed_beacon".to_owned(), bytes32(&hike.seed_beacon)),
        ("sample_bytes".to_owned(), Cbor::Uint(sizes.sample_bytes as u64)),
        ("steps_per_leg".to_owned(), Cbor::Uint(sizes.steps_per_leg)),
        ("last_interrupt".to_owned(), bytes32(&hike.last_interrupt)),
        (
            "last_interrupt_height".to_owned(),
            Cbor::Uint(hike.last_interrupt_height),
        ),
    ])
}

pub fn encode_hike(hike: &Hike) -> Vec<u8> {
    encode_hike_sized(hike, Sizes::production())
}

/// Encode with the profile in force. SPEC §16.1 may change only the four size
/// integers and the derived `n_slots`. The object gains no new field.
pub fn encode_hike_sized(hike: &Hike, sizes: Sizes) -> Vec<u8> {
    cbor::encode(&hike_cbor(hike, sizes))
}

fn pairs(value: &Cbor) -> Result<&[(String, Cbor)], Fail> {
    match value {
        Cbor::Map(pairs) => Ok(pairs),
        _ => Err(Fail("expected a map")),
    }
}

fn exact_keys(map: &[(String, Cbor)], expected: &[&str]) -> Result<(), Fail> {
    if map.len() != expected.len() {
        return Err(Fail("map field set"));
    }
    for key in expected {
        if !map.iter().any(|(k, _)| k == key) {
            return Err(Fail("map field set"));
        }
    }
    Ok(())
}

fn field<'a>(map: &'a [(String, Cbor)], key: &str) -> Result<&'a Cbor, Fail> {
    map.iter()
        .find(|(k, _)| k == key)
        .map(|(_, v)| v)
        .ok_or(Fail("missing field"))
}

fn field_text<'a>(map: &'a [(String, Cbor)], key: &str) -> Result<&'a str, Fail> {
    match field(map, key)? {
        Cbor::Text(s) => Ok(s),
        _ => Err(Fail("expected text")),
    }
}

fn field_uint(map: &[(String, Cbor)], key: &str) -> Result<u64, Fail> {
    match field(map, key)? {
        Cbor::Uint(n) => Ok(*n),
        _ => Err(Fail("expected unsigned")),
    }
}

fn field_bytes(map: &[(String, Cbor)], key: &str, len: usize) -> Result<Vec<u8>, Fail> {
    let bytes = field_byte_string(map, key)?;
    if bytes.len() == len {
        Ok(bytes)
    } else {
        Err(Fail("byte length"))
    }
}

fn field_byte_string(map: &[(String, Cbor)], key: &str) -> Result<Vec<u8>, Fail> {
    match field(map, key)? {
        Cbor::Bytes(b) => Ok(b.clone()),
        _ => Err(Fail("expected bytes")),
    }
}

fn field_array<'a>(map: &'a [(String, Cbor)], key: &str) -> Result<&'a [Cbor], Fail> {
    match field(map, key)? {
        Cbor::Array(items) => Ok(items),
        _ => Err(Fail("expected array")),
    }
}

fn arr32(bytes: &[u8]) -> [u8; 32] {
    let mut out = [0u8; 32];
    out.copy_from_slice(bytes);
    out
}

fn parse_stage(value: &Cbor) -> Result<(Stage, bool, Vec<&'static str>), Fail> {
    let map = pairs(value)?;
    let path_key = map.iter().any(|(key, _)| key == "path");
    exact_keys(map, if path_key { STAGE_KEYS } else { STAGE0_KEYS })?;
    let beacon = arr32(&field_bytes(map, "beacon", 32)?);
    let waypoint = arr32(&field_bytes(map, "waypoint", 32)?);
    // Shape does not decode `path`. Empty bytes are the section 13.3 produce fact.
    // Stage 0 has no path key (Rule A3.3).
    let path = if path_key {
        field_byte_string(map, "path")?
    } else {
        Vec::new()
    };
    let leg_count = field_uint(map, "leg_count")?;
    let beacon_height = field_uint(map, "beacon_height")?;
    let mut notes = Vec::new();
    let mut checkpoints = Vec::new();
    let mut checkpoint_elems = 0usize;
    let mut bad_checkpoint = false;
    for item in field_array(map, "checkpoints")? {
        checkpoint_elems += 1;
        match item {
            Cbor::Bytes(b) if b.len() == 32 => checkpoints.push(arr32(b)),
            _ => bad_checkpoint = true,
        }
    }
    let mut limbs: Vec<Option<[u8; 8]>> = Vec::new();
    let mut bad_limb = false;
    for item in field_array(map, "leg_results")? {
        match item {
            Cbor::Bytes(b) if b.len() == 8 => {
                let mut limb = [0u8; 8];
                limb.copy_from_slice(b);
                limbs.push(Some(limb));
            }
            _ => {
                bad_limb = true;
                limbs.push(None);
            }
        }
    }
    // Array length is the element count, including a short-width element.
    // A short width is `checkpoint width` or `leg result width`, not `leg_count`,
    // when those counts still agree.
    let counts_agree =
        leg_count as usize == checkpoint_elems && limbs.len() == checkpoint_elems;
    if !bad_checkpoint && !counts_agree {
        notes.push("leg_count");
    }
    if bad_checkpoint {
        notes.push("checkpoint");
        checkpoints.clear();
    }
    if bad_limb {
        notes.push("leg result");
    }
    let mut leg_results = Vec::new();
    let mut leg_result_missing = Vec::new();
    if !bad_checkpoint {
        let mut flags = Vec::with_capacity(checkpoints.len());
        let mut missing_any = false;
        for j in 0..checkpoints.len() {
            match limbs.get(j).copied().flatten() {
                Some(limb) => {
                    leg_results.push(limb);
                    flags.push(false);
                }
                None => {
                    leg_results.push([0u8; 8]);
                    flags.push(true);
                    missing_any = true;
                }
            }
        }
        // An empty flag list means every limb is present, so a round trip
        // matches a stage built without holes.
        if missing_any {
            leg_result_missing = flags;
        }
    }
    Ok((
        Stage {
            beacon,
            beacon_height,
            leg_count,
            waypoint,
            path,
            checkpoints,
            leg_results,
            checkpoint_rows_defined: !bad_checkpoint,
            leg_result_missing,
        },
        path_key,
        notes,
    ))
}

/// Object public key from a hike whose shape is true.
///
/// `Err` means the bytes are not a usable object, or the shape is false.
/// This does not record a later measurement, does not recompute a leg, and
/// does not return a leg result.
pub fn object_public_key(bytes: &[u8]) -> Result<[u8; 32], ()> {
    decode_hike(bytes)
        .map(|hike| hike.public_key)
        .map_err(|_| ())
}

/// What a sign-in can read from the object before section 14.
///
/// `Undecodable` is a CBOR value the reference decoder does not accept.
/// That case is a section 15 shape failure inside `verify`. A value that
/// decodes and has no 32-byte `public_key` is `NoPublicKey`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum YieldedKey {
    Undecodable,
    NoPublicKey,
    PublicKey([u8; 32]),
}

/// `public_key` when the bytes are one CBOR item and that field is 32 bytes.
///
/// Trailing bytes do not hide a key that the first item yielded. A duplicate
/// key, a tag, or a truncated item does not decode.
/// Rule A5. `Some(kind)` when the CBOR map's `kind` text is an earlier kind.
///
/// A map that does not decode, or a kind that is not in [`crate::consts::EARLIER_KINDS`],
/// is `None`. That case stays a section 14 shape measurement or a decode failure.
pub fn earlier_object_kind(bytes: &[u8]) -> Option<String> {
    let (value, _) = cbor::decode_head(bytes).ok()?;
    let Cbor::Map(pairs) = value else {
        return None;
    };
    let (_, item) = pairs.iter().find(|(key, _)| key == "kind")?;
    let Cbor::Text(kind) = item else {
        return None;
    };
    if crate::consts::EARLIER_KINDS.contains(&kind.as_str()) {
        Some(kind.clone())
    } else {
        None
    }
}

pub fn yielded_public_key(bytes: &[u8]) -> YieldedKey {
    let Ok((value, _trailing)) = cbor::decode_head(bytes) else {
        return YieldedKey::Undecodable;
    };
    let Cbor::Map(pairs) = value else {
        return YieldedKey::NoPublicKey;
    };
    let Some((_, item)) = pairs.iter().find(|(key, _)| key == "public_key") else {
        return YieldedKey::NoPublicKey;
    };
    match item {
        Cbor::Bytes(found) if found.len() == 32 => {
            let mut key = [0u8; 32];
            key.copy_from_slice(found);
            YieldedKey::PublicKey(key)
        }
        _ => YieldedKey::NoPublicKey,
    }
}

/// A typed section 8 object. `shape` is the section 14.1 measurement.
///
/// A `Fail` from [`read_measured_object`] means the bytes did not yield a
/// usable hike. A trailing byte or an extra top-level key is a false shape
/// on the returned object when the stage values did decode.
pub(crate) struct MeasuredObject {
    pub hike: Hike,
    pub shape: bool,
    pub shape_fail: Option<Fail>,
    /// Every shape note, in the order recorded. A seed-value mismatch is
    /// `seed_value`, not one of these notes.
    pub shape_notes: Vec<&'static str>,
}

fn top_keys_exact(map: &[(String, Cbor)]) -> bool {
    map.len() == TOP_KEYS.len() && TOP_KEYS.iter().all(|key| map.iter().any(|(k, _)| k == key))
}

pub(crate) fn read_measured_object(bytes: &[u8]) -> Result<MeasuredObject, Fail> {
    read_measured_object_sized(bytes, Sizes::production())
}

pub(crate) fn read_measured_object_sized(bytes: &[u8], sizes: Sizes) -> Result<MeasuredObject, Fail> {
    let (value, trailing) = cbor::decode_head(bytes)?;
    // A byte after the one data item is exactly `malformed cbor` (section 14).
    // A later field error must not replace that note. A map that still yields
    // a hike is measured; the trailing byte only makes the shape false.
    match read_measured_map(&value, trailing, sizes) {
        Ok(measured) => Ok(measured),
        Err(_) if trailing => Err(Fail("trailing bytes")),
        Err(fail) => Err(fail),
    }
}

fn read_measured_map(value: &Cbor, trailing: bool, sizes: Sizes) -> Result<MeasuredObject, Fail> {
    let map = pairs(value)?;
    let mut shape_fail = None;
    let mut shape_notes = Vec::new();
    let mut leg_count_noted = false;
    let mut note = |fail: Fail| {
        if fail.0 == "leg_count" {
            if leg_count_noted {
                return;
            }
            leg_count_noted = true;
        }
        if shape_fail.is_none() {
            shape_fail = Some(fail);
        }
        shape_notes.push(fail.0);
    };
    if trailing {
        note(Fail("trailing bytes"));
    }
    // An extra or missing top-level key is a false shape. Required fields that
    // did decode stay available to later measurements.
    if !top_keys_exact(map) {
        note(Fail("map field set"));
    }
    if field_text(map, "kind")? != KIND {
        note(Fail("kind"));
    }
    if field_text(map, "fill")? != FILL {
        note(Fail("fill"));
    }
    if field_uint(map, "slot_bytes")? != sizes.slot_bytes as u64 {
        note(Fail("slot_bytes"));
    }
    if field_uint(map, "n_slots")? != sizes.n_slots() as u64 {
        note(Fail("n_slots"));
    }
    if field_uint(map, "sample_bytes")? != sizes.sample_bytes as u64 {
        note(Fail("sample_bytes"));
    }
    if field_uint(map, "steps_per_leg")? != sizes.steps_per_leg {
        note(Fail("steps_per_leg"));
    }
    let public_key = arr32(&field_bytes(map, "public_key", 32)?);
    let seed_beacon = arr32(&field_bytes(map, "seed_beacon", 32)?);
    let stage_items = field_array(map, "stages")?;
    if stage_items.len() != STAGES {
        note(Fail("stages"));
    }
    let mut stages = Vec::with_capacity(stage_items.len());
    let mut path_keys = Vec::with_capacity(stage_items.len());
    for item in stage_items {
        let (stage, path_key, stage_notes) = parse_stage(item)?;
        for fail in stage_notes {
            note(Fail(fail));
        }
        path_keys.push(path_key);
        stages.push(stage);
    }
    for stage in &stages {
        if stage.leg_count < 1 {
            note(Fail("leg_count"));
            break;
        }
    }
    let last_interrupt = match map.iter().find(|(key, _)| key == "last_interrupt") {
        Some((_, Cbor::Bytes(bytes))) if bytes.len() == 32 => Some(arr32(bytes)),
        Some(_) => {
            note(Fail("last_interrupt"));
            None
        }
        None => None,
    };
    let last_interrupt_height = match map.iter().find(|(key, _)| key == "last_interrupt_height") {
        Some((_, Cbor::Uint(height))) => Some(*height),
        Some(_) => {
            note(Fail("last_interrupt"));
            None
        }
        None => None,
    };
    let last_absent = last_interrupt.is_none() || last_interrupt_height.is_none();
    if last_absent {
        note(Fail("last_interrupt"));
    }
    // Stage 0 beacon is the seed. Its waypoint is the seed value, Rule A2.2.
    // A stage-0 path key is a shape measurement, not a decode failure.
    if stages.len() == STAGES {
        if path_keys.first() == Some(&true) {
            // Stage 0's section 8 map has no `path` key. The extra key is
            // `field set`, and the key's presence is `stage-0 path key`.
            note(Fail("map field set"));
            note(Fail("stage-0 path"));
        }
        if path_keys.iter().skip(1).any(|present| !present) {
            note(Fail("path"));
        }
        if stages[0].beacon != seed_beacon {
            note(Fail("seed beacon"));
        }
        // Equality with SHA-256(seed) is `seed_value`, not a shape_item.
        // The shape condition is that the waypoint is 32 bytes, which the
        // field decoder already required.
        if !stages[17].path.is_empty() {
            note(Fail("W_17 path"));
        }
        for g in 0..STAGES - 1 {
            if stages[g + 1].beacon_height <= stages[g].beacon_height {
                note(Fail("beacon height"));
                break;
            }
            if stages[g + 1].beacon == stages[g].beacon {
                note(Fail("beacon"));
                break;
            }
        }
        match (last_interrupt, last_interrupt_height) {
            (Some(hash), Some(height)) => {
                if height <= stages[17].beacon_height || hash == stages[17].beacon {
                    note(Fail("last_interrupt"));
                }
            }
            (None, None) => {
                // The closing header is one of the stored headers. Both fields
                // absent violates height order and hash difference.
                note(Fail("beacon height"));
                note(Fail("beacon"));
            }
            (None, Some(height)) => {
                note(Fail("beacon"));
                if height <= stages[17].beacon_height {
                    note(Fail("beacon height"));
                }
            }
            (Some(hash), None) => {
                note(Fail("beacon height"));
                if hash == stages[17].beacon {
                    note(Fail("beacon"));
                }
            }
        }
    } else if stages.len() < STAGES {
        // A condition that tests a field's value is violated when that field is absent.
        // Stage 17 missing fails W_17 path, the last-interrupt comparison, and the
        // header-order pair that reads the missing beacons. The missing stages also
        // have no `leg_count`, so that condition is violated too. More than 18 stages
        // is only `stages length`: stage 17 may still be present.
        note(Fail("W_17 path"));
        note(Fail("last_interrupt"));
        note(Fail("beacon height"));
        note(Fail("beacon"));
        note(Fail("leg_count"));
    }
    // Rule A4.1. A stored height above 2^53−1 is `height_range` whether or not
    // the stage array has length 18. The value is not a usable record integer.
    let above_json = crate::consts::JSON_INT_MAX;
    if stages.iter().any(|stage| stage.beacon_height > above_json)
        || last_interrupt_height.is_some_and(|height| height > above_json)
    {
        note(Fail("height_range"));
    }
    Ok(MeasuredObject {
        shape: shape_fail.is_none(),
        shape_fail,
        shape_notes,
        hike: Hike {
            public_key,
            seed_beacon,
            stages,
            last_interrupt: last_interrupt.unwrap_or([0u8; 32]),
            last_interrupt_height: last_interrupt_height.unwrap_or(0),
            last_interrupt_absent: last_absent,
            last_interrupt_hash_absent: last_interrupt.is_none(),
            last_interrupt_height_absent: last_interrupt_height.is_none(),
        },
    })
}

pub fn decode_hike(bytes: &[u8]) -> Result<Hike, Fail> {
    let measured = read_measured_object(bytes)?;
    if let Some(fail) = measured.shape_fail {
        return Err(fail);
    }
    Ok(measured.hike)
}

/// Eighteen stages that pass `decode_hike`. Checkpoints and leg results are stored bytes.
pub fn structural_object() -> Hike {
    let public_key = [0x11u8; 32];
    let mut seed_beacon = [0u8; 32];
    seed_beacon[0] = 1;
    let seed_value =
        crate::sha::sha256(&crate::terrain::production_seed(&public_key, &seed_beacon));
    let mut stages = Vec::new();
    for g in 0..STAGES {
        let mut beacon = [0u8; 32];
        beacon[0] = g as u8 + 1;
        let waypoint = if g == 0 { seed_value } else { [g as u8; 32] };
        stages.push(Stage {
            beacon,
            beacon_height: g as u64 + 1,
            leg_count: 1,
            waypoint,
            path: if g == 0 || g + 1 == STAGES {
                Vec::new()
            } else {
                pathbody::encode_single_leaf(&waypoint)
            },
            checkpoints: vec![[0xAB; 32]],
            leg_results: vec![[1, 0, 0, 0, 0, 0, 0, 0]],
            checkpoint_rows_defined: true,
            leg_result_missing: Vec::new(),
        });
    }
    let mut last_interrupt = [0u8; 32];
    last_interrupt[0] = 99;
    Hike {
        public_key,
        seed_beacon,
        stages,
        last_interrupt,
        last_interrupt_height: (STAGES as u64) + 2,
        last_interrupt_absent: false,
        last_interrupt_hash_absent: false,
        last_interrupt_height_absent: false,
    }
}

#[cfg(test)]
pub(crate) fn sample_hike() -> Hike {
    structural_object()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cbor::{decode_one, encode, encode_wide_uints, Cbor};

    fn top_pairs(bytes: &[u8]) -> Vec<(String, Cbor)> {
        match decode_one(bytes).unwrap() {
            Cbor::Map(pairs) => pairs,
            other => panic!("expected map, got {other:?}"),
        }
    }

    #[test]
    fn cbor_key_order_and_kind() {
        let bytes = encode_hike(&sample_hike());
        let pairs = top_pairs(&bytes);
        let keys: Vec<&str> = pairs.iter().map(|(k, _)| k.as_str()).collect();
        assert_eq!(keys, TOP_KEYS);

        let mut sorted = TOP_KEYS.to_vec();
        sorted.sort_by_key(|name| {
            let mut enc = vec![0x60 + name.len() as u8];
            enc.extend_from_slice(name.as_bytes());
            enc
        });
        assert_eq!(sorted, TOP_KEYS);

        assert_eq!(pairs[1].1, Cbor::Text(KIND.to_owned()));
        assert_eq!(KIND.len(), 24);
        let mut kind_header = vec![0x78, 24];
        kind_header.extend_from_slice(KIND.as_bytes());
        assert!(bytes.windows(kind_header.len()).any(|w| w == kind_header));

        let prefix = hex_prefix();
        assert_eq!(&bytes[..prefix.len()], &prefix);

        match &pairs.iter().find(|(k, _)| k == "stages").unwrap().1 {
            Cbor::Array(stages) => {
                match &stages[0] {
                    Cbor::Map(stage) => {
                        let stage_keys: Vec<&str> = stage.iter().map(|(k, _)| k.as_str()).collect();
                        assert_eq!(stage_keys, STAGE0_KEYS);
                        assert!(!stage_keys.contains(&"path"));
                    }
                    _ => panic!("stage"),
                }
                match &stages[1] {
                    Cbor::Map(stage) => {
                        let stage_keys: Vec<&str> = stage.iter().map(|(k, _)| k.as_str()).collect();
                        assert_eq!(stage_keys, STAGE_KEYS);
                    }
                    _ => panic!("stage"),
                }
            }
            _ => panic!("stages"),
        }

        assert!(
            bytes
                .windows(3)
                .filter(|w| *w == [0x19, 0x40, 0x00])
                .count()
                >= 2
        );
        // sample_bytes = 1024, CBOR uint16 0x0400.
        assert!(bytes.windows(3).any(|w| w == [0x19, 0x04, 0x00]));
        // steps_per_leg = 2_000_000, CBOR uint32 0x001e8480.
        assert!(bytes
            .windows(5)
            .any(|w| w == [0x1a, 0x00, 0x1e, 0x84, 0x80]));
        assert!(!bytes
            .windows(5)
            .any(|w| w == [0x1a, 0x00, 0x0f, 0x42, 0x40]));
    }

    fn hex_prefix() -> Vec<u8> {
        // map(11), "fill", "dag2", "kind", text(24) kind value, "stages", array(18)
        let mut v = vec![
            0xab, 0x64, b'f', b'i', b'l', b'l', 0x64, b'd', b'a', b'g', b'2', 0x64, b'k', b'i',
            b'n', b'd', 0x78, 24,
        ];
        v.extend_from_slice(KIND.as_bytes());
        v.extend_from_slice(&[0x66, b's', b't', b'a', b'g', b'e', b's', 0x92]);
        v
    }

    #[test]
    fn wide_uints_round_trip() {
        let hike = sample_hike();
        let canonical = encode_hike(&hike);
        let decoded = decode_hike(&canonical).unwrap();
        assert_eq!(decoded, hike);
        let wide = encode_wide_uints(&decode_one(&canonical).unwrap());
        assert_ne!(wide, canonical);
        assert_eq!(decode_hike(&wide).unwrap(), hike);
    }

    #[test]
    fn structural_rejects() {
        let good = encode_hike(&sample_hike());
        assert!(decode_hike(&good).is_ok());

        assert!(decode_hike(&[]).is_err());
        assert!(decode_hike(&[0xbf, 0xff]).is_err());
        assert!(decode_hike(&[0xc0, 0x00]).is_err());
        assert!(decode_hike(&[0x00]).is_err());

        let mut trailing = good.clone();
        trailing.push(0x00);
        assert_eq!(decode_hike(&trailing), Err(Fail("trailing bytes")));

        let mut unknown = decode_one(&good).unwrap();
        if let Cbor::Map(pairs) = &mut unknown {
            pairs.push(("note".to_owned(), Cbor::Uint(1)));
        }
        assert!(decode_hike(&encode(&unknown)).is_err());

        let mut dup = decode_one(&good).unwrap();
        if let Cbor::Map(pairs) = &mut dup {
            let fill = pairs[0].1.clone();
            pairs.push(("fill".to_owned(), fill));
        }
        assert_eq!(decode_one(&encode(&dup)), Err(Fail("duplicate key")));

        let mut bad_kind = decode_one(&good).unwrap();
        set_top(&mut bad_kind, "kind", Cbor::Text("not-the-kind".to_owned()));
        assert!(decode_hike(&encode(&bad_kind)).is_err());

        let mut missing = decode_one(&good).unwrap();
        if let Cbor::Map(pairs) = &mut missing {
            pairs.retain(|(k, _)| k != "fill");
        }
        assert!(decode_hike(&encode(&missing)).is_err());

        let mut zero_legs = decode_one(&good).unwrap();
        set_stage_field(&mut zero_legs, 0, "leg_count", Cbor::Uint(0));
        assert!(decode_hike(&encode(&zero_legs)).is_err());

        let mut short_cp = decode_one(&good).unwrap();
        set_stage_field(
            &mut short_cp,
            0,
            "checkpoints",
            Cbor::Array(vec![Cbor::Bytes(vec![0xAB; 31])]),
        );
        assert!(decode_hike(&encode(&short_cp)).is_err());

        let mut short_limb = decode_one(&good).unwrap();
        set_stage_field(
            &mut short_limb,
            0,
            "leg_results",
            Cbor::Array(vec![Cbor::Bytes(vec![0; 7])]),
        );
        assert!(decode_hike(&encode(&short_limb)).is_err());

        let mut few_stages = decode_one(&good).unwrap();
        if let Cbor::Map(pairs) = &mut few_stages {
            if let Cbor::Array(stages) =
                &mut pairs.iter_mut().find(|(k, _)| k == "stages").unwrap().1
            {
                stages.pop();
            }
        }
        assert!(decode_hike(&encode(&few_stages)).is_err());

        let mut bad_height = decode_one(&good).unwrap();
        set_stage_field(&mut bad_height, 1, "beacon_height", Cbor::Uint(1));
        assert!(decode_hike(&encode(&bad_height)).is_err());

        let mut same_beacon = decode_one(&good).unwrap();
        set_stage_field(
            &mut same_beacon,
            1,
            "beacon",
            Cbor::Bytes({
                let mut b = vec![0u8; 32];
                b[0] = 1;
                b
            }),
        );
        assert!(decode_hike(&encode(&same_beacon)).is_err());

        let mut bad_wp = decode_one(&good).unwrap();
        set_stage_field(&mut bad_wp, 0, "waypoint", Cbor::Bytes(vec![0u8; 32]));
        let bad_wp_bytes = encode(&bad_wp);
        assert!(decode_hike(&bad_wp_bytes).is_ok());
        let measured = read_measured_object(&bad_wp_bytes).unwrap();
        assert!(measured.shape);
        assert!(measured.shape_fail.is_none());
        let record = crate::verify::verify_record_json(
            "production",
            &crate::verify::verify(
                &bad_wp_bytes,
                crate::verify::AuditDraw::PinnedSeed([0u8; 32]),
                |_| Err(()),
                |_| Ok(()),
            ),
        );
        assert!(record.contains("\"shape\":\"match\""));
        assert!(record.contains("\"shape_item\":[]"));
        assert!(record.contains("\"seed_value\":\"mismatch\""));

        let mut bad_seed = decode_one(&good).unwrap();
        set_top(&mut bad_seed, "seed_beacon", Cbor::Bytes(vec![9u8; 32]));
        assert!(decode_hike(&encode(&bad_seed)).is_err());

        let mut bad_fill = decode_one(&good).unwrap();
        set_top(&mut bad_fill, "fill", Cbor::Text("other".to_owned()));
        assert!(decode_hike(&encode(&bad_fill)).is_err());

        let mut missing_path = decode_one(&good).unwrap();
        if let Cbor::Map(pairs) = &mut missing_path {
            if let Cbor::Array(stages) =
                &mut pairs.iter_mut().find(|(k, _)| k == "stages").unwrap().1
            {
                if let Cbor::Map(stage) = &mut stages[1] {
                    stage.retain(|(k, _)| k != "path");
                }
            }
        }
        assert!(decode_hike(&encode(&missing_path)).is_err());

        // A present path that does not decode is not a false shape.
        let mut bad_path = decode_one(&good).unwrap();
        set_stage_field(&mut bad_path, 1, "path", Cbor::Bytes(vec![0x01]));
        assert!(decode_hike(&encode(&bad_path)).is_ok());

        let mut wrong_leaf = decode_one(&good).unwrap();
        set_stage_field(
            &mut wrong_leaf,
            1,
            "path",
            Cbor::Bytes(pathbody::encode_single_leaf(&[0xEE; 32])),
        );
        assert!(decode_hike(&encode(&wrong_leaf)).is_ok());

        let mut empty_path = decode_one(&good).unwrap();
        set_stage_field(&mut empty_path, 1, "path", Cbor::Bytes(Vec::new()));
        assert!(decode_hike(&encode(&empty_path)).is_ok());

        let mut stage0_path = decode_one(&good).unwrap();
        if let Cbor::Map(pairs) = &mut stage0_path {
            if let Cbor::Array(stages) =
                &mut pairs.iter_mut().find(|(k, _)| k == "stages").unwrap().1
            {
                if let Cbor::Map(stage) = &mut stages[0] {
                    stage.insert(0, ("path".to_owned(), Cbor::Bytes(Vec::new())));
                }
            }
        }
        let stage0_bytes = encode(&stage0_path);
        let measured = read_measured_object(&stage0_bytes).expect("stage 0 still decodes");
        assert!(measured.shape_notes.contains(&"map field set"));
        assert!(measured.shape_notes.contains(&"stage-0 path"));
        assert_eq!(measured.shape_fail, Some(Fail("map field set")));
        assert!(decode_hike(&stage0_bytes).is_err());

        let mut bad_slot = decode_one(&good).unwrap();
        set_top(&mut bad_slot, "slot_bytes", Cbor::Uint(8));
        assert!(decode_hike(&encode(&bad_slot)).is_err());

        let mut old_steps = decode_one(&good).unwrap();
        set_top(&mut old_steps, "steps_per_leg", Cbor::Uint(1_000_000));
        assert_eq!(decode_hike(&encode(&old_steps)), Err(Fail("steps_per_leg")));
        assert_eq!(STEPS_PER_LEG, 2_000_000);

        let read = read_measured_object(&encode(&old_steps)).unwrap();
        assert!(!read.shape);
        assert_eq!(read.hike.stages.len(), STAGES);

        let mut missing_interrupt = decode_one(&good).unwrap();
        if let Cbor::Map(pairs) = &mut missing_interrupt {
            pairs.retain(|(key, _)| key != "last_interrupt" && key != "last_interrupt_height");
        }
        let bytes = encode(&missing_interrupt);
        let read = read_measured_object(&bytes).unwrap();
        assert!(!read.shape);
        assert!(read.hike.last_interrupt_absent);
        let measurements = crate::verify::verify(
            &bytes,
            crate::verify::AuditDraw::PinnedSeed([0u8; 32]),
            |_| Err(()),
            |_| Ok(()),
        );
        assert!(matches!(
            measurements.version,
            crate::verify::ObjectLineage::Current
        ));
        let facts = crate::admit::failure_facts(&measurements);
        assert!(facts.contains(&"shape is false"));
        assert!(!facts.iter().any(|fact| *fact == "earlier_version"));
        let line = crate::verify::verify_record_json("production", &measurements);
        assert!(!line.contains("earlier version"));
    }

    #[test]
    fn yielded_public_key_splits_an_undecodable_value_from_a_short_key() {
        assert_eq!(yielded_public_key(&[]), YieldedKey::Undecodable);
        assert_eq!(yielded_public_key(&[0xff]), YieldedKey::Undecodable);
        assert_eq!(yielded_public_key(&[0x00]), YieldedKey::NoPublicKey);
        let short = encode(&Cbor::Map(vec![(
            "public_key".to_owned(),
            Cbor::Bytes(vec![0x11, 0x22]),
        )]));
        assert_eq!(yielded_public_key(&short), YieldedKey::NoPublicKey);
        let key = [0x33u8; 32];
        let full = encode(&Cbor::Map(vec![(
            "public_key".to_owned(),
            Cbor::Bytes(key.to_vec()),
        )]));
        assert_eq!(yielded_public_key(&full), YieldedKey::PublicKey(key));
    }

    #[test]
    fn stage_0_beacon_must_equal_the_seed_beacon() {
        let hike = sample_hike();
        let read = read_measured_object(&encode_hike(&hike)).unwrap();
        assert!(read.shape);
        assert!(read.shape_fail.is_none());

        let mut bad = hike;
        bad.seed_beacon = [9u8; 32];
        let read = read_measured_object(&encode_hike(&bad)).unwrap();
        assert!(!read.shape);
        assert_eq!(read.shape_fail, Some(Fail("seed beacon")));
        assert_eq!(read.hike.stages[0].beacon, sample_hike().stages[0].beacon);
        assert_eq!(decode_hike(&encode_hike(&bad)), Err(Fail("seed beacon")));
    }

    #[test]
    fn leg_count_zero_is_a_false_shape_and_still_a_usable_object() {
        let mut hike = sample_hike();
        for stage in &mut hike.stages {
            stage.leg_count = 0;
            stage.checkpoints.clear();
            stage.leg_results.clear();
        }
        let bytes = encode_hike(&hike);
        let read = read_measured_object(&bytes).unwrap();
        assert!(!read.shape);
        assert_eq!(read.shape_fail, Some(Fail("leg_count")));
        assert_eq!(read.hike.stages.len(), STAGES);
        assert!(decode_hike(&bytes).is_err());
    }

    fn small_record(
        bytes: &[u8],
        beacon_at: impl FnMut(u64) -> Result<crate::clock::IndexBeacon, ()>,
    ) -> serde_json::Value {
        let sizes = crate::consts::Sizes::conformance_small();
        let measurements = crate::verify::verify_sized(
            bytes,
            crate::verify::AuditDraw::PinnedSeed([0u8; 32]),
            beacon_at,
            |_| Ok(()),
            sizes,
        );
        serde_json::from_str(
            crate::verify::verify_record_json("conformance-small", &measurements).trim_end(),
        )
        .unwrap()
    }

    #[test]
    fn absent_last_interrupt_shape_item_is_the_four_closing_tokens() {
        let sizes = crate::consts::Sizes::conformance_small();
        let mut value = crate::cbor::decode_one(&encode_hike_sized(&sample_hike(), sizes)).unwrap();
        if let Cbor::Map(pairs) = &mut value {
            pairs.retain(|(key, _)| key != "last_interrupt" && key != "last_interrupt_height");
        }
        let record = small_record(&crate::cbor::encode(&value), |_| Err(()));
        assert_eq!(
            record["shape_item"],
            serde_json::json!([
                "field set",
                "last_interrupt",
                "header height order",
                "header hash differ"
            ])
        );
        assert_eq!(record["headers"][18]["stored_hash"], "not defined");
        assert_eq!(record["headers"][18]["stored_height"], "not defined");
        assert_eq!(record["headers"][18]["index_comparison"], "not read");
        assert_eq!(record["headers"][18]["T"], "not read");
        assert!(record["checkpoints"][17]["legs"].is_array());
    }

    #[test]
    fn absent_interrupt_hash_keeps_height_order_when_the_height_is_greater() {
        let sizes = crate::consts::Sizes::conformance_small();
        let mut value = crate::cbor::decode_one(&encode_hike_sized(&sample_hike(), sizes)).unwrap();
        if let Cbor::Map(pairs) = &mut value {
            pairs.retain(|(key, _)| key != "last_interrupt");
        }
        let record = small_record(&crate::cbor::encode(&value), |_| Err(()));
        let items = record["shape_item"].as_array().unwrap();
        assert!(items.iter().any(|item| item == "field set"));
        assert!(items.iter().any(|item| item == "last_interrupt"));
        assert!(items.iter().any(|item| item == "header hash differ"));
        assert!(items.iter().all(|item| item != "header height order"));
        assert_eq!(record["headers"][18]["stored_hash"], "not defined");
        assert_eq!(record["headers"][18]["stored_height"], STAGES as u64 + 2);
        assert_eq!(record["headers"][18]["index_comparison"], "not read");
        assert_eq!(record["headers"][18]["T"], "not read");
    }

    #[test]
    fn hash_mismatch_records_t_as_not_read() {
        let sizes = crate::consts::Sizes::conformance_small();
        let bytes = encode_hike_sized(&sample_hike(), sizes);
        let record = small_record(&bytes, |height| {
            Ok(crate::clock::IndexBeacon {
                natural: [0xff; 32],
                height,
                header_time_s: Some(1_700_000_001),
                timestamp_unix_ms: Some(9),
            })
        });
        assert_eq!(record["headers"][0]["index_comparison"], "mismatch");
        assert_eq!(record["headers"][0]["T"], "not read");
        assert_eq!(record["headers"][0]["stored_height"], 1);
    }

    #[test]
    fn a_short_checkpoint_leaves_that_stage_undefined_and_keeps_later_rows() {
        let sizes = crate::consts::Sizes::conformance_small();
        let mut value = crate::cbor::decode_one(&encode_hike_sized(&sample_hike(), sizes)).unwrap();
        set_stage_field(
            &mut value,
            2,
            "checkpoints",
            Cbor::Array(vec![Cbor::Bytes(vec![0x01])]),
        );
        let record = small_record(&crate::cbor::encode(&value), |_| Err(()));
        let items = record["shape_item"].as_array().unwrap();
        assert!(items.iter().any(|item| item == "checkpoint width"));
        assert_eq!(record["checkpoints"][2]["legs"], "not defined");
        assert!(record["checkpoints"][3]["legs"].is_array());
        assert_eq!(record["waypoints"][2]["result"], "mismatch");
    }

    #[test]
    fn a_missing_leg_result_is_mismatch_and_names_leg_count() {
        let sizes = crate::consts::Sizes::conformance_small();
        let mut value = crate::cbor::decode_one(&encode_hike_sized(&sample_hike(), sizes)).unwrap();
        set_stage_field(&mut value, 4, "leg_count", Cbor::Uint(2));
        set_stage_field(
            &mut value,
            4,
            "checkpoints",
            Cbor::Array(vec![
                Cbor::Bytes(vec![0x11; 32]),
                Cbor::Bytes(vec![0x22; 32]),
            ]),
        );
        set_stage_field(
            &mut value,
            4,
            "leg_results",
            Cbor::Array(vec![Cbor::Bytes(1u64.to_le_bytes().to_vec())]),
        );
        let record = small_record(&crate::cbor::encode(&value), |_| Err(()));
        let items = record["shape_item"].as_array().unwrap();
        assert!(items.iter().any(|item| item == "leg_count"));
        assert_eq!(record["checkpoints"][4]["legs"][0]["result"], "mismatch");
        assert_eq!(record["checkpoints"][4]["legs"][1]["result"], "mismatch");
        assert!(record["checkpoints"][5]["legs"].is_array());
    }

    #[test]
    fn a_short_leg_result_is_mismatch_and_names_leg_result_width() {
        let sizes = crate::consts::Sizes::conformance_small();
        let mut value = crate::cbor::decode_one(&encode_hike_sized(&sample_hike(), sizes)).unwrap();
        set_stage_field(
            &mut value,
            2,
            "leg_results",
            Cbor::Array(vec![Cbor::Bytes(vec![0x01])]),
        );
        let record = small_record(&crate::cbor::encode(&value), |_| Err(()));
        let items = record["shape_item"].as_array().unwrap();
        assert!(items.iter().any(|item| item == "leg result width"));
        assert!(items.iter().all(|item| item != "leg_count"));
        assert_eq!(record["checkpoints"][2]["legs"][0]["result"], "mismatch");
        assert!(record["checkpoints"][3]["legs"].is_array());
    }

    fn set_top(value: &mut Cbor, key: &str, next: Cbor) {
        if let Cbor::Map(pairs) = value {
            pairs.iter_mut().find(|(k, _)| k == key).unwrap().1 = next;
        }
    }

    fn set_stage_field(value: &mut Cbor, index: usize, key: &str, next: Cbor) {
        if let Cbor::Map(pairs) = value {
            if let Cbor::Array(stages) =
                &mut pairs.iter_mut().find(|(k, _)| k == "stages").unwrap().1
            {
                if let Cbor::Map(stage) = &mut stages[index] {
                    stage.iter_mut().find(|(k, _)| k == key).unwrap().1 = next;
                }
            }
        }
    }
}
