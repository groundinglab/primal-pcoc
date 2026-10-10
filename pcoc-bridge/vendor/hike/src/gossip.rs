//! Bitcoin P2P header subscription for the produce clock.
//!
//! This is not a full node. It accepts `headers` messages and answers `ping`.
//! Bari still plants waypoints. The wire is Bitcoin P2P (magic, checksum, 80-byte
//! headers). There is no second framing.
//!
//! Lab, not Lock. Ask-Christian items are listed in
//! `docs/records/gossip-slow-lan.md`.
//!
//! Live produce with no named peer starts from Bitcoin Core's mainnet DNS
//! seeds. A lookup returns node addresses. The producer connects and asks
//! those nodes for more peers. A bad address fails and is discarded. The
//! test profile and the slow-lan card do not use that list.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener, TcpStream, ToSocketAddrs};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::hex::encode_hex32;
use crate::http::{connectivity_wait, io_kind_class, plant_window_tips, LivePathBook};
use crate::produce::ProduceFailure;
use crate::sha::sha256;

/// Bitcoin mainnet or regtest. Magic and genesis are Bitcoin's, not a private wire.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GossipNetwork {
    Mainnet,
    Regtest,
}

/// Bitcoin Core `net.h`: `PING_INTERVAL`.
const BITCOIN_PING_INTERVAL: Duration = Duration::from_secs(2 * 60);
/// Bitcoin Core `net.h`: `TIMEOUT_INTERVAL`. Steady-state peer silence.
const BITCOIN_SILENCE_TIMEOUT: Duration = Duration::from_secs(20 * 60);
/// Same per-attempt connect/handshake limit as the HTTP agent. Not a tip-wait quota.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(30);
const PROTOCOL_VERSION: i32 = 70_015;
const MAX_MESSAGE: usize = 4_000_000;
const MAX_HEADERS: usize = 2_000;
const USER_AGENT: &str = "/satoshi-hike:0.1/";

/// Bitcoin Core mainnet DNS seeds.
///
/// Copied from `CMainParams::vSeeds` in Bitcoin Core `src/kernel/chainparams.cpp`
/// (master, checked 2026-10-04). Core stores a trailing dot. The lookup host
/// is the name without that dot. The port is mainnet `nDefaultPort`, 8333.
/// This is Core's own bootstrap, not a host this tree invents. Re-check the
/// copy against Bitcoin Core's mainnet chainparams when a producer release
/// is cut. No testnet, signet, or synthetic name belongs here.
pub const MAINNET_DNS_SEEDS: &[&str] = &[
    "dnsseed.bluematt.me",
    "seed.bitcoin.jonasschnelli.ch",
    "seed.btc.petertodd.net",
    "seed.bitcoin.sprovoost.nl",
    "dnsseed.emzy.de",
    "seed.bitcoin.wiz.biz",
    "seed.mainnet.achownodes.xyz",
];

/// Bitcoin Core mainnet `nDefaultPort`.
pub const MAINNET_DNS_PORT: u16 = 8333;

/// One address message carries at most this many peers. Bitcoin Core's cap.
const MAX_ADDR: u64 = 1_000;

#[derive(Clone)]
enum Dial {
    Named(String),
    MainnetSeeds(Arc<dyn Fn(&str) -> Result<Vec<SocketAddr>, ()> + Send + Sync>),
}

struct ClockOpts {
    dial: Dial,
    network: GossipNetwork,
    connect_timeout: Duration,
    ping_interval: Duration,
    silence_timeout: Duration,
    skip_pause: bool,
    log: Option<Arc<Mutex<Vec<String>>>>,
}

struct Shared {
    chain: Chain,
    live: bool,
    reorg: bool,
}

/// One tip of the plane sequence. `hash` is natural byte order.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TipTick {
    pub hash: [u8; 32],
    pub height: u64,
    pub previous: Option<[u8; 32]>,
    /// Bitcoin header `nTime`: unix seconds, little-endian bytes 68..72.
    /// Not milliseconds, and not the wall clock of the process that saw the header.
    pub n_time: u32,
}

/// A running header subscription. `observe` is the produce clock.
pub struct HeaderClock {
    shared: Arc<(Mutex<Shared>, Condvar)>,
}

impl HeaderClock {
    /// Subscribe to one named Bitcoin peer. Retries Class T faults inside the call.
    /// Does not dial until the background thread runs. Does not use DNS seeds.
    pub fn start(peer: impl Into<String>, network: GossipNetwork) -> Self {
        Self::spawn(ClockOpts {
            dial: Dial::Named(peer.into()),
            network,
            connect_timeout: HANDSHAKE_TIMEOUT,
            ping_interval: BITCOIN_PING_INTERVAL,
            silence_timeout: BITCOIN_SILENCE_TIMEOUT,
            skip_pause: false,
            log: None,
        })
    }

    /// Live mainnet start when no peer is named.
    ///
    /// The starting list is [`MAINNET_DNS_SEEDS`]. A lookup returns node
    /// addresses. This clock connects and asks those nodes for more peers.
    /// A bad address fails and is discarded. The call does not dial until the
    /// background thread runs, and it does not abort when one seed fails and
    /// another can be used.
    pub fn start_mainnet_dns_seeds() -> Self {
        Self::spawn(ClockOpts {
            dial: Dial::MainnetSeeds(Arc::new(lookup_mainnet_seed)),
            network: GossipNetwork::Mainnet,
            connect_timeout: HANDSHAKE_TIMEOUT,
            ping_interval: BITCOIN_PING_INTERVAL,
            silence_timeout: BITCOIN_SILENCE_TIMEOUT,
            skip_pause: false,
            log: None,
        })
    }

    fn spawn(opts: ClockOpts) -> Self {
        let shared = Arc::new((
            Mutex::new(Shared {
                chain: Chain::new(opts.network),
                live: false,
                reorg: false,
            }),
            Condvar::new(),
        ));
        let thread_shared = Arc::clone(&shared);
        thread::spawn(move || run_peer(opts, thread_shared));
        Self { shared }
    }

    /// Latest synced tip as `(natural hash, height)`.
    ///
    /// Blocks while the peer is down (Class T is the background thread).
    /// Returns an error only for a reorg of a tip already handed out.
    /// A live peer with an unchanged tip returns immediately, the same close
    /// rule as an HTTP poll.
    pub fn observe(&self) -> Result<([u8; 32], u64), ProduceFailure> {
        let ticks = self.wait_tips_after(None)?;
        let tick = ticks
            .last()
            .ok_or_else(|| ProduceFailure::new("clock read failed: reorg"))?;
        Ok((tick.hash, tick.height))
    }

    /// Wait out the Bari plant window on this clock.
    ///
    /// `satoshi-hike-produce` must not call this. It pauses the drive to wait
    /// for further headers before the first leg. Headers arrive while the
    /// drive is stopped, and the Observe after one leg then closes the stage.
    ///
    /// `open_tip` is the tip already used for Hashcash. The wait is
    /// [`plant_window_tips`] newer gossip tips. It does not dial Bari.
    /// `PCOC_PLANT_WINDOW_TIPS=0` returns without waiting.
    pub fn await_plant_window(&self, open_tip: [u8; 32]) -> Result<(), ProduceFailure> {
        let need = plant_window_tips();
        if need == 0 {
            eprintln!("plant_window call_site=fetch_path tips=0");
            return Ok(());
        }
        let mut seen = 0u32;
        let mut cursor = open_tip;
        let mut last_tip = open_tip;
        let mut last_height = 0u64;
        while seen < need {
            let ticks = self.wait_tips_after(Some(cursor))?;
            if ticks.is_empty() {
                return Err(ProduceFailure::new("clock read failed: reorg"));
            }
            for tick in ticks {
                seen = seen.saturating_add(1);
                last_tip = tick.hash;
                last_height = tick.height;
                cursor = tick.hash;
                if seen >= need {
                    break;
                }
            }
        }
        eprintln!(
            "plant_window call_site=fetch_path tips={need} advanced={seen} tip_natural={} height={last_height}",
            encode_hex32(&last_tip)
        );
        Ok(())
    }

    /// Block until the sequence has a tip after `after`.
    ///
    /// `None` returns the current live tip and does not replay earlier headers.
    /// A later call returns every header from the parent of `after` through the
    /// new tip, in height order, so a follower does not skip a tick.
    pub fn wait_tips_after(&self, after: Option<[u8; 32]>) -> Result<Vec<TipTick>, ProduceFailure> {
        let (lock, cv) = &*self.shared;
        let mut guard = lock.lock().unwrap_or_else(|err| err.into_inner());
        loop {
            if guard.reorg {
                return Err(ProduceFailure::new("clock read failed: reorg"));
            }
            if guard.live {
                match ticks_after(&guard.chain, after) {
                    Ok(ticks) if !ticks.is_empty() => {
                        for tick in &ticks {
                            guard.chain.publish(tick.hash);
                        }
                        return Ok(ticks);
                    }
                    Ok(_) => {}
                    Err(()) => {
                        return Err(ProduceFailure::new("clock read failed: reorg"));
                    }
                }
            }
            guard = cv.wait(guard).unwrap_or_else(|err| err.into_inner());
        }
    }

