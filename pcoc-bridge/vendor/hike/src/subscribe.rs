//! Subscriber for the section 14 record [`crate::verify`] already returned.
//!
//! `verify` does not call this module. A subscriber (the verify CLI, an ops
//! process) calls [`publish_verify`] after `verify` returns. The events name
//! the object, the checks in section 14 order, and what each measurement
//! found. They do not measure and they do not judge.

use crate::hex::encode_hex32;
use crate::object::object_public_key;
use crate::verify::{
    CheckpointMeasurement, LegRate, Measurements, Recorded, SixLegs, WaypointMeasurement,
};
use serde::Serialize;

/// Section 14 order. The recomputed leg count is 6. That count is not this list's length.
pub const CHECK_ORDER: [&str; 6] = [
    "shape",
    "checkpoints",
    "waypoints",
    "leg rate",
    "paths included",
    "six legs",
];

const NOT_READ: &str = "not read";
const COULD_NOT_DRAW: &str = "could not draw";

/// How much of the measurement record a subscriber writes.
///
/// `Quiet` is the default. It does not change what `verify` records or what
/// [`crate::admit`] decides.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VerifyLog {
    Quiet,
    Fail,
    Verbose,
}

impl VerifyLog {
    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "quiet" => Some(Self::Quiet),
            "fail" => Some(Self::Fail),
            "verbose" => Some(Self::Verbose),
            _ => None,
        }
    }

    /// `Fail` subscribes only when admission did not pass. `Verbose` always
    /// subscribes. `Quiet` does not.
    pub fn emits(self, admitted: bool) -> bool {
        match self {
            Self::Quiet => false,
            Self::Fail => !admitted,
            Self::Verbose => true,
        }
    }
}

/// What one section 14 measurement recorded. `Null` is a measurement that was not read.
#[derive(Clone, Debug, PartialEq)]
pub enum Found {
    Bool(bool),
    Null,
    Text(&'static str),
    Count(u64),
    Checkpoints {
        beacon_matches_index: Vec<bool>,
        digest_matches: Vec<Vec<bool>>,
        mismatch: bool,
    },
    Waypoints {
        seed_value_matches: bool,
        successor_differs: Vec<bool>,
        absent_paths: u64,
        present_path_does_not_match_plant: bool,
    },
    SixLegs {
        indices: Vec<u64>,
        matches: Vec<bool>,
    },
}

impl Serialize for Found {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        match self {
            Found::Bool(value) => serializer.serialize_bool(*value),
            Found::Null => serializer.serialize_none(),
            Found::Text(value) => serializer.serialize_str(value),
            Found::Count(value) => serializer.serialize_u64(*value),
            Found::Checkpoints {
                beacon_matches_index,
                digest_matches,
                mismatch,
            } => CheckpointFound {
                beacon_matches_index,
                digest_matches,
                mismatch: *mismatch,
            }
            .serialize(serializer),
            Found::Waypoints {
                seed_value_matches,
                successor_differs,
                absent_paths,
                present_path_does_not_match_plant,
            } => WaypointFound {
                seed_value: if *seed_value_matches {
                    "match"
                } else {
                    "mismatch"
                },
                successor_differs,
                absent_paths: *absent_paths,
                present_path_does_not_match_plant: *present_path_does_not_match_plant,
            }
            .serialize(serializer),
            Found::SixLegs { indices, matches } => {
                SixLegsFound { indices, matches }.serialize(serializer)
            }
        }
    }
}

#[derive(Serialize)]
struct CheckpointFound<'a> {
    beacon_matches_index: &'a [bool],
    digest_matches: &'a [Vec<bool>],
    mismatch: bool,
}

#[derive(Serialize)]
struct WaypointFound<'a> {
    seed_value: &'a str,
    successor_differs: &'a [bool],
    absent_paths: u64,
    present_path_does_not_match_plant: bool,
}

#[derive(Serialize)]
struct SixLegsFound<'a> {
    indices: &'a [u64],
    matches: &'a [bool],
}

/// One diagnostic event. The order of [`verify_events`] is the order the
/// checks were recorded.
#[derive(Clone, Debug, PartialEq)]
pub enum VerifyEvent {
    Object {
        input: String,
        opened: bool,
        bytes: Option<u64>,
        public_key: Option<String>,
    },
    Check {
        order: u8,
        name: &'static str,
        found: Found,
    },
}

