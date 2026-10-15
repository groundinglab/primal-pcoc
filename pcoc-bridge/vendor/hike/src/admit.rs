//! Section 15. The only admission judgment.
//!
//! A threshold here can change without changing section 13 and without
//! changing the measurements in section 14.

use crate::verify::{Measurements, Recorded, SixLegs};

/// Paths included, initial value 0. Zero included paths still pass.
fn paths_included_minimum() -> u64 {
    0
}

/// Section 15 outcome this library can name. `Clear` is not a Gate `accept`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Admission {
    /// No invariant refuse and no held read.
    Clear,
    Refuse,
    /// A `not read` that section 15 holds. Not a refuse.
    Held,
}

/// Apply section 15 to a section 14 record.
///
/// `true` is [`Admission::Clear`]. A held read is not clear and not a refuse.
pub fn admit(measurements: &Measurements) -> bool {
    admission(measurements) == Admission::Clear
}

/// Section 15. A short six-leg draw is [`Admission::Held`]. Fewer than six legs is [`Admission::Refuse`].
pub fn admission(measurements: &Measurements) -> Admission {
    if matches!(
        measurements.version,
        crate::verify::ObjectLineage::Earlier(_)
    ) {
        return Admission::Refuse;
    }
    if !measurements.shape {
        return Admission::Refuse;
    }
    if let Recorded::Obtained(checkpoints) = &measurements.checkpoints {
        if checkpoints.mismatch {
            return Admission::Refuse;
        }
    }
    if let Recorded::Obtained(waypoints) = &measurements.waypoints {
        if !waypoints.seed_value_matches {
            return Admission::Refuse;
        }
        if waypoints.present_path_does_not_match_plant {
            return Admission::Refuse;
        }
        if waypoints
            .successor_differs
            .iter()
            .any(|differs| *differs == Some(true))
        {
            return Admission::Refuse;
        }
    }
    let mut held = false;
    match &measurements.six_legs {
        SixLegs::Rewalked { matches, .. } => {
            if matches.iter().any(|matches| !matches) {
                return Admission::Refuse;
            }
        }
        // Fewer than six legs is settled by the object's bytes: refuse at once.
        SixLegs::CouldNotDraw => return Admission::Refuse,
        // A short draw, and a six-leg read that was not taken, are `not read`.
        // That is held, not a refuse.
        SixLegs::DrawNotObtained | SixLegs::NotObtained => held = true,
    }
    // An unread leg rate does not fail admission.
    if let Recorded::Obtained(count) = measurements.paths_included {
        if count < paths_included_minimum() {
            return Admission::Refuse;
        }
    }
    if held {
        Admission::Held
    } else {
        Admission::Clear
    }
}

