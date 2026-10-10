//! Section 14.3. A present path binds to the chain.
//!
//! Proxy success is the closed option A: `GET {index-url}/index/{block_height}`,
//! network `main` or `synthetic`, and any one recorded plant whose root and
//! locators equal the body. A recorded plant is an object in the top-level
//! `plants` array, and the top-level `plant` member when that member is an object.
//! That match stands in for zero value, the commitment script, and
//! `confirmations >= 0` on the proxy path only.
//!
//! Direct mainnet still evaluates those chain facts. `commitment_script` is the
//! byte construction in `services/bari/src/proof.rs`.

use serde_json::{Map, Value};

use crate::clock::json_without_duplicate_members;
use crate::hex::{decode_hex_digits, encode_hex32};
use crate::pathbody::PathLocators;

/// Bitcoin Core chain name for mainnet. `IndexSnapshot::to_stored` writes this
/// string into the portable-index `network` field.
pub const MAINNET_NETWORK: &str = "main";

/// Lab exception for the portable-index proxy only. Direct mainnet does not accept this name.
const PROXY_SYNTHETIC_NETWORK: &str = "synthetic";

/// `ANCHOR_VERSION` in `services/bari/src/proof.rs`.
const ANCHOR_VERSION: u8 = 0x02;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DirectFacts {
    pub mainnet: bool,
    pub transaction_in_block: bool,
    pub output_exists: bool,
    pub zero_value: bool,
    pub exact_script: bool,
    /// Header `confirmations`. Negative means the block is not in the best chain.
    pub confirmations: i64,
    pub authenticated_height: Option<u32>,
}

/// OP_RETURN payload `BARI` ‖ `0x02` ‖ `root`. Same bytes as Bari `commitment_script`.
pub fn commitment_script(root: &[u8; 32]) -> Vec<u8> {
    let mut script = Vec::with_capacity(39);
    script.extend_from_slice(&[0x6A, 0x25]);
    script.extend_from_slice(b"BARI");
    script.push(ANCHOR_VERSION);
    script.extend_from_slice(root);
    script
}

pub fn display_hex(natural: &[u8; 32]) -> String {
    let mut displayed = *natural;
    displayed.reverse();
    encode_hex32(&displayed)
}

fn natural_from_display(display: &str) -> Result<[u8; 32], ()> {
    let mut bytes = decode_hex_digits(display)?;
    bytes.reverse();
    Ok(bytes)
}

/// Proxy path. Locator and root equality stands in for value, script, and confirmations.
pub fn proxy_matches(document: &str, loc: &PathLocators) -> Result<(), ()> {
    let value = json_without_duplicate_members(document).map_err(|_| ())?;
    let Value::Object(top) = value else {
        return Err(());
    };
    let Some(Value::String(network)) = top.get("network") else {
        return Err(());
    };
    if network != MAINNET_NETWORK && network != PROXY_SYNTHETIC_NETWORK {
        return Err(());
    }
    let mut plants: Vec<&Map<String, Value>> = Vec::new();
    if let Some(Value::Array(items)) = top.get("plants") {
        for item in items {
            if let Value::Object(plant) = item {
                plants.push(plant);
            }
        }
    }
    if let Some(Value::Object(plant)) = top.get("plant") {
        plants.push(plant);
    }
    if plants.is_empty() {
        return Err(());
    }
    if plants.iter().any(|plant| plant_equals(plant, loc)) {
        Ok(())
    } else {
        Err(())
    }
}

fn plant_equals(plant: &Map<String, Value>, loc: &PathLocators) -> bool {
    let Ok(root) = natural_hex(plant, "root") else {
        return false;
    };
    let Ok(txid) = display_id(plant, "txid") else {
        return false;
    };
    let Ok(vout) = plant.get("vout").map(json_u32).unwrap_or(Err(())) else {
        return false;
    };
    let Ok(block_hash) = display_id(plant, "block_hash") else {
        return false;
    };
    let Ok(block_height) = plant.get("block_height").map(json_u32).unwrap_or(Err(())) else {
        return false;
    };
    root == loc.root
        && txid == loc.txid
        && vout == loc.vout
        && block_hash == loc.block_hash
        && block_height == loc.block_height
}

fn natural_hex(plant: &Map<String, Value>, key: &str) -> Result<[u8; 32], ()> {
    let Some(Value::String(spelling)) = plant.get(key) else {
        return Err(());
    };
    decode_hex_digits(spelling)
}

