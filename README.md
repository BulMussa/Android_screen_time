# Screen Time for Android

The phone side of Screen Time. It records when you use your phone, reads sleep from Health Connect (or lets you add it by hand), and syncs with your own Screen Time server. One window shows three 24-hour timelines for a chosen day: laptop, phone and sleep. The laptop row comes from the Windows app through the server.

Everything stays between this phone and your server; nothing goes anywhere else.

## What it does

- **Phone use:** reads Android's usage statistics (needs the *Usage access* permission) and records when the screen was on and how long each app was in the foreground. A background job runs about every 15 minutes, and again whenever the app is opened.
- **Sleep:** the first time, choose how you measure it. *Smartwatch or band* reads sleep sessions from Health Connect, so your band's app (for example Mi Fitness) must share sleep with Health Connect. *Manually* lets you add bed and wake times. If no sleep is recorded for last night, a reminder notification comes once a day after 09:00.
- **Sync:** sends changes to the server and fetches what other devices sent (laptop periods, in particular), every 15 minutes and on demand. It works offline: everything is kept in a local database and sent later.
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
  SyncEngine.kt      push and pull with the server
  Api.kt             HTTPS calls, connecting and disconnecting
  Work.kt            background job, scheduling and the sleep reminder
  Db.kt              local SQLite database
  Prefs.kt           settings and the encrypted device token
  Graph.kt           shared helpers
```

## Limitations

- Days follow the phone's timezone, so keep it on the timezone set for your account.
- Phone use counts screen-on time, including the lock screen.
- Reading Health Connect works while the app is open; in the background it depends on the Health Connect version.
