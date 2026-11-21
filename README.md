# Screen Time for Android

The phone side of Screen Time. It records when you use your phone, reads sleep from Health Connect (or lets you add it by hand), and syncs with your own Screen Time server. Everything can also be exported to a backup file and imported again. One window shows three 24-hour timelines for a chosen day: laptop, phone and sleep. The laptop row comes from the Windows app, through the server or straight from the laptop over your own network (see [Sync modes](#sync-modes)).

Everything stays between this phone, your server and your laptop; nothing goes anywhere else.

## What it does

- **Phone use:** reads Android's usage statistics (needs the *Usage access* permission) and records when the screen was on and how long each app was in the foreground. A background job runs about every 15 minutes, and again whenever the app is opened.
- **Sleep:** the first time, choose how you measure it. *Smartwatch or band* reads sleep sessions from Health Connect, so your band's app (for example Mi Fitness) must share sleep with Health Connect. *Manually* lets you add bed and wake times. If no sleep is recorded for last night, a reminder notification comes once a day after 09:00.
- **Sync:** sends changes to the server and fetches what other devices sent (laptop periods, in particular), every 15 minutes and on demand. It works offline: everything is kept in a local database and sent later.
- **Sync with** (in Settings): **Server** (the default), **Laptop directly** or **Both**; see [Sync modes](#sync-modes).
- **Laptop** (in Settings): sync directly with the Windows app over your own network, without a server. On the laptop, tick **Allow phone sync** and choose **Pair a phone…**; on the phone, tap **Pair with laptop** and scan the QR code. Scanning uses ZXing inside the app (no Google Play services), and the camera is only asked for, with an explanation first, when you tap the button. When the laptop's address changes, the phone finds it again by itself (see [Finding the laptop](#finding-the-laptop)); the sync status says how it was found, e.g. `Synced at 12:30 · laptop found via broadcast (192.168.1.20)`. If you'd rather not use the camera, **Enter code** takes the laptop's IP address, port, pairing secret and certificate fingerprint typed by hand. Pairing switches **Sync with** from Server to Both (or to Laptop directly when no server is connected). **Remove pairing** makes the phone forget the laptop; to revoke the phone on the laptop as well, use **Remove pairing** there too. See [Direct sync with the laptop](#direct-sync-with-the-laptop).
- **Backup** (in Settings): **Export backup** saves a JSON file, through the system file picker, with this phone's periods and app times (in the same format that is sent to the server) and the periods it has from other devices, deleted ones included. **Import backup** merges a backup in: every row has an `updated_at`, and the newer copy wins, the same rule a sync uses, so an old backup can't bring back a period that was deleted since. Backups use the same format as the Windows app's, so a PC backup can be imported here and the other way round. Periods are merged from any backup; this phone's own periods and app times only count as its own (and are sent on the next sync) when the backup is from this same install. From another install they are kept and shown, but not sent, and another device's app times are left out.
- **Reinstalling:** uninstalling the app deletes its database, so export a backup first. After installing again, import it: the periods come back, and the laptop and the server send theirs again. The new install has a new ID, so the old install's own periods are kept and shown but not sent again (the server and the laptop already have them).
- **Security:** the server address must be HTTPS. The password is used once to register the phone and is never stored. The device token, and the laptop's pairing secret, key and pinned certificate, are kept encrypted with a key from the Android Keystore.

## Requirements

- Android 9 (API 28) or newer.
- A running Screen Time server reachable from the phone (for example over Tailscale, with the Tailscale app connected), or the Windows app on the same network for direct sync, or both.
- For sleep from a band: Health Connect (built in from Android 14, a Google Play app before that).

## Build

Open this folder in Android Studio and run the `app` configuration on a phone with USB debugging, or build an APK from a terminal. Gradle 9 needs a JDK 17 or newer, e.g. the one inside Android Studio (`jbr` folder):

```bash
export JAVA_HOME="<Android Studio folder>/jbr"
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. With a phone connected over USB debugging, `adb install -r app/build/outputs/apk/debug/app-debug.apk` installs it.

Unit tests (the certificate pin, the request proof and the pairing code, checked against values from the Windows app's own code) run on the computer, no phone needed:

```bash
./gradlew testDebugUnitTest
```

Tests on a phone (the real SQLite database, the upgrade) need both APKs installed with `-r`, which keeps the app's data, and then `am instrument`:

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.screentime.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

Don't use `./gradlew connectedAndroidTest` on your own phone: it uninstalls the app afterwards, which deletes its database. On MIUI, installing over USB needs **Install via USB** in the developer options, and the phone asks to confirm each new package. The checks that need a person (real networks, the laptop's firewall, a reinstall) are in the Windows repo's [docs/testing-direct-sync.md](../Screen-time/docs/testing-direct-sync.md).

`local.properties` (not in git) must point to the Android SDK: `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`; Android Studio writes it for you.

## Project structure

```
app/src/main/java/com/screentime/mobile/
  ScreenTimeApp.kt   starts the background job
  MainActivity.kt    the window; reads and syncs when opened
  Ui.kt              screens: setup, timelines, sleep, settings
  Timeline.kt        builds the three rows for a day from the database
  Collectors.kt      phone use (usage statistics) and sleep (Health Connect)
  SyncEngine.kt      sync mode and when each transport runs
  SyncCore.kt        push and pull over a transport, the merge rule (no Android classes)
  Transport.kt       the Transport interface, the server transport, rows as JSON
  Backup.kt          backup export and import
  DirectTransport.kt the laptop as a transport: pinned HTTPS, proofs, pairing; the real discovery sources
  SyncPolicy.kt      when to try the laptop (trigger, Wi-Fi, backoff), the status texts (no Android classes)
  Network.kt         on Wi-Fi?, and Android 17's local network permission
  Discovery.kt       finding the laptop: the order and timeouts, the UDP messages (no Android classes)
  Peer.kt            pairing code, certificate pin and request proof (no Android classes)
  Api.kt             HTTPS calls, connecting and disconnecting
  Work.kt            background job, scheduling and the sleep reminder
  Db.kt              local SQLite database (what was sent is kept per transport)
  Prefs.kt           settings (sync mode, pull cursors); the encrypted device token and laptop pairing
  Graph.kt           shared helpers
```

```
app/src/test/java/com/screentime/mobile/
  PeerCryptoTest.kt  unit tests for Peer.kt
  DiscoveryTest.kt   unit tests for Discovery.kt, with fake sources: the order, the timeouts, the messages
  BothModeTest.kt    "both" with a fake server and a fake laptop: overlapping and conflicting rows,
                     cursors, one route down, timelines
  SyncPolicyTest.kt  unit tests for SyncPolicy.kt: every status text, when the background tries, the backoff
app/src/androidTest/java/com/screentime/mobile/
  DiscoveryLiveTest.kt  the real sources against a real laptop; skipped unless it's named (see the file)
  BothModeDbTest.kt     "both" against the real SQLite database (throwaway files)
  DbUpgradeTest.kt      the upgrade to version 3 on copies (of your real database, if copied in): no row lost,
                        repeating it, an interrupted upgrade, a damaged or truncated file
  StatusLiveTest.kt     the status texts for a wrong secret, a wrong key and no laptop, against a scratch listener
  BackgroundRunTest.kt  runs what the background job runs, once (WorkManager won't run periodic work early)
```

## Sync modes

Rows from other devices (the laptop's PC-on and active periods, and phone and sleep periods from elsewhere) arrive by two routes: the **server**, and the **laptop directly** over your own network.

| Mode | What syncs | Order |
|---|---|---|
| **Server** (the default) | your server only, as before | |
| **Laptop directly** | the paired laptop only (`DirectTransport`) | |
| **Both** | the laptop, then the server | the laptop first |

Each way of syncing is a transport with two calls: send the rows that changed, and fetch the rows changed since a cursor. The two transports are independent:

- **Progress.** Each keeps its own record of what it already sent, and its own pull cursor; one never moves the other's, so switching modes never sends rows twice.
- **Failures.** In **Both**, one that can't be reached (the server offline, the laptop off) neither stops nor changes the other; the status line says how each went.
- **Resets.** If one side doesn't know the cursor (e.g. it was restored from an older backup), only that transport pulls again from the start. Nothing is deleted, and the rows pulled again change nothing. Connecting to another server or disconnecting forgets only the rows that came from the server alone. Rows the laptop also delivered stay, along with the laptop's cursor; so do rows stored before routes were recorded.
- **Merging.** Every row has an ID that doesn't depend on the route (made from the device and the period), and an `updated_at`. A row is merged the same way whichever route brings it: the newer `updated_at` wins; on the same `updated_at` a deletion wins over a live copy, so the result doesn't depend on which route was heard first; otherwise the copy already stored stays, so the same row arriving by both routes is applied once. Each stored row also remembers which routes delivered it.
- **Timelines.** Only a row's kind and times are drawn, so a period looks the same whichever route brought it.

## The database

Everything is in one SQLite database, `screentime.db`. Version 3 added a `routes` column: rows from other devices that were already there count as both routes, and the phone's own rows have none. Upgrades run in one transaction together with the version change, so an upgrade cut off half way (the app killed, the phone switched off) leaves the old version as it was. Repeating the step changes nothing, because it checks whether the column is already there. If the file is damaged, Android's default would delete it; here it is renamed to `screentime.db.damaged-<time>` and kept, an empty database takes its place, and both transports pull again from the start. Only the phone's own periods that weren't in a backup are then lost; export backups now and then.

## Direct sync with the laptop

The phone talks to the Windows app with its phone sync protocol (version 1, described in the Windows app's README). The phone starts every exchange; the laptop only answers.

- **Pairing code.** The QR code is JSON: `{"name", "addresses", "port", "secret", "cert_sha256"}`. Only IP addresses are accepted, so no DNS lookup is made. The code works once, for 10 minutes.
- **Certificate pin.** The connection is HTTPS with a trust manager that accepts exactly one certificate: the one whose SHA-256 (of its DER bytes) is `cert_sha256`. No certificate authority is involved. If the laptop presents any other certificate, the connection is closed before anything is sent and the status says the certificate doesn't match. The laptop's certificate names no address, so the pin, not the host name, identifies it.
- **Proof.** No secret or key is ever sent. Each request gets a one-time nonce (`GET /peer/nonce`, or the `X-Peer-Nonce` header of the previous answer) and carries `Authorization: HMAC <nonce>:<mac>`, where `mac` is the hex HMAC-SHA256, keyed with the pairing secret (for `/peer/pair`) or this phone's key (everything else), over method, path with query, nonce and the hex SHA-256 of the body, joined by newlines. Bodies are sent with a `Content-Length`.
- **Pairing.** `POST /peer/pair` answers with this phone's key, which replaces the pairing secret in the encrypted preferences once the laptop has seen it used. If the laptop can't be reached right after scanning, pairing finishes on the next sync while the code is still valid.
- <a id="finding-the-laptop"></a>**Finding the laptop.** Before each sync with the laptop, `Discovery` tries in order, and stops at the first address where the laptop answers with the pinned certificate (`GET /peer/nonce`): (1) the last known address, i.e. the one that answered last, then the pairing code's; (2) a UDP broadcast to port 47614 asking "who is <the laptop's id>?" from this phone's id, sent to 255.255.255.255 and to each network's broadcast address, which only the paired laptop answers; (3) the Wi-Fi gateway, which is the laptop when the phone uses the laptop's hotspot; (4) the address typed under Settings, Laptop. Each probe gives up after 1.5 s, the broadcast waits 1.5 s and the gateway lookup 0.5 s; each runs on a worker thread with a hard limit, so nothing can hang a sync, and discovery refuses to run on the main thread. At most 3 addresses are probed per step, and none twice. The address that answered is saved, encrypted with the pairing, and tried first next time. Before the pairing's first answer the laptop's id isn't known yet, so the broadcast is skipped.
- **If the laptop isn't found.** Look at the laptop's Connect tab: an amber note under **Allow phone sync** says if Windows Firewall blocks the phone. The usual cause is a Wi-Fi network that Windows has as **Public** while Screen Time is allowed on Private networks only. On your own network, set it to Private on the laptop (Settings, Network & internet, Wi-Fi, the network, **Network profile type**).
- **Sync.** `POST /peer/push` takes this phone's rows (the same format as the server's push), and `GET /peer/pull?since=<cursor>` gives the laptop's PC-on and active periods. The laptop also sends its app times; the phone keeps only its own, so they are left out. A sync uses the address discovery found for all its requests.

## When it syncs with the laptop

- **Sync now** and **opening the app** always try the laptop, and the status line says how it went.
- **In the background**, every 15 minutes, the laptop is tried only while the phone is on Wi-Fi (or Ethernet). A
  laptop that isn't found is never reported from the background, and the status line keeps what it said before.
  After 2 misses in a row the background waits 30 minutes, then 1, 2 and at most 4 hours between tries; any
  successful sync starts over. Sync now and opening the app don't wait.
- With **Both**, the server still syncs every 15 minutes whatever the laptop does.

The status line for the laptop:

| Situation | Status |
|---|---|
| Synced | `Synced at 12:30 · laptop found via broadcast (192.168.1.20)` |
| Not on Wi-Fi (Sync now, opening the app) | `Not on Wi-Fi, so the laptop can't be reached. It syncs again when the phone is on Wi-Fi.` |
| Laptop off or not found (Sync now, opening the app) | `Laptop not found. Is it on, with Screen Time running and Allow phone sync ticked, on the same Wi-Fi? Will try again.` |
| Wrong pairing code | `The laptop didn't accept the pairing code: it's wrong, expired or already used. Pair again with a new code.` |
| Pairing removed on the laptop | `The laptop doesn't accept this phone anymore (the pairing was removed or replaced there). Pair again.` |
| Both, laptop not found in the background | `Synced with the server at 12:30` |

**Local network permission.** Android 17 makes access to devices on your Wi-Fi a runtime permission
(`ACCESS_LOCAL_NETWORK`, in the Nearby devices group), but only for apps that target Android 17 (API 37). This app
targets 36, where the internet permission still covers it, and Google's guidance is not to declare or ask for
`ACCESS_LOCAL_NETWORK` before targeting 37, so nothing is asked today. The app is ready for the move: once it
targets 37 (and the manifest declares the permission), Settings, Laptop shows a card that opens a screen explaining
what the access is for before Android asks, and the status says so if it isn't allowed.

## Limitations

- Days follow the phone's timezone, so keep it on the timezone set for your account.
- Phone use counts screen-on time, including the lock screen.
- Reading Health Connect works while the app is open; in the background it depends on the Health Connect version.
- The laptop must be reachable on its private address, so direct sync works only on the same network (or one routed to it). Some Wi-Fi networks (guest networks, "client isolation") block broadcasts or traffic between devices; then enter the laptop's address in Settings, or it can't be reached at all.
- When the laptop uses the phone's hotspot, the Wi-Fi gateway is the phone itself, so step 3 doesn't help; the broadcast to the hotspot's own broadcast address finds the laptop there.
- Removing the pairing on the phone doesn't revoke it on the laptop; use **Remove pairing** there too.
- In **Laptop directly**, nothing syncs while the laptop is asleep, closed or on another network; the phone keeps everything and sends it later. Use **Both** to keep the server as a fallback.
- If the laptop loses its store of the phone's rows (a damaged file it set aside), they come back from the server, or from the phone after pairing again. The phone keeps a record of what the laptop already has, so it doesn't send those rows again by itself.
