//! Flag parsing for the two programs. Usage errors carry no report.

use crate::consts::{DEFAULT_BARI_URL, DEFAULT_INDEX_URL};
use crate::gossip::GossipNetwork;
use crate::hex::decode_hex32;
use crate::plane::{
    is_https_live_origin, live_plane_host, LIVE_BARI_HOST, LIVE_INDEX_HOST,
};
use crate::subscribe::VerifyLog;

#[derive(Debug)]
pub struct UsageError;

/// Where produce reads the stage clock.
///
/// Both planes use `Gossip`. Test-profile Observe is the synth tip sequence.
/// `GossipNetwork::Regtest` on that profile is header wire magic only, not a
/// Bitcoin Core tip clock or plant. The explicit live profile is Bitcoin
/// selected-chain gossip (mainnet). `Http` is not selected by any profile
/// and is not an HTTP tip fallback.
/// Where a gossip clock finds its first peer.
///
/// `Named` is one `host:port`. The command line wins over
/// `PCOC_LIVE_GOSSIP_PEER`. `MainnetDnsSeeds` is the live default when neither
/// is set. Test and slow-lan never use it.
#[derive(Debug, PartialEq, Eq)]
pub enum GossipDial {
    Named(String),
    MainnetDnsSeeds,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ProduceClock {
    Http {
        tip_url: String,
    },
    Gossip {
        dial: GossipDial,
        network: GossipNetwork,
    },
}

/// A plane is one profile. `SlowLan` is the lab card, not a plane.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PlaneProfile {
    Test,
    Live,
    SlowLan,
}

/// Admit policy travels with the plane. The lab card has none.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AdmitPolicy {
    Test,
    Live,
}

/// Size profile on the verify record. Section 6, or the SPEC §16.1 small set.
///
/// `ConformanceSmall` is a verifier input. It is not a produce profile.
/// [`MeasureProfile::sizes`] is the four-integer hook. There is no other switch.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MeasureProfile {
    Production,
    ConformanceSmall,
}

impl MeasureProfile {
    /// Section 6, or SPEC §16.1 `conformance-small`. Produce does not call this.
    pub fn sizes(self) -> crate::consts::Sizes {
        match self {
            MeasureProfile::Production => crate::consts::Sizes::production(),
            MeasureProfile::ConformanceSmall => crate::consts::Sizes::conformance_small(),
        }
    }

    pub fn record_name(self) -> &'static str {
        match self {
            MeasureProfile::Production => "production",
            MeasureProfile::ConformanceSmall => "conformance-small",
        }
    }
}

pub struct ProduceArgs {
    pub public_key: [u8; 32],
    pub output: String,
    pub profile: PlaneProfile,
    pub policy: Option<AdmitPolicy>,
    pub clock: ProduceClock,
    pub bari_url: String,
    pub report: Option<String>,
}

pub struct VerifyArgs {
    pub input: String,
    pub profile: PlaneProfile,
    pub policy: AdmitPolicy,
    pub index_url: String,
    pub audit_seed: Option<[u8; 32]>,
    pub report: Option<String>,
    /// Subscriber for the section 14 record. `Quiet` is the default.
    pub log: VerifyLog,
    /// When set, §14.6 uses direct Bitcoin mainnet instead of the index proxy.
    /// The test profile rejects this. It is Bitcoin evidence, not the test gate.
    pub chain_rpc: Option<String>,
    /// `production` or `conformance-small` on the measurement record.
    pub measure: MeasureProfile,
}

enum FlagForm<'a> {
    Bare,
    Equals(&'a str),
}

fn match_flag<'a>(arg: &'a str, name: &str) -> Option<FlagForm<'a>> {
    if arg == name {
        Some(FlagForm::Bare)
    } else if let Some(rest) = arg.strip_prefix(name) {
        rest.strip_prefix('=').map(FlagForm::Equals)
    } else {
        None
    }
}

fn flag_value<'a>(
    form: FlagForm<'a>,
    args: &'a [String],
    index: &mut usize,
) -> Result<&'a str, UsageError> {
    match form {
        FlagForm::Equals(value) => {
            if value.is_empty() {
                Err(UsageError)
            } else {
                Ok(value)
            }
        }
        FlagForm::Bare => {
            let next = args.get(*index + 1).ok_or(UsageError)?;
            if next.is_empty() || next.starts_with('-') {
                return Err(UsageError);
            }
            *index += 1;
            Ok(next.as_str())
        }
    }
}

pub fn absolute_http_without_trailing_slash(url: &str) -> bool {
    if url.is_empty() || url.ends_with('/') {
        return false;
    }
    let Some((scheme, rest)) = url.split_once("://") else {
        return false;
    };
    if !scheme.eq_ignore_ascii_case("https") && !scheme.eq_ignore_ascii_case("http") {
        return false;
    }
    let host_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let host = &rest[..host_end];
    !host.is_empty() && !host.contains(' ')
}

