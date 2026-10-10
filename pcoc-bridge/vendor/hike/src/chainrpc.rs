//! Direct Bitcoin mainnet read for §14.6. Node JSON-RPC, not a Bari GET.

use std::io::Read;

use serde_json::{json, Value};

use crate::http::http_agent;
use crate::pathbody::PathLocators;
use crate::pathchain::{direct_facts, display_hex, DirectFacts, MAINNET_NETWORK};

pub struct ChainRpc {
    url: String,
    authorization: String,
    agent: ureq::Agent,
}

impl ChainRpc {
    pub fn new(url: String, user: String, password: String) -> Self {
        let token = base64_std(format!("{user}:{password}").as_bytes());
        Self {
            url,
            authorization: format!("Basic {token}"),
            agent: http_agent(),
        }
    }

    pub fn facts(&self, loc: &PathLocators) -> Result<DirectFacts, String> {
        let info = self.rpc("getblockchaininfo", json!([]))?;
        let chain = info
            .get("chain")
            .and_then(Value::as_str)
            .ok_or_else(|| "getblockchaininfo omitted chain".to_owned())?;
        if chain != MAINNET_NETWORK {
            return direct_facts(chain, None, None, loc)
                .map_err(|_| "chain facts rejected".to_owned());
        }
        let transaction = self.rpc(
            "getrawtransaction",
            json!([display_hex(&loc.txid), true, display_hex(&loc.block_hash)]),
        )?;
        let header = self.rpc(
            "getblockheader",
            json!([display_hex(&loc.block_hash), true]),
        )?;
        direct_facts(chain, Some(&transaction), Some(&header), loc)
            .map_err(|_| "chain facts incomplete".to_owned())
    }

    fn rpc(&self, method: &str, params: Value) -> Result<Value, String> {
        let payload = serde_json::to_vec(&json!({
            "jsonrpc": "1.0",
            "id": "hike-path-chain-bind",
            "method": method,
            "params": params,
        }))
        .map_err(|_| format!("{method} request"))?;
        let response = self
            .agent
            .post(&self.url)
            .set("Authorization", &self.authorization)
            .set("Content-Type", "application/json")
            .send_bytes(&payload);
        let response = match response {
            Ok(response) => response,
            Err(ureq::Error::Status(_, response)) => response,
            Err(err) => return Err(format!("{method} transport error: {err}")),
        };
        let status = response.status();
        let bytes = {
            let mut buf = Vec::new();
            response
                .into_reader()
                .read_to_end(&mut buf)
                .map_err(|err| format!("{method} body: {err}"))?;
            buf
        };
        let envelope: Value = serde_json::from_slice(&bytes)
            .map_err(|_| format!("{method} invalid JSON ({status})"))?;
        if let Some(error) = envelope.get("error").filter(|value| !value.is_null()) {
            return Err(format!("{method} RPC error: {error}"));
        }
        envelope
            .get("result")
            .cloned()
            .ok_or_else(|| format!("{method} returned no result"))
    }
}