/// Recorded facts that section 15 refuses, in the order [`admission`] checks them.
///
/// Empty when the outcome is not [`Admission::Refuse`]. A held read is not a fact.
/// This does not measure and does not change the judgment.
pub fn failure_facts(measurements: &Measurements) -> Vec<&'static str> {
    let mut facts = Vec::new();
    if matches!(
        measurements.version,
        crate::verify::ObjectLineage::Earlier(_)
    ) {
        facts.push("earlier_version");
        return facts;
    }
    if !measurements.shape {
        facts.push("shape is false");
    }
    if let Recorded::Obtained(checkpoints) = &measurements.checkpoints {
        if checkpoints.mismatch {
            facts.push("checkpoint mismatch");
        }
    }
    if let Recorded::Obtained(waypoints) = &measurements.waypoints {
        if !waypoints.seed_value_matches {
            facts.push("seed value mismatches");
        }
        if waypoints.present_path_does_not_match_plant {
            facts.push("a present path does not match the plant");
        }
        if waypoints
            .successor_differs
            .iter()
            .any(|differs| *differs == Some(true))
        {
            facts.push("a successor waypoint differs");
        }
    }
    match &measurements.six_legs {
        SixLegs::Rewalked { matches, .. } => {
            if matches.iter().any(|matches| !matches) {
                facts.push("a recomputed leg result differs");
            }
        }
        SixLegs::CouldNotDraw => {
            facts.push("fewer than 6 legs, so the six indices could not be drawn");
        }
        // A short draw is held. It is not a refuse fact.
        SixLegs::DrawNotObtained | SixLegs::NotObtained => {}
    }
    // An unread leg rate is not a fact. An absent path is not a fact.
    if let Recorded::Obtained(count) = measurements.paths_included {
        if count < paths_included_minimum() {
            facts.push("paths included is below the minimum");
        }
    }
    facts
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::verify::{CheckpointMeasurement, LegRate, WaypointMeasurement};

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
                indices: vec![0, 1, 2, 3, 4, 5],
                matches: vec![true; 6],
            },
            stages: crate::verify::StageReport::NotRead,
            record_body: String::new(),
        }
    }

    #[test]
    fn section_15_passes_an_unread_leg_rate_absent_paths_and_zero_included_paths() {
        let measurements = passing();
        assert_eq!(measurements.leg_rate, LegRate::NotRead);
        assert_eq!(
            match &measurements.waypoints {
                Recorded::Obtained(waypoints) => waypoints.absent_paths,
                Recorded::NotObtained => panic!("waypoints"),
            },
            18
        );
        assert_eq!(measurements.paths_included, Recorded::Obtained(0));
        assert!(admit(&measurements));
    }

    #[test]
    fn section_15_is_what_fails_admission() {
        let mut measurements = passing();
        measurements.shape = false;
        assert!(!admit(&measurements));

        let mut measurements = passing();
        if let Recorded::Obtained(waypoints) = &mut measurements.waypoints {
            waypoints.seed_value_matches = false;
        }
        assert!(!admit(&measurements));
        assert_eq!(failure_facts(&measurements), vec!["seed value mismatches"]);

        let mut measurements = passing();
        if let Recorded::Obtained(checkpoints) = &mut measurements.checkpoints {
            checkpoints.mismatch = true;
            checkpoints.beacon_matches_index[0] = false;
        }
        assert!(!admit(&measurements));

        let mut measurements = passing();
        if let Recorded::Obtained(waypoints) = &mut measurements.waypoints {
            waypoints.present_path_does_not_match_plant = true;
        }
        assert!(!admit(&measurements));

        let mut measurements = passing();
        if let Recorded::Obtained(waypoints) = &mut measurements.waypoints {
            waypoints.successor_differs[4] = Some(true);
        }
        assert!(!admit(&measurements));

        let mut measurements = passing();
        if let SixLegs::Rewalked { matches, .. } = &mut measurements.six_legs {
            matches[2] = false;
        }
        assert!(!admit(&measurements));

        let mut measurements = passing();
        measurements.six_legs = SixLegs::CouldNotDraw;
        assert!(!admit(&measurements));

        let mut measurements = passing();
        measurements.six_legs = SixLegs::DrawNotObtained;
        assert_eq!(admission(&measurements), Admission::Held);
        assert!(!admit(&measurements));
        assert!(failure_facts(&measurements).is_empty());

        let mut measurements = passing();
        measurements.six_legs = SixLegs::NotObtained;
        assert_eq!(admission(&measurements), Admission::Held);
        assert!(!admit(&measurements));
    }

    #[test]
    fn failure_facts_name_the_same_judgment_as_admit() {
        let samples = failure_samples();
        assert!(samples.len() > 8);
        for measurements in samples {
            assert_eq!(
                admission(&measurements) != Admission::Refuse,
                failure_facts(&measurements).is_empty(),
                "{measurements:?} -> {:?}",
                failure_facts(&measurements)
            );
            let text = failure_facts(&measurements).join(" ");
            assert!(!text.contains("walk output"));
            assert!(!text.contains("not read"));
            assert!(!text.contains("0.5"));
            assert!(!text.contains("0.25"));
            assert!(!text.contains("absent"));
        }
    }

    #[test]
    fn failure_facts_follow_the_admit_check_order_and_skip_an_unread_leg_rate() {
        let mut measurements = passing();
        measurements.shape = false;
        if let Recorded::Obtained(checkpoints) = &mut measurements.checkpoints {
            checkpoints.mismatch = true;
        }
        if let Recorded::Obtained(waypoints) = &mut measurements.waypoints {
            waypoints.absent_paths = 18;
            waypoints.present_path_does_not_match_plant = true;
            waypoints.successor_differs[1] = Some(true);
        }
        measurements.leg_rate = LegRate::NotRead;
        measurements.paths_included = Recorded::Obtained(0);
        measurements.six_legs = SixLegs::CouldNotDraw;
        assert_eq!(
            failure_facts(&measurements),
            vec![
                "shape is false",
                "checkpoint mismatch",
                "a present path does not match the plant",
                "a successor waypoint differs",
                "fewer than 6 legs, so the six indices could not be drawn",
            ]
        );
        assert!(!admit(&measurements));

        let mut draw = passing();
        draw.six_legs = SixLegs::DrawNotObtained;
        assert!(failure_facts(&draw).is_empty());
        assert_eq!(admission(&draw), Admission::Held);
        assert!(!admit(&draw));

        let mut rewalk = passing();
        if let SixLegs::Rewalked { matches, .. } = &mut rewalk.six_legs {
            matches[5] = false;
        }
        assert_eq!(
            failure_facts(&rewalk),
            vec!["a recomputed leg result differs"]
        );

        let mut unread = passing();
        unread.leg_rate = LegRate::NotRead;
        unread.paths_included = Recorded::Obtained(0);
        if let Recorded::Obtained(waypoints) = &mut unread.waypoints {
            waypoints.absent_paths = 18;
        }
        assert!(failure_facts(&unread).is_empty());
        assert!(admit(&unread));
    }

    fn failure_samples() -> Vec<Measurements> {
        let mut mismatch = passing();
        if let Recorded::Obtained(found) = &mut mismatch.checkpoints {
            found.mismatch = true;
        }
        let mut path = passing();
        if let Recorded::Obtained(found) = &mut path.waypoints {
            found.present_path_does_not_match_plant = true;
        }
        let mut successor = passing();
        if let Recorded::Obtained(found) = &mut successor.waypoints {
            found.successor_differs[0] = Some(true);
        }
        let mut rewalk = passing();
        if let SixLegs::Rewalked { matches, .. } = &mut rewalk.six_legs {
            matches[0] = false;
        }
        let mut shape = passing();
        shape.shape = false;
        let mut no_checkpoints = passing();
        no_checkpoints.checkpoints = Recorded::NotObtained;
        let mut no_waypoints = passing();
        no_waypoints.waypoints = Recorded::NotObtained;
        let mut could_not = passing();
        could_not.six_legs = SixLegs::CouldNotDraw;
        let mut missing_draw = passing();
        missing_draw.six_legs = SixLegs::DrawNotObtained;
        let mut not_obtained = passing();
        not_obtained.six_legs = SixLegs::NotObtained;
        let mut zero_paths = passing();
        zero_paths.paths_included = Recorded::Obtained(0);
        zero_paths.leg_rate = LegRate::NotRead;
        let mut unread_only = passing();
        unread_only.paths_included = Recorded::NotObtained;
        vec![
            passing(),
            shape,
            mismatch,
            no_checkpoints,
            path,
            successor,
            no_waypoints,
            rewalk,
            could_not,
            missing_draw,
            not_obtained,
            zero_paths,
            unread_only,
        ]
    }
}