fn take_url(form: FlagForm, args: &[String], index: &mut usize) -> Result<String, UsageError> {
    let value = flag_value(form, args, index)?;
    if !absolute_http_without_trailing_slash(value) {
        return Err(UsageError);
    }
    Ok(value.to_owned())
}

fn take_token(form: FlagForm, args: &[String], index: &mut usize) -> Result<String, UsageError> {
    Ok(flag_value(form, args, index)?.to_owned())
}

/// `host:port` or `[ipv6]:port`. Port 0 is rejected. DNS is not resolved here.
fn gossip_peer(value: &str) -> Result<String, UsageError> {
    let port = if let Some(rest) = value.strip_prefix('[') {
        let (host, port) = rest.rsplit_once("]:").ok_or(UsageError)?;
        if host.is_empty() || host.contains(']') {
            return Err(UsageError);
        }
        port
    } else {
        let (host, port) = value.rsplit_once(':').ok_or(UsageError)?;
        if host.is_empty() || host.contains(':') || host.contains(' ') {
            return Err(UsageError);
        }
        port
    };
    let parsed: u16 = port.parse().map_err(|_| UsageError)?;
    if parsed == 0 || port.starts_with('+') {
        return Err(UsageError);
    }
    Ok(value.to_owned())
}

/// When `--profile live` omits `--gossip-peer`, append `env_peer`.
///
/// An empty value does not append. A peer already on the command line wins.
/// This does not fill an HTTP tip URL and does not invent a host. An unset
/// value leaves the peer unnamed. Parse then starts from Bitcoin Core's
/// mainnet DNS seeds.
pub fn apply_live_gossip_env(mut args: Vec<String>, env_peer: Option<&str>) -> Vec<String> {
    if !profile_is_live(&args) || has_flag(&args, "--gossip-peer") {
        return args;
    }
    let Some(peer) = nonempty(env_peer) else {
        return args;
    };
    args.push("--gossip-peer".to_owned());
    args.push(peer);
    args
}

/// When the test profile omits `--gossip-peer` or `--bari-url`, append the env values.
///
/// Omitted `--profile` is the test profile. `live` and `slow-lan` are left alone.
/// An empty value does not append. A flag already on the command line wins.
/// This does not invent a hostname and does not fill a live origin.
pub fn apply_test_produce_env(
    mut args: Vec<String>,
    env_peer: Option<&str>,
    env_bari: Option<&str>,
) -> Vec<String> {
    if !profile_is_test(&args) {
        return args;
    }
    if !has_flag(&args, "--gossip-peer") {
        if let Some(peer) = nonempty(env_peer) {
            args.push("--gossip-peer".to_owned());
            args.push(peer);
        }
    }
    if !has_flag(&args, "--bari-url") {
        if let Some(bari) = nonempty(env_bari) {
            args.push("--bari-url".to_owned());
            args.push(bari);
        }
    }
    args
}

/// When the test profile omits `--index-url`, append `env_index`.
///
/// Omitted `--profile` is the test profile. `live` is left alone.
/// An empty value does not append. A flag already on the command line wins.
/// This does not invent a hostname and does not fill `https://index.groundinglab.io`.
pub fn apply_test_index_env(mut args: Vec<String>, env_index: Option<&str>) -> Vec<String> {
    if !profile_is_test(&args) || has_flag(&args, "--index-url") {
        return args;
    }
    let Some(index) = nonempty(env_index) else {
        return args;
    };
    args.push("--index-url".to_owned());
    args.push(index);
    args
}

fn nonempty(value: Option<&str>) -> Option<String> {
    value
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
}

fn has_flag(args: &[String], name: &str) -> bool {
    let prefix = format!("{name}=");
    args.iter()
        .any(|arg| arg == name || arg.starts_with(&prefix))
}

fn explicit_profile(args: &[String]) -> Option<&str> {
    args.iter().enumerate().find_map(|(index, arg)| {
        if let Some(name) = arg.strip_prefix("--profile=") {
            return Some(name);
        }
        if arg == "--profile" {
            return args.get(index + 1).map(String::as_str);
        }
        None
    })
}

fn profile_is_live(args: &[String]) -> bool {
    explicit_profile(args) == Some("live")
}

fn profile_is_test(args: &[String]) -> bool {
    matches!(explicit_profile(args), None | Some("test"))
}

