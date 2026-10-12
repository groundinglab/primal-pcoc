//! Test-plane gate. The tip sequence is a read. The plant is a write.
//! A skip is recorded beside the gate. It is not a pass.

/// Retired HTTP tip host, plus the live commitment and door hosts.
/// A test profile must not fill these. The tip host is not a produce default.
pub const LIVE_TIP_HOST: &str = "tip.pcoc.app";
pub const LIVE_BARI_HOST: &str = "bari.groundinglab.io";
pub const LIVE_INDEX_HOST: &str = "index.groundinglab.io";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GateJudgment {
    Pass,
    Fail,
}

/// Recorded beside the gate. Not an input to [`judge_test_policy`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GateSkip {
    pub reason: String,
}

/// Measurements the test policy judges. Verify against the test index is
/// `index_matches_tip`. This does not call the live door.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TestGateMeasurement {
    pub planted: bool,
    pub path_status: u16,
    pub index_is_live: bool,
    pub index_matches_tip: bool,
}

/// Test admit policy. A skip is not passed here. Live-index measurements fail.
pub fn judge_test_policy(measurement: &TestGateMeasurement) -> GateJudgment {
    if measurement.planted
        && measurement.path_status == 200
        && !measurement.index_is_live
        && measurement.index_matches_tip
    {
        GateJudgment::Pass
    } else {
        GateJudgment::Fail
    }
}

pub fn live_plane_host(url: &str) -> bool {
    host_is(url, LIVE_TIP_HOST) || host_is(url, LIVE_BARI_HOST) || host_is(url, LIVE_INDEX_HOST)
}

/// `tip.pcoc.app` is retired. A URL or a `host:port` gossip peer that names it is not a dial.
pub fn names_retired_tip(value: &str) -> bool {
    if value.contains("://") {
        return host_is(value, LIVE_TIP_HOST);
    }
    peer_host(value).is_some_and(|host| host.eq_ignore_ascii_case(LIVE_TIP_HOST))
}

fn peer_host(value: &str) -> Option<&str> {
    if let Some(rest) = value.strip_prefix('[') {
        let (host, _) = rest.rsplit_once("]:")?;
        if host.is_empty() {
            return None;
        }
        return Some(host);
    }
    let (host, _) = value.rsplit_once(':')?;
    if host.is_empty() || host.contains(':') {
        return None;
    }
    Some(host)
}

/// `https://{host}` with optional port 443. Scheme is case-insensitive.
pub fn is_https_live_origin(url: &str, host: &str) -> bool {
    let Some((scheme, rest)) = url.split_once("://") else {
        return false;
    };
    if !scheme.eq_ignore_ascii_case("https") {
        return false;
    }
    let host_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    if host_end != rest.len() {
        return false;
    }
    let hostport = &rest[..host_end];
    if hostport.contains('@') || hostport.is_empty() {
        return false;
    }
    let hostname = if let Some((name, port)) = hostport.rsplit_once(':') {
        if port != "443" {
            return false;
        }
        name
    } else {
        hostport
    };
    hostname.eq_ignore_ascii_case(host)
}

fn host_is(url: &str, host: &str) -> bool {
    let Some((_, rest)) = url.split_once("://") else {
        return false;
    };
    let host_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let hostport = &rest[..host_end];
    let hostname = hostport
        .rsplit_once('@')
        .map(|(_, host)| host)
        .unwrap_or(hostport);
    let hostname = if let Some((name, _)) = hostname.rsplit_once(':') {
        if name.starts_with('[') {
            hostname
        } else {
            name
        }
    } else {
        hostname
    };
    hostname.eq_ignore_ascii_case(host)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_policy_passes_only_the_whole_gate() {
        let pass = TestGateMeasurement {
            planted: true,
            path_status: 200,
            index_is_live: false,
            index_matches_tip: true,
        };
        assert_eq!(judge_test_policy(&pass), GateJudgment::Pass);
        assert_eq!(
            judge_test_policy(&TestGateMeasurement {
                path_status: 404,
                ..pass
            }),
            GateJudgment::Fail
        );
        assert_eq!(
            judge_test_policy(&TestGateMeasurement {
                index_is_live: true,
                ..pass
            }),
            GateJudgment::Fail
        );
        assert_eq!(
            judge_test_policy(&TestGateMeasurement {
                planted: false,
                ..pass
            }),
            GateJudgment::Fail
        );
        assert_eq!(
            judge_test_policy(&TestGateMeasurement {
                index_matches_tip: false,
                ..pass
            }),
            GateJudgment::Fail
        );
    }

    #[test]
    fn live_hosts_are_recognized_without_filling_them() {
        assert!(live_plane_host("https://tip.pcoc.app"));
        assert!(live_plane_host("http://bari.groundinglab.io:443"));
        assert!(live_plane_host("HTTPS://index.groundinglab.io"));
        assert!(!live_plane_host("http://127.0.0.1:38080"));
        assert!(!live_plane_host("https://bari.pcoc.app"));
        assert!(!live_plane_host("https://index.pcoc.app"));
        assert!(is_https_live_origin("HTTPS://tip.pcoc.app", LIVE_TIP_HOST));
        assert!(is_https_live_origin(
            "https://index.groundinglab.io:443",
            LIVE_INDEX_HOST
        ));
        assert!(!is_https_live_origin("http://tip.pcoc.app", LIVE_TIP_HOST));
        assert!(!is_https_live_origin(
            "https://tip.pcoc.app/index",
            LIVE_TIP_HOST
        ));
        assert!(names_retired_tip("tip.pcoc.app:9"));
        assert!(names_retired_tip("TIP.pcoc.app:8333"));
        assert!(names_retired_tip("https://tip.pcoc.app"));
        assert!(!names_retired_tip("synth.invalid:1"));
        assert!(!names_retired_tip("https://bari.groundinglab.io"));
    }
}
