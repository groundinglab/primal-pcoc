# PCOC cut points — primal-pcoc

Planning map for the Groundinglab fork of Primal Android. This document names files and symbols that already exist. It does not add product behavior.

PCOC (cost-backed hike objects) lives in [groundinglab/pcoc](https://github.com/groundinglab/pcoc). This repository consumes that work later as a dependency. It does not host the protocol.

Terms used below, unchanged: **leg**, **leg result**, **STAGES 18**. Bitcoin tip paces a leg. First-ship intent is parked until the Bari waypoint-anchoring GO. Assume the **live** profile. TEST stays paused: do not add a profile switch in this app.

Issue [#87](https://github.com/groundinglab/pcoc/issues/87) on `groundinglab/pcoc` was not readable from this environment (HTTP 404). The locks below are the ones given for this plan. Confirm them against the issue before any code phase in [DEV-PLAN.md](DEV-PLAN.md).

## Locks carried into the map

- In-app produce. Finishing produce associates the object. There is no separate register step.
- An empty centre button starts produce. After association it becomes admit.
- Verification is local. The verify screen calls the reference verifier. The admit gate does not own a second verifier.
- A leg keeps observing the Bitcoin tip with the screen off. The tip is a public sequencer tick. The app does not keep a header archive.
- Admit is Option B and lab-only until the GO. The admit wire and any signature stay unspecified here.
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
- `PrimalNwcService` — `specialUse` (NWC). `PrimalNwcService.start` calls `context.startForegroundService`.

`WalletSessionBridge` keeps the wallet session aligned with `ActiveAccountStore.activeUserId` inside the process. It is not a screen-off observer.

There is no `WorkManager` usage, no wake-lock header loop, and no Bitcoin block-header client. `PrimalFirebaseMessagingService` exists only under `app/src/google/`. The `aosp` baseline does not use it. Screen-off tip observation cannot depend on FCM.

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

### Centre button — produce, then admit

The centre slot is `PrimalTopLevelDestination.Wallet` inside `PrimalNavigationBar` / `PrimalNavigationBarItem`. The click handler is the `onClick` lambda in `PrimalNavigationBar` (change destination, or `onActiveDestinationClick` when the destination is already active). Wiring upward is `MainScreen.onTabChanged` and `MainScreen.onActiveDestinationClick`.

Today that slot opens the wallet dashboard (`MainScreenContent` → `WalletDashboardContent`). There is no produce state and no admit state on the button.

Later, the empty state of this control starts produce. When produce finishes, the object is associated, and the same control becomes admit. Association is the end of produce. Do not add a register function.

Replacing Wallet’s navigation is a product collision. See the ask list in [DEV-PLAN.md](DEV-PLAN.md) before changing `PrimalTopLevelDestination` or `onTabChanged`.

### In-app produce — invoke path

**No produce invoke path exists in this repository.**

Checked and absent:

- No `pcoc` package, module, or Gradle dependency.
- No Rust crate, UniFFI, or JNI bridge for hike objects.
- No `leg` / `leg result` types.

The JNI that does exist is `fr.acinq.secp256k1` behind `CryptoUtils.sign` / `CryptoUtils.verify` for Nostr events (`domain/nostr/.../CryptoUtils.kt`, `NostrUnsignedEvent.signOrThrow`). Breez Spark (`WalletRepositoryFactory`, `EnsureSparkWalletExistsUseCase`) is the Lightning wallet. Neither is a produce call.

The future call site is the empty centre control, after `groundinglab/pcoc` publishes an Android-callable artifact whose symbols are known. This plan does not name those symbols and does not add a Rust bridge.

`STAGES 18` stays in pcoc. This app does not reimplement the stage table. The UI later shows a leg and a leg result once sample objects define those fields.

### Local verify UI

There is no PCOC verify composable and no verify route.

Existing verifiers stay what they are:

- `NostrNotary.verifySignature` checks a Nostr event id against `event.sig` and `event.pubKey`.
- `Nip05VerificationService` (provided as `LocalNip05VerificationService` from `MainActivity`) checks an internet identifier.

A local verify screen is a new destination on the `NavHost` in `PrimalAppNavigation`, reached from the associated object. That screen calls the reference verifier. It passes the object and the current public tip (the sequencer tick). It does not keep a header archive, and it does not implement a second verifier. The admit gate does not call this verifier. The screen does not send a verify request through `SocketModule`, `NetworkingModule`, or `MempoolApiService`.

### Screen-off tip observe

There is no observer of the public tip.

The keep-alive pattern to copy, once a tip client is named, is `PrimalNwcService`:

- Manifest: `android:foregroundServiceType="specialUse"` and `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.
- Permissions already present: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`.
- Start: `PrimalNwcService.start` → `startForegroundService` → `onStartCommand` → `startForeground`.

The service starts when the leg begins and stops when the leg result is in. While the leg is open it keeps observing the public tip with the screen off. It does not store a header archive. It is not `WalletSessionBridge`, not `keepAliveSocketsHttpClient`, and not `MempoolApiService`.

`MempoolApiService.getUnconfirmedTransactions` / `getTransaction` / `lookupTransaction` stay wallet deposit lookups.

### Groundinglab badge

The fork still presents as upstream Primal:

- `R.string.app_name` is `Primal` (`app/src/main/res/values/strings.xml`).
- `AndroidManifest` `android:label="@string/app_name"`.
- `applicationId` is `net.primal.android` (`app/build.gradle.kts`). Signer and file-provider authorities are `${applicationId}.*`.
- `SettingsHomeScreen.VersionListItem` shows `state.version` (from `appVersionName`, currently `3.5.27`).
- `user/domain/Badges.kt` is `unreadNotificationsCount` and `unreadMessagesCount`. `PrimalNavigationBar` paints a badge only for `PrimalTopLevelDestination.Alerts`. `PrimalDrawer`’s `showPremiumBadge` is Primal Premium.

The badge cut is the visible name and the settings version row: `app_name` and `VersionListItem`. `Badges` and the premium drawer badge stay notification and premium UI.

Changing `applicationId` would fork the install identity and the signer authorities. That is a separate decision (ask list), not part of the badge string.

### Withhold admit on a foreign key

The admit gate withholds admit unless `ActiveAccountStore.activeUserId()` matches the pubkey produce associated with the object. That match is the whole gate. The gate does not call the reference verifier, and it does not own a verifier.

`activeUserId()` is the hex pubkey already stored for the logged-in account. `CredentialsStore.findOrThrow` still loads `Credential.nsec` for produce when `type == PrivateKey`. `isExternalSignerCredential` / `isNpubCredential` mark sessions whose `nsec` is null. `NostrNotary.signNostrEvent` still signs Nostr events, including the Amber branch. None of those calls are the admit gate.

The object’s associated-pubkey field is not in this repo. Until it is named, the centre control does not switch to admit. `NostrNotary.signNostrEvent` and `CryptoUtils.sign` stay Nostr event signing.

Option B admit, when it is eventually shown, is lab-only. The existing lab surface is `DeveloperToolsScreen` on route `developer_tools` (`SettingsNavigation.kt`, opened from `SettingsHomeScreen` when developer tools are enabled). Shipping admit on the centre button waits for the GO and for the wire.

## 3. Build baseline

Documented debug command (also in the README):

```bash
./gradlew :app:assembleAospDebug
```

Variant facts from `app/build.gradle.kts` and `gradle/libs.versions.toml` (these override the older README line that says compileSdk 36, AGP 8.13+, Kotlin 2.2):

| Item | Value |
| --- | --- |
| Flavor dimension | `distribution`: `aosp`, `google` |
| Build types | `debug`, `altRelease`, `playRelease`, `benchmark` |
| Baseline variant | `aospDebug` |
| `applicationId` | `net.primal.android` |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| AGP / Kotlin / JDK | 9.2.1 / 2.4.0 / 21 |
| Version | `appVersionName` `3.5.27`, `appVersionCode` `2724` |
| `google` extras | Play billing, Firebase messaging, ML Kit barcode — not on the `aosp` classpath |
| `aosp` extra | ZXing (`aospImplementation`) |

`aospDebug` needs no `config.properties`. Debug encryption is `NoEncryption`, and Room uses `FrameworkSQLiteOpenHelperFactory`. Release builds require `localStorage.keyAlias` in `config.properties` because `AESEncryption` reads `BuildConfig.LOCAL_STORAGE_KEY_ALIAS`. `googlePlayRelease` and `aospAltRelease` can take signing properties; otherwise they fall back to the debug keystore.

The Google Services plugin is applied to the whole `:app` module. `app/src/aosp/google-services.json` is present so an `aosp` assemble can configure it. FCM code remains in `src/google`.

This planning environment had JDK 21 and no Android SDK (`ANDROID_HOME` unset). `./gradlew :app:assembleAospDebug` was not executed for this docs change. The command above is still the baseline to re-run on a machine with the SDK before any later code phase. A green `aospDebug` assemble is the build gate. Do not treat `googleDebug` as the gate: it pulls Firebase and Play dependencies the screen-off path must not require.

## 4. Absent on purpose

Recorded so a later change does not fill them in from Primal primitives:

- Produce function and Rust/UniFFI bridge.
- Register step after produce.
- Admit request bytes and any admit signature.
- A client that observes the public Bitcoin tip with the screen off. The app does not keep a header archive.
- PCOC verify route.
- Live/TEST profile switch (`TEST` paused; assume live).
- `STAGES 18` implementation (stays in pcoc).