pub fn parse_produce(args: &[String]) -> Result<ProduceArgs, UsageError> {
    let mut public_key = None;
    let mut output = None;
    let mut tip_url = None;
    let mut bari_url = None;
    let mut report = None;
    let mut clock_source = None;
    let mut gossip_peer_value = None;
    let mut gossip_network = None;
    let mut profile = None;
    let mut i = 0;
    while i < args.len() {
        let arg = &args[i];
        if let Some(form) = match_flag(arg, "--pubkey-hex") {
            if public_key.is_some() {
                return Err(UsageError);
            }
            let value = flag_value(form, args, &mut i)?;
            public_key = Some(decode_hex32(value).map_err(|_| UsageError)?);
        } else if let Some(form) = match_flag(arg, "--output") {
            if output.is_some() {
                return Err(UsageError);
            }
            output = Some(flag_value(form, args, &mut i)?.to_owned());
        } else if let Some(form) = match_flag(arg, "--tip-url") {
            if tip_url.is_some() {
                return Err(UsageError);
            }
            tip_url = Some(take_url(form, args, &mut i)?);
        } else if let Some(form) = match_flag(arg, "--bari-url") {
            if bari_url.is_some() {
                return Err(UsageError);
            }
            bari_url = Some(take_url(form, args, &mut i)?);
        } else if let Some(form) = match_flag(arg, "--report") {
            if report.is_some() {
                return Err(UsageError);
            }
            report = Some(flag_value(form, args, &mut i)?.to_owned());
        } else if let Some(form) = match_flag(arg, "--clock-source") {
            if clock_source.is_some() {
                return Err(UsageError);
            }
            clock_source = Some(take_token(form, args, &mut i)?);
        } else if let Some(form) = match_flag(arg, "--gossip-peer") {
            if gossip_peer_value.is_some() {
                return Err(UsageError);
            }
            gossip_peer_value = Some(gossip_peer(&take_token(form, args, &mut i)?)?);
        } else if let Some(form) = match_flag(arg, "--gossip-network") {
            if gossip_network.is_some() {
                return Err(UsageError);
            }
            gossip_network = Some(take_token(form, args, &mut i)?);
        } else if let Some(form) = match_flag(arg, "--profile") {
            if profile.is_some() {
                return Err(UsageError);
            }
            profile = Some(take_token(form, args, &mut i)?);
        } else {
            return Err(UsageError);
        }
        i += 1;
    }

    // Omitted `--profile` is the test plane. It does not fill live origins.
    // `slow-lan` is a lab setting, not a plane.
    let profile = match profile.as_deref() {
        None | Some("test") => PlaneProfile::Test,
        Some("live") => PlaneProfile::Live,
        Some("slow-lan") => PlaneProfile::SlowLan,
        Some(_) => return Err(UsageError),
    };
    let network = match gossip_network.as_deref() {
        None => None,
        Some("mainnet") => Some(GossipNetwork::Mainnet),
        Some("regtest") => Some(GossipNetwork::Regtest),
        Some(_) => return Err(UsageError),
    };
    let clock_source = match clock_source.as_deref() {
        None => None,
        Some("http") => Some("http"),
        Some("gossip") => Some("gossip"),
        Some(_) => return Err(UsageError),
    };

    let (policy, clock, bari_url) = match profile {
        PlaneProfile::Test => {
            if tip_url.is_some() || clock_source == Some("http") {
                return Err(UsageError);
            }
            if network == Some(GossipNetwork::Mainnet) {
                return Err(UsageError);
            }
            let peer = gossip_peer_value.ok_or(UsageError)?;
            let bari_url = bari_url.ok_or(UsageError)?;
            if live_plane_host(&bari_url) {
                return Err(UsageError);
            }
            (
                Some(AdmitPolicy::Test),
                ProduceClock::Gossip {
                    dial: GossipDial::Named(peer),
                    network: GossipNetwork::Regtest,
                },
                bari_url,
            )
        }
        PlaneProfile::Live => {
            if tip_url.is_some() || clock_source == Some("http") {
                return Err(UsageError);
            }
            if network == Some(GossipNetwork::Regtest) {
                return Err(UsageError);
            }
            let bari_url = match bari_url {
                None => DEFAULT_BARI_URL.to_owned(),
                Some(url) if is_https_live_origin(&url, LIVE_BARI_HOST) => url,
                Some(_) => return Err(UsageError),
            };
            let dial = match gossip_peer_value {
                Some(peer) => GossipDial::Named(peer),
                None => GossipDial::MainnetDnsSeeds,
            };
            (
                Some(AdmitPolicy::Live),
                ProduceClock::Gossip {
                    dial,
                    network: GossipNetwork::Mainnet,
                },
                bari_url,
            )
        }
        PlaneProfile::SlowLan => {
            if tip_url.is_some() || clock_source == Some("http") {
                return Err(UsageError);
            }
            let peer = gossip_peer_value.ok_or(UsageError)?;
            let bari_url = bari_url.ok_or(UsageError)?;
            let network = network.unwrap_or(GossipNetwork::Regtest);
            (
                None,
                ProduceClock::Gossip {
                    dial: GossipDial::Named(peer),
                    network,
                },
                bari_url,
            )
        }
    };

    Ok(ProduceArgs {
        public_key: public_key.ok_or(UsageError)?,
        output: output.ok_or(UsageError)?,
        profile,
        policy,
        clock,
        bari_url,
        report,
    })
}