    /// Headers from `after` through the current tip, without waiting for a newer one.
    ///
    /// `None` is the current tip only. It does not replay headers from before
    /// this produce joined. When `after` is already the tip, the result is that
    /// one tip, so a stage bind does not pause for a header that has not
    /// arrived. When the tip has moved, the result is every header after
    /// `after`, in height order. The newest tip alone is not that list.
    pub fn headers_since(&self, after: Option<[u8; 32]>) -> Result<Vec<TipTick>, ProduceFailure> {
        let (lock, cv) = &*self.shared;
        let mut guard = lock.lock().unwrap_or_else(|err| err.into_inner());
        loop {
            if guard.reorg {
                return Err(ProduceFailure::new("clock read failed: reorg"));
            }
            if guard.live {
                let ticks = if after.is_none() || guard.chain.tip == after.unwrap() {
                    tick_of(&guard.chain, guard.chain.tip)
                        .map(|tick| vec![tick])
                        .ok_or(())
                } else {
                    ticks_after(&guard.chain, after)
                };
                match ticks {
                    Ok(ticks) if !ticks.is_empty() => {
                        for tick in &ticks {
                            guard.chain.publish(tick.hash);
                        }
                        return Ok(ticks);
                    }
                    Ok(_) => {}
                    Err(()) => {
                        return Err(ProduceFailure::new("clock read failed: reorg"));
                    }
                }
            }
            guard = cv.wait(guard).unwrap_or_else(|err| err.into_inner());
        }
    }

    #[cfg(test)]
    fn from_synced_chain(chain: Chain) -> Self {
        Self {
            shared: Arc::new((
                Mutex::new(Shared {
                    chain,
                    live: true,
                    reorg: false,
                }),
                Condvar::new(),
            )),
        }
    }
}

/// Apply every header since the book's last tip, then return the newest.
///
/// The path ask is due on one of those headers. The newest tip alone is not
/// that header.
#[cfg(test)]
pub(crate) fn regtest_clock_past_genesis(extra: usize) -> (HeaderClock, [u8; 32], u64) {
    let mut chain = Chain::new(GossipNetwork::Regtest);
    let start = chain.tip;
    let start_height = chain.tip_height;
    for i in 0..extra {
        let header = mine_extension(
            chain.tip,
            GossipNetwork::Regtest.extension_bits(),
            1_700_000_000 + i as u32,
        );
        assert!(
            matches!(chain.apply(&header), Apply::NewTip),
            "regtest extension {i} did not extend the tip"
        );
    }
    (HeaderClock::from_synced_chain(chain), start, start_height)
}

pub fn note_produce_headers(
    clock: &HeaderClock,
    book: &mut LivePathBook,
) -> Result<([u8; 32], u64), ProduceFailure> {
    let ticks = clock.headers_since(book.last_tip())?;
    let mut newest = None;
    for tick in ticks {
        book.on_tip(tick.hash, tick.height)?;
        newest = Some((tick.hash, tick.height));
    }
    newest.ok_or_else(|| ProduceFailure::new("clock read failed: reorg"))
}

fn tick_of(chain: &Chain, hash: [u8; 32]) -> Option<TipTick> {
    let node = chain.nodes.get(&hash)?;
    Some(TipTick {
        hash,
        height: node.height,
        previous: if node.height == 0 {
            None
        } else {
            Some(node.prev)
        },
        n_time: node.n_time,
    })
}

/// Ordered tips strictly after `after`. Empty when the tip is still `after`.
fn ticks_after(chain: &Chain, after: Option<[u8; 32]>) -> Result<Vec<TipTick>, ()> {
    if after.is_none() {
        return tick_of(chain, chain.tip).map(|tick| vec![tick]).ok_or(());
    }
    let after = after.unwrap();
    if chain.tip == after {
        return Ok(Vec::new());
    }
    if !chain.is_ancestor(after, chain.tip) {
        return Err(());
    }
    let mut rev = Vec::new();
    let mut cursor = chain.tip;
    while cursor != after {
        let tick = tick_of(chain, cursor).ok_or(())?;
        let prev = tick.previous.ok_or(())?;
        rev.push(tick);
        cursor = prev;
    }
    rev.reverse();
    Ok(rev)
}

fn run_peer(opts: ClockOpts, shared: Arc<(Mutex<Shared>, Condvar)>) {
    let dial = match &opts.dial {
        Dial::Named(peer) => Dial::Named(peer.clone()),
        Dial::MainnetSeeds(lookup) => Dial::MainnetSeeds(Arc::clone(lookup)),
    };
    match dial {
        Dial::Named(peer) => run_named(opts, peer, shared),
        Dial::MainnetSeeds(lookup) => run_seeds(opts, lookup, shared),
    }
}

fn run_named(opts: ClockOpts, peer: String, shared: Arc<(Mutex<Shared>, Condvar)>) {
    let mut attempt = 0u32;
    loop {
        if mark_down(&shared) {
            return;
        }
        match session(&opts, &peer, &shared, false, &mut Vec::new()) {
            Ok(SessionEnd::Reorg) => {
                mark_reorg(&shared);
                return;
            }
            Err(class) => {
                note_wait(&opts, class, &mut attempt);
            }
        }
    }
}

/// Resolve the mainnet seed list, then dial. One failed lookup or one bad
/// address is discarded. The thread keeps going when another address remains.
fn run_seeds(
    opts: ClockOpts,
    lookup: Arc<dyn Fn(&str) -> Result<Vec<SocketAddr>, ()> + Send + Sync>,
    shared: Arc<(Mutex<Shared>, Condvar)>,
) {
    let mut attempt = 0u32;
    let mut pending: Vec<SocketAddr> = Vec::new();
    loop {
        if mark_down(&shared) {
            return;
        }
        if pending.is_empty() {
            pending = resolve_mainnet_seeds(lookup.as_ref(), &opts.log);
            if pending.is_empty() {
                note_wait(&opts, "dns", &mut attempt);
                continue;
            }
        }
        let addr = pending.remove(0);
        let mut learned = Vec::new();
        match session(&opts, &addr.to_string(), &shared, true, &mut learned) {
            Ok(SessionEnd::Reorg) => {
                mark_reorg(&shared);
                return;
            }
            Err(class) => {
                emit(
                    &opts.log,
                    format!("gossip_seed_skip call_site=gossip_peer peer={addr} reason={class}"),
                );
                for extra in learned {
                    if usable_peer_addr(extra) && extra != addr && !pending.contains(&extra) {
                        pending.push(extra);
                    }
                }
                if pending.is_empty() {
                    note_wait(&opts, class, &mut attempt);
                }
            }
        }
    }
}

fn mark_down(shared: &Arc<(Mutex<Shared>, Condvar)>) -> bool {
    let (lock, cv) = &**shared;
    let mut guard = lock.lock().unwrap_or_else(|err| err.into_inner());
    if guard.reorg {
        return true;
    }
    guard.live = false;
    cv.notify_all();
    false
}

fn mark_reorg(shared: &Arc<(Mutex<Shared>, Condvar)>) {
    let (lock, cv) = &**shared;
    let mut guard = lock.lock().unwrap_or_else(|err| err.into_inner());
    guard.reorg = true;
    guard.live = false;
    cv.notify_all();
}

fn note_wait(opts: &ClockOpts, class: &'static str, attempt: &mut u32) {
    let line = connectivity_wait("gossip_peer", class, attempt, !opts.skip_pause);
    if let Some(log) = &opts.log {
        log.lock().unwrap_or_else(|err| err.into_inner()).push(line);
    }
}

enum SessionEnd {
    Reorg,
}

fn session(
    opts: &ClockOpts,
    peer: &str,
    shared: &Arc<(Mutex<Shared>, Condvar)>,
    learn: bool,
    learned: &mut Vec<SocketAddr>,
) -> Result<SessionEnd, &'static str> {
    let mut stream = connect_peer(peer, opts.connect_timeout)?;
    stream.set_nodelay(true).map_err(|_| "peer_silence")?;
    let magic = opts.network.magic();
    handshake(&mut stream, magic, opts.connect_timeout)?;
    emit(
        &opts.log,
        format!("gossip_estab call_site=gossip_peer peer={peer}"),
    );
    write_message(&mut stream, magic, "sendheaders", &[])?;
    if learn {
        write_message(&mut stream, magic, "getaddr", &[])?;
    }
    let mut waiting_pong: Option<Instant> = None;
    let mut last_ping = Instant::now();
    let mut synced = false;
    // A batch of exactly MAX_HEADERS asks for more. Silence after that batch,
    // when the tip header is recent, means the peer has nothing newer. The
    // seed Observe is that tip. It does not wait for the next block.
    let mut asked_for_more = false;
    send_getheaders(opts, shared, &mut stream)?;
    loop {
        let read_for = if synced {
            opts.ping_interval
        } else {
            opts.connect_timeout
        };
        match read_message(&mut stream, magic, read_for) {
            Ok(message) => {
                waiting_pong = None;
                if message.command == "ping" {
                    let nonce = message.payload;
                    write_message(&mut stream, magic, "pong", &nonce)?;
                    continue;
                }
                if learn && message.command == "addr" {
                    if let Ok(addrs) = parse_addr_payload(&message.payload) {
                        for addr in addrs {
                            if usable_peer_addr(addr) && !learned.contains(&addr) {
                                learned.push(addr);
                            }
                        }
                    }
                    continue;
                }
                if message.command == "headers" {
                    match apply_headers(opts, shared, &message.payload)? {
                        HeadersApply::Reorg => return Ok(SessionEnd::Reorg),
                        HeadersApply::Gap => {
                            asked_for_more = false;
                            send_getheaders(opts, shared, &mut stream)?;
                        }
                        HeadersApply::Batch { count } => {
                            if !synced {
                                if count < MAX_HEADERS {
                                    mark_live(shared);
                                    synced = true;
                                    asked_for_more = false;
                                } else {
                                    asked_for_more = true;
                                    send_getheaders(opts, shared, &mut stream)?;
                                }
                            }
                        }
                    }
                    continue;
                }
            }
            Err("timeout") if !synced && asked_for_more && tip_is_recent(shared) => {
                mark_live(shared);
                synced = true;
                asked_for_more = false;
            }
            Err("timeout") if !synced => return Err("peer_silence"),
            Err("timeout") => {
                // One outstanding ping, as Bitcoin Core does. A new ping must not
                // reset the silence clock while the previous pong is missing.
                if let Some(since) = waiting_pong {
                    if since.elapsed() >= opts.silence_timeout {
                        return Err("peer_silence");
                    }
                } else if last_ping.elapsed() >= opts.ping_interval {
                    let nonce = random_u64().to_le_bytes();
                    write_message(&mut stream, magic, "ping", &nonce)?;
                    waiting_pong = Some(Instant::now());
                    last_ping = Instant::now();
                }
            }
            Err(class) => return Err(class),
        }
    }
}

