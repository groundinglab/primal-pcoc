//! On-phone produce and verify, in-process, on the vendored `hike` crate.
//!
//! Produce is the desktop live profile: Bitcoin mainnet gossip, posts to
//! Bari, paths from the same book. Verify is the section 14 measurement.
//! Neither surface is a policy verdict.

mod jni;
mod produce;
mod verify;

pub use produce::{produce_args, scripted, LiveConfig, STOPPED};
pub use verify::{verify_object, IndexReads, VerifyOutcome};

/// Live Bari. `parse_produce` of `--profile live` fills this origin.
pub const BARI_URL: &str = "https://bari.groundinglab.io";
/// Live portable index. Verify reads this and nothing else.
pub const INDEX_URL: &str = hike::DEFAULT_INDEX_URL;

pub fn redact(line: &str) -> String {
    const CHARSET: &str = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    let mut out = String::with_capacity(line.len());
    let mut rest = line;
    loop {
        let next = ["ncryptsec1", "nsec1"]
            .iter()
            .filter_map(|prefix| rest.find(prefix).map(|index| (index, *prefix)))
            .min_by_key(|(index, prefix)| (*index, std::cmp::Reverse(prefix.len())));
        let Some((at, prefix)) = next else {
            out.push_str(rest);
            return out;
        };
        out.push_str(&rest[..at]);
        let tail = &rest[at + prefix.len()..];
        let run = tail
            .char_indices()
            .find(|(_, ch)| !CHARSET.contains(ch.to_ascii_lowercase()))
            .map(|(index, _)| index)
            .unwrap_or(tail.len());
        out.push_str("[redacted]");
        rest = &tail[run..];
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn live_hosts_are_bari_and_the_index() {
        assert_eq!(BARI_URL, "https://bari.groundinglab.io");
        assert_eq!(INDEX_URL, "https://index.groundinglab.io");
        assert_eq!(hike::DEFAULT_INDEX_URL, INDEX_URL);
    }

    #[test]
    fn live_args_parse_as_the_reference_live_profile() {
        let config = LiveConfig {
            public_key_hex: "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
                .into(),
            output: "/tmp/hike.cbor".into(),
            gossip_peer: None,
        };
        let parsed = hike::parse_produce(&produce_args(&config)).expect("parses");
        assert_eq!(parsed.profile, hike::PlaneProfile::Live);
        assert_eq!(parsed.bari_url, BARI_URL);
        assert!(matches!(
            parsed.clock,
            hike::ProduceClock::Gossip {
                dial: hike::GossipDial::MainnetDnsSeeds,
                ..
            }
        ));

        let named = LiveConfig {
            gossip_peer: Some(" 192.0.2.7:8333 ".into()),
            ..config
        };
        let parsed = hike::parse_produce(&produce_args(&named)).expect("named peer parses");
        assert!(matches!(
            parsed.clock,
            hike::ProduceClock::Gossip {
                dial: hike::GossipDial::Named(ref peer),
                ..
            } if peer == "192.0.2.7:8333"
        ));
    }

    #[test]
    fn redact_hides_a_secret_key_spelling() {
        let line = "note nsec1qqqq end";
        let redacted = redact(line);
        assert!(!redacted.contains("nsec1"));
        assert!(redacted.contains("[redacted]"));
    }
}