pub fn parse_verify(args: &[String]) -> Result<VerifyArgs, UsageError> {
    let mut input = None;
    let mut index_url = None;
    let mut audit_seed = None;
    let mut report = None;
    let mut chain_rpc = None;
    let mut profile = None;
    let mut log = None;
    let mut i = 0;
    while i < args.len() {
        let arg = &args[i];
        if let Some(form) = match_flag(arg, "--input") {
            if input.is_some() {
                return Err(UsageError);
            }
            input = Some(flag_value(form, args, &mut i)?.to_owned());
        } else if let Some(form) = match_flag(arg, "--index-url") {
            if index_url.is_some() {
                return Err(UsageError);
            }
            index_url = Some(take_url(form, args, &mut i)?);
        } else if let Some(form) = match_flag(arg, "--audit-seed") {
            if audit_seed.is_some() {
                return Err(UsageError);
            }
            let value = flag_value(form, args, &mut i)?;
            audit_seed = Some(decode_hex32(value).map_err(|_| UsageError)?);
        } else if let Some(form) = match_flag(arg, "--report") {
            if report.is_some() {
                return Err(UsageError);
            }
            report = Some(flag_value(form, args, &mut i)?.to_owned());
        } else if let Some(form) = match_flag(arg, "--chain-rpc") {
            if chain_rpc.is_some() {
                return Err(UsageError);
            }
            chain_rpc = Some(take_url(form, args, &mut i)?);
        } else if let Some(form) = match_flag(arg, "--profile") {
            if profile.is_some() {
                return Err(UsageError);
            }
            profile = Some(take_token(form, args, &mut i)?);
        } else if let Some(form) = match_flag(arg, "--log") {
            if log.is_some() {
                return Err(UsageError);
            }
            log = Some(VerifyLog::parse(flag_value(form, args, &mut i)?).ok_or(UsageError)?);
        } else {
            return Err(UsageError);
        }
        i += 1;
    }
    let (profile, measure) = match profile.as_deref() {
        None | Some("test") | Some("production") => {
            (PlaneProfile::Test, MeasureProfile::Production)
        }
        Some("conformance-small") => (PlaneProfile::Test, MeasureProfile::ConformanceSmall),
        Some("live") => (PlaneProfile::Live, MeasureProfile::Production),
        Some(_) => return Err(UsageError),
    };
    let (policy, index_url) = match profile {
        PlaneProfile::Test => {
            if chain_rpc.is_some() {
                return Err(UsageError);
            }
            let index_url = index_url.ok_or(UsageError)?;
            if live_plane_host(&index_url) {
                return Err(UsageError);
            }
            (AdmitPolicy::Test, index_url)
        }
        PlaneProfile::Live => {
            let index_url = match index_url {
                None => DEFAULT_INDEX_URL.to_owned(),
                Some(url) if is_https_live_origin(&url, LIVE_INDEX_HOST) => url,
                Some(_) => return Err(UsageError),
            };
            (AdmitPolicy::Live, index_url)
        }
        PlaneProfile::SlowLan => return Err(UsageError),
    };
    Ok(VerifyArgs {
        input: input.ok_or(UsageError)?,
        profile,
        policy,
        index_url,
        audit_seed,
        report,
        chain_rpc,
        log: log.unwrap_or(VerifyLog::Quiet),
        measure,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex64() -> String {
        "ab".repeat(32)
    }

    fn produce(args: &[&str]) -> Result<ProduceArgs, UsageError> {
        let owned: Vec<String> = args.iter().map(|s| (*s).to_owned()).collect();
        parse_produce(&owned)
    }

    #[test]
    fn produce_flags() {
        assert!(produce(&[
            "--pubkey-hex",
            &format!("0x{}", hex64()),
            "--output=out.cbor",
            "--report",
            "rep.json",
        ])
        .is_err());

        let ok = produce(&[
            "--pubkey-hex",
            &format!("0x{}", hex64()),
            "--output=out.cbor",
            "--report",
            "rep.json",
            "--gossip-peer",
            "127.0.0.1:38532",
            "--bari-url",
            "http://127.0.0.1:9",
        ])
        .unwrap();
        assert_eq!(ok.output, "out.cbor");
        assert_eq!(ok.profile, PlaneProfile::Test);
        assert_eq!(ok.policy, Some(AdmitPolicy::Test));
        assert_eq!(
            ok.clock,
            ProduceClock::Gossip {
                dial: GossipDial::Named("127.0.0.1:38532".to_owned()),
                network: GossipNetwork::Regtest,
            }
        );
        assert_eq!(ok.bari_url, "http://127.0.0.1:9");
        assert_eq!(ok.report.as_deref(), Some("rep.json"));
        assert_eq!(ok.public_key[0], 0xab);

        let url = produce(&[
            "--profile",
            "test",
            "--bari-url",
            "http://127.0.0.1:8",
            "--gossip-peer",
            "127.0.0.1:9",
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
        ])
        .unwrap();
        assert_eq!(
            url.clock,
            ProduceClock::Gossip {
                dial: GossipDial::Named("127.0.0.1:9".to_owned()),
                network: GossipNetwork::Regtest,
            }
        );
        assert_eq!(url.bari_url, "http://127.0.0.1:8");

        let unpinned = produce(&[
            "--profile",
            "live",
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
        ])
        .unwrap();
        assert_eq!(
            unpinned.clock,
            ProduceClock::Gossip {
                dial: GossipDial::MainnetDnsSeeds,
                network: GossipNetwork::Mainnet,
            }
        );
        assert_eq!(unpinned.bari_url, DEFAULT_BARI_URL);
        assert!(produce(&[
            "--profile",
            "slow-lan",
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
            "--bari-url",
            "http://127.0.0.1:9",
        ])
        .is_err());
        let live = produce(&[
            "--profile",
            "live",
            "--gossip-peer",
            "live-peer.invalid:9",
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
        ])
        .unwrap();
        assert_eq!(live.profile, PlaneProfile::Live);
        assert_eq!(live.policy, Some(AdmitPolicy::Live));
        assert_eq!(
            live.clock,
            ProduceClock::Gossip {
                dial: GossipDial::Named("live-peer.invalid:9".to_owned()),
                network: GossipNetwork::Mainnet,
            }
        );
        assert_eq!(live.bari_url, DEFAULT_BARI_URL);

        assert!(produce(&[
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
            "--tip-url",
            "https://tip.example.invalid/",
        ])
        .is_err());
        assert!(produce(&[
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
            "--index-url",
            "https://index.groundinglab.io",
        ])
        .is_err());
        assert!(produce(&["--pubkey-hex", &hex64(), "--output", "out.cbor", "extra"]).is_err());
        assert!(produce(&["--pubkey-hex", &hex64(), "--output", "a", "--output", "b",]).is_err());
        assert!(produce(&["--help"]).is_err());
        assert!(produce(&["--pubkey-hex", "zz", "--output", "a"]).is_err());
        assert!(produce(&[
            "--pubkey-hex",
            &hex64(),
            "--output",
            "a",
            "--bari-url",
            "bari.groundinglab.io",
        ])
        .is_err());
        assert!(produce(&[
            "--profile",
            "live",
            "--tip-url",
            "HTTPS://tip.example.invalid",
            "--gossip-peer",
            "live-peer.invalid:9",
            "--bari-url",
            "https://bari.groundinglab.io",
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
        ])
        .is_err());
        assert!(produce(&[
            "--profile",
            "live",
            "--tip-url",
            "http://127.0.0.1:9",
            "--bari-url",
            "https://bari.groundinglab.io",
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
        ])
        .is_err());
        assert!(produce(&[
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
            "--gossip-peer",
            "127.0.0.1:38532",
            "--bari-url",
            "https://bari.groundinglab.io",
        ])
        .is_err());
        assert!(produce(&[
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
            "--clock-source",
            "http",
            "--gossip-peer",
            "127.0.0.1:38532",
            "--bari-url",
            "http://127.0.0.1:9",
        ])
        .is_err());
        assert!(produce(&[
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
            "--gossip-network",
            "mainnet",
            "--gossip-peer",
            "127.0.0.1:8333",
            "--bari-url",
            "http://127.0.0.1:9",
        ])
        .is_err());
        let live_gossip = produce(&[
            "--profile",
            "live",
            "--clock-source",
            "gossip",
            "--gossip-network",
            "mainnet",
            "--gossip-peer",
            "live-peer.invalid:9",
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
        ])
        .unwrap();
        assert_eq!(
            live_gossip.clock,
            ProduceClock::Gossip {
                dial: GossipDial::Named("live-peer.invalid:9".to_owned()),
                network: GossipNetwork::Mainnet,
            }
        );
        assert!(produce(&[
            "--profile",
            "live",
            "--clock-source",
            "http",
            "--gossip-peer",
            "live-peer.invalid:9",
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
        ])
        .is_err());
        assert!(produce(&[
            "--profile",
            "live",
            "--gossip-network",
            "regtest",
            "--gossip-peer",
            "live-peer.invalid:9",
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
        ])
        .is_err());
        assert!(produce(&[
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
            "--clock-source",
            "gossip",
            "--gossip-peer",
            "127.0.0.1:18444",
        ])
        .is_err());
        assert!(produce(&[
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
            "--clock-source",
            "gossip",
            "--gossip-peer",
            "127.0.0.1:0",
            "--bari-url",
            "http://127.0.0.1:9",
        ])
        .is_err());
        assert!(produce(&[
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
            "--profile",
            "slow-lan",
            "--tip-url",
            "https://tip.example.invalid",
            "--gossip-peer",
            "127.0.0.1:18444",
            "--bari-url",
            "http://127.0.0.1:9",
        ])
        .is_err());
        assert!(produce(&[
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
            "--gossip-peer",
            "127.0.0.1:18444",
        ])
        .is_err());
        let slow = produce(&[
            "--profile",
            "slow-lan",
            "--gossip-peer",
            "[::1]:18444",
            "--bari-url",
            "http://127.0.0.1:9",
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
        ])
        .unwrap();
        assert_eq!(
            slow.clock,
            ProduceClock::Gossip {
                dial: GossipDial::Named("[::1]:18444".to_owned()),
                network: GossipNetwork::Regtest,
            }
        );
        assert_eq!(slow.bari_url, "http://127.0.0.1:9");
        let explicit = produce(&[
            "--clock-source",
            "gossip",
            "--gossip-network",
            "mainnet",
            "--profile",
            "slow-lan",
            "--gossip-peer",
            "127.0.0.1:8333",
            "--bari-url",
            "http://127.0.0.1:9",
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
        ])
        .unwrap();
        assert_eq!(
            explicit.clock,
            ProduceClock::Gossip {
                dial: GossipDial::Named("127.0.0.1:8333".to_owned()),
                network: GossipNetwork::Mainnet,
            }
        );
        assert!(produce(&[
            "--pubkey-hex",
            &hex64(),
            "--output",
            "out.cbor",
            "--bari-url",
            "ftp://example.com",
        ])
        .is_err());
    }

    #[test]
    fn live_peer_env_does_not_fill_an_http_tip() {
        let base = vec![
            "--profile".to_owned(),
            "live".to_owned(),
            "--pubkey-hex".to_owned(),
            hex64(),
            "--output".to_owned(),
            "out.cbor".to_owned(),
        ];
        let filled = apply_live_gossip_env(base.clone(), Some("  live-peer.invalid:9  "));
        assert_eq!(
            filled,
            vec![
                "--profile".to_owned(),
                "live".to_owned(),
                "--pubkey-hex".to_owned(),
                hex64(),
                "--output".to_owned(),
                "out.cbor".to_owned(),
                "--gossip-peer".to_owned(),
                "live-peer.invalid:9".to_owned(),
            ]
        );
        let parsed = parse_produce(&filled).unwrap();
        assert_eq!(
            parsed.clock,
            ProduceClock::Gossip {
                dial: GossipDial::Named("live-peer.invalid:9".to_owned()),
                network: GossipNetwork::Mainnet,
            }
        );
        assert_eq!(apply_live_gossip_env(base.clone(), None), base);
        assert_eq!(apply_live_gossip_env(base.clone(), Some("  ")), base);
        assert_eq!(
            parse_produce(&base).unwrap().clock,
            ProduceClock::Gossip {
                dial: GossipDial::MainnetDnsSeeds,
                network: GossipNetwork::Mainnet,
            }
        );
        let explicit = apply_live_gossip_env(
            vec![
                "--profile=live".to_owned(),
                "--gossip-peer".to_owned(),
                "kept.invalid:9".to_owned(),
            ],
            Some("other.invalid:9"),
        );
        assert_eq!(
            explicit,
            vec![
                "--profile=live".to_owned(),
                "--gossip-peer".to_owned(),
                "kept.invalid:9".to_owned(),
            ]
        );
        let test_profile = vec!["--profile".to_owned(), "test".to_owned()];
        assert_eq!(
            apply_live_gossip_env(test_profile.clone(), Some("live-peer.invalid:9")),
            test_profile
        );
    }

    #[test]
    fn test_profile_env_fills_omitted_flags_and_does_not_invent_a_host() {
        let base = vec![
            "--pubkey-hex".to_owned(),
            hex64(),
            "--output".to_owned(),
            "out.cbor".to_owned(),
        ];
        assert_eq!(apply_test_produce_env(base.clone(), None, None), base);
        assert_eq!(
            apply_test_produce_env(base.clone(), Some("  "), Some(" ")),
            base
        );
        let filled = apply_test_produce_env(
            base,
            Some("  synth.invalid:1  "),
            Some(" http://synth.invalid:1 "),
        );
        let parsed = parse_produce(&filled).unwrap();
        assert_eq!(parsed.profile, PlaneProfile::Test);
        assert_eq!(
            parsed.clock,
            ProduceClock::Gossip {
                dial: GossipDial::Named("synth.invalid:1".to_owned()),
                network: GossipNetwork::Regtest,
            }
        );
        assert_eq!(parsed.bari_url, "http://synth.invalid:1");
        assert!(!filled.iter().any(|arg| arg == "--tip-url"));
        assert!(!filled.iter().any(|arg| arg.contains("groundinglab.io")));
        assert!(!filled.iter().any(|arg| arg.contains("192.168")));

        let explicit = vec![
            "--profile=test".to_owned(),
            "--gossip-peer".to_owned(),
            "kept.invalid:1".to_owned(),
            "--bari-url".to_owned(),
            "http://kept.invalid:1".to_owned(),
            "--pubkey-hex".to_owned(),
            hex64(),
            "--output".to_owned(),
            "out.cbor".to_owned(),
        ];
        assert_eq!(
            apply_test_produce_env(
                explicit.clone(),
                Some("other.invalid:2"),
                Some("http://other.invalid:2")
            ),
            explicit
        );

        let live = vec!["--profile".to_owned(), "live".to_owned()];
        assert_eq!(
            apply_test_produce_env(
                live.clone(),
                Some("synth.invalid:1"),
                Some("http://synth.invalid:1")
            ),
            live
        );
        let slow = vec!["--profile".to_owned(), "slow-lan".to_owned()];
        assert_eq!(
            apply_test_produce_env(
                slow.clone(),
                Some("synth.invalid:1"),
                Some("http://synth.invalid:1")
            ),
            slow
        );

        let live_bari = apply_test_produce_env(
            vec![
                "--profile".to_owned(),
                "test".to_owned(),
                "--pubkey-hex".to_owned(),
                hex64(),
                "--output".to_owned(),
                "out.cbor".to_owned(),
                "--gossip-peer".to_owned(),
                "synth.invalid:1".to_owned(),
            ],
            None,
            Some("https://bari.groundinglab.io"),
        );
        assert!(parse_produce(&live_bari).is_err());

        let index_base = vec!["--input".to_owned(), "hike.cbor".to_owned()];
        assert_eq!(apply_test_index_env(index_base.clone(), None), index_base);
        let indexed = apply_test_index_env(index_base, Some(" http://index.invalid:1 "));
        assert_eq!(
            parse_verify(&indexed).unwrap().index_url,
            "http://index.invalid:1"
        );
        assert!(!indexed.iter().any(|arg| arg.contains("groundinglab.io")));
        let live_index = vec![
            "--profile".to_owned(),
            "live".to_owned(),
            "--input".to_owned(),
            "hike.cbor".to_owned(),
        ];
        assert_eq!(
            apply_test_index_env(live_index.clone(), Some("http://index.invalid:1")),
            live_index
        );
        let kept = vec![
            "--index-url".to_owned(),
            "http://kept.invalid:1".to_owned(),
            "--input".to_owned(),
            "hike.cbor".to_owned(),
        ];
        assert_eq!(
            apply_test_index_env(kept.clone(), Some("http://other.invalid:1")),
            kept
        );
    }

    #[test]
    fn verify_flags() {
        assert!(parse_verify(&["--input".to_owned(), "hike.cbor".to_owned()]).is_err());
        let owned = vec![
            "--input".to_owned(),
            "hike.cbor".to_owned(),
            "--index-url".to_owned(),
            "http://127.0.0.1:7".to_owned(),
        ];
        let ok = parse_verify(&owned).unwrap();
        assert_eq!(ok.input, "hike.cbor");
        assert_eq!(ok.profile, PlaneProfile::Test);
        assert_eq!(ok.measure, MeasureProfile::Production);
        assert_eq!(ok.policy, AdmitPolicy::Test);
        assert_eq!(ok.index_url, "http://127.0.0.1:7");
        assert!(ok.audit_seed.is_none());
        assert!(ok.chain_rpc.is_none());
        assert_eq!(ok.log, VerifyLog::Quiet);
        let indexed = vec![
            "--index-url".to_owned(),
            "http://127.0.0.1:7".to_owned(),
            "--input".to_owned(),
            "hike.cbor".to_owned(),
        ];
        assert_eq!(
            parse_verify(&indexed).unwrap().index_url,
            "http://127.0.0.1:7"
        );
        assert!(parse_verify(&[
            "--input".to_owned(),
            "hike.cbor".to_owned(),
            "--index-url".to_owned(),
            "https://index.groundinglab.io".to_owned(),
        ])
        .is_err());
        let live = parse_verify(&[
            "--profile".to_owned(),
            "live".to_owned(),
            "--input".to_owned(),
            "hike.cbor".to_owned(),
        ])
        .unwrap();
        assert_eq!(live.profile, PlaneProfile::Live);
        assert_eq!(live.measure, MeasureProfile::Production);
        let production = parse_verify(&[
            "--profile".to_owned(),
            "production".to_owned(),
            "--input".to_owned(),
            "hike.cbor".to_owned(),
            "--index-url".to_owned(),
            "http://127.0.0.1:7".to_owned(),
        ])
        .unwrap();
        assert_eq!(production.profile, PlaneProfile::Test);
        assert_eq!(production.measure, MeasureProfile::Production);
        let small = parse_verify(&[
            "--profile".to_owned(),
            "conformance-small".to_owned(),
            "--input".to_owned(),
            "hike.cbor".to_owned(),
            "--index-url".to_owned(),
            "http://127.0.0.1:7".to_owned(),
        ])
        .unwrap();
        assert_eq!(small.profile, PlaneProfile::Test);
        assert_eq!(small.measure, MeasureProfile::ConformanceSmall);
        assert_eq!(small.measure.sizes(), crate::consts::Sizes::conformance_small());
        assert_eq!(small.measure.record_name(), "conformance-small");
        assert_ne!(
            MeasureProfile::Production.sizes(),
            MeasureProfile::ConformanceSmall.sizes()
        );
        assert!(parse_produce(&[
            "--profile".to_owned(),
            "conformance-small".to_owned(),
            "--pubkey-hex".to_owned(),
            hex64(),
            "--output".to_owned(),
            "out.cbor".to_owned(),
        ])
        .is_err());
        assert!(parse_verify(&[
            "--profile".to_owned(),
            "live".to_owned(),
            "--profile".to_owned(),
            "production".to_owned(),
            "--input".to_owned(),
            "hike.cbor".to_owned(),
        ])
        .is_err());
        assert_eq!(live.policy, AdmitPolicy::Live);
        assert_eq!(live.index_url, DEFAULT_INDEX_URL);
        assert!(parse_verify(&[
            "--input".to_owned(),
            "hike.cbor".to_owned(),
            "--index-url".to_owned(),
            "https://index.groundinglab.io/".to_owned(),
        ])
        .is_err());
        assert!(parse_verify(&[
            "--input".to_owned(),
            "hike.cbor".to_owned(),
            "--tip-url".to_owned(),
            "https://tip.example.invalid".to_owned(),
        ])
        .is_err());

        let seeded = vec![
            "--audit-seed".to_owned(),
            format!("0x{}", hex64()),
            "--index-url".to_owned(),
            "http://127.0.0.1:7".to_owned(),
            "--input=hike.cbor".to_owned(),
        ];
        assert!(parse_verify(&seeded).unwrap().audit_seed.is_some());
        assert!(parse_verify(&[
            "--audit-seed".to_owned(),
            "00".to_owned(),
            "--input".to_owned(),
            "a".to_owned()
        ])
        .is_err());
        assert!(parse_verify(&[]).is_err());
        let direct = vec![
            "--profile".to_owned(),
            "live".to_owned(),
            "--input".to_owned(),
            "hike.cbor".to_owned(),
            "--chain-rpc".to_owned(),
            "http://127.0.0.1:8332".to_owned(),
        ];
        assert_eq!(
            parse_verify(&direct).unwrap().chain_rpc.as_deref(),
            Some("http://127.0.0.1:8332")
        );
        assert!(parse_verify(&[
            "--input".to_owned(),
            "hike.cbor".to_owned(),
            "--index-url".to_owned(),
            "http://127.0.0.1:7".to_owned(),
            "--chain-rpc".to_owned(),
            "http://127.0.0.1:8332".to_owned(),
        ])
        .is_err());
        assert!(parse_verify(&[
            "--input".to_owned(),
            "hike.cbor".to_owned(),
            "--chain-rpc".to_owned(),
            "http://127.0.0.1:8332/".to_owned(),
        ])
        .is_err());
        assert!(parse_verify(&[
            "--chain-rpc".to_owned(),
            "http://127.0.0.1:8332".to_owned(),
            "--input".to_owned(),
            "hike.cbor".to_owned(),
            "--chain-rpc".to_owned(),
            "http://127.0.0.1:8333".to_owned(),
        ])
        .is_err());
    }

    #[test]
    fn verify_log_degree_defaults_to_quiet() {
        let base = [
            "--input".to_owned(),
            "hike.cbor".to_owned(),
            "--index-url".to_owned(),
            "http://127.0.0.1:7".to_owned(),
        ];
        assert_eq!(parse_verify(&base).unwrap().log, VerifyLog::Quiet);
        let mut verbose = base.to_vec();
        verbose.push("--log".to_owned());
        verbose.push("verbose".to_owned());
        assert_eq!(parse_verify(&verbose).unwrap().log, VerifyLog::Verbose);
        let mut fail = base.to_vec();
        fail.push("--log=fail".to_owned());
        assert_eq!(parse_verify(&fail).unwrap().log, VerifyLog::Fail);
        let mut quiet = base.to_vec();
        quiet.push("--log".to_owned());
        quiet.push("quiet".to_owned());
        assert_eq!(parse_verify(&quiet).unwrap().log, VerifyLog::Quiet);
        let mut unknown = base.to_vec();
        unknown.push("--log".to_owned());
        unknown.push("debug".to_owned());
        assert!(parse_verify(&unknown).is_err());
        let mut missing = base.to_vec();
        missing.push("--log".to_owned());
        assert!(parse_verify(&missing).is_err());
        let mut repeated = verbose;
        repeated.push("--log".to_owned());
        repeated.push("fail".to_owned());
        assert!(parse_verify(&repeated).is_err());
    }
}