fn base64_std(bytes: &[u8]) -> String {
    const TABLE: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::new();
    let mut index = 0;
    while index + 3 <= bytes.len() {
        let n = ((bytes[index] as u32) << 16)
            | ((bytes[index + 1] as u32) << 8)
            | (bytes[index + 2] as u32);
        out.push(TABLE[((n >> 18) & 63) as usize] as char);
        out.push(TABLE[((n >> 12) & 63) as usize] as char);
        out.push(TABLE[((n >> 6) & 63) as usize] as char);
        out.push(TABLE[(n & 63) as usize] as char);
        index += 3;
    }
    let rest = bytes.len() - index;
    if rest == 1 {
        let n = (bytes[index] as u32) << 16;
        out.push(TABLE[((n >> 18) & 63) as usize] as char);
        out.push(TABLE[((n >> 12) & 63) as usize] as char);
        out.push('=');
        out.push('=');
    } else if rest == 2 {
        let n = ((bytes[index] as u32) << 16) | ((bytes[index + 1] as u32) << 8);
        out.push(TABLE[((n >> 18) & 63) as usize] as char);
        out.push(TABLE[((n >> 12) & 63) as usize] as char);
        out.push(TABLE[((n >> 6) & 63) as usize] as char);
        out.push('=');
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pathbody::{encode_single_leaf, path_locators};
    use crate::pathchain::{commitment_script, direct_matches, display_hex};
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::thread;

    fn encode_hex(bytes: &[u8]) -> String {
        const HEX: &[u8; 16] = b"0123456789abcdef";
        let mut out = String::new();
        for byte in bytes {
            out.push(HEX[(byte >> 4) as usize] as char);
            out.push(HEX[(byte & 0xf) as usize] as char);
        }
        out
    }

    fn read_http(stream: &mut std::net::TcpStream) -> String {
        let _ = stream.set_read_timeout(Some(std::time::Duration::from_secs(2)));
        let mut buf = Vec::new();
        let mut tmp = [0u8; 4096];
        let header_end = loop {
            let n = stream.read(&mut tmp).unwrap_or(0);
            if n == 0 {
                return String::new();
            }
            buf.extend_from_slice(&tmp[..n]);
            if let Some(pos) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
                break pos + 4;
            }
        };
        let head = String::from_utf8_lossy(&buf[..header_end]).to_string();
        let length = head
            .lines()
            .find_map(|line| {
                line.split_once(':')
                    .filter(|(name, _)| name.eq_ignore_ascii_case("content-length"))
                    .and_then(|(_, value)| value.trim().parse::<usize>().ok())
            })
            .unwrap_or(0);
        while buf.len() < header_end + length {
            let n = stream.read(&mut tmp).unwrap_or(0);
            if n == 0 {
                break;
            }
            buf.extend_from_slice(&tmp[..n]);
        }
        String::from_utf8_lossy(&buf).into_owned()
    }

    fn reply(stream: &mut std::net::TcpStream, body: &[u8]) {
        let response = format!(
            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            body.len()
        );
        let _ = stream.write_all(response.as_bytes());
        let _ = stream.write_all(body);
    }

    fn serve<F>(calls: usize, respond: F) -> String
    where
        F: Fn(&str) -> Vec<u8> + Send + 'static,
    {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        thread::spawn(move || {
            for _ in 0..calls {
                let Ok((mut stream, _)) = listener.accept() else {
                    break;
                };
                let request = read_http(&mut stream);
                reply(&mut stream, &respond(&request));
            }
        });
        format!("http://{addr}")
    }

    #[test]
    fn rpc_honest_mainnet_accepts() {
        let loc = path_locators(&encode_single_leaf(&[9u8; 32])).unwrap();
        let script = encode_hex(&commitment_script(&loc.root));
        let txid = display_hex(&loc.txid);
        let block = display_hex(&loc.block_hash);
        let height = loc.block_height;
        let vout = loc.vout;
        let url = serve(3, move |request| {
            if request.contains("getblockchaininfo") {
                return br#"{"result":{"chain":"main"},"error":null}"#.to_vec();
            }
            if request.contains("getrawtransaction") {
                let body = format!(
                    r#"{{"result":{{"txid":"{txid}","blockhash":"{block}","in_active_chain":true,"vout":[{{"n":{vout},"value":0,"scriptPubKey":{{"hex":"{script}"}}}}]}},"error":null}}"#
                );
                return body.into_bytes();
            }
            if request.contains("getblockheader") {
                let body =
                    format!(r#"{{"result":{{"confirmations":1,"height":{height}}},"error":null}}"#);
                return body.into_bytes();
            }
            br#"{"result":null,"error":{"code":-1,"message":"unknown"}}"#.to_vec()
        });
        let rpc = ChainRpc::new(url, "user".to_owned(), "secret".to_owned());
        let facts = rpc.facts(&loc).unwrap();
        direct_matches(&facts, &loc).unwrap();
    }

    #[test]
    fn rpc_signet_fails_closed_without_a_transaction_read() {
        let loc = path_locators(&encode_single_leaf(&[9u8; 32])).unwrap();
        let url = serve(1, |_| {
            br#"{"result":{"chain":"signet"},"error":null}"#.to_vec()
        });
        let rpc = ChainRpc::new(url, "user".to_owned(), "secret".to_owned());
        let facts = rpc.facts(&loc).unwrap();
        assert!(direct_matches(&facts, &loc).is_err());
    }
}
