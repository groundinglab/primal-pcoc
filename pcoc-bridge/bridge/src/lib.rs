//! On-phone produce and verify, in-process, on the pcoc Rust SDK.
//!
//! Produce on this build uses the SDK live header source.
//! Verify is `pcoc::verify`, the section 14 measurement. Neither surface is a
//! policy verdict. Object kind and domain bytes come from the SDK exports.

mod jni;
mod produce;
mod verify;

pub use produce::{scripted, LiveConfig, STOPPED};
pub use verify::{verify_object, IndexReads, VerifyOutcome};

/// Live Bari. Produce posts waypoints here.
pub const BARI_URL: &str = "https://bari.groundinglab.io";
/// Live portable index. Verify reads this. The SDK does not name a default index.
pub const INDEX_URL: &str = "https://index.groundinglab.io";

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
    }

    #[test]
    fn redact_hides_a_secret_key_spelling() {
        let line = "note nsec1qqqq end";
        let redacted = redact(line);
        assert!(!redacted.contains("nsec1"));
        assert!(redacted.contains("[redacted]"));
    }
}