fn mark_live(shared: &Arc<(Mutex<Shared>, Condvar)>) {
    let (lock, cv) = &**shared;
    let mut guard = lock.lock().unwrap_or_else(|err| err.into_inner());
    guard.live = true;
    cv.notify_all();
}

/// The tip header is close to now, so this is the peer's tip rather than an
/// initial-sync cursor from years ago. A header mined minutes ago still counts.
/// A historical header does not: silence during that sync is a stall, and the
/// session reconnects instead of opening stage 0 on the cursor.
fn tip_is_recent(shared: &Arc<(Mutex<Shared>, Condvar)>) -> bool {
    let (lock, _) = &**shared;
    let guard = lock.lock().unwrap_or_else(|err| err.into_inner());
    if guard.chain.tip_height == 0 {
        return false;
    }
    let Some(node) = guard.chain.nodes.get(&guard.chain.tip) else {
        return false;
    };
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0);
    let n_time = u64::from(node.n_time);
    const DAY: u64 = 24 * 60 * 60;
    const TWO_HOURS: u64 = 2 * 60 * 60;
    now.saturating_add(TWO_HOURS) >= n_time && n_time.saturating_add(DAY) >= now
}

fn send_getheaders(
    opts: &ClockOpts,
    shared: &Arc<(Mutex<Shared>, Condvar)>,
    stream: &mut TcpStream,
) -> Result<(), &'static str> {
    let (lock, _) = &**shared;
    let guard = lock.lock().unwrap_or_else(|err| err.into_inner());
    let locator = guard.chain.tip;
    drop(guard);
    let mut payload = Vec::with_capacity(4 + 1 + 32 + 32);
    payload.extend_from_slice(&(PROTOCOL_VERSION as u32).to_le_bytes());
    payload.push(1);
    payload.extend_from_slice(&locator);
    payload.extend_from_slice(&[0u8; 32]);
    write_message(stream, opts.network.magic(), "getheaders", &payload)
}

enum HeadersApply {
    Reorg,
    Gap,
    Batch { count: usize },
}

fn apply_headers(
    opts: &ClockOpts,
    shared: &Arc<(Mutex<Shared>, Condvar)>,
    payload: &[u8],
) -> Result<HeadersApply, &'static str> {
    let headers = parse_headers(payload).map_err(|_| "peer_silence")?;
    let count = headers.len();
    let (lock, cv) = &**shared;
    let mut guard = lock.lock().unwrap_or_else(|err| err.into_inner());
    let mut gap = false;
    for header in headers {
        match guard.chain.apply(&header) {
            Apply::Reorg => {
                guard.reorg = true;
                guard.live = false;
                cv.notify_all();
                return Ok(HeadersApply::Reorg);
            }
            Apply::Gap { header, prev } => {
                gap = true;
                let line = format!(
                    "header_skip call_site=gossip_peer missed_prev={} tip_height={} header={}",
                    display_hex(&prev),
                    guard.chain.tip_height,
                    display_hex(&header)
                );
                emit(&opts.log, line);
            }
            Apply::BadPow { header } => {
                let line = format!(
                    "header_reject call_site=gossip_peer reason=pow header={}",
                    display_hex(&header)
                );
                emit(&opts.log, line);
                return Err("peer_silence");
            }
            Apply::NewTip => {
                if guard.live {
                    cv.notify_all();
                }
            }
            Apply::Known | Apply::Side => {}
        }
    }
    if gap {
        Ok(HeadersApply::Gap)
    } else {
        Ok(HeadersApply::Batch { count })
    }
}

fn emit(log: &Option<Arc<Mutex<Vec<String>>>>, line: String) {
    eprintln!("{line}");
    crate::note_ops_line(&line);
    if let Some(log) = log {
        log.lock().unwrap_or_else(|err| err.into_inner()).push(line);
    }
}

fn handshake(
    stream: &mut TcpStream,
    magic: [u8; 4],
    timeout: Duration,
) -> Result<(), &'static str> {
    write_message(stream, magic, "version", &version_payload(0))?;
    let mut got_version = false;
    let mut got_verack = false;
    let deadline = Instant::now() + timeout;
    while !got_version || !got_verack {
        let left = deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return Err("peer_silence");
        }
        let message = match read_message(stream, magic, left) {
            Ok(message) => message,
            Err("timeout") => return Err("peer_silence"),
            Err(class) => return Err(class),
        };
        if message.command == "version" {
            got_version = true;
            write_message(stream, magic, "verack", &[])?;
        } else if message.command == "verack" {
            got_verack = true;
        } else if message.command == "ping" {
            write_message(stream, magic, "pong", &message.payload)?;
        }
    }
    Ok(())
}

fn connect_peer(peer: &str, timeout: Duration) -> Result<TcpStream, &'static str> {
    let addrs: Vec<SocketAddr> = match peer.to_socket_addrs() {
        Ok(iter) => iter.collect(),
        Err(_) => return Err("dns"),
    };
    if addrs.is_empty() {
        return Err("dns");
    }
    let mut last = "tcp_refuse";
    for addr in addrs {
        match TcpStream::connect_timeout(&addr, timeout) {
            Ok(stream) => return Ok(stream),
            Err(err) => last = io_kind_class(err.kind()).unwrap_or("tcp_refuse"),
        }
    }
    Err(last)
}

fn lookup_mainnet_seed(seed: &str) -> Result<Vec<SocketAddr>, ()> {
    let query = format!("{seed}:{MAINNET_DNS_PORT}");
    let addrs: Vec<SocketAddr> = query.to_socket_addrs().map_err(|_| ())?.collect();
    if addrs.is_empty() {
        Err(())
    } else {
        Ok(addrs)
    }
}

/// Look up every mainnet seed. A failed lookup is discarded. An address with
/// port 0, an unspecified address, or a multicast address is discarded.
/// The remaining addresses are the dial list. An empty result is not a start
/// failure by itself: the caller retries.
fn resolve_mainnet_seeds(
    lookup: &dyn Fn(&str) -> Result<Vec<SocketAddr>, ()>,
    log: &Option<Arc<Mutex<Vec<String>>>>,
) -> Vec<SocketAddr> {
    let mut out = Vec::new();
    for seed in MAINNET_DNS_SEEDS {
        let addrs = match lookup(seed) {
            Ok(addrs) if !addrs.is_empty() => addrs,
            _ => {
                emit(
                    log,
                    format!("gossip_seed_skip call_site=gossip_peer seed={seed} reason=dns"),
                );
                continue;
            }
        };
        for addr in addrs {
            if !usable_peer_addr(addr) {
                emit(
                    log,
                    format!(
                        "gossip_seed_skip call_site=gossip_peer peer={addr} reason=bad_address"
                    ),
                );
                continue;
            }
            if !out.contains(&addr) {
                out.push(addr);
            }
        }
    }
    out
}

fn usable_peer_addr(addr: SocketAddr) -> bool {
    let ip = addr.ip();
    addr.port() != 0 && !ip.is_unspecified() && !ip.is_multicast()
}

/// Bitcoin `addr` payload. A truncated body is discarded whole. One bad
/// address inside a readable body is omitted by [`usable_peer_addr`].
fn parse_addr_payload(payload: &[u8]) -> Result<Vec<SocketAddr>, ()> {
    let mut cursor = Cursor::new(payload);
    let count = cursor.varint()?;
    if count > MAX_ADDR {
        return Err(());
    }
    let mut addrs = Vec::with_capacity(count as usize);
    for _ in 0..count {
        let _time = cursor.take(4)?;
        let _services = cursor.take(8)?;
        let ip_bytes = cursor.take(16)?;
        let port_bytes = cursor.take(2)?;
        let mut ip = [0u8; 16];
        ip.copy_from_slice(ip_bytes);
        let port = u16::from_be_bytes(port_bytes.try_into().unwrap());
        addrs.push(socket_from_addr_bytes(ip, port));
    }
    if cursor.rest().is_empty() {
        Ok(addrs)
    } else {
        Err(())
    }
}

fn socket_from_addr_bytes(ip: [u8; 16], port: u16) -> SocketAddr {
    let addr = if ip[..10].iter().all(|byte| *byte == 0) && ip[10] == 0xff && ip[11] == 0xff {
        IpAddr::V4(Ipv4Addr::new(ip[12], ip[13], ip[14], ip[15]))
    } else {
        IpAddr::V6(Ipv6Addr::from(ip))
    };
    SocketAddr::new(addr, port)
}

#[derive(Clone, Copy)]
struct Node {
    prev: [u8; 32],
    height: u64,
    /// Bitcoin header `nTime`, unix seconds.
    n_time: u32,
}

