package com.screentime.mobile

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs what the 15-minute background job runs, once, now: WorkManager doesn't run periodic work early,
 * even when forced. It acts on the phone's real data, exactly like the background job does. Skipped unless
 * asked for:
 *
 *   adb shell am instrument -w -e class com.screentime.mobile.BackgroundRunTest -e runBackgroundSync true
 *     com.screentime.mobile.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Reports the status line and the backoff state in the instrumentation output.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundRunTest {
    @Test
    fun runOnce() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e runBackgroundSync true", args.getString("runBackgroundSync") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val status = runBlocking { Pipeline.run(instrumentation.targetContext, Trigger.BACKGROUND) }
        val prefs = Graph.prefs
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("status", status)
                putString("misses", prefs.directMisses.toString())
                putString("lastAttemptMs", prefs.directLastAttemptMs.toString())
                putString("onLocalNetwork", Net.onLocalNetwork(instrumentation.targetContext).toString())
            },
        )
    }
}
