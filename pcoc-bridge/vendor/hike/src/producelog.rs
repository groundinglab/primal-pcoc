//! Append-only event log for a live produce.
//!
//! The file is not the hike object. Nothing in the leg, the checkpoint, or the
//! leg result writes here. Records are appended after the work they name.

use std::io::Write;
use std::path::{Path, PathBuf};

use serde::Serialize;

use crate::hex::encode_hex32;

/// `{output}.events`. Not the hike object path.
pub fn event_log_path(output: &str) -> PathBuf {
    PathBuf::from(format!("{output}.events"))
}

/// One live run. Created empty, then appended. A failed append does not change
/// the hike.
pub struct ProduceEventLog {
    file: std::fs::File,
}

impl ProduceEventLog {
    pub fn create(path: &Path) -> std::io::Result<Self> {
        let file = std::fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(path)?;
        Ok(Self { file })
    }

    pub fn block(&mut self, height: u64, tip: &[u8; 32]) {
        let tip = encode_hex32(tip);
        self.write_line(&BlockLine {
            event: "hike.produce.block",
            height,
            tip: &tip,
        });
    }

    pub fn waypoint(&mut self, stage: u64, waypoint: &[u8; 32]) {
        let waypoint = encode_hex32(waypoint);
        self.write_line(&WaypointLine {
            event: "hike.produce.waypoint",
            stage,
            waypoint: &waypoint,
        });
    }

    pub fn posted(
        &mut self,
        stage: u64,
        waypoint: &[u8; 32],
        posted: bool,
        succeeded: bool,
        status: Option<u16>,
    ) {
        let waypoint = encode_hex32(waypoint);
        self.write_line(&PostedLine {
            event: "hike.produce.posted",
            stage,
            waypoint: &waypoint,
            posted,
            succeeded,
            status,
        });
    }

    pub fn checked(&mut self, waypoint: &[u8; 32], checked: bool) {
        let waypoint = encode_hex32(waypoint);
        self.write_line(&CheckedLine {
            event: "hike.produce.checked",
            waypoint: &waypoint,
            checked,
        });
    }

    /// After the path GET has returned. `worked` is true only for a body that
    /// was stored. A miss stays `worked: false`.
    pub fn fetch(
        &mut self,
        waypoint: &[u8; 32],
        worked: bool,
        status: Option<u16>,
        block: Option<&[u8; 32]>,
        block_height: Option<u32>,
        miss: Option<&str>,
    ) {
        let waypoint = encode_hex32(waypoint);
        let block = block.map(encode_hex32);
        self.write_line(&FetchLine {
            event: "hike.produce.fetch",
            waypoint: &waypoint,
            worked,
            status,
            block: block.as_deref(),
            block_height,
            miss,
        });
    }

    fn write_line<T: Serialize>(&mut self, line: &T) {
        let Ok(text) = serde_json::to_string(line) else {
            return;
        };
        if let Err(err) = writeln!(self.file, "{text}") {
            eprintln!("event log: {err}");
            return;
        }
        if let Err(err) = self.file.sync_data() {
            eprintln!("event log: {err}");
        }
    }
}

#[derive(Serialize)]
struct BlockLine<'a> {
    event: &'static str,
    height: u64,
    tip: &'a str,
}

#[derive(Serialize)]
struct WaypointLine<'a> {
    event: &'static str,
    stage: u64,
    waypoint: &'a str,
}

#[derive(Serialize)]
struct PostedLine<'a> {
    event: &'static str,
    stage: u64,
    waypoint: &'a str,
    posted: bool,
    succeeded: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    status: Option<u16>,
}

#[derive(Serialize)]
struct CheckedLine<'a> {
    event: &'static str,
    waypoint: &'a str,
    checked: bool,
}

#[derive(Serialize)]
struct FetchLine<'a> {
    event: &'static str,
    waypoint: &'a str,
    worked: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    status: Option<u16>,
    #[serde(skip_serializing_if = "Option::is_none")]
    block: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    block_height: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    miss: Option<&'a str>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn event_log_is_not_the_hike_object_path() {
        let object = "out/hike.cbor";
        let log = event_log_path(object);
        assert_ne!(log.as_os_str(), std::ffi::OsStr::new(object));
        assert_eq!(log, PathBuf::from("out/hike.cbor.events"));
    }
}