struct Chain {
    nodes: HashMap<[u8; 32], Node>,
    tip: [u8; 32],
    tip_height: u64,
    published: Vec<[u8; 32]>,
}

enum Apply {
    Known,
    Side,
    NewTip,
    Gap { header: [u8; 32], prev: [u8; 32] },
    Reorg,
    BadPow { header: [u8; 32] },
}

impl Chain {
    fn new(network: GossipNetwork) -> Self {
        let mut chain = Self {
            nodes: HashMap::new(),
            tip: [0u8; 32],
            tip_height: 0,
            published: Vec::new(),
        };
        let genesis = network.genesis_header();
        let hash = sha256d(&genesis);
        chain.nodes.insert(
            hash,
            Node {
                prev: prev_of(&genesis),
                height: 0,
                n_time: n_time_of(&genesis),
            },
        );
        chain.tip = hash;
        chain
    }

    fn publish(&mut self, hash: [u8; 32]) {
        if self.published.last().copied() != Some(hash) {
            self.published.push(hash);
        }
    }

    fn apply(&mut self, header: &[u8; 80]) -> Apply {
        let hash = sha256d(header);
        if !proof_of_work(header, &hash) {
            return Apply::BadPow { header: hash };
        }
        if self.nodes.contains_key(&hash) {
            return Apply::Known;
        }
        let prev = prev_of(header);
        let Some(parent) = self.nodes.get(&prev).copied() else {
            return Apply::Gap { header: hash, prev };
        };
        let height = parent.height + 1;
        self.nodes.insert(
            hash,
            Node {
                prev,
                height,
                n_time: n_time_of(header),
            },
        );
        if height > self.tip_height {
            if self
                .published
                .iter()
                .any(|old| !self.is_ancestor(*old, hash))
            {
                return Apply::Reorg;
            }
            self.tip = hash;
            self.tip_height = height;
            return Apply::NewTip;
        }
        Apply::Side
    }

    fn is_ancestor(&self, ancestor: [u8; 32], of: [u8; 32]) -> bool {
        let mut cursor = of;
        loop {
            if cursor == ancestor {
                return true;
            }
            let Some(node) = self.nodes.get(&cursor) else {
                return false;
            };
            if node.height == 0 {
                return false;
            }
            cursor = node.prev;
        }
    }
}

fn prev_of(header: &[u8; 80]) -> [u8; 32] {
    let mut prev = [0u8; 32];
    prev.copy_from_slice(&header[4..36]);
    prev
}

/// Bitcoin header `nTime` (unix seconds), bytes 68..72 little-endian.
fn n_time_of(header: &[u8; 80]) -> u32 {
    u32::from_le_bytes(header[68..72].try_into().unwrap())
}

fn sha256d(bytes: &[u8]) -> [u8; 32] {
    sha256(&sha256(bytes))
}

fn display_hex(natural: &[u8; 32]) -> String {
    let mut display = *natural;
    display.reverse();
    encode_hex32(&display)
}

/// Bitcoin compact `nBits` target. `None` when the compact form is negative or overflows.
fn target_from_bits(bits: u32) -> Option<[u8; 32]> {
    if bits & 0x0080_0000 != 0 {
        return None;
    }
    let exponent = (bits >> 24) as usize;
    let mantissa = bits & 0x007f_ffff;
    if exponent > 32 {
        return None;
    }
    let mut target = [0u8; 32];
    if mantissa == 0 || exponent == 0 {
        return Some(target);
    }
    if exponent <= 3 {
        let word = mantissa >> (8 * (3 - exponent));
        target[0] = (word & 0xff) as u8;
        if exponent > 1 {
            target[1] = ((word >> 8) & 0xff) as u8;
        }
        if exponent > 2 {
            target[2] = ((word >> 16) & 0xff) as u8;
        }
        return Some(target);
    }
    let start = exponent - 3;
    if start + 2 >= 32 {
        return None;
    }
    target[start] = (mantissa & 0xff) as u8;
    target[start + 1] = ((mantissa >> 8) & 0xff) as u8;
    target[start + 2] = ((mantissa >> 16) & 0xff) as u8;
    Some(target)
}

fn proof_of_work(header: &[u8; 80], hash: &[u8; 32]) -> bool {
    let bits = u32::from_le_bytes(header[72..76].try_into().unwrap());
    let Some(target) = target_from_bits(bits) else {
        return false;
    };
    for i in (0..32).rev() {
        if hash[i] < target[i] {
            return true;
        }
        if hash[i] > target[i] {
            return false;
        }
    }
    true
}

fn encode_header(
    version: u32,
    prev: [u8; 32],
    merkle: [u8; 32],
    time: u32,
    bits: u32,
    nonce: u32,
) -> [u8; 80] {
    let mut header = [0u8; 80];
    header[0..4].copy_from_slice(&version.to_le_bytes());
    header[4..36].copy_from_slice(&prev);
    header[36..68].copy_from_slice(&merkle);
    header[68..72].copy_from_slice(&time.to_le_bytes());
    header[72..76].copy_from_slice(&bits.to_le_bytes());
    header[76..80].copy_from_slice(&nonce.to_le_bytes());
    header
}

const GENESIS_MERKLE: [u8; 32] = [
    0x3b, 0xa3, 0xed, 0xfd, 0x7a, 0x7b, 0x12, 0xb2, 0x7a, 0xc7, 0x2c, 0x3e, 0x67, 0x76, 0x8f, 0x61,
    0x7f, 0xc8, 0x1b, 0xc3, 0x88, 0x8a, 0x51, 0x32, 0x3a, 0x9f, 0xb8, 0xaa, 0x4b, 0x1e, 0x5e, 0x4a,
];

impl GossipNetwork {
    fn magic(self) -> [u8; 4] {
        match self {
            Self::Mainnet => [0xf9, 0xbe, 0xb4, 0xd9],
            Self::Regtest => [0xfa, 0xbf, 0xb5, 0xda],
        }
    }

    fn genesis_header(self) -> [u8; 80] {
        match self {
            Self::Mainnet => encode_header(
                1,
                [0u8; 32],
                GENESIS_MERKLE,
                1_231_006_505,
                0x1d00_ffff,
                2_083_236_893,
            ),
            Self::Regtest => {
                encode_header(1, [0u8; 32], GENESIS_MERKLE, 1_296_688_602, 0x207f_ffff, 2)
            }
        }
    }

    fn extension_bits(self) -> u32 {
        match self {
            Self::Mainnet => 0x1d00_ffff,
            Self::Regtest => 0x207f_ffff,
        }
    }
}

fn mine_extension(prev: [u8; 32], bits: u32, time: u32) -> [u8; 80] {
    let mut time = time;
    loop {
        for nonce in 0..100_000u32 {
            let header = encode_header(1, prev, [0u8; 32], time, bits, nonce);
            let hash = sha256d(&header);
            if proof_of_work(&header, &hash) {
                return header;
            }
        }
        time = time.wrapping_add(1);
    }
}

struct Message {
    command: String,
    payload: Vec<u8>,
}

fn write_message(
    stream: &mut TcpStream,
    magic: [u8; 4],
    command: &str,
    payload: &[u8],
) -> Result<(), &'static str> {
    let mut command_bytes = [0u8; 12];
    let raw = command.as_bytes();
    if raw.len() > 12 {
        return Err("peer_silence");
    }
    command_bytes[..raw.len()].copy_from_slice(raw);
    let sum = sha256d(payload);
    let mut frame = Vec::with_capacity(24 + payload.len());
    frame.extend_from_slice(&magic);
    frame.extend_from_slice(&command_bytes);
    frame.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    frame.extend_from_slice(&sum[..4]);
    frame.extend_from_slice(payload);
    stream
        .write_all(&frame)
        .map_err(|err| io_kind_class(err.kind()).unwrap_or("peer_silence"))
}

fn read_message(
    stream: &mut TcpStream,
    magic: [u8; 4],
    timeout: Duration,
) -> Result<Message, &'static str> {
    stream
        .set_read_timeout(Some(timeout))
        .map_err(|_| "peer_silence")?;
    let mut head = [0u8; 24];
    read_exact(stream, &mut head)?;
    if head[0..4] != magic {
        return Err("peer_silence");
    }
    let length = u32::from_le_bytes(head[16..20].try_into().unwrap()) as usize;
    if length > MAX_MESSAGE {
        return Err("peer_silence");
    }
    let mut payload = vec![0u8; length];
    if length > 0 {
        read_exact(stream, &mut payload)?;
    }
    let sum = sha256d(&payload);
    if head[20..24] != sum[..4] {
        return Err("peer_silence");
    }
    let command = head[4..16]
        .iter()
        .take_while(|byte| **byte != 0)
        .map(|byte| *byte as char)
        .collect();
    Ok(Message { command, payload })
}

fn read_exact(stream: &mut TcpStream, buf: &mut [u8]) -> Result<(), &'static str> {
    let mut filled = 0;
    while filled < buf.len() {
        match stream.read(&mut buf[filled..]) {
            Ok(0) => return Err("peer_silence"),
            Ok(n) => filled += n,
            Err(err)
                if err.kind() == std::io::ErrorKind::TimedOut
                    || err.kind() == std::io::ErrorKind::WouldBlock =>
            {
                return Err("timeout");
            }
            Err(err) if err.kind() == std::io::ErrorKind::Interrupted => {}
            Err(err) => return Err(io_kind_class(err.kind()).unwrap_or("peer_silence")),
        }
    }
    Ok(())
}

