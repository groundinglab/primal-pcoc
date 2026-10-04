# PCOC development plan — primal-pcoc

Short sequence for integrating PCOC into this fork. Tonight’s product SPEC is [FEATURES-v0.1.md](FEATURES-v0.1.md). The file and symbol map is [CUT-POINTS.md](CUT-POINTS.md). This plan adds no product code.

PCOC stays in [groundinglab/pcoc](https://github.com/groundinglab/pcoc). This repo integrates a dependency. It does not re-home the protocol, **STAGES 18**, or a signature scheme. A leg is 1,000,000 steps. STAGES is 18. The tip interrupts the drive. A leg ending is not the end of observation.

## Gates

**Tonight (import + live sign-in)** needs a real live PCOC CBOR file, produced elsewhere (pepebook, VivoBook, or a Bari run). It does not need an in-app produce artifact. The Bari waypoint-anchoring GO does not block import + sign-in when that file exists. The wire is locked in [FEATURES-v0.1.md](FEATURES-v0.1.md). UI placement and the CBOR `public_key` decoder are the tonight asks there.

**Later** (in-app produce, local verify, screen-off tip observe, Option B admit) stay behind their own asks. In-app produce still waits on the Bari GO and on a published produce artifact. A leg and a leg result in sample objects belong to those later phases. Live profile only. TEST stays paused: no test-profile flag in the app.

Issue #87 was not readable here (HTTP 404). If the issue contradicts a lock in [FEATURES-v0.1.md](FEATURES-v0.1.md) or [CUT-POINTS.md](CUT-POINTS.md), the issue wins and this plan is updated before code. The 2026-10-04 sign-in lock is the wire until those READMEs are readable here.

## Ordered phases

Smallest diff first. Each phase is one reviewable change. A phase that needs an answer in the ask list stays unstarted.

### 0. Planning docs (this change)

Add `docs/pcoc/CUT-POINTS.md`, `docs/pcoc/DEV-PLAN.md`, `docs/pcoc/FEATURES-v0.1.md`, and a README pointer. Leave `LICENSE` and `NOTICE.md` untouched. No Kotlin, Gradle, or resource edits.

### 1. Tonight — producer nsec login

Use the existing path in [FEATURES-v0.1.md](FEATURES-v0.1.md): bech32 nsec → `LoginHandler` / `CredentialsStore.saveNsec` → `ActiveAccountStore.activeUserId()` equals the object `public_key`. No new key store. No `InternalSigner`.

### 2. Tonight — import and associate

After the UI-placement ask: Android file picker only, then bind that CBOR file to the active account. Association is import, not a register step. Mismatch on `public_key` withholds sign-in. This phase does not add a produce artifact and does not wait on the Bari GO. It does wait on a real live object file and on a named pcoc decoder for `public_key` (ask in the SPEC).

Build gate: `./gradlew :app:assembleAospDebug` (see the baseline notes in [CUT-POINTS.md](CUT-POINTS.md)).

### 3. Tonight — live sign-in

On the same surface as phase 2: scan the tip-hash QR, BIP-340-sign the display-order hash bytes with the producer nsec, POST the locked JSON to `https://admission.pcoc.app/v1/sign-in`, show `decision`. The app may call the gate itself. Do not add a website↔phone sync. Do not call `/v1/challenge` or `/v1/admit`. The centre stays Wallet.

### 4. Later — Groundinglab badge

Optional. Not required for the tonight demo.

Touch `R.string.app_name` and `SettingsHomeScreen.VersionListItem` only if the ask list confirms that pair. Leave `applicationId` (`net.primal.android`), `Badges`, and the premium drawer badge alone.

### 5. Later — centre control

Blocked. The centre stays `PrimalTopLevelDestination.Wallet` and `WalletDashboardContent`. Do not retarget `PrimalNavigationBar`, `MainScreen.onTabChanged`, or `onActiveDestinationClick`.

Produce-on-centre and admit-on-centre are Ask Christian only. This phase does not add a produce state or an admit state to the centre item.

### 6. Later — in-app produce, once a real artifact exists

Depends on a published Android-callable API from `groundinglab/pcoc` whose symbols are written down (ask list). This phase adds that dependency only. It does not place the call on Wallet, and it does not retarget the centre.

Finishing produce associates the object. Do not add a register function. Do not add a JNI or UniFFI layer in this repo to invent the call. `STAGES 18` remains inside pcoc. The UI shows the sample object’s leg and leg result. A leg is 1,000,000 steps.

If the artifact is not published, or the call site is still unnamed, this phase does not start. A stub that returns a made-up object is out of scope.

### 7. Later — local verify

Blocked. Not tonight. Unfilled until the ask list names the surface and the tip client.

Do not add a `NavHost` destination. Do not invent a verify screen. Do not pass a sample object plus headers held by the app. There is no verify route and no header client in this tree. Leave both absent.

When Christian names the surface, that surface calls the reference verifier. The screen does not own a verifier. The Option B admit gate does not own a second verifier and does not call verify. Tonight’s sign-in posts to the admission gate; the gate runs reference verify.

`NostrNotary.verifySignature` and `Nip05VerificationService` stay on their current screens. They are not the reference verifier. The tip is a public sequencer tick. The app does not keep a header archive.

### 8. Later — screen-off tip observe

Blocked on the tip client (ask list). Not tonight. `PrimalNwcService` is a pattern only: `start` → `startForegroundService` → `onStartCommand` → `startForeground`, with `foregroundServiceType="specialUse"`. That lifecycle is NWC’s. It is not the hike.

Do not invent a service whose start or stop follows produce, a leg, or a leg result. Do not assign start or stop to a leg. A leg is 1,000,000 steps. STAGES is 18. The tip interrupts the drive. A leg ending is not the end of observation.

No header client is added in this phase. The app does not keep a header archive. Screen-off work on an `aosp` build does not use `PrimalFirebaseMessagingService`. `MempoolApiService` stays the wallet deposit client. `libs.bitcoinj.core` being on the classpath is not an observe path.

### 9. Later — Option B admit, after a Christian GO

Not tonight. `/v1/challenge` and `/v1/admit` stay lab-only until that GO. Tonight uses `POST /v1/sign-in` only, as locked in [FEATURES-v0.1.md](FEATURES-v0.1.md).

The lab surface is `DeveloperToolsScreen` (`developer_tools`), or it stays unnamed. The centre stays Wallet. This phase does not switch the centre to admit. Admit-on-centre remains Ask Christian only.

Option B withhold matches `ActiveAccountStore.activeUserId()` to the object’s associated pubkey and does not call the reference verifier. This phase starts only after the Option B wire is specified. It does not define bytes, and it does not define a signature. `NostrNotary.signNostrEvent` and `CryptoUtils.sign` remain Nostr event signing. The tonight BIP-340 sign is the tip-hash bytes, not an Option B payload.

## Ask Christian

Tonight blockers are only the two in [FEATURES-v0.1.md](FEATURES-v0.1.md) section 6: where Import and Sign-in live, and which pcoc decoder reads `public_key`. Stop on those. Do not invent an answer in code.

Later, still stop rather than invent:

1. **Option B wire and signature.** What are Option B’s bytes, and is there a signature at all? Phase 9 waits. Tonight’s sign-in body stays the two fields in the SPEC.
2. **Produce artifact.** Which Android-callable coordinates and symbols does `groundinglab/pcoc` expose? This tree has no Rust bridge. Phase 6 waits. This does not block phases 1–3.
3. **Produce association record.** When in-app produce finishes, which field is the object id and which field is the associated pubkey? Phase 9 waits on this. Naming it does not move admit onto the centre. Tonight’s import uses the object `public_key` from the decoder ask, not this produce record.
4. **Tip client.** Which client reads the public tip? Phase 8 waits. Do not bind start or stop to a leg. The app does not keep a header archive. The same answer blocks phase 7. mempool.space transaction lookup is the wallet path.
5. **Centre versus Wallet, including produce-on-centre and admit-on-centre.** Wallet stays the centre item (`WalletDashboardContent`). Do not retarget it. Phases 5 and 6 wait. Tonight’s UI is the settings ask in the SPEC, not this one.
6. **Badge scope.** Launcher label (`app_name`), settings version row (`VersionListItem`), or both? Does `applicationId` stay `net.primal.android`? Phase 4 waits. Not a tonight blocker.
7. **Sample object shape for later UI.** Where do sample objects live, and which fields are the leg and the leg result? The app does not copy STAGES 18. A live CBOR file is enough for phases 1–3.
8. **Issue #87 parity.** The earlier cut-point locks came from the task brief because issue #87 returned 404 here. Confirm they still match for the later phases. Live profile only. TEST paused.

## Working agreement

- One phase per pull request, after phase 0.
- Baseline build for every code phase: `./gradlew :app:assembleAospDebug`.
- Preserve the MIT `LICENSE` and `NOTICE.md`.
- Keep upstream Primal behavior working on the paths a phase does not touch. Wallet, Nostr signing, and NWC stay in place until a phase says otherwise.
- If an answer in the ask list is still open, stop. A smaller diff that avoids the open question is the next step. A guessed wire is not.
