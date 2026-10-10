//! JSON report `satoshi-hike-cli-report-v0.2`.
//!
//! `result` uses a section 4 token. `present` means the object or the verify
//! record was written. `not read` means it was not.

use serde::Serialize;

pub const SCHEMA: &str = "satoshi-hike-cli-report-v0.2";

#[derive(Serialize)]
pub struct Report {
    pub schema: &'static str,
    pub command: &'static str,
    pub result: &'static str,
    pub rung: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub audit_draw: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub audit_indices: Option<Vec<u64>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub output: Option<String>,
}

impl Report {
    pub fn produce_pass(output: String) -> Self {
        Self {
            schema: SCHEMA,
            command: "produce",
            result: "present",
            rung: None,
            audit_draw: None,
            audit_indices: None,
            output: Some(output),
        }
    }

    pub fn produce_fail() -> Self {
        Self {
            schema: SCHEMA,
            command: "produce",
            result: "not read",
            rung: None,
            audit_draw: None,
            audit_indices: None,
            output: None,
        }
    }

    pub fn verify_outcome(
        passed: bool,
        rung: Option<&'static str>,
        audit_draw: Option<&'static str>,
        audit_indices: Option<Vec<u64>>,
    ) -> Self {
        Self {
            schema: SCHEMA,
            command: "verify",
            result: if passed { "present" } else { "not read" },
            rung,
            audit_draw,
            audit_indices,
            output: None,
        }
    }

    pub fn to_bytes(&self) -> Vec<u8> {
        let mut bytes = serde_json::to_vec(self).expect("report");
        bytes.push(b'\n');
        bytes
    }
}

pub fn emit_report(report: &Report, report_path: Option<&str>) -> Result<(), String> {
    let bytes = report.to_bytes();
    use std::io::Write;
    let mut stdout = std::io::stdout().lock();
    stdout
        .write_all(&bytes)
        .map_err(|e| format!("stdout: {e}"))?;
    stdout.flush().map_err(|e| format!("stdout: {e}"))?;
    if let Some(path) = report_path {
        std::fs::write(path, &bytes).map_err(|e| format!("report: {e}"))?;
    }
    Ok(())
}

pub fn parent_dir_exists(path: &str) -> Result<(), String> {
    if path.is_empty() {
        return Err("path is empty".into());
    }
    let path = std::path::Path::new(path);
    if path.is_dir() {
        return Err("path is a directory".into());
    }
    let parent = match path.parent() {
        Some(parent) if !parent.as_os_str().is_empty() => parent,
        _ => std::path::Path::new("."),
    };
    if !parent.is_dir() {
        return Err("parent directory does not exist".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn report_schema_and_field_order() {
        let fail = Report::produce_fail();
        assert_eq!(
            fail.to_bytes(),
            br#"{"schema":"satoshi-hike-cli-report-v0.2","command":"produce","result":"not read","rung":null}"#
                .as_slice()
                .iter()
                .copied()
                .chain(std::iter::once(b'\n'))
                .collect::<Vec<_>>()
        );
        let pass = Report::produce_pass("out.cbor".to_owned());
        assert_eq!(
            std::str::from_utf8(&pass.to_bytes()).unwrap(),
            "{\"schema\":\"satoshi-hike-cli-report-v0.2\",\"command\":\"produce\",\"result\":\"present\",\"rung\":null,\"output\":\"out.cbor\"}\n"
        );
        let verify = Report::verify_outcome(false, Some("structural"), None, None);
        assert_eq!(
            std::str::from_utf8(&verify.to_bytes()).unwrap(),
            "{\"schema\":\"satoshi-hike-cli-report-v0.2\",\"command\":\"verify\",\"result\":\"not read\",\"rung\":\"structural\"}\n"
        );
        let audit =
            Report::verify_outcome(true, None, Some("pinned-seed"), Some((0..18).collect()));
        assert_eq!(
            std::str::from_utf8(&audit.to_bytes()).unwrap(),
            "{\"schema\":\"satoshi-hike-cli-report-v0.2\",\"command\":\"verify\",\"result\":\"present\",\"rung\":null,\"audit_draw\":\"pinned-seed\",\"audit_indices\":[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17]}\n"
        );
        let text = String::from_utf8(fail.to_bytes()).unwrap()
            + std::str::from_utf8(&pass.to_bytes()).unwrap()
            + std::str::from_utf8(&verify.to_bytes()).unwrap();
        assert!(!text.contains("pass"));
        assert!(!text.contains("fail"));
    }
}