fn version_payload(start_height: i32) -> Vec<u8> {
    let mut payload = Vec::with_capacity(86);
    payload.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
    payload.extend_from_slice(&0u64.to_le_bytes());
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs() as i64)
        .unwrap_or(0);
    payload.extend_from_slice(&now.to_le_bytes());
    payload.extend_from_slice(&[0u8; 26]);
    payload.extend_from_slice(&[0u8; 26]);
    payload.extend_from_slice(&random_u64().to_le_bytes());
    let agent = USER_AGENT.as_bytes();
    payload.push(agent.len() as u8);
    payload.extend_from_slice(agent);
    payload.extend_from_slice(&start_height.to_le_bytes());
    payload.push(0);
    payload
}

fn random_u64() -> u64 {
    let mut bytes = [0u8; 8];
    if getrandom::getrandom(&mut bytes).is_err() {
        return 1;
    }
    u64::from_le_bytes(bytes)
}

fn parse_headers(payload: &[u8]) -> Result<Vec<[u8; 80]>, ()> {
    let mut cursor = Cursor::new(payload);
    let count = cursor.varint()?;
    if count > MAX_HEADERS as u64 {
        return Err(());
    }
    let mut headers = Vec::with_capacity(count as usize);
    for _ in 0..count {
        let bytes = cursor.take(80)?;
        let mut header = [0u8; 80];
        header.copy_from_slice(bytes);
        let txs = cursor.varint()?;
        if txs != 0 {
            return Err(());
        }
        headers.push(header);
    }
    if cursor.rest().is_empty() {
        Ok(headers)
    } else {
        Err(())
    }
}

struct Cursor<'a> {
    buf: &'a [u8],
    at: usize,
}

impl<'a> Cursor<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, at: 0 }
    }

    fn rest(&self) -> &'a [u8] {
        &self.buf[self.at..]
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8], ()> {
        if self.at + n > self.buf.len() {
            return Err(());
        }
        let slice = &self.buf[self.at..self.at + n];
        self.at += n;
        Ok(slice)
    }

    fn varint(&mut self) -> Result<u64, ()> {
        let first = *self.take(1)?.first().ok_or(())?;
        match first {
            0xff => {
                let bytes = self.take(8)?;
                Ok(u64::from_le_bytes(bytes.try_into().unwrap()))
            }
            0xfe => {
                let bytes = self.take(4)?;
                Ok(u32::from_le_bytes(bytes.try_into().unwrap()) as u64)
            }
            0xfd => {
                let bytes = self.take(2)?;
                Ok(u16::from_le_bytes(bytes.try_into().unwrap()) as u64)
            }
            value => Ok(u64::from(value)),
        }
    }
}

fn push_varint(out: &mut Vec<u8>, value: u64) {
    if value < 0xfd {
        out.push(value as u8);
    } else if value <= u64::from(u16::MAX) {
        out.push(0xfd);
        out.extend_from_slice(&(value as u16).to_le_bytes());
    } else if value <= u64::from(u32::MAX) {
        out.push(0xfe);
        out.extend_from_slice(&(value as u32).to_le_bytes());
    } else {
        out.push(0xff);
        out.extend_from_slice(&value.to_le_bytes());
    }
}

fn headers_payload(headers: &[[u8; 80]]) -> Vec<u8> {
    let mut payload = Vec::new();
    push_varint(&mut payload, headers.len() as u64);
    for header in headers {
        payload.extend_from_slice(header);
        payload.push(0);
    }
    payload
}

/// Lab synth peer. Speaks regtest wire magic headers only (header network
/// magic for gossip). That magic is not a Bitcoin Core plant and not a tip
/// clock. Does not mine mainnet.
///
/// Accepts any number of concurrent tip subscribers. One cadence extends the
/// chain. Each extension is written to every subscriber that has sent
/// `sendheaders`. A closed socket is dropped and the other subscriptions stay up.
pub fn serve_synth(bind: &str, interval: Duration) -> std::io::Result<()> {
    let listener = TcpListener::bind(bind)?;
    listener.set_nonblocking(true)?;
    let network = GossipNetwork::Regtest;
    let chain = Arc::new(Mutex::new(vec![network.genesis_header()]));
    let mut next_mine = Instant::now() + interval;
    loop {
        if Instant::now() >= next_mine {
            extend_synth(&chain, network);
            next_mine = Instant::now() + interval;
        }
        match listener.accept() {
            Ok((stream, _)) => {
                let chain = Arc::clone(&chain);
                thread::spawn(move || {
                    if let Err(err) = synth_connection(stream, network, &chain) {
                        if !dropped_subscriber(&err) {
                            eprintln!("satoshi-hike-gossip-synth: {err}");
                        }
                    }
                });
            }
            Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(20));
            }
            Err(err) => return Err(err),
        }
    }
}

fn extend_synth(chain: &Mutex<Vec<[u8; 80]>>, network: GossipNetwork) {
    let prev = {
        let headers = chain.lock().unwrap_or_else(|err| err.into_inner());
        sha256d(headers.last().expect("synth chain starts at genesis"))
    };
    let time = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs() as u32)
        .unwrap_or(0);
    let header = mine_extension(prev, network.extension_bits(), time);
    chain
        .lock()
        .unwrap_or_else(|err| err.into_inner())
        .push(header);
}

fn dropped_subscriber(err: &std::io::Error) -> bool {
    matches!(
        err.kind(),
        std::io::ErrorKind::UnexpectedEof
            | std::io::ErrorKind::ConnectionReset
            | std::io::ErrorKind::ConnectionAborted
            | std::io::ErrorKind::BrokenPipe
    )
}

fn peer_io(err: &'static str) -> std::io::Error {
    let kind = match err {
        "peer_silence" | "peer closed" => std::io::ErrorKind::UnexpectedEof,
        "tcp_reset" => std::io::ErrorKind::ConnectionReset,
        _ => std::io::ErrorKind::Other,
    };
    std::io::Error::new(kind, err)
}

fn synth_connection(
    mut stream: TcpStream,
    network: GossipNetwork,
    chain: &Mutex<Vec<[u8; 80]>>,
) -> std::io::Result<()> {
    stream.set_nonblocking(false)?;
    let _ = stream.set_nodelay(true);
    let magic = network.magic();
    let _ = stream.set_read_timeout(Some(Duration::from_millis(200)));
    let mut got_version = false;
    let mut announced = 0usize;
    let mut sendheaders = false;
    loop {
        push_synth_tip(&mut stream, magic, chain, sendheaders, &mut announced)?;
        match read_message(&mut stream, magic, Duration::from_millis(200)) {
            Ok(message) if message.command == "version" && !got_version => {
                got_version = true;
                let height = {
                    let headers = chain.lock().unwrap_or_else(|err| err.into_inner());
                    (headers.len() as i32) - 1
                };
                write_message(&mut stream, magic, "version", &version_payload(height))
                    .map_err(peer_io)?;
                write_message(&mut stream, magic, "verack", &[]).map_err(peer_io)?;
            }
            Ok(message) if message.command == "verack" => {}
            Ok(message) if message.command == "sendheaders" => {
                sendheaders = true;
                let headers = chain.lock().unwrap_or_else(|err| err.into_inner());
                announced = headers.len() - 1;
            }
            Ok(message) if message.command == "ping" => {
                write_message(&mut stream, magic, "pong", &message.payload).map_err(peer_io)?;
            }
            Ok(message) if message.command == "getheaders" => {
                let (reply, tip_index) = {
                    let headers = chain.lock().unwrap_or_else(|err| err.into_inner());
                    (headers_after(&headers, &message.payload), headers.len() - 1)
                };
                write_message(&mut stream, magic, "headers", &headers_payload(&reply))
                    .map_err(peer_io)?;
                announced = tip_index;
            }
            Ok(_) => {}
            Err("timeout") => {}
            Err(other) => return Err(peer_io(other)),
        }
    }
}

/// Write headers mined since `announced` to one subscriber.
///
/// The chain lock is not held across the write, so one slow socket does not
/// stall the cadence or the other subscribers. A write error drops this socket.
fn push_synth_tip(
    stream: &mut TcpStream,
    magic: [u8; 4],
    chain: &Mutex<Vec<[u8; 80]>>,
    sendheaders: bool,
    announced: &mut usize,
) -> std::io::Result<()> {
    if !sendheaders {
        return Ok(());
    }
    let batch = {
        let headers = chain.lock().unwrap_or_else(|err| err.into_inner());
        if *announced + 1 >= headers.len() {
            None
        } else {
            let end = (*announced + 1 + MAX_HEADERS).min(headers.len());
            Some((headers[*announced + 1..end].to_vec(), end - 1))
        }
    };
    let Some((fresh, announced_to)) = batch else {
        return Ok(());
    };
    write_message(stream, magic, "headers", &headers_payload(&fresh)).map_err(peer_io)?;
    *announced = announced_to;
    Ok(())
}

fn headers_after(headers: &[[u8; 80]], getheaders: &[u8]) -> Vec<[u8; 80]> {
    let Ok(locators) = parse_locators(getheaders) else {
        return Vec::new();
    };
    let hashes: Vec<[u8; 32]> = headers.iter().map(|header| sha256d(header)).collect();
    let mut start = 0usize;
    for locator in locators {
        if let Some(index) = hashes.iter().position(|hash| *hash == locator) {
            start = index + 1;
            break;
        }
    }
    headers
        .iter()
        .skip(start)
        .take(MAX_HEADERS)
        .copied()
        .collect()
}

