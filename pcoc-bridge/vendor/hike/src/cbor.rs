//! Definite-length CBOR (RFC 8949) for one hike object.
//!
//! Produce uses the core deterministic encoding: shortest unsigned integers
//! and map keys in encoded-key byte order. Verification accepts any
//! definite-length encoding of the same values, including non-shortest
//! unsigned integers and any key order.

use std::collections::HashSet;

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum Cbor {
    Uint(u64),
    Bytes(Vec<u8>),
    Text(String),
    Array(Vec<Cbor>),
    Map(Vec<(String, Cbor)>),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Fail(pub &'static str);

struct Cur<'a> {
    data: &'a [u8],
    i: usize,
}

impl<'a> Cur<'a> {
    fn rest(&self) -> &'a [u8] {
        &self.data[self.i..]
    }

    fn byte(&mut self) -> Result<u8, Fail> {
        let b = *self.rest().first().ok_or(Fail("truncated"))?;
        self.i += 1;
        Ok(b)
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8], Fail> {
        if self.rest().len() < n {
            return Err(Fail("truncated"));
        }
        let start = self.i;
        self.i += n;
        Ok(&self.data[start..self.i])
    }

    fn read_len(&mut self, ai: u8) -> Result<u64, Fail> {
        match ai {
            0..=23 => Ok(u64::from(ai)),
            24 => Ok(u64::from(self.byte()?)),
            25 => {
                let b = self.take(2)?;
                Ok(u64::from(u16::from_be_bytes(b.try_into().unwrap())))
            }
            26 => {
                let b = self.take(4)?;
                Ok(u64::from(u32::from_be_bytes(b.try_into().unwrap())))
            }
            27 => {
                let b = self.take(8)?;
                Ok(u64::from_be_bytes(b.try_into().unwrap()))
            }
            _ => Err(Fail("unsupported additional info")),
        }
    }

    fn item(&mut self) -> Result<Cbor, Fail> {
        let head = self.byte()?;
        let major = head >> 5;
        let ai = head & 0x1f;
        match major {
            0 => Ok(Cbor::Uint(self.read_len(ai)?)),
            1 => Err(Fail("negative integer")),
            2 => self.bytes_item(ai),
            3 => self.text_item(ai),
            4 => self.array_item(ai),
            5 => self.map_item(ai),
            6 => Err(Fail("tag")),
            _ => Err(Fail("simple or float")),
        }
    }

    fn count(&mut self, ai: u8) -> Result<usize, Fail> {
        if ai == 31 {
            return Err(Fail("indefinite length"));
        }
        let n = self.read_len(ai)?;
        let n = usize::try_from(n).map_err(|_| Fail("length"))?;
        // Each remaining element occupies at least one byte.
        if n > self.rest().len() {
            return Err(Fail("length exceeds input"));
        }
        Ok(n)
    }

    fn bytes_item(&mut self, ai: u8) -> Result<Cbor, Fail> {
        let n = self.count(ai)?;
        Ok(Cbor::Bytes(self.take(n)?.to_vec()))
    }

    fn text_item(&mut self, ai: u8) -> Result<Cbor, Fail> {
        let n = self.count(ai)?;
        let bytes = self.take(n)?;
        let text = std::str::from_utf8(bytes).map_err(|_| Fail("text is not utf-8"))?;
        Ok(Cbor::Text(text.to_owned()))
    }

    fn array_item(&mut self, ai: u8) -> Result<Cbor, Fail> {
        let n = self.count(ai)?;
        let mut items = Vec::with_capacity(n);
        for _ in 0..n {
            items.push(self.item()?);
        }
        Ok(Cbor::Array(items))
    }

    fn map_item(&mut self, ai: u8) -> Result<Cbor, Fail> {
        let n = self.count(ai)?;
        let mut pairs = Vec::with_capacity(n);
        let mut seen = HashSet::with_capacity(n);
        for _ in 0..n {
            let key = self.item()?;
            let Cbor::Text(key) = key else {
                return Err(Fail("map key is not text"));
            };
            if !seen.insert(key.clone()) {
                return Err(Fail("duplicate key"));
            }
            pairs.push((key, self.item()?));
        }
        Ok(Cbor::Map(pairs))
    }
}

/// One CBOR data item, plus whether any byte follows it.
pub(crate) fn decode_head(data: &[u8]) -> Result<(Cbor, bool), Fail> {
    if data.is_empty() {
        return Err(Fail("empty"));
    }
    let mut cur = Cur { data, i: 0 };
    let value = cur.item()?;
    Ok((value, cur.i != data.len()))
}

#[cfg(test)]
pub(crate) fn decode_one(data: &[u8]) -> Result<Cbor, Fail> {
    let (value, trailing) = decode_head(data)?;
    if trailing {
        return Err(Fail("trailing bytes"));
    }
    Ok(value)
}

#[derive(Clone, Copy)]
enum UintWidth {
    Shortest,
    #[cfg(test)]
    Eight,
}

fn put_uint(out: &mut Vec<u8>, major: u8, n: u64, width: UintWidth) {
    match width {
        #[cfg(test)]
        UintWidth::Eight => {
            out.push((major << 5) | 27);
            out.extend_from_slice(&n.to_be_bytes());
        }
        UintWidth::Shortest => {
            if n < 24 {
                out.push((major << 5) | n as u8);
            } else if n < 256 {
                out.push((major << 5) | 24);
                out.push(n as u8);
            } else if n < 65536 {
                out.push((major << 5) | 25);
                out.extend_from_slice(&(n as u16).to_be_bytes());
            } else if n < 0x1_0000_0000 {
                out.push((major << 5) | 26);
                out.extend_from_slice(&(n as u32).to_be_bytes());
            } else {
                out.push((major << 5) | 27);
                out.extend_from_slice(&n.to_be_bytes());
            }
        }
    }
}

fn encode_into(out: &mut Vec<u8>, value: &Cbor, width: UintWidth) {
    match value {
        Cbor::Uint(n) => put_uint(out, 0, *n, width),
        Cbor::Bytes(b) => {
            put_uint(out, 2, b.len() as u64, width);
            out.extend_from_slice(b);
        }
        Cbor::Text(s) => {
            put_uint(out, 3, s.len() as u64, width);
            out.extend_from_slice(s.as_bytes());
        }
        Cbor::Array(items) => {
            put_uint(out, 4, items.len() as u64, width);
            for item in items {
                encode_into(out, item, width);
            }
        }
        Cbor::Map(pairs) => {
            put_uint(out, 5, pairs.len() as u64, width);
            for (key, item) in pairs {
                put_uint(out, 3, key.len() as u64, width);
                out.extend_from_slice(key.as_bytes());
                encode_into(out, item, width);
            }
        }
    }
}

pub(crate) fn encode(value: &Cbor) -> Vec<u8> {
    let mut out = Vec::new();
    encode_into(&mut out, value, UintWidth::Shortest);
    out
}

#[cfg(test)]
pub(crate) fn encode_wide_uints(value: &Cbor) -> Vec<u8> {
    let mut out = Vec::new();
    encode_into(&mut out, value, UintWidth::Eight);
    out
}