impl VerifyEvent {
    /// One compact JSON object, with no trailing newline.
    pub fn line(&self) -> String {
        match self {
            VerifyEvent::Object {
                input,
                opened,
                bytes,
                public_key,
            } => serde_json::to_string(&ObjectLine {
                event: "hike.verify.object",
                input,
                opened: *opened,
                bytes: *bytes,
                public_key: public_key.as_deref(),
            })
            .expect("object event"),
            VerifyEvent::Check { order, name, found } => serde_json::to_string(&CheckLine {
                event: "hike.verify.check",
                order: *order,
                name,
                found,
            })
            .expect("check event"),
        }
    }
}

#[derive(Serialize)]
struct ObjectLine<'a> {
    event: &'static str,
    input: &'a str,
    opened: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    bytes: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    public_key: Option<&'a str>,
}

#[derive(Serialize)]
struct CheckLine<'a> {
    event: &'static str,
    order: u8,
    name: &'a str,
    found: &'a Found,
}

/// The object and the record a subscriber asked to see.
///
/// `measurements` is the value `verify` returned. This struct does not rerun it.
pub struct VerifyObservation<'a> {
    pub input: &'a str,
    pub opened: bool,
    pub bytes: Option<u64>,
    pub public_key: Option<String>,
    pub measurements: Option<&'a Measurements>,
}

impl<'a> VerifyObservation<'a> {
    /// Bytes were read. `public_key` is set when the shape measurement can
    /// name one, which is when [`object_public_key`] succeeds.
    pub fn opened(input: &'a str, bytes: &'a [u8], measurements: &'a Measurements) -> Self {
        Self {
            input,
            opened: true,
            bytes: Some(u64::try_from(bytes.len()).unwrap_or(u64::MAX)),
            public_key: object_public_key(bytes).ok().map(|key| encode_hex32(&key)),
            measurements: Some(measurements),
        }
    }

    /// The path could not be opened. That failure is outside section 14.
    pub fn unopened(input: &'a str) -> Self {
        Self {
            input,
            opened: false,
            bytes: None,
            public_key: None,
            measurements: None,
        }
    }
}

/// Events for one observation: the object, then the six checks in section 14
/// order when the bytes were read. An unopened path has no check lines.
pub fn verify_events(observation: &VerifyObservation<'_>) -> Vec<VerifyEvent> {
    let mut events = vec![VerifyEvent::Object {
        input: observation.input.to_owned(),
        opened: observation.opened,
        bytes: observation.bytes,
        public_key: observation.public_key.clone(),
    }];
    if !observation.opened {
        return events;
    }
    if let Some(measurements) = observation.measurements {
        events.extend(check_events(measurements));
    }
    events
}

fn check_events(measurements: &Measurements) -> Vec<VerifyEvent> {
    let found = [
        Found::Bool(measurements.shape),
        checkpoint_found(&measurements.checkpoints),
        waypoint_found(&measurements.waypoints),
        Found::Text(match measurements.leg_rate {
            LegRate::NotRead => NOT_READ,
        }),
        match measurements.paths_included {
            Recorded::Obtained(count) => Found::Count(count),
            Recorded::NotObtained => Found::Null,
        },
        six_legs_found(&measurements.six_legs),
    ];
    CHECK_ORDER
        .into_iter()
        .zip(found)
        .enumerate()
        .map(|(index, (name, found))| VerifyEvent::Check {
            order: (index + 1) as u8,
            name,
            found,
        })
        .collect()
}

fn checkpoint_found(recorded: &Recorded<CheckpointMeasurement>) -> Found {
    match recorded {
        Recorded::NotObtained => Found::Null,
        Recorded::Obtained(found) => Found::Checkpoints {
            beacon_matches_index: found.beacon_matches_index.clone(),
            digest_matches: found.digest_matches.clone(),
            mismatch: found.mismatch,
        },
    }
}

fn waypoint_found(recorded: &Recorded<WaypointMeasurement>) -> Found {
    match recorded {
        Recorded::NotObtained => Found::Null,
        Recorded::Obtained(found) => Found::Waypoints {
            seed_value_matches: found.seed_value_matches,
            // The diagnostic event stays a bool array. `not defined` is not a differ.
            successor_differs: found
                .successor_differs
                .iter()
                .map(|differs| *differs == Some(true))
                .collect(),
            absent_paths: found.absent_paths,
            present_path_does_not_match_plant: found.present_path_does_not_match_plant,
        },
    }
}

fn six_legs_found(six_legs: &SixLegs) -> Found {
    match six_legs {
        SixLegs::NotObtained | SixLegs::DrawNotObtained => Found::Text(NOT_READ),
        SixLegs::CouldNotDraw => Found::Text(COULD_NOT_DRAW),
        SixLegs::Rewalked { indices, matches } => Found::SixLegs {
            indices: indices.clone(),
            matches: matches.clone(),
        },
    }
}

