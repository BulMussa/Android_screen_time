# Screen Time for Android

The phone side of Screen Time. It records when you use your phone, reads sleep from Health Connect (or lets you add it by hand), and syncs with your own Screen Time server. Everything can also be exported to a backup file and imported again. One window shows three 24-hour timelines for a chosen day: laptop, phone and sleep. The laptop row comes from the Windows app through the server.

Everything stays between this phone and your server; nothing goes anywhere else.

## What it does

- **Phone use:** reads Android's usage statistics (needs the *Usage access* permission) and records when the screen was on and how long each app was in the foreground. A background job runs about every 15 minutes, and again whenever the app is opened.
- **Sleep:** the first time, choose how you measure it. *Smartwatch or band* reads sleep sessions from Health Connect, so your band's app (for example Mi Fitness) must share sleep with Health Connect. *Manually* lets you add bed and wake times. If no sleep is recorded for last night, a reminder notification comes once a day after 09:00.
- **Sync:** sends changes to the server and fetches what other devices sent (laptop periods, in particular), every 15 minutes and on demand. It works offline: everything is kept in a local database and sent later.
- **Sync with** (in Settings): **Server** (the default), **PC directly** or **Both**. Each way of syncing is a transport with two calls: send the rows that changed, and fetch the rows changed since a cursor. Each transport keeps its own record of what it already sent and where its last pull stopped, so switching never sends rows twice. Syncing directly with the PC over your own network isn't built yet, so for now **PC directly** syncs nothing and **Both** works like **Server**, the same as in the Windows app.
- **Backup** (in Settings): **Export backup** saves a JSON file, through the system file picker, with this phone's periods and app times (in the same format that is sent to the server) and the periods it has from other devices, deleted ones included. **Import backup** merges a backup in: every row has an `updated_at`, and the newer copy wins, the same rule a sync uses, so an old backup can't bring back a period that was deleted since. Backups use the same format as the Windows app's, so a PC backup can be imported here and the other way round. Periods are merged from any backup; this phone's own periods and app times only count as its own (and are sent on the next sync) when the backup is from this same install. From another install they are kept and shown, but not sent, and another device's app times are left out.
- **Security:** the server address must be HTTPS. The password is used once to register the phone and is never stored. The device token is kept encrypted with a key from the Android Keystore.

## Requirements

- Android 9 (API 28) or newer.
- A running Screen Time server reachable from the phone (for example over Tailscale, with the Tailscale app connected).
- For sleep from a band: Health Connect (built in from Android 14, a Google Play app before that).

## Build

Open this folder in Android Studio and run the `app` configuration on a phone with USB debugging, or build an APK from a terminal. Gradle 9 needs a JDK 17 or newer, e.g. the one inside Android Studio (`jbr` folder):

```bash
export JAVA_HOME="<Android Studio folder>/jbr"
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. With a phone connected over USB debugging, `adb install -r app/build/outputs/apk/debug/app-debug.apk` installs it.

`local.properties` (not in git) must point to the Android SDK: `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`; Android Studio writes it for you.

## Project structure

```
app/src/main/java/com/screentime/mobile/
  ScreenTimeApp.kt   starts the background job
  MainActivity.kt    the window; reads and syncs when opened
  Ui.kt              screens: setup, timelines, sleep, settings
  Timeline.kt        builds the three rows for a day from the database
  Collectors.kt      phone use (usage statistics) and sleep (Health Connect)
  SyncEngine.kt      sync mode; push and pull over each transport
  Transport.kt       the Transport interface, the server transport, rows as JSON
  Backup.kt          backup export and import
  Api.kt             HTTPS calls, connecting and disconnecting
  Work.kt            background job, scheduling and the sleep reminder
  Db.kt              local SQLite database (what was sent is kept per transport)
  Prefs.kt           settings (sync mode, pull cursors) and the encrypted device token
  Graph.kt           shared helpers
```

## Limitations

- Days follow the phone's timezone, so keep it on the timezone set for your account.
- Phone use counts screen-on time, including the lock screen.
- Reading Health Connect works while the app is open; in the background it depends on the Health Connect version.
- Connecting to a server, disconnecting, or the server not knowing the pull cursor clears the periods from other devices and pulls them again. That also clears periods imported from another device's backup that the server doesn't have, so import a backup after connecting.
