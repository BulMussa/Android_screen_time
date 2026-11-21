# Rules for AI agents working on this project

## Rebuild the APK after every new feature

- There is no need to stop the app before implementing or testing a feature.
- When a feature is done and tested, rebuild the APK with `./gradlew assembleDebug` (with `JAVA_HOME` pointing at a JDK 17 or newer, e.g. Android Studio's `jbr` folder). The APK is `app/build/outputs/apk/debug/app-debug.apk`.
- Don't create commits and don't push. Leave the changes in the working tree for the user to review.

## Test every new feature with everything it needs

- Test each feature for real, not only by reading the code:
  - Build it with warnings in mind: `./gradlew assembleDebug` must succeed, and new compiler warnings in the changed code should be fixed.
  - Run the unit tests on the computer: `./gradlew testDebugUnitTest`. Add unit tests for new logic, keeping it in files with no Android classes where possible (like `SyncCore.kt`, `SyncPolicy.kt`, `Discovery.kt`, `Peer.kt`).
  - When a phone is connected over USB debugging, run the tests on the phone too: `./gradlew assembleDebug assembleDebugAndroidTest`, install both APKs with `adb install -r`, then `adb shell am instrument -w com.screentime.mobile.test/androidx.test.runner.AndroidJUnitRunner`.
  - Exercise the UI that changed: the timelines, sleep, settings and setup screens. Take screenshots when the look matters.
  - Check that the rebuilt APK installs and starts.
  - Cover edge cases, simulating them when they can't happen live: midnight, timezone changes, no network, the server or the laptop unreachable, a wrong pairing code or certificate, an interrupted database upgrade, a damaged database, old backups.
- Tests must not change the user's real data or settings:
  - Never use `./gradlew connectedAndroidTest` on the user's phone: it uninstalls the app afterwards, which deletes its database.
  - Always install with `adb install -r`, which keeps the app's data. Never uninstall the app or clear its data.
  - Tests that touch the database use throwaway files or copies, never the real `screentime.db`.
  - Tests must not send fake rows to the user's real server or paired laptop, and must not change the pairing, the server connection or the sync mode. Use fakes or a scratch listener instead.
- Screenshot only the app's own screens. Never keep screenshots that captured the user's other apps or notifications.
- Report what was tested and what couldn't be tested (for example, tests that need a phone when none is connected).
