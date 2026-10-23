# PCOC cut points — primal-pcoc

Planning map for the Groundinglab fork of Primal Android. This document names files and symbols that already exist. It does not add product behavior.

PCOC (cost-backed hike objects) lives in [groundinglab/pcoc](https://github.com/groundinglab/pcoc). This repository consumes that work later as a dependency. It does not host the protocol.

Terms used below, unchanged: **leg**, **leg result**, **STAGES 18**. A leg is 2,000,000 steps (the SDK 2.3 line, `STEPS_PER_LEG`). The 1,000,000 figure is withdrawn. Every step reads a full 16 KiB slot of a 256 MiB terrain. STAGES is 18. The tip interrupts the drive. A leg ending is not the end of observation. First-ship intent is parked until the Bari waypoint-anchoring GO. Assume the **live** profile. TEST stays paused: do not add a profile switch in this app.

The locks below are the ones given for this plan. The normative text is the specification in [groundinglab/pcoc](https://github.com/groundinglab/pcoc) ([`spec/`](https://github.com/groundinglab/pcoc/tree/main/spec)).

## Locks carried into the map

- In-app produce. Finishing produce associates the object. There is no separate register step.
- The centre item is Wallet. Do not retarget it. Produce-on-centre and admit-on-centre are Ask Christian only. Until that answer, the centre stays Wallet.
- No verify route in this tree. When Christian names the surface, that surface calls the reference verifier. The admit gate does not own a second verifier. Until the ask list answers, do not add a verify screen.
- No client in this tree reads the public tip. The tip is a public sequencer tick, not an archive the app stores. `PrimalNwcService` is a foreground-service pattern only. Do not bind a start or stop to a leg.
- Admit is Option B and lab-only until the GO. The lab surface is `DeveloperToolsScreen`, or it stays unnamed. The centre does not switch to admit. The admit wire and any signature stay unspecified here.
- No invented signature, and no invented Rust bridge.

## 1. Architecture skim

Kotlin Multiplatform modules are listed in `settings.gradle.kts`. The Android shell is `:app` (`namespace` / `applicationId` `net.primal.android`). Shared domain and data sit beside it:

| Area | Modules |
| --- | --- |
| App shell | `:app` — Compose, Hilt, navigation |
| Nostr + config | `:domain:nostr`, `:domain:account`, `:core:nips`, `:core:app-config`, `:core:utils` |
| Primal social data | `:domain:primal`, `:data:caching:local`, `:data:caching:remote`, `:data:caching:repository` |
| Account + signer | `:data:account:local`, `:data:account:remote`, `:data:account:signer`, `:data:account:repository` |
| Wallet | `:domain:wallet`, `:data:wallet:local`, `:data:wallet:remote-primal`, `:data:wallet:remote-nwc`, `:data:wallet:repository` |
| Network | `:core:networking-primal`, `:core:networking-http`, `:core:networking-upload`, `:core:networking-lightning` |

Process entry is `PrimalApp.onCreate` (`app/src/main/kotlin/net/primal/android/PrimalApp.kt`). It initializes `AppConfigInitializer`, `PrimalRepositoryFactory`, `WalletRepositoryFactory`, and `AccountRepositoryFactory`, then starts `WalletSessionBridge.start`.

### Compose shell and navigation

`MainActivity.onCreate` calls `setContent`. After `splashViewModel.isAuthCheckComplete`, it mounts `PrimalAppNavigation` with start destination `"main"` when logged in and `"welcome"` otherwise (`MainActivity.kt`).

`PrimalAppNavigation` (the public composable in `PrimalAppNavigation.kt`) wraps drawer-destination handling, `AppOverlays`, picture-in-picture, live stream, and audio, then a private `NavHost`. Auth routes are `welcome`, `login`, and `onboarding`. The logged-in shell is route `"main"`, built by `NavGraphBuilder.main`, which calls `MainScreen`.

`MainScreen` holds `activeTab` (`rememberSaveable`, default `PrimalTopLevelDestination.Feeds`) and passes it to `MainScreenScaffold`. That scaffold is `PrimalMainScaffold` (`PrimalMainScaffold.kt`): top bar, content, overlays, snackbar, floating action button, and `ScaffoldBottomBar`.

`ScaffoldBottomBar` renders `PrimalNavigationBar`. Destinations, in order, are `Feeds`, `Reads`, `Wallet`, `Alerts`, `Explore` (`PrimalTopLevelDestination` in `PrimalNavigationBar.kt`). The geometric centre item is **Wallet**.

Tab changes flow `PrimalNavigationBar` → `onTopLevelDestinationChanged` → `PrimalMainScaffold.onPrimaryDestinationChanged` → `MainScreen.onTabChanged`, which assigns `activeTab`. Re-tapping the active tab calls `MainScreen.onActiveDestinationClick` (Explore opens search; other tabs scroll via `handleActiveDestinationClick`). `MainScreenFab` shows `NewPostFloatingActionButton` on Feeds and Alerts only. That FAB publishes a note. It is not the centre control.

Deep links that select a tab write `REQUESTED_TAB_KEY` on the `"main"` back stack (`MainActivity.setMainScreenRequestedTab`). Paths handled there are `/reads`, `/explore`, and `/notifications`. There is no centre-button deep link.

### Keys and nsec

Account material is a `Credential` (`nsec`, `npub`, `CredentialType`) in `app/src/main/kotlin/net/primal/android/user/domain/Credential.kt`.

| Type | `nsec` | Role |
| --- | --- | --- |
| `PrivateKey` | present | Logged-in account key |
| `ExternalSigner` | null | Amber / external signer |
| `PublicKey` | null | npub-only login |
| `InternalSigner` | present | Device key for Nostr Connect, separate from the account |

Persistence is `UserModule.provideCredentialsStore`: DataStore file `credentials.json`, serializer `CredentialsSerialization`. Debug binds `DebugSecurityModule.provideEncryption` to `NoEncryption` (`app/src/debug/kotlin/net/primal/android/security.di/DebugSecurityModule.kt`). Release binds `ReleaseSecurityModule.provideEncryption` to `AESEncryption` with `BuildConfig.LOCAL_STORAGE_KEY_ALIAS`. The active account id is a separate DataStore, `active_account.txt`, via `UserModule.provideActiveAccountDataStore` and `ActiveAccountStore`.

### Background

Three foreground services are declared in `app/src/main/AndroidManifest.xml`:

- `PrimalMediaSessionService` — `foregroundServiceType="mediaPlayback"`
- `PrimalRemoteSignerService` — `specialUse` (remote signer)
- `PrimalNwcService` — `specialUse` (NWC). `PrimalNwcService.start` calls `context.startForegroundService`. That start belongs to NWC. It is not a hike lifecycle.

`WalletSessionBridge` keeps the wallet session aligned with `ActiveAccountStore.activeUserId` inside the process. It is not a screen-off observer.

There is no `WorkManager` usage and no client that reads the public tip. `PrimalFirebaseMessagingService` exists only under `app/src/google/`. The `aosp` baseline does not use it. A later tip client on `aosp` cannot depend on FCM.

### Network modules

`SocketModule` (`app/src/main/kotlin/net/primal/android/networking/di/SocketModule.kt`) provides three `PrimalApiClient`s from `PrimalApiClientFactory.getDefault`: caching (`@PrimalCacheApiClient`), upload (`@PrimalUploadApiClient`), and wallet (`@PrimalWalletApiClient`), plus `NostrSocketClientFactory`. Sockets are Ktor WebSockets in `:core:networking-primal` (`NostrSocketClientImpl`). `keepAliveSocketsHttpClient` is an in-process socket option, not a screen-off service.

`NetworkingModule` builds an unauthenticated OkHttp/Retrofit client whose base URL is `https://primal.net`.

Wallet chain lookups go through `MempoolApiService` (`data/wallet/repository/.../MempoolApiService.kt`), constructed inside `PendingDepositsSyncerImpl`. It calls mempool.space for address mempool transactions and a tx by id. It does not read block headers and it does not run while the screen is off.

`fr.acinq.bitcoin` is used for address and mnemonic parsing (`WalletStringUtils`, `RecoveryPhraseValidator`). `libs.bitcoinj.core` is on the `:app` classpath (`app/build.gradle.kts`) and has no `org.bitcoinj` call site in this tree. `CryptoUtils` in `:domain:nostr` uses Acinq secp256k1 JNI to sign and verify Nostr events. That is the only JNI crypto path in the repo.

## 2. Cut points

### Keys — create, load, save

**Create (new account).** `OnboardingViewModel` holds `keyPair = CryptoUtils.generateHexEncodedKeypair()` and `createNostrAccount()` calls `CreateAccountHandler.createNostrAccount(privateKey = keyPair.privateKey, ...)`. The handler calls `credentialsStore.saveNsec` and, on success, `authRepository.loginWithNsec`. `AuthRepository.loginWithNsec` saves again and calls `ActiveAccountStore.setActiveUserId`.

**Create (import / login).** `LoginViewModel.login` calls `LoginHandler.login`. `LoginHandler.saveCredentials` dispatches on `CredentialType`: `saveNsec`, `saveNpub`, or `saveExternalSignerNpub`. `CredentialType.InternalSigner` throws. Success calls the matching `AuthRepository.loginWith*` method, which sets the active user id.

**Load.** `CredentialsStore.findOrThrow(npub)` and the `credentials` `StateFlow`. `NostrNotary.findNsecOrThrow` decodes the hex pubkey to npub and reads `Credential.nsec`. `ActiveAccountStore.activeUserId` / `activeUserAccount()` is the logged-in pubkey (hex). PCOC code that needs “whose key is this” reads `ActiveAccountStore`, then `CredentialsStore`. It does not call `getOrCreateInternalSignerCredentials`.

**Save.** `CredentialsStore.saveNsec`, `saveNpub`, `saveExternalSignerNpub`, `removeCredentialByNsec`, `removeCredentialByNpub`, `clearCredentials`. File and encryption are the DataStore path in the skim above.

**Internal signer is a different key.** `CredentialsStore.getOrCreateInternalSignerCredentials` generates an `InternalSigner` credential when none is stored. Callers are Nostr Connect and the remote signer (`PrimalRemoteSignerService`, `NostrConnectViewModel`, `PermissionsViewModel`, and the google-flavor push handlers). That key is not the hike key.

### Centre button

The centre slot is `PrimalTopLevelDestination.Wallet` inside `PrimalNavigationBar` / `PrimalNavigationBarItem`. The click handler is the `onClick` lambda in `PrimalNavigationBar` (change destination, or `onActiveDestinationClick` when the destination is already active). Wiring upward is `MainScreen.onTabChanged` and `MainScreen.onActiveDestinationClick`.

That slot opens the wallet dashboard (`MainScreenContent` → `WalletDashboardContent`). It has no produce state and no admit state.

Do not retarget Wallet. Do not change `PrimalTopLevelDestination`, `onTabChanged`, or `onActiveDestinationClick` to produce or admit. Admit-on-centre is Ask Christian only ([DEV-PLAN.md](DEV-PLAN.md)). Until that answer, the centre stays Wallet. Lab admit stays on `DeveloperToolsScreen`, or unnamed. Finishing produce associates the object. That association does not move admit onto the centre, and it does not add a register function.

### In-app produce — invoke path

**No produce invoke path exists in this repository.**

Checked and absent:

- Account → PCOC lives in `net.primal.android.pcoc`. Enroll stores the picked file bytes for the signed-in account. It does not call `hike::yielded_public_key` or `object_public_key`, and it does not load a hike JNI library.
- `pcoc-bridge/` links the pcoc Rust SDK (tag `sdk-rust-v2.3.1`) and exposes produce and verify through JNI. The SDK is the step, the fill, and the live header source.
- No `leg` / `leg result` types.

The JNI that does exist for signatures is `fr.acinq.secp256k1` behind `CryptoUtils` (`domain/nostr/.../CryptoUtils.kt`). `CryptoUtils.sign` (`secp256k1.signSchnorr`) is the BIP-340 primitive. Request service admission calls it on the 32 bytes of query `h`. Nostr event signing stays on `NostrNotary.signNostrEvent` and `NostrUnsignedEvent.signOrThrow`. Do not use those for admission. `CryptoUtils.verify` checks a Nostr event signature. The phone does not show a local verify result. Breez Spark (`WalletRepositoryFactory`, `EnsureSparkWalletExistsUseCase`) is the Lightning wallet. Neither Spark nor the Nostr event signer is a produce call.

No produce call site exists. Do not place one on Wallet. Where an in-app produce call would sit is Ask Christian, after `groundinglab/pcoc` publishes an Android-callable artifact whose symbols are known. This plan does not name those symbols and does not add a Rust bridge.

`STAGES` is 18. A leg is 2,000,000 steps (the SDK 2.3 line, `STEPS_PER_LEG`). The 1,000,000 figure is withdrawn. Verify any uses those constants. It does not invent a per-stage table, and it does not show a leg or a leg result.

### Local verify

Account → PCOC **Verify any PCOC** (`VerifyPcocScreen`) runs the Java port of section 14 and shows the measurement record. It does not apply section 15 and it does not show valid or invalid. See [FEATURES-v0.1.md](FEATURES-v0.1.md).

The tip client is also absent. Verify reads `https://index.pcoc.app`. It does not keep a header archive.

`NostrNotary.verifySignature` checks a Nostr event id against `event.sig` and `event.pubKey`. `Nip05VerificationService` (provided as `LocalNip05VerificationService` from `MainActivity`) checks an internet identifier. Neither is the reference verifier.

Verify any is that surface. The admit gate does not own a second verifier and does not call verify.

### Screen-off tip observe

There is no observer of the public tip, and no header client. Leave both absent until Ask Christian names the client.

`PrimalNwcService` is a pattern for a foreground service, and only that:

- Manifest: `android:foregroundServiceType="specialUse"` and `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.
- Permissions already present: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`.
- What the NWC service does today: `PrimalNwcService.start` → `startForegroundService` → `onStartCommand` → `startForeground`.

That start and stop is NWC’s lifecycle. It is not the hike. Do not invent a service that starts when produce begins, when a leg begins, or when a leg result is in. Do not assign start or stop to a leg. A leg is 2,000,000 steps (the SDK 2.3 line, `STEPS_PER_LEG`). The 1,000,000 figure is withdrawn. STAGES is 18. The tip interrupts the drive. A leg ending is not the end of observation.

The app does not keep a header archive. The missing client is not `WalletSessionBridge`, not `keepAliveSocketsHttpClient`, and not `MempoolApiService`.

`MempoolApiService.getUnconfirmedTransactions` / `getTransaction` / `lookupTransaction` stay wallet deposit lookups.

### Groundinglab badge

The fork still presents as upstream Primal:

- `R.string.app_name` is `Primal` (`app/src/main/res/values/strings.xml`).
- `AndroidManifest` `android:label="@string/app_name"`.
- `applicationId` is `net.primal.android` (`app/build.gradle.kts`). Signer and file-provider authorities are `${applicationId}.*`.
- `SettingsHomeScreen.VersionListItem` shows `state.version` (from `appVersionName`, currently `3.5.49`).
- `user/domain/Badges.kt` is `unreadNotificationsCount` and `unreadMessagesCount`. `PrimalNavigationBar` paints a badge only for `PrimalTopLevelDestination.Alerts`. `PrimalDrawer`’s `showPremiumBadge` is Primal Premium.

The badge cut is the visible name and the settings version row: `app_name` and `VersionListItem`. `Badges` and the premium drawer badge stay notification and premium UI.

Changing `applicationId` would fork the install identity and the signer authorities. That is a separate decision (ask list), not part of the badge string.

### Withhold admit on a foreign key

The admit gate withholds admit unless `ActiveAccountStore.activeUserId()` matches the pubkey produce associated with the object. That match is the whole gate. The gate does not call the reference verifier, and it does not own a verifier.

`activeUserId()` is the hex pubkey already stored for the logged-in account. `CredentialsStore.findOrThrow` still loads `Credential.nsec` for produce when `type == PrivateKey`. `isExternalSignerCredential` / `isNpubCredential` mark sessions whose `nsec` is null. `NostrNotary.signNostrEvent` still signs Nostr events, including the Amber branch. None of those calls are the admit gate, and none of them are verify.

The object’s associated-pubkey field for Option B is not in this repo. Naming it does not move admit onto the centre. The centre stays Wallet. Admit-on-centre is Ask Christian only. Nostr event signing stays on `NostrNotary.signNostrEvent` and `signOrThrow`. Request service admission uses `CryptoUtils.sign` on the 32 bytes of query `h`. That use is not Option B and not a Nostr event.

Option B admit, when it is eventually shown, is lab-only. The existing lab surface is `DeveloperToolsScreen` on route `developer_tools` (`SettingsNavigation.kt`, opened from `SettingsHomeScreen` when developer tools are enabled), or the surface stays unnamed. The centre does not switch to admit.

## 3. Build baseline

Documented debug command (also in the README). On a clean tree the `mkdir` is required; see below.

```bash
mkdir -p pcoc-bridge/target
./gradlew :app:assembleAospDebug
```

Variant facts from `app/build.gradle.kts` and `gradle/libs.versions.toml` (the same pins as the README):

| Item | Value |
| --- | --- |
| Flavor dimension | `distribution`: `aosp`, `google` |
| Build types | `debug`, `altRelease`, `playRelease`, `benchmark` |
| Baseline variant | `aospDebug` |
| `applicationId` | `net.primal.android` |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| AGP / Kotlin / JDK | 9.2.1 / 2.4.0 / 21 |
| Gradle wrapper | 9.6.0 (`./gradlew`) |
| NDK | `26.3.11579264`. Set `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT`. `pcoc-bridge/build-android.sh` does not read `ANDROID_HOME`. If neither variable is set it uses `$HOME/Android/Sdk/ndk/26.3.11579264`. |
| Rust | 1.83 or newer, targets `aarch64-linux-android` and `x86_64-linux-android`. `python3` is required by `pcoc-bridge/build-android.sh`. |
| SDK packages | `platforms;android-37.0` (there is no `platforms;android-37`), `build-tools;37.0.0`, `platform-tools`. Set `ANDROID_HOME` or `sdk.dir`. |
| Version | `appVersionName` `3.5.49`, `appVersionCode` `2746` |
| `google` extras | Play billing, Firebase messaging, ML Kit barcode — not on the `aosp` classpath |
| `aosp` extra | ZXing (`aospImplementation`) |

`aospDebug` needs no `config.properties`. On a clean tree, run `mkdir -p pcoc-bridge/target` before `./gradlew :app:assembleAospDebug`. The bridge script copies a crate into that directory and does not create it. Debug encryption is `NoEncryption`, and Room uses `FrameworkSQLiteOpenHelperFactory`. Release builds require `localStorage.keyAlias` in `config.properties` because `AESEncryption` reads `BuildConfig.LOCAL_STORAGE_KEY_ALIAS`. `googlePlayRelease` and `aospAltRelease` can take signing properties; otherwise they fall back to the debug keystore.

The Google Services plugin is applied to the whole `:app` module. `app/src/aosp/google-services.json` is present so an `aosp` assemble can configure it. FCM code remains in `src/google`.

A green `aospDebug` assemble is the build gate. The command needs JDK 21 (`JAVA_HOME`), the SDK packages in the table (`ANDROID_HOME` or `sdk.dir`), NDK `26.3.11579264` (`ANDROID_NDK_HOME`), Rust 1.83 or newer with the two Android targets, `python3`, and `mkdir -p pcoc-bridge/target` on a clean tree. Do not treat `googleDebug` as the gate: it pulls Firebase and Play dependencies the screen-off path must not require.

## 4. Absent on purpose

Recorded so a later change does not fill them in from Primal primitives:

- The produce bridge is `pcoc-bridge/`. Create calls it. Verify calls the same crate's section 14 measurement.
- Register step after produce.
- Option B admit bytes. Account → Request service admission is the locked `{object_b64, signature}` POST to the scanned HTTPS URL, not Option B and not a phone→gate POST.
- A client that reads the public Bitcoin tip. The app does not keep a header archive. No start/stop bound to a leg. `PrimalNwcService` stays the NWC service.
- A second verifier on the admit gate. Verify any is the Java section 14 measurement door. It does not apply section 15.
- Live/TEST profile switch (`TEST` paused; assume live). The verify door has no synth and no `test-*` host.
- A per-stage table. `STAGES` is 18.
