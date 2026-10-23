//! Verify, in-process. The measurement is `pcoc::verify`.
//! An unreadable value is not a record. A file that could not be opened is
//! the caller's concern. Malformed bytes are the SDK's section 14 record.

use crate::INDEX_URL;
use pcoc::cbor::{self, Cbor};
use pcoc::profile::Profile;
use pcoc::verify::{HeightIndex, Precheck, Verified};
use serde_json::json;
use sha2::{Digest, Sha256};
use std::io::Read;
use std::time::{Duration, Instant};

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct IndexReads {
    pub issued: u32,
    pub answered: u32,
    pub total_ms: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum VerifyOutcome {
    Record {
        /// Section 14 JSON as `pcoc::verify` wrote it. No trailing newline.
        json: String,
        bytes: u64,
        sha256: String,
        reads: IndexReads,
    },
    Unreadable,
}

/// `threads` stays on the JNI signature. The SDK verifier does not take a pool.
pub fn verify_object(bytes: &[u8], index_url: &str, threads: usize) -> VerifyOutcome {
    verify_with(bytes, index_url, threads, &Profile::production())
}

/// CASES S1 sizes. Tests of a scripted object use this. The phone does not.
#[cfg(test)]
pub(crate) fn verify_object_sized(bytes: &[u8], index_url: &str, threads: usize) -> VerifyOutcome {
    let profile = pcoc_produce::cases_profile(pcoc_produce::Case::S1);
    verify_with(bytes, index_url, threads, &profile)
}

fn verify_with(bytes: &[u8], index_url: &str, threads: usize, profile: &Profile) -> VerifyOutcome {
    let _pool = threads.max(1);
    if nesting_too_deep(bytes) {
        return VerifyOutcome::Unreadable;
    }
    if matches!(pcoc::precheck(bytes, profile), Precheck::DecodeFailure) {
        return VerifyOutcome::Unreadable;
    }
    let url = if index_url.is_empty() {
        INDEX_URL
    } else {
        index_url
    };
    let mut reads = IndexReads::default();
    let verified = {
        let mut index = HttpIndex {
            base: url.trim().trim_end_matches('/').to_owned(),
            agent: http_agent(),
            reads: &mut reads,
        };
        pcoc::verify(bytes, profile, &mut index)
    };
    let json = match verified {
        Verified::Record(json) | Verified::UnknownKind(json) => json,
        Verified::DecodeFailure => return VerifyOutcome::Unreadable,
    };
    if serde_json::from_str::<serde_json::Value>(&json).is_err() {
        return VerifyOutcome::Unreadable;
    }
    VerifyOutcome::Record {
        json,
        bytes: bytes.len() as u64,
        sha256: hex_encode(&Sha256::digest(bytes)),
        reads,
    }
}

struct HttpIndex<'a> {
    base: String,
    agent: ureq::Agent,
    reads: &'a mut IndexReads,
}

impl HeightIndex for HttpIndex<'_> {
    fn read_height(&mut self, height: u64) -> Option<Vec<u8>> {
        self.reads.issued += 1;
        let url = format!("{}/index/{height}", self.base);
        let started = Instant::now();
        let response = self.agent.get(&url).call().ok()?;
        if response.status() != 200 {
            return None;
        }
        let mut body = Vec::new();
        if response
            .into_reader()
            .take(1 << 20)
            .read_to_end(&mut body)
            .is_err()
        {
            return None;
        }
        self.reads.answered += 1;
        self.reads.total_ms += started.elapsed().as_millis() as u64;
        Some(body)
    }
}

fn http_agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .redirects(0)
        .timeout(Duration::from_secs(10))
        .user_agent("pcoc-hike")
        .build()
}

pub fn outcome_json(outcome: &VerifyOutcome, index_url: &str) -> String {
    match outcome {
        VerifyOutcome::Unreadable => json!({ "outcome": "unreadable" }).to_string(),
        VerifyOutcome::Record {
            json: record,
            bytes,
            sha256,
            reads,
        } => json!({
            "outcome": "record",
            "record": record,
            "bytes": bytes,
            "sha256": sha256,
            "issued": reads.issued,
            "answered": reads.answered,
            "total_ms": reads.total_ms,
            "index_url": index_url,
        })
        .to_string(),
    }
}

/// Containers at this depth are still decoded. One more is rejected.
const MAX_NESTING: usize = 63;

pub fn yielded_key(bytes: &[u8]) -> Option<[u8; 32]> {
    if nesting_too_deep(bytes) {
        return None;
    }
    let value = cbor::decode_one(bytes).ok()?;
    find_key(&value)
}