fn display_id(plant: &Map<String, Value>, key: &str) -> Result<[u8; 32], ()> {
    let Some(Value::String(spelling)) = plant.get(key) else {
        return Err(());
    };
    natural_from_display(spelling)
}

fn json_u32(value: &Value) -> Result<u32, ()> {
    let number = value.as_u64().ok_or(())?;
    u32::try_from(number).map_err(|_| ())
}

/// Direct mainnet. Steps 3–8, including a hard height match.
pub fn direct_matches(facts: &DirectFacts, loc: &PathLocators) -> Result<(), ()> {
    if !facts.mainnet
        || !facts.transaction_in_block
        || !facts.output_exists
        || !facts.zero_value
        || !facts.exact_script
        || facts.confirmations < 0
        || facts.authenticated_height != Some(loc.block_height)
    {
        return Err(());
    }
    Ok(())
}

/// Interpret Bitcoin Core JSON the way Bari `post_check` does, then keep the
/// authenticated height for the hard compare in `direct_matches`.
pub fn direct_facts(
    chain_name: &str,
    transaction: Option<&Value>,
    header: Option<&Value>,
    loc: &PathLocators,
) -> Result<DirectFacts, ()> {
    if chain_name != MAINNET_NETWORK {
        return Ok(DirectFacts {
            mainnet: false,
            transaction_in_block: false,
            output_exists: false,
            zero_value: false,
            exact_script: false,
            confirmations: -1,
            authenticated_height: None,
        });
    }
    let transaction = transaction.ok_or(())?;
    let header = header.ok_or(())?;
    let display_txid = display_hex(&loc.txid);
    let display_block = display_hex(&loc.block_hash);
    let transaction_in_block = transaction.get("txid").and_then(Value::as_str)
        == Some(display_txid.as_str())
        && transaction.get("blockhash").and_then(Value::as_str) == Some(display_block.as_str())
        && transaction.get("in_active_chain").and_then(Value::as_bool) == Some(true);
    let output = transaction
        .get("vout")
        .and_then(Value::as_array)
        .and_then(|outputs| {
            outputs
                .iter()
                .find(|output| output.get("n").and_then(Value::as_u64) == Some(u64::from(loc.vout)))
        });
    let output_exists = output.is_some();
    let zero_value = output
        .and_then(|output| output.get("value"))
        .and_then(Value::as_f64)
        == Some(0.0);
    let expected_script = encode_hex(&commitment_script(&loc.root));
    let exact_script = output
        .and_then(|output| output.get("scriptPubKey"))
        .and_then(|script| script.get("hex"))
        .and_then(Value::as_str)
        == Some(expected_script.as_str());
    let confirmations = header
        .get("confirmations")
        .and_then(Value::as_i64)
        .ok_or(())?;
    let authenticated_height = if confirmations >= 0 {
        let height = header.get("height").and_then(Value::as_u64).ok_or(())?;
        Some(u32::try_from(height).map_err(|_| ())?)
    } else {
        None
    };
    Ok(DirectFacts {
        mainnet: true,
        transaction_in_block,
        output_exists,
        zero_value,
        exact_script,
        confirmations,
        authenticated_height,
    })
}

fn encode_hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 0xf) as usize] as char);
    }
    out
}