/// Receives the measurement record without running section 14 again.
pub trait VerifySubscriber {
    fn on_verify_event(&mut self, event: &VerifyEvent);
}

/// Hand the record to `subscriber`. `verify` does not call this.
pub fn publish_verify(observation: &VerifyObservation<'_>, subscriber: &mut dyn VerifySubscriber) {
    for event in verify_events(observation) {
        subscriber.on_verify_event(&event);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::admit::admit;
    use crate::object::{encode_hike, structural_object};
    use crate::verify::{verify, AuditDraw, CheckpointMeasurement, WaypointMeasurement};

    struct Collect(Vec<String>);

    impl VerifySubscriber for Collect {
        fn on_verify_event(&mut self, event: &VerifyEvent) {
            self.0.push(event.line());
        }
    }

    fn passing() -> Measurements {
        Measurements {
            version: crate::verify::ObjectLineage::Current,
            shape: true,
            checkpoints: Recorded::Obtained(CheckpointMeasurement {
                beacon_matches_index: vec![true; 18],
                digest_matches: vec![vec![true]; 18],
                mismatch: false,
            }),
            waypoints: Recorded::Obtained(WaypointMeasurement {
                seed_value_matches: true,
                successor_differs: vec![Some(false); 17],
                absent_paths: 18,
                present_path_does_not_match_plant: false,
                anchors: Vec::new(),
            }),
            leg_rate: LegRate::NotRead,
            paths_included: Recorded::Obtained(0),
            six_legs: SixLegs::Rewalked {
                indices: vec![4, 9, 1, 0, 8, 2],
                matches: vec![true; 6],
            },
            stages: crate::verify::StageReport::NotRead,
            record_body: String::new(),
        }
    }

    fn lines_of(observation: &VerifyObservation<'_>) -> Vec<String> {
        let mut sink = Collect(Vec::new());
        publish_verify(observation, &mut sink);
        sink.0
    }

    #[test]
    fn degrees_stay_quiet_until_a_fail_or_a_request() {
        assert_eq!(VerifyLog::parse("quiet"), Some(VerifyLog::Quiet));
        assert_eq!(VerifyLog::parse("fail"), Some(VerifyLog::Fail));
        assert_eq!(VerifyLog::parse("verbose"), Some(VerifyLog::Verbose));
        assert_eq!(VerifyLog::parse("debug"), None);
        assert!(!VerifyLog::Quiet.emits(true));
        assert!(!VerifyLog::Quiet.emits(false));
        assert!(!VerifyLog::Fail.emits(true));
        assert!(VerifyLog::Fail.emits(false));
        assert!(VerifyLog::Verbose.emits(true));
        assert!(VerifyLog::Verbose.emits(false));
    }

    #[test]
    fn an_unopened_path_names_the_object_and_does_not_invent_checks() {
        let lines = lines_of(&VerifyObservation::unopened("missing.cbor"));
        assert_eq!(
            lines,
            vec![
                r#"{"event":"hike.verify.object","input":"missing.cbor","opened":false}"#
                    .to_owned(),
            ]
        );
        assert!(lines.iter().all(|line| !line.contains("hike.verify.note")));
        assert!(lines.iter().all(|line| !line.contains("hike.verify.check")));
    }

    #[test]
    fn malformed_bytes_report_the_six_checks_in_order() {
        let bytes = Vec::new();
        let measurements = verify(
            &bytes,
            AuditDraw::PinnedSeed([0u8; 32]),
            |_| panic!("no beacon read"),
            |_| -> crate::verify::ChainRead { panic!("no path bind") },
        );
        assert!(!admit(&measurements));
        let lines = lines_of(&VerifyObservation::opened(
            "hike.cbor",
            &bytes,
            &measurements,
        ));
        assert_eq!(lines.len(), 7);
        assert_eq!(
            lines[0],
            r#"{"event":"hike.verify.object","input":"hike.cbor","opened":true,"bytes":0}"#
        );
        assert!(!lines[0].contains("public_key"));
        assert_eq!(
            lines[1],
            r#"{"event":"hike.verify.check","order":1,"name":"shape","found":false}"#
        );
        assert_eq!(
            lines[2],
            r#"{"event":"hike.verify.check","order":2,"name":"checkpoints","found":null}"#
        );
        assert_eq!(
            lines[3],
            r#"{"event":"hike.verify.check","order":3,"name":"waypoints","found":null}"#
        );
        assert_eq!(
            lines[4],
            r#"{"event":"hike.verify.check","order":4,"name":"leg rate","found":"not read"}"#
        );
        assert_eq!(
            lines[5],
            r#"{"event":"hike.verify.check","order":5,"name":"paths included","found":null}"#
        );
        assert_eq!(
            lines[6],
            r#"{"event":"hike.verify.check","order":6,"name":"six legs","found":"not read"}"#
        );
        assert!(lines.iter().all(|line| !line.contains("hike.verify.note")));
        let text = lines.join("\n");
        assert!(!text.contains("walk output"));
        assert!(!text.contains("0.5"));
        assert!(!text.contains("0.25"));
    }

    #[test]
    fn a_pass_keeps_the_hidden_numbers_and_has_no_failure_line() {
        let measurements = passing();
        assert!(admit(&measurements));
        assert_eq!(measurements.leg_rate, LegRate::NotRead);
        assert_eq!(measurements.paths_included, Recorded::Obtained(0));
        let bytes = encode_hike(&structural_object());
        let lines = lines_of(&VerifyObservation::opened(
            "obj.cbor",
            &bytes,
            &measurements,
        ));
        assert_eq!(lines.len(), 7);
        assert!(lines[0].contains("\"opened\":true"));
        assert!(lines[0].contains(&format!("\"bytes\":{}", bytes.len())));
        assert!(lines[0].contains(&format!("\"public_key\":\"{}\"", "11".repeat(32))));
        assert_eq!(
            lines[4],
            r#"{"event":"hike.verify.check","order":4,"name":"leg rate","found":"not read"}"#
        );
        assert_eq!(
            lines[5],
            r#"{"event":"hike.verify.check","order":5,"name":"paths included","found":0}"#
        );
        assert_eq!(
            lines[6],
            r#"{"event":"hike.verify.check","order":6,"name":"six legs","found":{"indices":[4,9,1,0,8,2],"matches":[true,true,true,true,true,true]}}"#
        );
        assert!(lines.iter().all(|line| !line.contains("hike.verify.note")));
        let names: Vec<&str> = lines
            .iter()
            .filter(|line| line.contains("hike.verify.check"))
            .map(|line| line.as_str())
            .collect();
        for (index, name) in CHECK_ORDER.iter().enumerate() {
            assert!(names[index].contains(&format!("\"order\":{}", index + 1)));
            assert!(names[index].contains(&format!("\"name\":\"{name}\"")));
        }
    }

    #[test]
    fn checkpoint_and_waypoint_findings_keep_the_recorded_vectors() {
        let mut measurements = passing();
        if let Recorded::Obtained(found) = &mut measurements.checkpoints {
            found.beacon_matches_index = vec![false, true];
            found.digest_matches = vec![vec![], vec![true, false]];
            found.mismatch = true;
        }
        if let Recorded::Obtained(found) = &mut measurements.waypoints {
            found.seed_value_matches = false;
            found.successor_differs = vec![Some(false), Some(true)];
            found.absent_paths = 3;
            found.present_path_does_not_match_plant = true;
        }
        measurements.six_legs = SixLegs::CouldNotDraw;
        let lines = lines_of(&VerifyObservation {
            input: "x",
            opened: true,
            bytes: Some(4),
            public_key: None,
            measurements: Some(&measurements),
        });
        assert_eq!(
            lines[2],
            r#"{"event":"hike.verify.check","order":2,"name":"checkpoints","found":{"beacon_matches_index":[false,true],"digest_matches":[[],[true,false]],"mismatch":true}}"#
        );
        assert_eq!(
            lines[3],
            r#"{"event":"hike.verify.check","order":3,"name":"waypoints","found":{"seed_value":"mismatch","successor_differs":[false,true],"absent_paths":3,"present_path_does_not_match_plant":true}}"#
        );
        assert_eq!(
            lines[6],
            r#"{"event":"hike.verify.check","order":6,"name":"six legs","found":"could not draw"}"#
        );
        assert_eq!(lines.len(), 7);
        assert!(lines.iter().all(|line| !line.contains("hike.verify.note")));
        assert!(!admit(&measurements));
    }

    #[test]
    fn a_short_draw_is_named_not_read() {
        let mut measurements = passing();
        measurements.six_legs = SixLegs::DrawNotObtained;
        let lines = lines_of(&VerifyObservation {
            input: "x",
            opened: true,
            bytes: Some(1),
            public_key: None,
            measurements: Some(&measurements),
        });
        assert_eq!(
            lines[6],
            r#"{"event":"hike.verify.check","order":6,"name":"six legs","found":"not read"}"#
        );
        let text = lines.join("\n");
        assert!(!text.contains("not obtained"));
        assert!(!text.contains("opening_waypoint"));
        assert!(!text.contains("seed_value_matches"));
        assert_eq!(lines.len(), 7);
        assert!(lines.iter().all(|line| !line.contains("hike.verify.note")));
    }
}
