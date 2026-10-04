# PCOC development plan — primal-pcoc

Short sequence for integrating PCOC into this fork. Read [CUT-POINTS.md](CUT-POINTS.md) for the file and symbol map. This plan adds no product code.

PCOC stays in [groundinglab/pcoc](https://github.com/groundinglab/pcoc). This repo integrates a dependency. It does not re-home the protocol, **STAGES 18**, or a signature scheme. A leg is 1,000,000 steps. STAGES is 18. The tip interrupts the drive. A leg ending is not the end of observation.

## Gates

No phase below phase 0 starts until both are true:

1. Bari waypoint-anchoring **GO** (first-ship intent on pcoc issue #87 is parked until then).
2. Sample objects exist, including a **leg** and a **leg result** this UI can render.

Until those gates, the only change in this repo is planning docs. Assume the **live** profile. Leave TEST paused: no test-profile flag in the app.

Issue #87 was not readable here (HTTP 404). If the issue contradicts a lock in [CUT-POINTS.md](CUT-POINTS.md), the issue wins and this plan is updated before code.

## Ordered phases

Smallest diff first. Each phase is one reviewable change. A phase that needs an answer in the ask list stays unstarted.

### 0. Planning docs (this change)

Add `docs/pcoc/CUT-POINTS.md`, `docs/pcoc/DEV-PLAN.md`, and a README pointer. Leave `LICENSE` and `NOTICE.md` untouched. No Kotlin, Gradle, or resource edits.

### 1. Groundinglab badge

After the gates: a visible mark that this build is the Groundinglab fork.

Touch `R.string.app_name` and `SettingsHomeScreen.VersionListItem` only if the ask list confirms that pair. Leave `applicationId` (`net.primal.android`), `Badges`, and the premium drawer badge alone.

Build gate: `./gradlew :app:assembleAospDebug` (see the baseline notes in [CUT-POINTS.md](CUT-POINTS.md)).

### 2. Read-only key gate

A single helper that answers, for the active account:

- hex pubkey from `ActiveAccountStore.activeUserId()`
- whether `CredentialsStore.findOrThrow` yields `CredentialType.PrivateKey` with a non-null `nsec`

No new storage. No call to `getOrCreateInternalSignerCredentials`. No produce, no admit, no signature, no verify. This helper does not decide admit. Admit withhold is phase 7 and only matches `activeUserId`.

### 3. Centre control

Blocked. The centre stays `PrimalTopLevelDestination.Wallet` and `WalletDashboardContent`. Do not retarget `PrimalNavigationBar`, `MainScreen.onTabChanged`, or `onActiveDestinationClick`.

Produce-on-centre and admit-on-centre are Ask Christian only. This phase does not add a produce state or an admit state to the centre item, and it does not claim the centre switches after association.

### 4. In-app produce, once a real artifact exists

Depends on a published Android-callable API from `groundinglab/pcoc` whose symbols are written down (ask list). This phase adds that dependency only. It does not place the call on Wallet, and it does not retarget the centre.

Finishing produce associates the object. Do not add a register function. Do not add a JNI or UniFFI layer in this repo to invent the call. `STAGES 18` remains inside pcoc. The UI shows the sample object’s leg and leg result. A leg is 1,000,000 steps.

If the artifact is not published, or the call site is still unnamed, this phase does not start. A stub that returns a made-up object is out of scope.

### 5. Local verify

Blocked. Unfilled until the ask list names the surface and the tip client.

Do not add a `NavHost` destination. Do not invent a verify screen. Do not pass a sample object plus headers held by the app. There is no verify route and no header client in this tree. Leave both absent.

When Christian names the surface, that surface calls the reference verifier. The screen does not own a verifier. The admit gate does not own a second verifier. Phase 7 only matches `activeUserId` and does not call verify.

`NostrNotary.verifySignature` and `Nip05VerificationService` stay on their current screens. They are not the reference verifier. The tip is a public sequencer tick. The app does not keep a header archive.

### 6. Screen-off tip observe

Blocked on the tip client (ask list). `PrimalNwcService` is a pattern only: `start` → `startForegroundService` → `onStartCommand` → `startForeground`, with `foregroundServiceType="specialUse"`. That lifecycle is NWC’s. It is not the hike.

Do not invent a service whose start or stop follows produce, a leg, or a leg result. Do not assign start or stop to a leg. A leg is 1,000,000 steps. STAGES is 18. The tip interrupts the drive. A leg ending is not the end of observation.

No header client is added in this phase. The app does not keep a header archive. Screen-off work on an `aosp` build does not use `PrimalFirebaseMessagingService`. `MempoolApiService` stays the wallet deposit client. `libs.bitcoinj.core` being on the classpath is not an observe path.

### 7. Admit, lab-only, after the wire exists

Last. Option B. Lab-only until a later GO that explicitly ships admit.

The lab surface is `DeveloperToolsScreen` (`developer_tools`), or it stays unnamed. The centre stays Wallet. This phase does not switch the centre to admit. Admit-on-centre remains Ask Christian only.

The gate shows admit only when `ActiveAccountStore.activeUserId()` matches the object’s associated pubkey, and withholds admit on a mismatch. That match is the whole gate. Phase 7 does not call the reference verifier.

This phase starts only after the admit wire is specified. It does not define bytes, and it does not define a signature. `NostrNotary.signNostrEvent` and `CryptoUtils.sign` remain Nostr event signing.

## Ask Christian

Stop on these. Do not invent an answer in code or in a follow-up design doc.

1. **Admit wire and signature.** What are Option B’s bytes, and is there a signature at all? No scheme will be proposed from this repo. Phase 7 waits.
2. **Produce artifact.** Which Android-callable coordinates and symbols does `groundinglab/pcoc` expose (Maven/AAR, package, function names)? This tree has no Rust bridge and will not grow one to fill the gap. Phase 4 waits.
3. **Association record.** When produce finishes, which field is the object id and which field is the associated pubkey? Foreign-key withhold compares that pubkey to `ActiveAccountStore.activeUserId()`. Phase 7 waits on this. Naming the field does not move admit onto the centre.
4. **Tip client.** Which client reads the public tip (the sequencer tick)? Phase 6 waits. Do not bind that client’s start or stop to a leg. The app does not keep a header archive. mempool.space transaction lookup is the wallet path and is not the proposal. The same answer blocks phase 5: do not invent a verify screen or a stored-header archive while it is open.
5. **Centre control versus Wallet, including admit-on-centre.** `PrimalTopLevelDestination` order is Feeds, Reads, Wallet, Alerts, Explore. Wallet is the centre item and opens `WalletDashboardContent`. Until this is answered, the centre stays Wallet. Do not retarget it. Lab admit stays `DeveloperToolsScreen` or unnamed. Does produce or admit replace Wallet, or is either a different surface? Phases 3 and the centre half of phase 4 wait.
6. **Badge scope.** Is the Groundinglab mark the launcher label (`app_name`), the settings version row (`VersionListItem`), or both? Does `applicationId` stay `net.primal.android`? Phase 1 waits.
7. **Sample object shape.** Where do the sample objects live, and which fields are the leg and the leg result the UI must show? Confirm the app does not copy STAGES 18.
8. **Issue #87 parity.** The locks in [CUT-POINTS.md](CUT-POINTS.md) came from the task brief because issue #87 returned 404 here. Confirm they match the parked issue, including live-profile only and TEST paused.

## Working agreement

- One phase per pull request, after phase 0.
- Baseline build for every code phase: `./gradlew :app:assembleAospDebug`.
- Preserve the MIT `LICENSE` and `NOTICE.md`.
- Keep upstream Primal behavior working on the paths a phase does not touch. Wallet, Nostr signing, and NWC stay in place until a phase says otherwise.
- If an answer in the ask list is still open, stop. A smaller diff that avoids the open question is the next step. A guessed wire is not.