fn find_key(value: &Cbor) -> Option<[u8; 32]> {
    match value {
        Cbor::Map(pairs) => {
            if let Some(Cbor::Bytes(raw)) = cbor::map_get(pairs, "public_key") {
                if raw.len() == 32 {
                    let mut key = [0u8; 32];
                    key.copy_from_slice(raw);
                    return Some(key);
                }
            }
            pairs.iter().find_map(|(_, child)| find_key(child))
        }
        Cbor::Array(items) => items.iter().find_map(find_key),
        _ => None,
    }
}

fn nesting_too_deep(bytes: &[u8]) -> bool {
    !nesting_within_limit(bytes)
}

struct Header {
    major: u8,
    arg: u64,
    header: usize,
}

fn read_header(data: &[u8]) -> Option<Header> {
    let ib = *data.first()?;
    let major = ib >> 5;
    let ai = ib & 0x1f;
    if ai == 31 {
        return None;
    }
    if ai < 24 {
        return Some(Header {
            major,
            arg: u64::from(ai),
            header: 1,
        });
    }
    let nbytes = match ai {
        24 => 1,
        25 => 2,
        26 => 4,
        27 => 8,
        _ => return None,
    };
    if data.len() < 1 + nbytes {
        return None;
    }
    let mut arg = 0u64;
    for byte in &data[1..1 + nbytes] {
        arg = (arg << 8) | u64::from(*byte);
    }
    Some(Header {
        major,
        arg,
        header: 1 + nbytes,
    })
}

/// `false` when an array or map would nest deeper than 63.
fn nesting_within_limit(data: &[u8]) -> bool {
    if data.is_empty() {
        return true;
    }
    let mut i = 0usize;
    let mut depth = 0usize;
    let mut open: Vec<u64> = Vec::new();
    loop {
        if i >= data.len() {
            return true;
        }
        let Some(step) = read_header(&data[i..]) else {
            return true;
        };
        if step.header > data.len() - i {
            return true;
        }
        match step.major {
            4 | 5 => {
                let unread = data.len() - i - step.header;
                if step.arg > unread as u64 {
                    return true;
                }
                let children = if step.major == 5 {
                    step.arg.saturating_mul(2)
                } else {
                    step.arg
                };
                i += step.header;
                if children == 0 {
                    if !finish_item(&mut open, &mut depth) {
                        return true;
                    }
                } else {
                    depth += 1;
                    if depth > MAX_NESTING {
                        return false;
                    }
                    open.push(children);
                }
            }
            2 | 3 => {
                let unread = data.len() - i - step.header;
                if step.arg > unread as u64 {
                    return true;
                }
                i += step.header + step.arg as usize;
                if !finish_item(&mut open, &mut depth) {
                    return true;
                }
            }
            0 | 1 | 7 => {
                i += step.header;
                if !finish_item(&mut open, &mut depth) {
                    return true;
                }
            }
            _ => return true,
        }
    }
}

fn finish_item(open: &mut Vec<u64>, depth: &mut usize) -> bool {
    if open.is_empty() {
        return false;
    }
    loop {
        let left = open.last_mut().expect("open container");
        *left -= 1;
        if *left > 0 {
            return true;
        }
        open.pop();
        *depth -= 1;
        if open.is_empty() {
            return false;
        }
    }
}

fn hex_encode(bytes: &[u8]) -> String {
    pcoc::hexutil::hex_encode(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn junk_bytes_are_a_measurement_and_do_not_need_the_index() {
        let outcome = verify_object(&[0xff, 0x00, 0x01], "http://127.0.0.1:9", 2);
        match outcome {
            VerifyOutcome::Record { json, reads, .. } => {
                assert_eq!(reads.issued, 0);
                assert!(json.contains("\"profile\""));
                let _ = serde_json::from_str::<serde_json::Value>(&json).unwrap();
            }
            VerifyOutcome::Unreadable => panic!("malformed bytes are an SDK record"),
        }
    }

    #[test]
    fn a_minimal_public_key_map_yields_that_key() {
        let mut key = [0u8; 32];
        key[0] = 0x11;
        key[31] = 0x22;
        let bytes = public_key_map(&key);
        assert_eq!(yielded_key(&bytes), Some(key));
    }

    #[test]
    fn depth_63_yields_the_key_and_64_is_rejected() {
        let key = [0x42u8; 32];
        assert_eq!(yielded_key(&nest_key(1, &key)), Some(key));
        assert_eq!(yielded_key(&nest_key(63, &key)), Some(key));
        assert!(nesting_too_deep(&nest_key(64, &key)));
        assert_eq!(yielded_key(&nest_key(64, &key)), None);
    }

    fn public_key_map(key: &[u8; 32]) -> Vec<u8> {
        cbor::encode_map(&[("public_key".into(), cbor::encode_bytes(key))])
    }

    fn nest_key(depth: usize, key: &[u8; 32]) -> Vec<u8> {
        let mut bytes = public_key_map(key);
        for _ in 1..depth {
            bytes = cbor::encode_array(&[bytes]);
        }
        bytes
    }
}