fn parse_locators(payload: &[u8]) -> Result<Vec<[u8; 32]>, ()> {
    let mut cursor = Cursor::new(payload);
    let _version = cursor.take(4)?;
    let count = cursor.varint()?;
    if count > 64 {
        return Err(());
    }
    let mut locators = Vec::with_capacity(count as usize);
    for _ in 0..count {
        let bytes = cursor.take(32)?;
        let mut hash = [0u8; 32];
        hash.copy_from_slice(bytes);
        locators.push(hash);
    }
    Ok(locators)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hex::encode_hex32;
    use std::sync::atomic::{AtomicBool, Ordering};

    fn regtest_chain(n: usize) -> Vec<[u8; 80]> {
        let network = GossipNetwork::Regtest;
        let mut headers = vec![network.genesis_header()];
        for i in 0..n {
            let prev = sha256d(headers.last().unwrap());
            headers.push(mine_extension(
                prev,
                network.extension_bits(),
                1_700_000_000 + i as u32,
            ));
        }
        headers
    }

    struct LabClock {
        clock: HeaderClock,
        log: Arc<Mutex<Vec<String>>>,
    }

    fn start_lab(peer: &str, ping: Duration, silence: Duration) -> LabClock {
        let log = Arc::new(Mutex::new(Vec::new()));
        let clock = HeaderClock::spawn(ClockOpts {
            dial: Dial::Named(peer.to_owned()),
            network: GossipNetwork::Regtest,
            connect_timeout: Duration::from_secs(2),
            ping_interval: ping,
            silence_timeout: silence,
            skip_pause: true,
            log: Some(Arc::clone(&log)),
        });
        LabClock { clock, log }
    }

    fn wait_observe(clock: &HeaderClock) -> ([u8; 32], u64) {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let (lock, _) = &*clock.shared;
            let guard = lock.lock().unwrap_or_else(|err| err.into_inner());
            if guard.reorg {
                drop(guard);
                panic!("reorg before observe");
            }
            if guard.live {
                drop(guard);
                return clock.observe().expect("live tip");
            }
            drop(guard);
            if Instant::now() > deadline {
                panic!("clock did not sync");
            }
            thread::sleep(Duration::from_millis(10));
        }
    }

    #[test]
    fn genesis_hashes_and_natural_order_match_bitcoin() {
        let mainnet = GossipNetwork::Mainnet.genesis_header();
        let main_hash = sha256d(&mainnet);
        assert_eq!(
            display_hex(&main_hash),
            "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"
        );
        assert!(proof_of_work(&mainnet, &main_hash));
        assert_ne!(main_hash[0], main_hash[31]);
        let regtest = GossipNetwork::Regtest.genesis_header();
        assert_eq!(
            display_hex(&sha256d(&regtest)),
            "0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206"
        );
        assert!(proof_of_work(&regtest, &sha256d(&regtest)));
    }

    #[test]
    fn gap_is_not_reorg_and_a_taller_fork_of_a_published_tip_is() {
        let mut chain = Chain::new(GossipNetwork::Regtest);
        let h1 = mine_extension(chain.tip, GossipNetwork::Regtest.extension_bits(), 50);
        assert!(matches!(chain.apply(&h1), Apply::NewTip));
        assert_eq!(chain.tip_height, 1);
        chain.publish(chain.tip);
        let unlinked = mine_extension([0x11; 32], GossipNetwork::Regtest.extension_bits(), 51);
        assert!(matches!(chain.apply(&unlinked), Apply::Gap { .. }));
        assert_eq!(chain.tip_height, 1);
        let _sibling = mine_extension(sha256d(&h1), GossipNetwork::Regtest.extension_bits(), 52);
        let genesis_tip = {
            let mut fresh = Chain::new(GossipNetwork::Regtest);
            let a = mine_extension(fresh.tip, GossipNetwork::Regtest.extension_bits(), 60);
            fresh.apply(&a);
            let b = mine_extension(fresh.tip, GossipNetwork::Regtest.extension_bits(), 61);
            fresh.apply(&b);
            fresh.publish(fresh.tip);
            let fork = mine_extension(sha256d(&a), GossipNetwork::Regtest.extension_bits(), 62);
            assert!(matches!(fresh.apply(&fork), Apply::Side));
            let taller =
                mine_extension(sha256d(&fork), GossipNetwork::Regtest.extension_bits(), 63);
            assert!(matches!(fresh.apply(&taller), Apply::Reorg));
            fresh.tip_height
        };
        assert_eq!(genesis_tip, 2);
    }

    #[test]
    fn tip_tick_carries_header_ntime_in_unix_seconds() {
        let genesis = HeaderClock::from_synced_chain(Chain::new(GossipNetwork::Mainnet));
        let tick = genesis.headers_since(None).unwrap();
        assert_eq!(tick.len(), 1);
        assert_eq!(tick[0].height, 0);
        // Mainnet genesis `nTime`. Read from the header bytes, not a wall clock.
        assert_eq!(tick[0].n_time, 1_231_006_505);

        let mut chain = Chain::new(GossipNetwork::Regtest);
        let parent = chain.tip;
        let header = mine_extension(
            parent,
            GossipNetwork::Regtest.extension_bits(),
            1_700_000_123,
        );
        let n_time = n_time_of(&header);
        assert!(n_time >= 1_700_000_123);
        assert!(matches!(chain.apply(&header), Apply::NewTip));
        let clock = HeaderClock::from_synced_chain(chain);
        let ticks = clock.headers_since(Some(parent)).unwrap();
        assert_eq!(ticks.len(), 1);
        assert_eq!(ticks[0].n_time, n_time);
        assert_eq!(u64::from(ticks[0].n_time), u64::from(n_time));
    }

    #[test]
    fn headers_since_includes_the_header_the_ask_is_due_on() {
        let (clock, start, start_height) = regtest_clock_past_genesis(6);
        let only_newest = clock.headers_since(None).unwrap();
        assert_eq!(only_newest.len(), 1);
        assert_eq!(only_newest[0].height, start_height + 6);

        let ticks = clock.headers_since(Some(start)).unwrap();
        let heights: Vec<u64> = ticks.iter().map(|tick| tick.height).collect();
        assert_eq!(
            heights,
            (start_height + 1..=start_height + 6).collect::<Vec<_>>()
        );
        assert_eq!(ticks[3].height, start_height + 4);
        assert_ne!(ticks[3].hash, ticks.last().unwrap().hash);

        let again = clock
            .headers_since(Some(ticks.last().unwrap().hash))
            .unwrap();
        assert_eq!(again.len(), 1);
        assert_eq!(again[0].hash, ticks.last().unwrap().hash);
    }

    fn scripted(after_sync: ScriptAfter) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        thread::spawn(move || {
            let mut sessions = 0usize;
            loop {
                let Ok((stream, _)) = listener.accept() else {
                    break;
                };
                sessions += 1;
                let _ = serve_script(stream, sessions, &after_sync);
                if sessions >= after_sync.sessions() {
                    break;
                }
            }
        });
        format!("{addr}")
    }

    enum ScriptAfter {
        /// Session 1 stays up until `release`, then closes. Session 2 extends the chain and stays up.
        Close {
            release: Arc<AtomicBool>,
        },
        /// After the test publishes the height-1 tip, push a taller fork from genesis.
        Reorg {
            published: Arc<AtomicBool>,
        },
        GapThenExtend,
        SilencePong,
    }

    impl ScriptAfter {
        fn sessions(&self) -> usize {
            match self {
                Self::Close { .. } => 2,
                Self::Reorg { .. } | Self::GapThenExtend | Self::SilencePong => 1,
            }
        }
    }

    fn serve_script(
        mut stream: TcpStream,
        session: usize,
        after: &ScriptAfter,
    ) -> Result<(), &'static str> {
        let network = GossipNetwork::Regtest;
        let magic = network.magic();
        let mut chain = match after {
            ScriptAfter::Close { .. } if session >= 2 => regtest_chain(2),
            _ => regtest_chain(1),
        };
        let _ = stream.set_nodelay(true);
        let mut got_version = false;
        let mut pushed = false;
        let mut fork_sent = false;
        loop {
            let message = match read_message(&mut stream, magic, Duration::from_millis(40)) {
                Ok(message) => message,
                Err("timeout") => {
                    if finish_script(
                        after,
                        session,
                        pushed,
                        &mut fork_sent,
                        &mut stream,
                        magic,
                        &chain,
                    )? {
                        return Ok(());
                    }
                    continue;
                }
                Err("peer_silence") => return Ok(()),
                Err(err) => return Err(err),
            };
            if message.command == "version" && !got_version {
                got_version = true;
                write_message(&mut stream, magic, "version", &version_payload(1))?;
                write_message(&mut stream, magic, "verack", &[])?;
            } else if message.command == "ping" {
                if !matches!(after, ScriptAfter::SilencePong) {
                    write_message(&mut stream, magic, "pong", &message.payload)?;
                }
            } else if message.command == "getheaders" {
                let reply = headers_after(&chain, &message.payload);
                write_message(&mut stream, magic, "headers", &headers_payload(&reply))?;
                if !pushed {
                    pushed = true;
                    push_scripted(after, &mut stream, magic, &mut chain)?;
                }
                if finish_script(
                    after,
                    session,
                    pushed,
                    &mut fork_sent,
                    &mut stream,
                    magic,
                    &chain,
                )? {
                    return Ok(());
                }
            }
        }
    }

    fn push_scripted(
        after: &ScriptAfter,
        stream: &mut TcpStream,
        magic: [u8; 4],
        chain: &mut Vec<[u8; 80]>,
    ) -> Result<(), &'static str> {
        if !matches!(after, ScriptAfter::GapThenExtend) {
            return Ok(());
        }
        let network = GossipNetwork::Regtest;
        let unlinked = mine_extension([0x22; 32], network.extension_bits(), 90);
        write_message(stream, magic, "headers", &headers_payload(&[unlinked]))?;
        let extension =
            mine_extension(sha256d(chain.last().unwrap()), network.extension_bits(), 91);
        chain.push(extension);
        write_message(stream, magic, "headers", &headers_payload(&[extension]))
    }

    /// `true` means the scripted peer should drop the socket.
    fn finish_script(
        after: &ScriptAfter,
        session: usize,
        pushed: bool,
        fork_sent: &mut bool,
        stream: &mut TcpStream,
        magic: [u8; 4],
        chain: &[[u8; 80]],
    ) -> Result<bool, &'static str> {
        if !pushed {
            return Ok(false);
        }
        match after {
            ScriptAfter::Close { release } if session == 1 && release.load(Ordering::SeqCst) => {
                Ok(true)
            }
            ScriptAfter::Reorg { published } if !*fork_sent && published.load(Ordering::SeqCst) => {
                *fork_sent = true;
                let network = GossipNetwork::Regtest;
                let sibling = mine_extension(sha256d(&chain[0]), network.extension_bits(), 80);
                let taller = mine_extension(sha256d(&sibling), network.extension_bits(), 81);
                write_message(
                    stream,
                    magic,
                    "headers",
                    &headers_payload(&[sibling, taller]),
                )?;
                Ok(false)
            }
            _ => Ok(false),
        }
    }

    #[test]
    fn subscribe_returns_natural_tip_then_peer_silence_recovers() {
        let release = Arc::new(AtomicBool::new(false));
        let peer = scripted(ScriptAfter::Close {
            release: Arc::clone(&release),
        });
        let lab = start_lab(&peer, Duration::from_secs(30), Duration::from_secs(30));
        let (first, height) = wait_observe(&lab.clock);
        assert_eq!(height, 1);
        {
            let lines = lab.log.lock().unwrap();
            assert!(
                lines.iter().any(|line| {
                    line == &format!("gossip_estab call_site=gossip_peer peer={peer}")
                }),
                "{lines:?}"
            );
        }
        let headers = regtest_chain(1);
        assert_eq!(first, sha256d(headers.last().unwrap()));
        assert_ne!(display_hex(&first), encode_hex32(&first));
        release.store(true, Ordering::SeqCst);
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let lines = lab.log.lock().unwrap();
            if lines
                .iter()
                .any(|line| line.contains("error_class=peer_silence"))
            {
                break;
            }
            drop(lines);
            if Instant::now() > deadline {
                panic!("no peer_silence line");
            }
            thread::sleep(Duration::from_millis(10));
        }
        let (_tip, height) = wait_observe(&lab.clock);
        assert_eq!(height, 2);
    }

    #[test]
    fn reorg_of_a_published_tip_fails_the_clock() {
        let published = Arc::new(AtomicBool::new(false));
        let peer = scripted(ScriptAfter::Reorg {
            published: Arc::clone(&published),
        });
        let lab = start_lab(&peer, Duration::from_secs(30), Duration::from_secs(30));
        let (_tip, height) = wait_observe(&lab.clock);
        assert_eq!(height, 1);
        published.store(true, Ordering::SeqCst);
        let deadline = Instant::now() + Duration::from_secs(5);
        let err = loop {
            match lab.clock.observe() {
                Err(err) => break err,
                Ok((_tip, 1)) if Instant::now() < deadline => {
                    thread::sleep(Duration::from_millis(10));
                }
                Ok((tip, height)) => {
                    panic!("tip moved without reorg {height} {}", display_hex(&tip))
                }
            }
        };
        assert!(err.message.contains("reorg"), "{}", err.message);
        let lines = lab.log.lock().unwrap();
        assert!(lines
            .iter()
            .all(|line| !line.contains("error_class=http_5xx")));
    }

    #[test]
    fn missed_pong_is_peer_silence() {
        let peer = scripted(ScriptAfter::SilencePong);
        let lab = start_lab(&peer, Duration::from_millis(50), Duration::from_millis(200));
        let (_tip, height) = wait_observe(&lab.clock);
        assert_eq!(height, 1);
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let lines = lab.log.lock().unwrap();
            if lines
                .iter()
                .any(|line| line.contains("error_class=peer_silence"))
            {
                break;
            }
            drop(lines);
            if Instant::now() > deadline {
                panic!("missed pong did not wait");
            }
            thread::sleep(Duration::from_millis(20));
        }
    }

    #[test]
    fn gapped_header_logs_header_skip_and_does_not_fail() {
        let peer = scripted(ScriptAfter::GapThenExtend);
        let lab = start_lab(&peer, Duration::from_secs(30), Duration::from_secs(30));
        let deadline = Instant::now() + Duration::from_secs(5);
        let height = loop {
            if let Ok((_tip, height)) = lab.clock.observe() {
                if height >= 2 {
                    break height;
                }
            }
            if Instant::now() > deadline {
                panic!("extension did not arrive");
            }
            thread::sleep(Duration::from_millis(10));
        };
        assert!(height >= 2);
        let lines = lab.log.lock().unwrap();
        assert!(
            lines.iter().any(|line| line.starts_with("header_skip ")),
            "{lines:?}"
        );
        assert!(lines.iter().all(|line| !line.contains("clock read failed")));
    }

    #[test]
    fn refused_peer_is_tcp_refuse() {
        let err = connect_peer("127.0.0.1:1", Duration::from_secs(2)).unwrap_err();
        assert_eq!(err, "tcp_refuse");
    }

    #[test]
    fn synth_peer_feeds_the_same_subscribe_path() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        drop(listener);
        let bind = addr.to_string();
        thread::spawn(move || {
            let _ = serve_synth(&bind, Duration::from_millis(30));
        });
        let lab = start_lab(
            &addr.to_string(),
            Duration::from_secs(2),
            Duration::from_secs(2),
        );
        let deadline = Instant::now() + Duration::from_secs(5);
        let height = loop {
            if let Ok((_tip, height)) = lab.clock.observe() {
                if height >= 1 {
                    break height;
                }
            }
            if Instant::now() > deadline {
                panic!("synth produced no header");
            }
            thread::sleep(Duration::from_millis(20));
        };
        assert!(height >= 1);
    }

    #[test]
    fn synth_fans_out_tip_advances_to_every_subscriber() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        drop(listener);
        let bind = addr.to_string();
        thread::spawn(move || {
            let _ = serve_synth(&bind, Duration::from_millis(40));
        });
        let peer = addr.to_string();
        let ready = Instant::now() + Duration::from_secs(5);
        loop {
            if TcpStream::connect(&peer).is_ok() {
                break;
            }
            if Instant::now() > ready {
                panic!("synth did not accept");
            }
            thread::sleep(Duration::from_millis(10));
        }
        let first = start_lab(&peer, Duration::from_secs(2), Duration::from_secs(2));
        let second = start_lab(&peer, Duration::from_secs(2), Duration::from_secs(2));
        let (_tip_a, height_a) = wait_observe(&first.clock);
        let (_tip_b, height_b) = wait_observe(&second.clock);
        assert_gossip_estab(&first, &peer);
        assert_gossip_estab(&second, &peer);
        drop_subscribed_peer(&peer);
        let target = height_a.max(height_b) + 1;
        let later_a = wait_height_at_least(&first.clock, target);
        let later_b = wait_height_at_least(&second.clock, target);
        assert!(later_a >= target);
        assert!(later_b >= target);
    }

    fn assert_gossip_estab(lab: &LabClock, peer: &str) {
        let lines = lab.log.lock().unwrap();
        assert!(
            lines
                .iter()
                .any(|line| line == &format!("gossip_estab call_site=gossip_peer peer={peer}")),
            "{lines:?}"
        );
    }

    fn wait_height_at_least(clock: &HeaderClock, target: u64) -> u64 {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let (lock, _) = &*clock.shared;
            let guard = lock.lock().unwrap_or_else(|err| err.into_inner());
            if guard.reorg {
                drop(guard);
                panic!("reorg before tip advance");
            }
            if guard.live && guard.chain.tip_height >= target {
                return guard.chain.tip_height;
            }
            drop(guard);
            if Instant::now() > deadline {
                panic!("subscriber did not reach height {target}");
            }
            thread::sleep(Duration::from_millis(10));
        }
    }

    #[test]
    fn plant_window_follows_gossip_tips_and_does_not_dial_bari() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        drop(listener);
        let peer = format!("127.0.0.1:{port}");
        let peer_thread = peer.clone();
        thread::spawn(move || {
            let _ = serve_synth(&peer_thread, Duration::from_millis(40));
        });
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            if TcpStream::connect(&peer).is_ok() {
                break;
            }
            if Instant::now() > deadline {
                panic!("synth did not accept");
            }
            thread::sleep(Duration::from_millis(10));
        }
        let clock = HeaderClock::start(&peer, GossipNetwork::Regtest);
        let (open_tip, open_height) = wait_observe(&clock);
        let started = Instant::now();
        clock.await_plant_window(open_tip).expect("plant window");
        let (now_tip, now_height) = clock.observe().expect("tip after window");
        assert!(
            now_height >= open_height.saturating_add(3),
            "open={open_height} now={now_height}"
        );
        assert_ne!(now_tip, open_tip);
        assert!(
            started.elapsed() < Duration::from_secs(8),
            "plant window held {:?}",
            started.elapsed()
        );
    }

    /// Handshake and `sendheaders`, then close. The synth must drop this socket
    /// without stopping the subscribers that stay connected.
    fn drop_subscribed_peer(peer: &str) {
        let mut stream = TcpStream::connect(peer).unwrap();
        let magic = GossipNetwork::Regtest.magic();
        handshake(&mut stream, magic, Duration::from_secs(2)).unwrap();
        write_message(&mut stream, magic, "sendheaders", &[]).unwrap();
    }

    #[test]
    fn mainnet_seed_list_is_core_mainnet_and_not_a_single_peer() {
        assert_eq!(
            MAINNET_DNS_SEEDS,
            [
                "dnsseed.bluematt.me",
                "seed.bitcoin.jonasschnelli.ch",
                "seed.btc.petertodd.net",
                "seed.bitcoin.sprovoost.nl",
                "dnsseed.emzy.de",
                "seed.bitcoin.wiz.biz",
                "seed.mainnet.achownodes.xyz",
            ]
        );
        assert_eq!(MAINNET_DNS_PORT, 8333);
        assert!(MAINNET_DNS_SEEDS.len() > 1);
        for seed in MAINNET_DNS_SEEDS {
            assert!(!seed.contains("pcoc"), "{seed}");
            assert!(!seed.contains("testnet"), "{seed}");
            assert!(!seed.contains("signet"), "{seed}");
            assert!(!seed.ends_with('.'), "{seed}");
        }
    }

    #[test]
    fn seed_start_discards_a_failed_lookup_and_a_bad_address() {
        let release = Arc::new(AtomicBool::new(false));
        let header_peer = scripted(ScriptAfter::Close {
            release: Arc::clone(&release),
        });
        let header_addr: SocketAddr = header_peer.parse().unwrap();
        let intro = spawn_introducer(&[SocketAddr::from(([127, 0, 0, 1], 1)), header_addr]);
        let asked = Arc::new(Mutex::new(Vec::<String>::new()));
        let asked_lookup = Arc::clone(&asked);
        let lookup = Arc::new(move |seed: &str| -> Result<Vec<SocketAddr>, ()> {
            asked_lookup.lock().unwrap().push(seed.to_owned());
            match seed {
                "dnsseed.bluematt.me" => Err(()),
                "seed.bitcoin.jonasschnelli.ch" => Ok(vec![SocketAddr::from(([0, 0, 0, 0], 8333))]),
                "seed.btc.petertodd.net" => Ok(vec![intro]),
                _ => Err(()),
            }
        });
        let log = Arc::new(Mutex::new(Vec::new()));
        let clock = HeaderClock::spawn(ClockOpts {
            dial: Dial::MainnetSeeds(lookup),
            network: GossipNetwork::Regtest,
            connect_timeout: Duration::from_millis(500),
            ping_interval: Duration::from_secs(30),
            silence_timeout: Duration::from_secs(30),
            skip_pause: true,
            log: Some(Arc::clone(&log)),
        });
        let (_tip, height) = wait_observe(&clock);
        assert_eq!(height, 1);
        let asked = asked.lock().unwrap().clone();
        assert_eq!(&asked[..MAINNET_DNS_SEEDS.len()], MAINNET_DNS_SEEDS);
        let lines = log.lock().unwrap().clone();
        assert!(
            lines.iter().any(|line| {
                line == "gossip_seed_skip call_site=gossip_peer seed=dnsseed.bluematt.me reason=dns"
            }),
            "{lines:?}"
        );
        assert!(
            lines.iter().any(|line| {
                line == "gossip_seed_skip call_site=gossip_peer peer=0.0.0.0:8333 reason=bad_address"
            }),
            "{lines:?}"
        );
        assert!(
            lines.iter().any(|line| {
                line == &format!("gossip_estab call_site=gossip_peer peer={header_peer}")
            }),
            "{lines:?}"
        );
        assert!(lines
            .iter()
            .all(|line| !line.contains("peer=0.0.0.0:8333 reason=tcp")));
        release.store(true, Ordering::SeqCst);
    }

    #[test]
    fn addr_payload_drops_a_bad_address_and_keeps_a_good_one() {
        let good = SocketAddr::from(([127, 0, 0, 1], 8333));
        let bad = SocketAddr::from(([0, 0, 0, 0], 8333));
        let payload = encode_addr_payload(&[bad, good]);
        let parsed = parse_addr_payload(&payload).unwrap();
        let usable: Vec<SocketAddr> = parsed
            .into_iter()
            .filter(|addr| usable_peer_addr(*addr))
            .collect();
        assert_eq!(usable, vec![good]);
        assert!(parse_addr_payload(&payload[..payload.len() - 1]).is_err());
    }

    /// Answers `getaddr` with the scripted peers, then stays up until the
    /// caller times out waiting for headers. Regtest wire magic, so the
    /// fixture does not mine mainnet difficulty.
    fn spawn_introducer(learned: &[SocketAddr]) -> SocketAddr {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let learned = learned.to_vec();
        thread::spawn(move || {
            let Ok((stream, _)) = listener.accept() else {
                return;
            };
            let _ = serve_introducer(stream, &learned);
        });
        addr
    }

    fn serve_introducer(mut stream: TcpStream, learned: &[SocketAddr]) -> Result<(), &'static str> {
        let magic = GossipNetwork::Regtest.magic();
        let _ = stream.set_nodelay(true);
        let mut got_version = false;
        let mut answered = false;
        loop {
            let message = match read_message(&mut stream, magic, Duration::from_secs(2)) {
                Ok(message) => message,
                Err("peer_silence") | Err("timeout") if answered => return Ok(()),
                Err(err) => return Err(err),
            };
            if message.command == "version" && !got_version {
                got_version = true;
                write_message(&mut stream, magic, "version", &version_payload(0))?;
                write_message(&mut stream, magic, "verack", &[])?;
            } else if message.command == "getaddr" && !answered {
                write_message(&mut stream, magic, "addr", &encode_addr_payload(learned))?;
                answered = true;
            }
        }
    }

    fn encode_addr_payload(addrs: &[SocketAddr]) -> Vec<u8> {
        let mut payload = Vec::new();
        push_varint(&mut payload, addrs.len() as u64);
        for addr in addrs {
            payload.extend_from_slice(&0u32.to_le_bytes());
            payload.extend_from_slice(&0u64.to_le_bytes());
            payload.extend_from_slice(&wire_ip(*addr));
            payload.extend_from_slice(&addr.port().to_be_bytes());
        }
        payload
    }

    fn wire_ip(addr: SocketAddr) -> [u8; 16] {
        match addr.ip() {
            IpAddr::V4(ip) => {
                let octets = ip.octets();
                let mut bytes = [0u8; 16];
                bytes[10] = 0xff;
                bytes[11] = 0xff;
                bytes[12..].copy_from_slice(&octets);
                bytes
            }
            IpAddr::V6(ip) => ip.octets(),
        }
    }

    /// Genesis plus `n` extensions. The tip header's `nTime` is `age` before now.
    fn recent_regtest_chain(n: usize, age: Duration) -> Vec<[u8; 80]> {
        let network = GossipNetwork::Regtest;
        let mut headers = vec![network.genesis_header()];
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs() as u32;
        let tip_time = now.saturating_sub(age.as_secs() as u32);
        let start = tip_time.saturating_sub(n as u32);
        for i in 0..n {
            let prev = sha256d(headers.last().unwrap());
            headers.push(mine_extension(
                prev,
                network.extension_bits(),
                start.saturating_add(i as u32),
            ));
        }
        headers
    }

    /// Serves `chain`. A getheaders that has nothing newer sends an empty
    /// headers message when `empty` is set, and otherwise stays quiet.
    fn serve_caught_up(mut stream: TcpStream, chain: Vec<[u8; 80]>, empty: bool) {
        let magic = GossipNetwork::Regtest.magic();
        let mut got_version = false;
        loop {
            let message = match read_message(&mut stream, magic, Duration::from_secs(3)) {
                Ok(message) => message,
                Err("timeout") => continue,
                Err(_) => return,
            };
            if message.command == "version" && !got_version {
                got_version = true;
                let _ = write_message(
                    &mut stream,
                    magic,
                    "version",
                    &version_payload(chain.len() as i32 - 1),
                );
                let _ = write_message(&mut stream, magic, "verack", &[]);
            } else if message.command == "ping" {
                let _ = write_message(&mut stream, magic, "pong", &message.payload);
            } else if message.command == "getheaders" {
                let reply = headers_after(&chain, &message.payload);
                if reply.is_empty() && !empty {
                    continue;
                }
                let _ = write_message(&mut stream, magic, "headers", &headers_payload(&reply));
            }
        }
    }

    fn caught_up_peer(empty: bool) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let chain = recent_regtest_chain(MAX_HEADERS, Duration::from_secs(16 * 60));
        thread::spawn(move || {
            if let Ok((stream, _)) = listener.accept() {
                serve_caught_up(stream, chain, empty);
            }
        });
        format!("{addr}")
    }

    #[test]
    fn empty_headers_after_a_full_batch_is_the_current_tip() {
        let peer = caught_up_peer(true);
        let lab = start_lab(&peer, Duration::from_secs(30), Duration::from_secs(30));
        let (_tip, height) = wait_observe(&lab.clock);
        assert_eq!(height, MAX_HEADERS as u64);
    }

    #[test]
    fn silence_after_a_full_batch_does_not_wait_for_a_newer_header() {
        let peer = caught_up_peer(false);
        let lab = start_lab(&peer, Duration::from_secs(30), Duration::from_secs(30));
        let (_tip, height) = wait_observe(&lab.clock);
        assert_eq!(
            height, MAX_HEADERS as u64,
            "the seed Observe is the header already in hand"
        );
    }
}
