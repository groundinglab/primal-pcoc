//! Portable-index JSON body. HTTP lives in the produce program.

use std::fmt;

use serde::de::{self, DeserializeSeed, Deserializer, MapAccess, SeqAccess, Visitor};
use serde_json::{Map, Number, Value};

use crate::hex::decode_hex_digits;

#[derive(Debug)]
pub struct ClockError;

pub fn natural_beacon(display: &[u8; 32]) -> [u8; 32] {
    let mut natural = [0u8; 32];
    for i in 0..32 {
        natural[i] = display[31 - i];
    }
    natural
}

/// One index item.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct IndexBeacon {
    pub natural: [u8; 32],
    pub height: u64,
    /// Bitcoin header `nTime` in unix seconds (`header.time`) when that field
    /// is a JSON integer in `0 … 2^32 − 1`. Absent, not an integer, or outside
    /// that range is not read. A fractional part or an exponent is not an
    /// integer. This is the chain header time. It is not filled from
    /// `timestamp_unix_ms`.
    pub header_time_s: Option<u64>,
    /// Bari's wall-clock observation, unix milliseconds
    /// (`header.timestamp_unix_ms`), when that field is an integer. Absent, or
    /// not an integer, is not read. This is not the miner clock.
    pub timestamp_unix_ms: Option<u64>,
}

pub fn parse_index_document(body: &str) -> Result<([u8; 32], u64), ClockError> {
    let beacon = parse_index_beacon(body)?;
    Ok((beacon.natural, beacon.height))
}

pub fn parse_index_beacon(body: &str) -> Result<IndexBeacon, ClockError> {
    let value = json_without_duplicate_members(body)?;
    let Value::Object(top) = value else {
        return Err(ClockError);
    };
    let Some(Value::Object(header)) = top.get("header") else {
        return Err(ClockError);
    };
    let Some(Value::String(hash)) = header.get("hash") else {
        return Err(ClockError);
    };
    let Some(Value::Number(height)) = header.get("height") else {
        return Err(ClockError);
    };
    if !height.is_u64() {
        return Err(ClockError);
    }
    let height = height.as_u64().ok_or(ClockError)?;
    let display = decode_hex_digits(hash).map_err(|_| ClockError)?;
    let header_time_s = header_time_seconds(header);
    let timestamp_unix_ms = integer_field(header, "timestamp_unix_ms");
    Ok(IndexBeacon {
        natural: natural_beacon(&display),
        height,
        header_time_s,
        timestamp_unix_ms,
    })
}

/// Section 9. `header.time` is a JSON integer in `0 … 2^32 − 1`.
/// `Number::is_u64` is false for a fractional part or an exponent.
fn header_time_seconds(header: &Map<String, Value>) -> Option<u64> {
    let secs = integer_field(header, "time")?;
    u32::try_from(secs).ok().map(u64::from)
}

fn integer_field(header: &Map<String, Value>, name: &str) -> Option<u64> {
    match header.get(name) {
        Some(Value::Number(n)) if n.is_u64() => n.as_u64(),
        _ => None,
    }
}

pub(crate) fn json_without_duplicate_members(body: &str) -> Result<Value, ClockError> {
    let mut deserializer = serde_json::Deserializer::from_str(body);
    let value = deserializer
        .deserialize_any(StrictVisitor)
        .map_err(|_| ClockError)?;
    deserializer.end().map_err(|_| ClockError)?;
    Ok(value)
}

#[derive(Copy, Clone)]
struct StrictSeed;

impl<'de> DeserializeSeed<'de> for StrictSeed {
    type Value = Value;

    fn deserialize<D: Deserializer<'de>>(self, deserializer: D) -> Result<Value, D::Error> {
        deserializer.deserialize_any(StrictVisitor)
    }
}

struct StrictVisitor;

impl<'de> Visitor<'de> for StrictVisitor {
    type Value = Value;

    fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
        formatter.write_str("a JSON value")
    }

    fn visit_bool<E: de::Error>(self, value: bool) -> Result<Value, E> {
        Ok(Value::Bool(value))
    }

    fn visit_i64<E: de::Error>(self, value: i64) -> Result<Value, E> {
        Ok(Value::Number(value.into()))
    }

    fn visit_u64<E: de::Error>(self, value: u64) -> Result<Value, E> {
        Ok(Value::Number(value.into()))
    }

    fn visit_f64<E: de::Error>(self, value: f64) -> Result<Value, E> {
        Number::from_f64(value)
            .map(Value::Number)
            .ok_or_else(|| de::Error::custom("not a JSON number"))
    }

    fn visit_str<E: de::Error>(self, value: &str) -> Result<Value, E> {
        Ok(Value::String(value.to_owned()))
    }

    fn visit_string<E: de::Error>(self, value: String) -> Result<Value, E> {
        Ok(Value::String(value))
    }

    fn visit_unit<E: de::Error>(self) -> Result<Value, E> {
        Ok(Value::Null)
    }

    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Value, A::Error> {
        let mut items = Vec::new();
        while let Some(item) = seq.next_element_seed(StrictSeed)? {
            items.push(item);
        }
        Ok(Value::Array(items))
    }

    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Value, A::Error> {
        let mut object = Map::new();
        while let Some(key) = map.next_key::<String>()? {
            if object.contains_key(&key) {
                return Err(de::Error::custom("duplicate member name"));
            }
            let value = map.next_value_seed(StrictSeed)?;
            object.insert(key, value);
        }
        Ok(Value::Object(object))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hash_digits() -> String {
        let mut s = String::new();
        for i in 0..32 {
            s.push_str(&format!("{i:02x}"));
        }
        s
    }

    #[test]
    fn reverses_display_order_and_keeps_height() {
        let body = format!(
            r#"{{"extra":1,"header":{{"hash":"{}","height":42,"bits":1}}}}"#,
            hash_digits()
        );
        let (natural, height) = parse_index_document(&body).unwrap();
        assert_eq!(height, 42);
        assert_eq!(natural[0], 31);
        assert_eq!(natural[31], 0);
        let upper = format!(
            r#"{{"header":{{"hash":"{}","height":18446744073709551615}}}}"#,
            hash_digits().to_ascii_uppercase()
        );
        assert_eq!(parse_index_document(&upper).unwrap().1, u64::MAX);
    }

    #[test]
    fn rejects_bad_bodies() {
        let hash = hash_digits();
        assert!(
            parse_index_document(&format!(r#"{{"header":{{"hash":"{hash}","height":1.5}}}}"#))
                .is_err()
        );
        assert!(
            parse_index_document(&format!(r#"{{"header":{{"hash":"0x{hash}","height":1}}}}"#))
                .is_err()
        );
        assert!(parse_index_document(&format!(
            r#"{{"header":{{"hash":"{}","height":1}}}}"#,
            &hash[..62]
        ))
        .is_err());
        assert!(parse_index_document(r#"{"hash":"aa","height":1}"#).is_err());
        assert!(
            parse_index_document(&format!(r#"{{"header":{{"hash":"{hash}","height":-1}}}}"#))
                .is_err()
        );
        assert!(parse_index_document(&format!(
            r#"{{"header":{{"hash":"{hash}","height":42.0}}}}"#
        ))
        .is_err());
        assert!(
            parse_index_document(&format!(r#"{{"header":{{"hash":"{hash}","height":1.0}}}}"#))
                .is_err()
        );
        assert!(
            parse_index_document(&format!(r#"{{"header":{{"hash":"{hash}","height":1e2}}}}"#))
                .is_err()
        );
        assert!(
            parse_index_document(&format!(r#"{{"header":{{"hash":"{hash}","height":2E1}}}}"#))
                .is_err()
        );
        let bom = format!("\u{feff}{{\"header\":{{\"hash\":\"{hash}\",\"height\":1}}}}");
        assert!(parse_index_document(&bom).is_err());
        assert!(parse_index_document(&format!(
            r#"{{"header":{{"hash":"{hash}","height":1}}}} {{"header":{{"hash":"{hash}","height":2}}}}"#
        ))
        .is_err());
    }

    #[test]
    fn rejects_duplicate_member_names() {
        let hash = hash_digits();
        assert!(parse_index_document(&format!(
            r#"{{"header":{{"hash":"{hash}","height":1}},"header":{{"hash":"{hash}","height":2}}}}"#
        ))
        .is_err());
        assert!(parse_index_document(&format!(
            r#"{{"header":{{"hash":"{hash}","hash":"{hash}","height":1}}}}"#
        ))
        .is_err());
        assert!(parse_index_document(&format!(
            r#"{{"header":{{"hash":"{hash}","height":1,"height":2}}}}"#
        ))
        .is_err());
        assert!(parse_index_document(&format!(
            r#"{{"header":{{"hash":"{hash}","height":1}},"extra":{{"a":1,"a":2}}}}"#
        ))
        .is_err());
    }

    #[test]
    fn accepts_a_header_object_with_other_members() {
        let body = r#"{"version":2,"network":"synthetic","header":{"hash":"028928e28e8d2c3f92485ddf18c81b576586218c7dc9c6f4925b9febf8479930","height":21943,"timestamp_unix_ms":1790437534158},"plant":null}"#;
        let (natural, height) = parse_index_document(body).unwrap();
        assert_eq!(height, 21943);
        assert_eq!(natural[0], 0x30);
        assert_eq!(natural[31], 0x02);
        let beacon = parse_index_beacon(body).unwrap();
        assert_eq!(beacon.header_time_s, None);
        assert_eq!(beacon.timestamp_unix_ms, Some(1790437534158));
        let hash = hash_digits();
        let bare = format!(r#"{{"header":{{"hash":"{hash}","height":7}}}}"#);
        let bare_beacon = parse_index_beacon(&bare).unwrap();
        assert_eq!(bare_beacon.header_time_s, None);
        assert_eq!(bare_beacon.timestamp_unix_ms, None);
        let text = format!(
            r#"{{"header":{{"hash":"{hash}","height":7,"timestamp_unix_ms":"1790437534158"}}}}"#
        );
        let text_beacon = parse_index_beacon(&text).unwrap();
        assert_eq!(text_beacon.header_time_s, None);
        assert_eq!(text_beacon.timestamp_unix_ms, None);
        assert_eq!(text_beacon.height, 7);
        let both = format!(
            r#"{{"header":{{"hash":"{hash}","height":7,"time":1700000050,"timestamp_unix_ms":1700000118000}}}}"#
        );
        let both_beacon = parse_index_beacon(&both).unwrap();
        assert_eq!(both_beacon.header_time_s, Some(1_700_000_050));
        assert_eq!(both_beacon.timestamp_unix_ms, Some(1_700_000_118_000));
        let observed_only = format!(
            r#"{{"header":{{"hash":"{hash}","height":7,"timestamp_unix_ms":1700000118000}}}}"#
        );
        let observed = parse_index_beacon(&observed_only).unwrap();
        assert_eq!(observed.header_time_s, None);
        assert_eq!(observed.timestamp_unix_ms, Some(1_700_000_118_000));
        let time_text =
            format!(r#"{{"header":{{"hash":"{hash}","height":7,"time":"1700000050"}}}}"#);
        assert_eq!(parse_index_beacon(&time_text).unwrap().header_time_s, None);
        let zero = format!(r#"{{"header":{{"hash":"{hash}","height":7,"time":0}}}}"#);
        assert_eq!(parse_index_beacon(&zero).unwrap().header_time_s, Some(0));
        let max = format!(r#"{{"header":{{"hash":"{hash}","height":7,"time":4294967295}}}}"#);
        assert_eq!(
            parse_index_beacon(&max).unwrap().header_time_s,
            Some(4_294_967_295)
        );
        let overflow = format!(r#"{{"header":{{"hash":"{hash}","height":7,"time":4294967296}}}}"#);
        assert_eq!(parse_index_beacon(&overflow).unwrap().header_time_s, None);
        let fraction = format!(r#"{{"header":{{"hash":"{hash}","height":7,"time":1.0}}}}"#);
        assert_eq!(parse_index_beacon(&fraction).unwrap().header_time_s, None);
        let exponent = format!(r#"{{"header":{{"hash":"{hash}","height":7,"time":1e2}}}}"#);
        assert_eq!(parse_index_beacon(&exponent).unwrap().header_time_s, None);
        let negative = format!(r#"{{"header":{{"hash":"{hash}","height":7,"time":-1}}}}"#);
        assert_eq!(parse_index_beacon(&negative).unwrap().header_time_s, None);
    }
}
