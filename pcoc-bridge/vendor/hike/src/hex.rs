/// Optional leading `0x` and exactly 64 hexadecimal digits. No spaces.
pub fn decode_hex32(spelling: &str) -> Result<[u8; 32], ()> {
    let spelling = spelling.strip_prefix("0x").unwrap_or(spelling);
    decode_hex_digits(spelling)
}

/// 64 lowercase hexadecimal digits. Byte 0 is the first two digits.
pub fn encode_hex32(bytes: &[u8; 32]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(64);
    for byte in bytes {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 0xf) as usize] as char);
    }
    out
}

/// Exactly 64 hexadecimal digits. A `0x` prefix is not accepted.
pub fn decode_hex_digits(spelling: &str) -> Result<[u8; 32], ()> {
    if spelling.len() != 64 {
        return Err(());
    }
    let mut out = [0u8; 32];
    for i in 0..32 {
        let byte = u8::from_str_radix(&spelling[i * 2..i * 2 + 2], 16).map_err(|_| ())?;
        out[i] = byte;
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prefix_and_case() {
        let digits = "aa".repeat(31) + "BB";
        let with = format!("0x{digits}");
        assert_eq!(decode_hex32(&with).unwrap(), decode_hex32(&digits).unwrap());
        assert_eq!(decode_hex32(&digits).unwrap()[31], 0xBB);
        assert!(decode_hex32(&"aa".repeat(31)).is_err());
        assert!(
            decode_hex32("0Xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .is_err()
        );
        assert!(decode_hex_digits(&with).is_err());
    }
}
