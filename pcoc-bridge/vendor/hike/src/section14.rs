//! Section 14 verify record. Compact JSON, keys in the order section 14 lists.
//!
//! The profile key is applied by [`crate::verify_record_json`]. This module
//! writes the rest of the object, starting with a comma. RFC 8785 remains the
//! hash encoding. The byte order here is the reference CLI and the conformance
//! pack.

use crate::clock::IndexBeacon;
use crate::consts::JSON_INT_MAX;
use crate::hex::encode_hex32;
use crate::pathchain::display_hex;
use crate::verify::{Anchor, SixLegs};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Plant {
    Match,
    Mismatch,
    NotRead,
    NotApplicable,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct PathFact {
    pub stage_present: bool,
    pub empty: bool,
    /// The plant read was issued. An out-of-window path is not issued.
    pub plant_read: bool,
    pub plant: Plant,
    pub block_hash: Option<[u8; 32]>,
    pub block_height: Option<u64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum IndexCmp {
    Match { height: u64, time: Option<u64> },
    Mismatch,
    NotRead,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct HeaderFact {
    pub stored_hash: Option<[u8; 32]>,
    pub stored_height: Option<u64>,
    pub cmp: IndexCmp,
}

pub(crate) struct RecordInput<'a> {
    pub shape: bool,
    pub shape_notes: &'a [&'static str],
    pub headers: &'a [HeaderFact],
    pub opening_match: Option<bool>,
    pub digest_matches: Option<&'a [Vec<bool>]>,
    /// One flag per stage index. Missing or false means that stage's `legs` is `not defined`.
    pub checkpoint_rows_defined: &'a [bool],
    pub successor_differs: Option<&'a [Option<bool>]>,
    pub paths: &'a [PathFact],
    pub anchors: &'a [Anchor],
    pub checkpoint_counts: &'a [Option<u64>],
    pub six: &'a SixLegs,
}

/// JSON after `"profile":...`, including the closing brace. Starts with a comma.
pub(crate) fn record_tail<F>(input: &RecordInput<'_>, beacon_at: &mut F) -> String
where
    F: FnMut(u64) -> Result<IndexBeacon, ()>,
{
    let mut out = String::new();
    push_pair(
        &mut out,
        "shape",
        &q(if input.shape { "match" } else { "mismatch" }),
    );
    push_pair(&mut out, "shape_item", &shape_items(input.shape_notes));
    push_pair(&mut out, "headers", &headers_json(input.headers));
    push_pair(&mut out, "seed_value", &opt_match(input.opening_match));
    push_pair(
        &mut out,
        "checkpoints",
        &checkpoints_json(input.digest_matches, input.checkpoint_rows_defined),
    );
    push_pair(
        &mut out,
        "waypoints",
        &waypoints_json(input.successor_differs),
    );
    push_pair(&mut out, "paths", &paths_json(input.paths, input.headers));
    push_pair(&mut out, "anchors", &anchors_json(input.anchors));
    let anchored: Vec<usize> = (0..16)
        .filter(|index| input.anchors.get(*index) == Some(&Anchor::Anchored))
        .collect();
    push_pair(&mut out, "anchored_total", &anchored.len().to_string());
    push_pair(&mut out, "skip_list", &skip_list(input.paths));
    let book = bookends(input, &anchored, beacon_at);
    push_book(&mut out, &book);
    push_pair(&mut out, "six_leg", &six_leg_json(input.six));
    push_pair(&mut out, "six_leg_runs", &six_runs_json(input.six));
    push_pair(&mut out, "analysis", &analysis_json(input, &book));
    out.push('}');
    out
}

/// No usable hike. Array lengths stay as section 14 states.
pub(crate) fn undecoded_tail(notes: &[&'static str]) -> String {
    let headers = vec![
        HeaderFact {
            stored_hash: None,
            stored_height: None,
            cmp: IndexCmp::NotRead,
        };
        19
    ];
    let paths = vec![
        PathFact {
            stage_present: false,
            empty: true,
            plant_read: false,
            plant: Plant::NotRead,
            block_hash: None,
            block_height: None,
        };
        17
    ];
    let input = RecordInput {
        shape: false,
        shape_notes: notes,
        headers: &headers,
        opening_match: None,
        digest_matches: None,
        checkpoint_rows_defined: &[],
        successor_differs: None,
        paths: &paths,
        anchors: &[],
        checkpoint_counts: &[],
        six: &SixLegs::NotObtained,
    };
    record_tail(&input, &mut |_| Err(()))
}

/// Section 7.3 decoder failures. One of these is exactly `["malformed cbor"]`.
const MALFORMED_CBOR: &[&str] = &[
    "empty",
    "truncated",
    "unsupported additional info",
    "negative integer",
    "tag",
    "simple or float",
    "indefinite length",
    "length",
    "length exceeds input",
    "text is not utf-8",
    "map key is not text",
    "duplicate key",
    "trailing bytes",
    "malformed cbor",
];

fn shape_items(notes: &[&'static str]) -> String {
    if notes.iter().any(|note| MALFORMED_CBOR.contains(note)) {
        return "[\"malformed cbor\"]".to_owned();
    }
    const ORDER: &[(&str, &[&str])] = &[
        ("malformed cbor", &["malformed cbor"]),
        (
            "field set",
            &[
                "map field set",
                "path",
                "expected a map",
                "missing field",
                "expected text",
                "expected unsigned",
                "byte length",
                "expected bytes",
                "expected array",
            ],
        ),
        ("stage-0 path key", &["stage-0 path"]),
        ("W_17 path", &["W_17 path"]),
        (
            "constant",
            &[
                "kind",
                "fill",
                "slot_bytes",
                "n_slots",
                "sample_bytes",
                "steps_per_leg",
            ],
        ),
        ("stages length", &["stages"]),
        ("leg_count", &["leg_count"]),
        ("checkpoint width", &["checkpoint"]),
        ("leg result width", &["leg result"]),
        ("seed beacon", &["seed beacon"]),
        ("last_interrupt", &["last_interrupt"]),
        ("header height order", &["beacon height"]),
        ("header hash differ", &["beacon"]),
        ("height_range", &["height_range"]),
    ];
    let mut items = Vec::new();
    for (token, sources) in ORDER {
        if notes.iter().any(|note| sources.contains(note)) {
            items.push(format!("\"{token}\""));
        }
    }
    format!("[{}]", items.join(","))
}

fn headers_json(headers: &[HeaderFact]) -> String {
    let mut parts = Vec::with_capacity(19);
    for index in 0..19 {
        parts.push(header_json(headers.get(index)));
    }
    format!("[{}]", parts.join(","))
}

fn header_json(fact: Option<&HeaderFact>) -> String {
    let Some(fact) = fact else {
        return obj(&[
            ("stored_hash", "\"not defined\""),
            ("stored_height", "\"not defined\""),
            ("index_comparison", "\"not read\""),
            ("T", "\"not read\""),
        ]);
    };
    let hash = match fact.stored_hash {
        Some(hash) => q(&encode_hex32(&hash)),
        None => "\"not defined\"".to_owned(),
    };
    let height = match fact.stored_height {
        Some(height) if height <= JSON_INT_MAX => height.to_string(),
        _ => "\"not defined\"".to_owned(),
    };
    let (comparison, time) = match &fact.cmp {
        IndexCmp::Match { time, .. } => (
            "\"match\"",
            match time {
                Some(time) if *time <= u64::from(u32::MAX) => time.to_string(),
                _ => "\"not read\"".to_owned(),
            },
        ),
        IndexCmp::Mismatch => ("\"mismatch\"", "\"not read\"".to_owned()),
        IndexCmp::NotRead => ("\"not read\"", "\"not read\"".to_owned()),
    };
    obj(&[
        ("stored_hash", &hash),
        ("stored_height", &height),
        ("index_comparison", comparison),
        ("T", &time),
    ])
}

fn checkpoints_json(digests: Option<&[Vec<bool>]>, rows_defined: &[bool]) -> String {
    let mut parts = Vec::with_capacity(18);
    for g in 0..18 {
        let defined = rows_defined.get(g).copied().unwrap_or(false);
        let legs = if !defined {
            "\"not defined\"".to_owned()
        } else if let Some(legs) = digests.and_then(|rows| rows.get(g)) {
            let items = legs
                .iter()
                .enumerate()
                .map(|(j, matches)| {
                    obj(&[
                        ("j", &j.to_string()),
                        (
                            "result",
                            if *matches {
                                "\"match\""
                            } else {
                                "\"mismatch\""
                            },
                        ),
                    ])
                })
                .collect::<Vec<_>>()
                .join(",");
            format!("[{items}]")
        } else {
            "\"not defined\"".to_owned()
        };
        parts.push(obj(&[("g", &g.to_string()), ("legs", &legs)]));
    }
    format!("[{}]", parts.join(","))
}

fn waypoints_json(differs: Option<&[Option<bool>]>) -> String {
    let mut parts = Vec::with_capacity(17);
    for g in 1..=17 {
        // `None` is an absent or unusable stage pair. That is `not defined`, not `mismatch`.
        let result = match differs.and_then(|rows| rows.get(g - 1)).copied().flatten() {
            Some(true) => "\"mismatch\"",
            Some(false) => "\"match\"",
            None => "\"not defined\"",
        };
        parts.push(obj(&[("g", &g.to_string()), ("result", result)]));
    }
    format!("[{}]", parts.join(","))
}

fn paths_json(paths: &[PathFact], headers: &[HeaderFact]) -> String {
    let mut parts = Vec::with_capacity(17);
    for g in 1..=17 {
        let fact = paths.get(g - 1);
        parts.push(if g == 17 {
            path_17(fact)
        } else {
            path_1_to_16(g, fact, headers.get(g))
        });
    }
    format!("[{}]", parts.join(","))
}

fn path_1_to_16(g: usize, fact: Option<&PathFact>, interrupt: Option<&HeaderFact>) -> String {
    let Some(fact) = fact.filter(|fact| fact.stage_present) else {
        return obj(&[
            ("g", &g.to_string()),
            ("path_state", "\"not defined\""),
            ("plant", "\"not defined\""),
            ("P_g", "\"not defined\""),
            ("height_P_g", "\"not defined\""),
            ("d_g", "\"not defined\""),
            ("claimed_block", "\"not defined\""),
        ]);
    };
    if fact.empty {
        return obj(&[
            ("g", &g.to_string()),
            ("path_state", "\"skip\""),
            ("plant", "\"n/a\""),
            ("P_g", "\"not defined\""),
            ("height_P_g", "\"not defined\""),
            ("d_g", "\"not defined\""),
            ("claimed_block", "\"not defined\""),
        ]);
    }
    let claimed = block_json(fact.block_hash, fact.block_height);
    let (pg, height_pg, dg) = match fact.plant {
        Plant::Match => {
            let pg = block_json(fact.block_hash, fact.block_height);
            let height_pg = fact
                .block_height
                .filter(|height| *height <= JSON_INT_MAX)
                .map(|height| height.to_string())
                .unwrap_or_else(|| "\"not defined\"".to_owned());
            let dg = match (fact.block_height, interrupt) {
                (Some(plant_height), Some(header)) => match &header.cmp {
                    IndexCmp::Match { height, .. } if *height <= JSON_INT_MAX => {
                        signed(plant_height as i128 - *height as i128)
                            .unwrap_or_else(|| "\"not defined\"".to_owned())
                    }
                    _ => "\"not defined\"".to_owned(),
                },
                _ => "\"not defined\"".to_owned(),
            };
            (pg, height_pg, dg)
        }
        _ => (
            "\"not defined\"".to_owned(),
            "\"not defined\"".to_owned(),
            "\"not defined\"".to_owned(),
        ),
    };
    obj(&[
        ("g", &g.to_string()),
        ("path_state", "\"present\""),
        ("plant", plant_token(&fact.plant)),
        ("P_g", &pg),
        ("height_P_g", &height_pg),
        ("d_g", &dg),
        ("claimed_block", &claimed),
    ])
}

fn path_17(fact: Option<&PathFact>) -> String {
    let g = "17";
    let Some(fact) = fact.filter(|fact| fact.stage_present) else {
        return obj(&[
            ("g", g),
            ("w17_path", "\"not defined\""),
            ("claimed_block", "\"not defined\""),
        ]);
    };
    if fact.empty {
        return obj(&[
            ("g", g),
            ("w17_path", "\"not fetched by design\""),
            ("claimed_block", "\"not defined\""),
        ]);
    }
    obj(&[
        ("g", g),
        ("w17_path", "\"not defined\""),
        (
            "claimed_block",
            &block_json(fact.block_hash, fact.block_height),
        ),
    ])
}

fn plant_token(plant: &Plant) -> &'static str {
    match plant {
        Plant::Match => "\"match\"",
        Plant::Mismatch => "\"mismatch\"",
        Plant::NotRead => "\"not read\"",
        Plant::NotApplicable => "\"n/a\"",
    }
}

fn anchors_json(anchors: &[Anchor]) -> String {
    let items = (0..16)
        .map(|index| match anchors.get(index) {
            Some(Anchor::Anchored) => "\"anchored\"",
            _ => "\"not anchored\"",
        })
        .collect::<Vec<_>>()
        .join(",");
    format!("[{items}]")
}

fn skip_list(paths: &[PathFact]) -> String {
    let items = (0..16)
        .filter(|index| {
            paths
                .get(*index)
                .is_some_and(|fact| fact.stage_present && fact.empty)
        })
        .map(|index| (index + 1).to_string())
        .collect::<Vec<_>>()
        .join(",");
    format!("[{items}]")
}

enum Book {
    NotDefined,
    NotRead,
    Ready(ReadyBook),
}

struct ReadyBook {
    a: u64,
    b: u64,
    n: String,
    s: String,
    delta_h: String,
    pb: String,
    height_pb: String,
    cmp_pb: String,
    t_pb: String,
    height_i: String,
    t_i: String,
    fixed_n: String,
    fixed_s: String,
    fixed_delta: String,
    legs_outside: String,
}

fn bookends<F>(input: &RecordInput<'_>, anchored: &[usize], beacon_at: &mut F) -> Book
where
    F: FnMut(u64) -> Result<IndexBeacon, ()>,
{
    let present = input
        .paths
        .iter()
        .take(16)
        .filter(|fact| fact.stage_present && !fact.empty)
        .count();
    if present < 2 {
        return Book::NotDefined;
    }
    // An issued plant GET that did not return a document. A path outside the
    // window is not issued (`plant_read` is false) and is not this miss.
    if input
        .paths
        .iter()
        .take(16)
        .any(|fact| fact.plant_read && matches!(fact.plant, Plant::NotRead))
    {
        return Book::NotRead;
    }
    // An unread header that is not a bookend endpoint does not, by itself,
    // make `a`, `b`, or `N` `not read`. The endpoint check is below.
    if anchored.len() < 2 {
        return Book::NotDefined;
    }
    let a = anchored[0] + 1;
    let b = anchored[anchored.len() - 1] + 1;
    let header_a = match input.headers.get(a) {
        Some(header) => header,
        None => return Book::NotRead,
    };
    if matches!(header_a.cmp, IndexCmp::NotRead) {
        return Book::NotRead;
    }
    let path_b = match input.paths.get(b - 1) {
        Some(path) => path,
        None => return Book::NotRead,
    };
    let (Some(pb_hash), Some(pb_height)) = (path_b.block_hash, path_b.block_height) else {
        return Book::NotRead;
    };
    let pb = compare_block(pb_hash, pb_height, beacon_at);
    if matches!(pb, IndexCmp::NotRead) {
        return Book::NotRead;
    }
    let n = count_range(input.checkpoint_counts, a, b);
    let fixed_n = count_range(input.checkpoint_counts, 1, b);
    let legs_outside = legs_outside(input.checkpoint_counts, a, b);
    let (height_i, t_i) = endpoint(header_a);
    let (height_pb, t_pb, cmp_pb) = match &pb {
        IndexCmp::Match { height, time } => {
            (json_u(*height), json_time(*time), "\"match\"".to_owned())
        }
        IndexCmp::Mismatch => (
            "\"not defined\"".to_owned(),
            "\"not read\"".to_owned(),
            "\"mismatch\"".to_owned(),
        ),
        IndexCmp::NotRead => unreachable!("a missing P_b read already returned"),
    };
    let s = subtract_times(&t_pb, &t_i);
    let delta_h = subtract_heights(&height_pb, &height_i);
    let header_1 = input.headers.get(1);
    let fixed_s = match header_1 {
        Some(header) => {
            let (_, t1) = endpoint(header);
            subtract_times(&t_pb, &t1)
        }
        None => "\"not read\"".to_owned(),
    };
    let fixed_delta = fixed_delta(header_1, &pb, &height_pb);
    Book::Ready(ReadyBook {
        a: a as u64,
        b: b as u64,
        n: n.unwrap_or_else(|| "\"not defined\"".to_owned()),
        s,
        delta_h,
        pb: block_json(Some(pb_hash), Some(pb_height)),
        height_pb,
        cmp_pb,
        t_pb,
        height_i,
        t_i,
        fixed_n: fixed_n.unwrap_or_else(|| "\"not defined\"".to_owned()),
        fixed_s,
        fixed_delta,
        legs_outside: legs_outside.unwrap_or_else(|| "\"not defined\"".to_owned()),
    })
}

fn compare_block<F>(hash: [u8; 32], height: u64, beacon_at: &mut F) -> IndexCmp
where
    F: FnMut(u64) -> Result<IndexBeacon, ()>,
{
    if height > JSON_INT_MAX {
        return IndexCmp::NotRead;
    }
    match beacon_at(height) {
        Err(()) => IndexCmp::NotRead,
        Ok(beacon) => {
            let time = beacon
                .header_time_s
                .filter(|time| *time <= u64::from(u32::MAX));
            if beacon.natural == hash && beacon.height == height && beacon.height <= JSON_INT_MAX {
                IndexCmp::Match {
                    height: beacon.height,
                    time,
                }
            } else {
                IndexCmp::Mismatch
            }
        }
    }
}

fn endpoint(header: &HeaderFact) -> (String, String) {
    match &header.cmp {
        IndexCmp::Match { height, time } => (json_u(*height), json_time(*time)),
        IndexCmp::Mismatch | IndexCmp::NotRead => {
            ("\"not defined\"".to_owned(), "\"not read\"".to_owned())
        }
    }
}

fn json_u(height: u64) -> String {
    if height <= JSON_INT_MAX {
        height.to_string()
    } else {
        "\"not defined\"".to_owned()
    }
}

fn json_time(time: Option<u64>) -> String {
    match time {
        Some(time) if time <= u64::from(u32::MAX) && time <= JSON_INT_MAX => time.to_string(),
        _ => "\"not read\"".to_owned(),
    }
}

fn subtract_times(later: &str, earlier: &str) -> String {
    match (later.parse::<i128>().ok(), earlier.parse::<i128>().ok()) {
        (Some(later), Some(earlier)) => {
            signed(later - earlier).unwrap_or_else(|| "\"not read\"".to_owned())
        }
        _ => "\"not read\"".to_owned(),
    }
}

fn subtract_heights(later: &str, earlier: &str) -> String {
    match (later.parse::<i128>().ok(), earlier.parse::<i128>().ok()) {
        (Some(later), Some(earlier)) => {
            signed(later - earlier).unwrap_or_else(|| "\"not defined\"".to_owned())
        }
        _ => "\"not defined\"".to_owned(),
    }
}

fn fixed_delta(header: Option<&HeaderFact>, pb: &IndexCmp, height_pb: &str) -> String {
    let Some(header) = header else {
        return "\"not read\"".to_owned();
    };
    if matches!(header.cmp, IndexCmp::NotRead) || matches!(pb, IndexCmp::NotRead) {
        return "\"not read\"".to_owned();
    }
    if matches!(header.cmp, IndexCmp::Mismatch) || matches!(pb, IndexCmp::Mismatch) {
        return "\"not defined\"".to_owned();
    }
    let (height_i, _) = endpoint(header);
    subtract_heights(height_pb, &height_i)
}

fn count_range(counts: &[Option<u64>], from: usize, until: usize) -> Option<String> {
    if from >= until {
        return Some("0".to_owned());
    }
    let mut total = 0u128;
    for index in from..until {
        total += u128::from(*counts.get(index)?.as_ref()?);
    }
    let total = u64::try_from(total).ok()?;
    (total <= JSON_INT_MAX).then(|| total.to_string())
}

fn legs_outside(counts: &[Option<u64>], a: usize, b: usize) -> Option<String> {
    let mut total = 0u128;
    for index in 0..18 {
        if (a..b).contains(&index) {
            continue;
        }
        total += u128::from(*counts.get(index)?.as_ref()?);
    }
    let total = u64::try_from(total).ok()?;
    (total <= JSON_INT_MAX).then(|| total.to_string())
}

fn push_token_book(out: &mut String, token: &str) {
    let value = format!("\"{token}\"");
    for key in ["a", "b", "N", "S", "delta_h"] {
        push_pair(out, key, &value);
    }
    push_pair(out, "fixed_start", &fixed_tokens(&value, &value, &value));
    for key in [
        "P_b",
        "height_P_b",
        "index_comparison_P_b",
        "T_P_b",
        "height_I_a_minus_1",
        "T_I_a_minus_1",
    ] {
        push_pair(out, key, &value);
    }
}

fn push_book(out: &mut String, book: &Book) {
    match book {
        Book::NotDefined => push_token_book(out, "not defined"),
        Book::NotRead => push_token_book(out, "not read"),
        Book::Ready(book) => {
            push_pair(out, "a", &book.a.to_string());
            push_pair(out, "b", &book.b.to_string());
            push_pair(out, "N", &book.n);
            push_pair(out, "S", &book.s);
            push_pair(out, "delta_h", &book.delta_h);
            push_pair(
                out,
                "fixed_start",
                &fixed_tokens(&book.fixed_n, &book.fixed_s, &book.fixed_delta),
            );
            push_pair(out, "P_b", &book.pb);
            push_pair(out, "height_P_b", &book.height_pb);
            push_pair(out, "index_comparison_P_b", &book.cmp_pb);
            push_pair(out, "T_P_b", &book.t_pb);
            push_pair(out, "height_I_a_minus_1", &book.height_i);
            push_pair(out, "T_I_a_minus_1", &book.t_i);
        }
    }
}

fn fixed_tokens(n: &str, s: &str, delta: &str) -> String {
    obj(&[("N", n), ("S", s), ("delta_h", delta)])
}

fn analysis_json(input: &RecordInput<'_>, book: &Book) -> String {
    let legs =
        count_range(input.checkpoint_counts, 1, 18).unwrap_or_else(|| "\"not defined\"".to_owned());
    let span = match (input.headers.get(18), input.headers.get(1)) {
        (Some(end), Some(start)) => {
            let (_, t_end) = endpoint(end);
            let (_, t_start) = endpoint(start);
            subtract_times(&t_end, &t_start)
        }
        _ => "\"not read\"".to_owned(),
    };
    let mut rows = Vec::with_capacity(18);
    for g in 0..18 {
        let count = match input.checkpoint_counts.get(g) {
            Some(Some(count)) if *count <= JSON_INT_MAX => count.to_string(),
            Some(Some(_)) => "\"not defined\"".to_owned(),
            Some(None) | None => "\"not defined\"".to_owned(),
        };
        let span_g = stage_span(input.headers, g);
        rows.push(obj(&[
            ("g", &g.to_string()),
            ("checkpoint_count", &count),
            ("span", &span_g),
        ]));
    }
    let outside = match book {
        Book::NotDefined => "\"not defined\"".to_owned(),
        Book::NotRead => "\"not read\"".to_owned(),
        Book::Ready(book) => book.legs_outside.clone(),
    };
    obj(&[
        ("whole_hike_legs", &legs),
        ("whole_hike_span", &span),
        ("per_stage", &format!("[{}]", rows.join(","))),
        ("legs_outside", &outside),
    ])
}

fn stage_span(headers: &[HeaderFact], g: usize) -> String {
    let (left, right) = if g == 0 {
        (0, 1)
    } else if g <= 16 {
        (g, g + 1)
    } else {
        (17, 18)
    };
    match (headers.get(right), headers.get(left)) {
        (Some(end), Some(start)) => {
            let (_, t_end) = endpoint(end);
            let (_, t_start) = endpoint(start);
            subtract_times(&t_end, &t_start)
        }
        _ => "\"not read\"".to_owned(),
    }
}

fn six_leg_json(six: &SixLegs) -> String {
    match six {
        SixLegs::NotObtained | SixLegs::DrawNotObtained => "\"not read\"".to_owned(),
        SixLegs::CouldNotDraw => "\"not defined\"".to_owned(),
        SixLegs::Rewalked { indices, matches } => six_object(indices, matches),
    }
}

#[cfg(test)]
mod six_run_token_tests {
    use super::*;

    #[test]
    fn six_leg_runs_is_empty_when_fewer_than_six_legs_and_a_string_when_not_read() {
        assert_eq!(six_runs_json(&SixLegs::DrawNotObtained), "\"not read\"");
        assert_eq!(six_runs_json(&SixLegs::NotObtained), "\"not read\"");
        assert_eq!(six_leg_json(&SixLegs::CouldNotDraw), "\"not defined\"");
        assert_eq!(six_runs_json(&SixLegs::CouldNotDraw), "[]");
    }

    #[test]
    fn an_unusable_waypoint_row_is_not_defined() {
        let rows = [Some(false), Some(true), None];
        let json = waypoints_json(Some(&rows));
        assert!(json.contains(r#"{"g":1,"result":"match"}"#));
        assert!(json.contains(r#"{"g":2,"result":"mismatch"}"#));
        assert!(json.contains(r#"{"g":3,"result":"not defined"}"#));
        assert!(!json.contains(r#"{"g":3,"result":"mismatch"}"#));
    }

    #[test]
    fn nested_cli_keys_follow_the_section_14_order() {
        let headers = [HeaderFact {
            stored_hash: Some([1; 32]),
            stored_height: Some(1),
            cmp: IndexCmp::NotRead,
        }];
        let headers = headers_json(&headers);
        let hash = headers.find("\"stored_hash\"").unwrap();
        let height = headers.find("\"stored_height\"").unwrap();
        let cmp = headers.find("\"index_comparison\"").unwrap();
        let time = headers.find("\"T\"").unwrap();
        assert!(hash < height && height < cmp && cmp < time);
        let path = path_1_to_16(
            1,
            Some(&PathFact {
                stage_present: true,
                empty: false,
                plant_read: false,
                plant: Plant::Mismatch,
                block_hash: Some([2; 32]),
                block_height: Some(4),
            }),
            None,
        );
        let g = path.find("\"g\"").unwrap();
        let state = path.find("\"path_state\"").unwrap();
        let plant = path.find("\"plant\"").unwrap();
        let pg = path.find("\"P_g\"").unwrap();
        let hpg = path.find("\"height_P_g\"").unwrap();
        let dg = path.find("\"d_g\"").unwrap();
        let claimed = path.find("\"claimed_block\"").unwrap();
        assert!(g < state && state < plant && plant < pg && pg < hpg && hpg < dg && dg < claimed);
        let block = path[claimed..].to_owned();
        let block_hash = block.find("\"block_hash\"").unwrap();
        let block_height = block.find("\"block_height\"").unwrap();
        assert!(block_hash < block_height);
        let fixed = fixed_tokens("1", "2", "3");
        let n = fixed.find("\"N\"").unwrap();
        let s = fixed.find("\"S\"").unwrap();
        let delta = fixed.find("\"delta_h\"").unwrap();
        assert!(n < s && s < delta);
    }

    fn present(plant_read: bool, plant: Plant) -> PathFact {
        PathFact {
            stage_present: true,
            empty: false,
            plant_read,
            plant,
            block_hash: Some([9; 32]),
            block_height: Some(5),
        }
    }

    #[test]
    fn unread_non_endpoint_headers_do_not_make_bookends_not_read() {
        let paths = vec![present(false, Plant::NotRead); 16];
        let mut ready_headers = vec![
            HeaderFact {
                stored_hash: Some([1; 32]),
                stored_height: Some(1),
                cmp: IndexCmp::NotRead,
            };
            19
        ];
        let counts = vec![Some(0u64); 18];
        let anchors = vec![Anchor::NotAnchored; 16];
        let six = SixLegs::CouldNotDraw;
        let notes: &[&str] = &[];
        {
            let input = RecordInput {
                shape: true,
                shape_notes: notes,
                headers: &ready_headers,
                opening_match: Some(true),
                digest_matches: None,
                checkpoint_rows_defined: &[],
                successor_differs: None,
                paths: &paths,
                anchors: &anchors,
                checkpoint_counts: &counts,
                six: &six,
            };
            assert!(matches!(
                bookends(&input, &[], &mut |_| Err(())),
                Book::NotDefined
            ));
            let mut issued = paths.clone();
            issued[0].plant_read = true;
            let input = RecordInput {
                paths: &issued,
                ..input
            };
            assert!(matches!(
                bookends(&input, &[], &mut |_| Err(())),
                Book::NotRead
            ));
        }

        ready_headers[1] = HeaderFact {
            stored_hash: Some([2; 32]),
            stored_height: Some(2),
            cmp: IndexCmp::Match {
                height: 2,
                time: Some(2_000),
            },
        };
        let ready_paths = vec![present(true, Plant::Match); 16];
        let ready_anchors = vec![Anchor::Anchored, Anchor::Anchored];
        let input = RecordInput {
            shape: true,
            shape_notes: notes,
            headers: &ready_headers,
            opening_match: Some(true),
            digest_matches: None,
            checkpoint_rows_defined: &[],
            successor_differs: None,
            paths: &ready_paths,
            anchors: &ready_anchors,
            checkpoint_counts: &counts,
            six: &six,
        };
        let book = bookends(&input, &[0, 1], &mut |height| {
            Ok(IndexBeacon {
                natural: [9; 32],
                height,
                header_time_s: Some(5_000),
                timestamp_unix_ms: None,
            })
        });
        match book {
            Book::Ready(book) => {
                assert_eq!(book.a, 1);
                assert_eq!(book.b, 2);
            }
            Book::NotRead => panic!("unrelated unread header made the bookends not read"),
            Book::NotDefined => panic!("two anchored paths were not defined"),
        }
    }
}

fn six_runs_json(six: &SixLegs) -> String {
    match six {
        // A short draw is the string `not read`. That value is not an array and not `[]`.
        SixLegs::NotObtained | SixLegs::DrawNotObtained => "\"not read\"".to_owned(),
        // Fewer than six legs. `six_leg` is the string. `six_leg_runs` is `[]` only in this case.
        SixLegs::CouldNotDraw => "[]".to_owned(),
        SixLegs::Rewalked { indices, matches } => format!("[{}]", six_object(indices, matches)),
    }
}

fn six_object(indices: &[u64], matches: &[bool]) -> String {
    let indices = indices
        .iter()
        .map(|index| index.to_string())
        .collect::<Vec<_>>()
        .join(",");
    let results = matches
        .iter()
        .map(|matches| {
            if *matches {
                "\"match\""
            } else {
                "\"mismatch\""
            }
        })
        .collect::<Vec<_>>()
        .join(",");
    format!("{{\"indices\":[{indices}],\"results\":[{results}]}}")
}

fn opt_match(value: Option<bool>) -> String {
    match value {
        Some(true) => "\"match\"".to_owned(),
        Some(false) => "\"mismatch\"".to_owned(),
        None => "\"not defined\"".to_owned(),
    }
}

fn block_json(hash: Option<[u8; 32]>, height: Option<u64>) -> String {
    match (hash, height) {
        (Some(hash), Some(height)) if height <= JSON_INT_MAX => obj(&[
            ("block_hash", &q(&display_hex(&hash))),
            ("block_height", &height.to_string()),
        ]),
        _ => "\"not defined\"".to_owned(),
    }
}

fn signed(value: i128) -> Option<String> {
    let bound = i128::from(JSON_INT_MAX);
    if (-bound..=bound).contains(&value) {
        Some(value.to_string())
    } else {
        None
    }
}

fn q(text: &str) -> String {
    format!("\"{text}\"")
}

fn obj(pairs: &[(&str, &str)]) -> String {
    let body = pairs
        .iter()
        .map(|(key, value)| format!("\"{key}\":{value}"))
        .collect::<Vec<_>>()
        .join(",");
    format!("{{{body}}}")
}

fn push_pair(out: &mut String, key: &str, value: &str) {
    out.push(',');
    out.push('"');
    out.push_str(key);
    out.push_str("\":");
    out.push_str(value);
}

#[cfg(test)]
mod tests {
    use super::shape_items;

    #[test]
    fn a_decoder_failure_is_exactly_malformed_cbor() {
        for note in [
            "empty",
            "truncated",
            "tag",
            "negative integer",
            "duplicate key",
            "trailing bytes",
            "simple or float",
        ] {
            assert_eq!(shape_items(&[note]), "[\"malformed cbor\"]");
        }
        assert_eq!(
            shape_items(&["trailing bytes", "kind", "leg_count"]),
            "[\"malformed cbor\"]"
        );
        assert_eq!(shape_items(&["kind"]), "[\"constant\"]");
        assert_eq!(shape_items(&[]), "[]");
    }
}
