//! Test-plane host checks. The tip sequence is a read. The plant is a write.
//! The test-plane policy judgment lives in the `hike-net` crate.

/// Live Bari on the public zone. The test profile must not dial it.
pub const LIVE_BARI_HOST: &str = "bari.groundinglab.io";
/// Live index on the public zone. The test profile must not dial it.
pub const LIVE_INDEX_HOST: &str = "index.groundinglab.io";
/// Public zone. A test profile must not dial the apex or any host under it.
const LIVE_ZONE: &str = "groundinglab.io";


pub fn live_plane_host(url: &str) -> bool {
    let Some(host) = url_hostname(url) else {
        return false;
    };
    live_zone_host(host)
}

/// `groundinglab.io` and every `*.groundinglab.io` name. Those are the live hosts.
fn live_zone_host(host: &str) -> bool {
    if host.eq_ignore_ascii_case(LIVE_ZONE) {
        return true;
    }
    let bytes = host.as_bytes();
    let suffix = b".groundinglab.io";
    bytes.len() > suffix.len() && bytes[bytes.len() - suffix.len()..].eq_ignore_ascii_case(suffix)
}

/// `https://{host}` with optional port 443. Scheme is case-insensitive.
pub fn is_https_live_origin(url: &str, host: &str) -> bool {
    let Some((scheme, rest)) = url.split_once("://") else {
        return false;
    };
    if !scheme.eq_ignore_ascii_case("https") {
        return false;
    }
    let host_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    if host_end != rest.len() {
        return false;
    }
    let hostport = &rest[..host_end];
    if hostport.contains('@') || hostport.is_empty() {
        return false;
    }
    let hostname = if let Some((name, port)) = hostport.rsplit_once(':') {
        if port != "443" {
            return false;
        }
        name
    } else {
        hostport
    };
    hostname.eq_ignore_ascii_case(host)
}

fn url_hostname(url: &str) -> Option<&str> {
    let (_, rest) = url.split_once("://")?;
    let host_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let hostport = &rest[..host_end];
    let hostname = hostport
        .rsplit_once('@')
        .map(|(_, host)| host)
        .unwrap_or(hostport);
    let hostname = if let Some((name, _)) = hostname.rsplit_once(':') {
        if name.starts_with('[') {
            hostname
        } else {
            name
        }
    } else {
        hostname
    };
    if hostname.is_empty() {
        None
    } else {
        Some(hostname)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn live_hosts_are_recognized_without_filling_them() {
        assert!(live_plane_host("http://bari.groundinglab.io:443"));
        assert!(live_plane_host("HTTPS://index.groundinglab.io"));
        assert!(live_plane_host(
            "https://admission.groundinglab.io/v1/sign-in"
        ));
        assert!(live_plane_host("https://WWW.groundinglab.io"));
        assert!(live_plane_host("https://groundinglab.io/sign-in/"));
        assert!(!live_plane_host("https://relay.example"));
        assert!(!live_plane_host("http://127.0.0.1:38080"));
        assert!(is_https_live_origin("HTTPS://bari.groundinglab.io", LIVE_BARI_HOST));
        assert!(is_https_live_origin(
            "https://index.groundinglab.io:443",
            LIVE_INDEX_HOST
        ));
        assert!(!is_https_live_origin("http://bari.groundinglab.io", LIVE_BARI_HOST));
        assert!(!is_https_live_origin(
            "https://index.groundinglab.io/index",
            LIVE_INDEX_HOST
        ));
    }
}