#[cfg(test)]
pub(crate) fn proxy_document_matching(loc: &PathLocators) -> String {
    format!(
        r#"{{"version":2,"network":"main","header":{{"hash":"{hash}","height":1}},"plant":{{"root":"{root}","tree_size":99,"txid":"{txid}","vout":{vout},"block_hash":"{block}","block_height":{height}}}}}"#,
        hash = "ab".repeat(32),
        root = encode_hex32(&loc.root),
        txid = display_hex(&loc.txid),
        vout = loc.vout,
        block = display_hex(&loc.block_hash),
        height = loc.block_height,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn locators() -> PathLocators {
        let mut txid = [0x44; 32];
        txid[0] = 0x01;
        txid[31] = 0xfe;
        let mut block_hash = [0x55; 32];
        block_hash[0] = 0x10;
        block_hash[31] = 0x90;
        PathLocators {
            root: [0xab; 32],
            txid,
            vout: 2,
            block_hash,
            block_height: 123_456,
        }
    }

    fn honest_tx(loc: &PathLocators) -> Value {
        serde_json::json!({
            "txid": display_hex(&loc.txid),
            "blockhash": display_hex(&loc.block_hash),
            "in_active_chain": true,
            "vout": [{
                "n": loc.vout,
                "value": 0.0,
                "scriptPubKey": { "hex": encode_hex(&commitment_script(&loc.root)) }
            }]
        })
    }

    fn honest_header(loc: &PathLocators, confirmations: i64) -> Value {
        serde_json::json!({
            "confirmations": confirmations,
            "height": loc.block_height
        })
    }

    #[test]
    fn commitment_script_matches_bari_literal() {
        let root = [0xAB; 32];
        assert_eq!(
            encode_hex(&commitment_script(&root)),
            format!("6a254241524902{}", "ab".repeat(32))
        );
        assert_eq!(commitment_script(&root).len(), 39);
    }

    #[test]
    fn proxy_accepts_a_matching_mainnet_plant() {
        let loc = locators();
        let document = proxy_document_matching(&loc);
        assert!(document.contains("\"tree_size\":99"));
        proxy_matches(&document, &loc).unwrap();
        let without_tree_size = document.replace(",\"tree_size\":99", "");
        proxy_matches(&without_tree_size, &loc).unwrap();
        let synthetic = document.replace("\"network\":\"main\"", "\"network\":\"synthetic\"");
        proxy_matches(&synthetic, &loc).unwrap();
        let synthetic_bad_txid = synthetic.replace(
            &format!("\"txid\":\"{}\"", display_hex(&loc.txid)),
            &format!("\"txid\":\"{}\"", "01".repeat(32)),
        );
        assert!(proxy_matches(&synthetic_bad_txid, &loc).is_err());
    }

    #[test]
    fn proxy_denies_null_plant_mismatch_and_non_mainnet() {
        let loc = locators();
        let document = proxy_document_matching(&loc);
        assert!(proxy_matches(r#"{"network":"main","plant":null}"#, &loc).is_err());
        assert!(proxy_matches(r#"{"network":"main"}"#, &loc).is_err());
        assert!(proxy_matches("", &loc).is_err());
        for network in ["signet", "testnet4", "regtest", "testnet"] {
            let wrong = document.replace(
                "\"network\":\"main\"",
                &format!("\"network\":\"{network}\""),
            );
            assert!(proxy_matches(&wrong, &loc).is_err(), "{network}");
        }
        let wrong_txid = document.replace(
            &format!("\"txid\":\"{}\"", display_hex(&loc.txid)),
            &format!("\"txid\":\"{}\"", "01".repeat(32)),
        );
        assert!(proxy_matches(&wrong_txid, &loc).is_err());
        let wrong_vout = document.replace(
            &format!("\"vout\":{}", loc.vout),
            &format!("\"vout\":{}", loc.vout + 1),
        );
        assert!(proxy_matches(&wrong_vout, &loc).is_err());
        let wrong_block = document.replace(
            &format!("\"block_hash\":\"{}\"", display_hex(&loc.block_hash)),
            &format!("\"block_hash\":\"{}\"", "02".repeat(32)),
        );
        assert!(proxy_matches(&wrong_block, &loc).is_err());
        let wrong_height = document.replace(
            &format!("\"block_height\":{}", loc.block_height),
            &format!("\"block_height\":{}", loc.block_height + 1),
        );
        assert!(proxy_matches(&wrong_height, &loc).is_err());
        let mut wrong_root_loc = loc;
        wrong_root_loc.root[0] ^= 0xff;
        assert!(proxy_matches(&document, &wrong_root_loc).is_err());
    }

    #[test]
    fn proxy_matches_any_object_in_the_plants_array() {
        let loc = locators();
        let right = format!(
            r#"{{"root":"{root}","tree_size":7,"txid":"{txid}","vout":{vout},"block_hash":"{block}","block_height":{height}}}"#,
            root = encode_hex32(&loc.root),
            txid = display_hex(&loc.txid),
            vout = loc.vout,
            block = display_hex(&loc.block_hash),
            height = loc.block_height,
        );
        let wrong = right.replace(
            &format!("\"txid\":\"{}\"", display_hex(&loc.txid)),
            &format!("\"txid\":\"{}\"", "01".repeat(32)),
        );
        let only_wrong = format!(r#"{{"network":"main","plants":[{wrong}]}}"#);
        assert!(proxy_matches(&only_wrong, &loc).is_err());
        let second = format!(r#"{{"network":"main","plants":[{wrong},{right}]}}"#);
        proxy_matches(&second, &loc).unwrap();
        let nulls = format!(r#"{{"network":"main","plant":null,"plants":[null,{right}]}}"#);
        proxy_matches(&nulls, &loc).unwrap();
        let empty = r#"{"network":"main","plant":null,"plants":[]}"#;
        assert!(proxy_matches(empty, &loc).is_err());
        let plant_wrong_array_right =
            format!(r#"{{"network":"synthetic","plant":{wrong},"plants":[{right}]}}"#);
        proxy_matches(&plant_wrong_array_right, &loc).unwrap();
    }

    #[test]
    fn proxy_root_is_natural_and_ids_are_display_order() {
        let loc = locators();
        assert_ne!(loc.txid[0], loc.txid[31]);
        let document = proxy_document_matching(&loc);
        let value: Value = serde_json::from_str(&document).unwrap();
        let plant = value.get("plant").unwrap();
        assert_eq!(plant["root"].as_str().unwrap(), encode_hex32(&loc.root));
        assert_eq!(plant["txid"].as_str().unwrap(), display_hex(&loc.txid));
        assert_ne!(plant["txid"].as_str().unwrap(), encode_hex32(&loc.txid));
        assert!(proxy_matches(&document, &loc).is_ok());
    }

    #[test]
    fn direct_accepts_best_chain_including_zero_confirmations() {
        let loc = locators();
        for confirmations in [0, 1, 6] {
            let facts = direct_facts(
                MAINNET_NETWORK,
                Some(&honest_tx(&loc)),
                Some(&honest_header(&loc, confirmations)),
                &loc,
            )
            .unwrap();
            direct_matches(&facts, &loc).unwrap();
        }
    }

    #[test]
    fn direct_denies_script_value_root_height_and_non_mainnet() {
        let loc = locators();
        let mut bad_value = honest_tx(&loc);
        bad_value["vout"][0]["value"] = serde_json::json!(0.0001);
        let facts = direct_facts(
            MAINNET_NETWORK,
            Some(&bad_value),
            Some(&honest_header(&loc, 2)),
            &loc,
        )
        .unwrap();
        assert!(direct_matches(&facts, &loc).is_err());

        let mut bad_script = honest_tx(&loc);
        let other = commitment_script(&[0x22; 32]);
        bad_script["vout"][0]["scriptPubKey"]["hex"] = serde_json::json!(encode_hex(&other));
        let facts = direct_facts(
            MAINNET_NETWORK,
            Some(&bad_script),
            Some(&honest_header(&loc, 2)),
            &loc,
        )
        .unwrap();
        assert!(!facts.exact_script);
        assert!(direct_matches(&facts, &loc).is_err());

        let mut missing_output = honest_tx(&loc);
        missing_output["vout"][0]["n"] = serde_json::json!(loc.vout + 3);
        let facts = direct_facts(
            MAINNET_NETWORK,
            Some(&missing_output),
            Some(&honest_header(&loc, 2)),
            &loc,
        )
        .unwrap();
        assert!(direct_matches(&facts, &loc).is_err());

        let mut invented_txid = honest_tx(&loc);
        invented_txid["txid"] = serde_json::json!("11".repeat(32));
        let facts = direct_facts(
            MAINNET_NETWORK,
            Some(&invented_txid),
            Some(&honest_header(&loc, 2)),
            &loc,
        )
        .unwrap();
        assert!(!facts.transaction_in_block);
        assert!(direct_matches(&facts, &loc).is_err());

        let facts = direct_facts(
            MAINNET_NETWORK,
            Some(&honest_tx(&loc)),
            Some(&honest_header(&loc, -1)),
            &loc,
        )
        .unwrap();
        assert!(direct_matches(&facts, &loc).is_err());

        let mut wrong_height = honest_header(&loc, 3);
        wrong_height["height"] = serde_json::json!(loc.block_height + 1);
        let facts = direct_facts(
            MAINNET_NETWORK,
            Some(&honest_tx(&loc)),
            Some(&wrong_height),
            &loc,
        )
        .unwrap();
        assert_eq!(facts.authenticated_height, Some(loc.block_height + 1));
        assert!(facts.transaction_in_block && facts.exact_script && facts.zero_value);
        assert!(direct_matches(&facts, &loc).is_err());

        for network in ["signet", "testnet4", "regtest", "synthetic"] {
            let facts = direct_facts(network, None, None, &loc).unwrap();
            assert!(!facts.mainnet);
            assert!(direct_matches(&facts, &loc).is_err());
        }
    }
}
