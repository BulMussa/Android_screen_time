package com.screentime.mobile

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { SETUP, HOME, SLEEP, SETTINGS, LOCAL_NETWORK }

@Composable
fun AppRoot() {
    // A paired laptop counts as set up too: "Laptop directly" needs no server
    var screen by remember { mutableStateOf(if (Graph.prefs.connected || Graph.prefs.peer != null) Screen.HOME else Screen.SETUP) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { // the background sync may have brought new data
            delay(5_000)
            tick++
        }
    }
    BackHandler(enabled = screen != Screen.HOME && screen != Screen.SETUP) {
        screen = if (screen == Screen.LOCAL_NETWORK) Screen.SETTINGS else Screen.HOME
    }
    val page = Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)
    when (screen) {
        Screen.SETUP -> SetupScreen(page, onDone = { screen = Screen.HOME })
        Screen.HOME -> HomeScreen(page, tick, onSleep = { screen = Screen.SLEEP }, onSettings = { screen = Screen.SETTINGS })
        Screen.SLEEP -> SleepScreen(page, tick, onBack = { screen = Screen.HOME })
        Screen.SETTINGS -> SettingsScreen(
            page, tick, onBack = { screen = Screen.HOME },
            onDisconnected = { screen = Screen.SETUP },
            onLocalNetwork = { screen = Screen.LOCAL_NETWORK },
        )
        Screen.LOCAL_NETWORK -> LocalNetworkScreen(page, onDone = { screen = Screen.SETTINGS })
    }
}

@Composable
private fun SetupScreen(modifier: Modifier, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var device by remember { mutableStateOf(Build.MODEL ?: "Phone") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Screen Time", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Connect to your own server so your laptop, phone and sleep show up together. " +
                "The password is only used to register this phone and is never saved.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Server address (https://…)") }, singleLine = true)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(device, { device = it }, Modifier.fillMaxWidth(), label = { Text("Device name") }, singleLine = true)
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = ""
                    scope.launch {
                        val problem = withContext(Dispatchers.IO) {
                            try {
                                Api.connect(server, name, password, device)
                                null
                            } catch (e: SyncException) {
                                e.message ?: "Couldn't connect"
                            }
                        }
                        busy = false
                        if (problem == null) {
                            password = ""
                            Scheduler.syncNow(context)
                            onDone()
                        } else {
                            error = problem
                        }
                    }
                },
            ) { Text(if (busy) "Connecting…" else "Connect") }
            OutlinedButton(onClick = onDone) { Text("Use without a server") }
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier, tick: Int, onSleep: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var day by remember { mutableStateOf(LocalDate.now()) }
    var info by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(Graph.prefs.status) }
    var syncing by remember { mutableStateOf(false) }
    val rows = remember(day, tick) { Timelines.rows(day) }
    val today = LocalDate.now()
    val hasUsage = remember(tick) { UsageCollector.hasAccess(context) }
    val sleepMethod = remember(tick) { Graph.prefs.sleepMethod }
    LaunchedEffect(tick) { if (!syncing) status = Graph.prefs.status }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Screen Time", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onSleep) { Text("Sleep") }
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { day = day.minusDays(1); info = "" }) { Text("‹") }
            Text(
                day.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (day != today) TextButton(onClick = { day = today; info = "" }) { Text("Today") }
            TextButton(enabled = day < today, onClick = { day = day.plusDays(1); info = "" }) { Text("›") }
        }
        TimelineView(rows, showNow = day == today) { info = it }
        Text(
            if (info.isEmpty()) "Tap a bar to see its times." else info,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            OutlinedButton(
                enabled = !syncing,
                onClick = {
                    syncing = true
                    status = "Syncing…"
                    scope.launch {
                        status = Pipeline.run(context, Trigger.MANUAL)
                        syncing = false
                    }
                },
            ) { Text("Sync now") }
        }

        if (!hasUsage) {
            HintCard(
                "Allow usage access",
                "Screen Time needs it to see when you use your phone. Find Screen Time in the list and switch it on.",
                "Open settings",
            ) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        if (sleepMethod == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("How do you measure sleep?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "With a smartwatch or band, sleep can come from Health Connect. Otherwise you add it by hand, " +
                            "and a reminder asks you each morning.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { Graph.prefs.sleepMethod = "band" }) { Text("Smartwatch or band") }
                        OutlinedButton(onClick = { Graph.prefs.sleepMethod = "manual" }) { Text("Manually") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HintCard(title: String, text: String, button: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun TimelineView(rows: List<TimelineRow>, showNow: Boolean, onInfo: (String) -> Unit) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val gap = MaterialTheme.colorScheme.surface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val nowColor = if (isSystemInDarkTheme()) COLOR_NOW_DARK else COLOR_NOW
    val nowText = MaterialTheme.colorScheme.onSurface
    val nowStyle = MaterialTheme.typography.bodyMedium // the now-line's time, a bit bigger than the hours
    val measurer = rememberTextMeasurer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val bars = remember { mutableStateMapOf<Int, Rect>() } // each row's bar, in root coordinates

    Column(
        Modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .drawWithContent {
                drawContent()
                if (!showNow || bars.isEmpty()) return@drawWithContent
                // One line across all three rows, from just above the first bar to just below the last
                val first = bars.values.minBy { it.top }
                val last = bars.values.maxBy { it.bottom }
                val now = LocalTime.now()
                val x = first.left - origin.x + first.width * now.toSecondOfDay() / 86_400f
                val w = 3.dp.toPx()
                val y1 = first.top - origin.y - 3.dp.toPx()
                val y2 = last.bottom - origin.y + 3.dp.toPx()
                // Round caps reach half the width past each end, so the ends are moved in by that much.
                drawLine(nowColor, Offset(x, y1 + w / 2), Offset(x, y2 - w / 2), strokeWidth = w, cap = StrokeCap.Round)
                // The time on top of the line, centered on it but kept inside the view near the edges
                val label = measurer.measure(now.format(DateTimeFormatter.ofPattern("HH:mm")), nowStyle)
                val half = label.size.width / 2f
                val xl = minOf(maxOf(x, half + 1), size.width - half - 1)
                drawText(label, color = nowText, topLeft = Offset(xl - half, y1 - label.size.height))
            },
    ) {
        Spacer(Modifier.height(22.dp)) // room on top for the now-line's time
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(78.dp)) {
                    Text(row.name, style = MaterialTheme.typography.labelLarge)
                    Text(row.summary, style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(28.dp)
                        .onGloballyPositioned { bars[index] = it.boundsInRoot() }
                        .pointerInput(row) {
                            detectTapGestures { pos ->
                                val second = (pos.x / size.width * 86_400).toLong()
                                val slack = (4.dp.toPx() / size.width * 86_400).toLong()
                                val hit = row.parts.reversed().firstNotNullOfOrNull { part ->
                                    part.spans.firstOrNull { second >= it.start - slack && second <= it.end + slack }
                                        ?.let { part to it }
                                }
                                onInfo(if (hit == null) "" else Timelines.info(hit.first.title, hit.second))
                            }
                        },
                ) {
                    // A bar with fully round ends: the periods are cut to its shape, square in the middle.
                    val pill = Path().apply {
                        addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(size.height / 2)))
                    }
                    clipPath(pill) {
                        drawRect(track, size = size)
                        for (h in 1..23) { // thin gaps divide the gray bar into hours; the colored spans cover them
                            val x = size.width * h / 24f
                            drawLine(gap, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                        }
                        row.parts.forEach { part ->
                            part.spans.forEach { s ->
                                val x1 = size.width * s.start / 86_400f
                                val x2 = size.width * s.end / 86_400f
                                drawRect(part.color, Offset(x1, 0f), Size(maxOf(x2 - x1, 1.dp.toPx()), size.height))
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 78.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

private fun pickTime(context: Context, initial: LocalTime, onPicked: (LocalTime) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPicked(LocalTime.of(h, m)) }, initial.hour, initial.minute, true).show()
}

@Composable
private fun SleepScreen(modifier: Modifier, tick: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    var wakeDay by remember { mutableStateOf(LocalDate.now()) }
    var bed by remember { mutableStateOf(LocalTime.of(23, 0)) }
    var wake by remember { mutableStateOf(LocalTime.of(7, 0)) }
    var error by remember { mutableStateOf("") }
    val zone = ZoneId.systemDefault()
    val entries = remember(tick, reload) {
        val now = System.currentTimeMillis() / 1000
        Graph.db.overlapping(listOf("sleep"), now - 8 * 86_400, now + 86_400).sortedByDescending { it.start }
    }
    val time = DateTimeFormatter.ofPattern("EEE d MMM  HH:mm")
    val clock = DateTimeFormatter.ofPattern("HH:mm")

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Sleep", style = MaterialTheme.typography.headlineSmall)
        }
        Text("Add sleep by hand", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Woke up on", modifier = Modifier.weight(1f))
            TextButton(onClick = { wakeDay = wakeDay.minusDays(1) }) { Text("‹") }
            Text(wakeDay.format(DateTimeFormatter.ofPattern("EEE d MMM")))
            TextButton(enabled = wakeDay < LocalDate.now(), onClick = { wakeDay = wakeDay.plusDays(1) }) { Text("›") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickTime(context, bed) { bed = it } }) { Text("Fell asleep ${bed.format(clock)}") }
            OutlinedButton(onClick = { pickTime(context, wake) { wake = it } }) { Text("Woke up ${wake.format(clock)}") }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            val wakeAt = wakeDay.atTime(wake).atZone(zone).toEpochSecond()
            val bedDay = if (bed.isAfter(wake)) wakeDay.minusDays(1) else wakeDay // the night before
            val bedAt = bedDay.atTime(bed).atZone(zone).toEpochSecond()
            if (wakeAt <= bedAt) {
                error = "Waking up must be after falling asleep."
            } else if (wakeAt > System.currentTimeMillis() / 1000) {
                error = "That is in the future."
            } else {
                error = ""
                Graph.db.saveLocal(Interval(UUID.randomUUID().toString(), "sleep", bedAt, wakeAt, "manual", 0, true, false))
                Scheduler.syncNow(context)
                reload++
            }
        }) { Text("Save") }

        Spacer(Modifier.height(8.dp))
        Text("Last 7 days", style = MaterialTheme.typography.titleSmall)
        if (entries.isEmpty()) Text("No sleep recorded yet.", style = MaterialTheme.typography.bodySmall)
        entries.forEach { e ->
            val start = Instant.ofEpochSecond(e.start).atZone(zone)
            val end = Instant.ofEpochSecond(e.end).atZone(zone)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${start.format(time)} – ${end.format(clock)}")
                    Text(
                        "${short(e.end - e.start)} · " + when (e.source) {
                            "manual" -> "added by hand"
                            "health_connect" -> "Health Connect"
                            else -> e.source
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (e.source == "manual") {
                    TextButton(onClick = {
                        Graph.db.saveLocal(e.copy(deleted = true))
                        Scheduler.syncNow(context)
                        reload++
                    }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    tick: Int,
    onBack: () -> Unit,
    onDisconnected: () -> Unit,
    onLocalNetwork: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var healthMessage by remember { mutableStateOf("") }
    val prefs = Graph.prefs
    val hasUsage = remember(tick, reload) { UsageCollector.hasAccess(context) }
    val method = remember(reload) { prefs.sleepMethod }

    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        healthMessage = if (granted.containsAll(SleepCollector.permissions)) "Sleep access allowed." else "Sleep access not allowed."
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { SleepCollector.scan(context) } }
            Scheduler.syncNow(context)
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { reload++ }
    val mode = remember(reload) { prefs.syncMode }
    var backupMessage by remember { mutableStateOf("") }
    var backupBusy by remember { mutableStateOf(false) }

    fun backupTask(work: () -> String) {
        backupBusy = true
        backupMessage = ""
        scope.launch {
            backupMessage = withContext(Dispatchers.IO) { work() }
            backupBusy = false
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) backupTask {
            try {
                val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("can't open the file")
                val (own, others) = stream.use { Backup.export(it) }
                "Backup saved: $own rows of this phone, $others from other devices."
            } catch (e: Exception) {
                "Couldn't save the backup: ${e.message}"
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backupTask {
            try {
                val stream = context.contentResolver.openInputStream(uri) ?: throw BackupException("Couldn't open the file.")
                val result = stream.use { Backup.import(it) }
                Scheduler.syncNow(context)
                "Backup imported: ${result.stored} periods and ${result.usage} app times were newer and merged in" +
                    " (${result.read} rows read)." +
                    if (result.skipped > 0) " ${result.skipped} app times of another device were left out." else ""
            } catch (e: Exception) { // BackupException, or the file can't be read
                "Couldn't import: ${e.message}"
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        Text("Server", style = MaterialTheme.typography.titleSmall)
        if (prefs.connected) {
            Text("Connected to ${prefs.server} as \"${prefs.deviceName}\".")
            Text(prefs.status, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirm = true }) { Text("Disconnect") }
        } else {
            Text("Not connected. Phone use is still recorded on this phone.")
            Button(onClick = onDisconnected) { Text("Connect") }
        }

        Spacer(Modifier.height(8.dp))
        Text("Sync with", style = MaterialTheme.typography.titleSmall)
        listOf("server" to "Server", "direct" to "Laptop directly", "both" to "Both").forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = mode == value, role = Role.RadioButton, onClick = {
                    prefs.syncMode = value
                    reload++
                    Scheduler.syncNow(context)
                }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == value, onClick = null) // the whole row is the button
                Text(label)
            }
        }
        if (mode != "server" && prefs.peer == null) {
            Text("Pair with your laptop below to sync with it directly.", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        LaptopSection(reload, onLocalNetwork) { reload++ }

        Spacer(Modifier.height(8.dp))
        Text("Backup", style = MaterialTheme.typography.titleSmall)
        Text(
            "A JSON file with everything on this phone. Importing merges a backup in (also one from the Windows app); " +
                "for each period the newer copy wins.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !backupBusy, onClick = { exportLauncher.launch(Backup.fileName()) }) { Text("Export backup") }
            OutlinedButton(enabled = !backupBusy, onClick = {
                importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
            }) { Text("Import backup") }
        }
        if (backupMessage.isNotEmpty()) Text(backupMessage, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(8.dp))
        Text("Phone use", style = MaterialTheme.typography.titleSmall)
        Text(if (hasUsage) "Usage access is on." else "Usage access is off, so phone use can't be recorded.")
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
            Text("Open usage access settings")
        }

        Spacer(Modifier.height(8.dp))
        Text("Sleep", style = MaterialTheme.typography.titleSmall)
        listOf("band" to "Smartwatch or band (Health Connect)", "manual" to "Manually").forEach { (value, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = method == value, onClick = { prefs.sleepMethod = value; reload++ })
                Text(label)
            }
        }
        if (method == "band") {
            Text(
                "Your band's app (for example Mi Fitness) must share sleep with Health Connect. " +
                    "Check its settings under data sharing.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!SleepCollector.available(context)) {
                        healthMessage = "Health Connect isn't available. Install or update it from Google Play."
                    } else {
                        healthLauncher.launch(SleepCollector.permissions)
                    }
                }) { Text("Allow sleep access") }
            }
            if (healthMessage.isNotEmpty()) Text(healthMessage, style = MaterialTheme.typography.bodySmall)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            OutlinedButton(onClick = { notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("Allow sleep reminders")
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Disconnect from the server?") },
            text = { Text("What was already sent stays there, and this phone stops sending.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    Api.disconnect()
                    onDisconnected()
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}


/**
 * Pairing with the laptop for direct sync: scan its QR code (the camera is asked for with an explanation
 * first) or type the code by hand. The pairing secret, the key and the pinned certificate are kept in the
 * encrypted preferences; "Remove pairing" deletes them.
 */
@Composable
private fun LaptopSection(reload: Int, onLocalNetwork: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = Graph.prefs
    val peer = remember(reload) { prefs.peer }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var explainCamera by remember { mutableStateOf(false) }
    var cameraBlocked by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    /** Saves a pairing code and asks the laptop for this phone's key right away. */
    fun usePairing(pairing: PeerPairing) {
        DirectTransport.savePairing(pairing)
        val switched = when (prefs.syncMode) { // pairing means: sync with it
            "server" -> if (prefs.connected) "both" else "direct"
            else -> null
        }
        if (switched != null) prefs.syncMode = switched
        onChanged()
        busy = true
        message = "Pairing with the laptop…"
        scope.launch {
            message = withContext(Dispatchers.IO) {
                val result = try {
                    synchronized(SyncEngine.lock) { DirectTransport(pairing, context).pair() }
                    "Paired with ${prefs.peer?.name ?: "the laptop"}."
                } catch (e: SyncException) {
                    when {
                        e is LaptopNotFoundException || e is NoWifiException ->
                            "Saved, but the laptop couldn't be reached yet. ${e.message} Pairing finishes on the next sync " +
                                "while the code is still valid (10 minutes)."
                        else -> SyncStatus.problem("direct", e) // wrong code, wrong certificate, …
                    }
                }
                result + when (switched) {
                    "both" -> " Sync with is now Both."
                    "direct" -> " Sync with is now Laptop directly."
                    else -> ""
                }
            }
            busy = false
            onChanged()
            Scheduler.syncNow(context)
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult // closed without scanning
        try {
            usePairing(PairingCode.parse(text))
        } catch (e: PairingCodeException) {
            message = e.message ?: "This isn't a Screen Time pairing code."
        }
    }

    fun scan() {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the code from Pair phone in Screen Time on your laptop")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scan()
        } else {
            val activity = context.findActivity()
            // No rationale to show again means "don't ask again": only system settings can allow it now.
            cameraBlocked = activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            message = "Without the camera, enter the pairing code by hand."
        }
    }

    Text("Laptop", style = MaterialTheme.typography.titleSmall)
    val needsLocalNetwork = remember(reload) { LocalNetworkAccess.missing(context) }
    if (needsLocalNetwork) {
        HintCard(
            "Allow local network access",
            "Syncing with your laptop needs access to devices on your Wi-Fi.",
            "Why and how",
            onLocalNetwork,
        )
    }
    if (peer != null) {
        Text(
            "Paired with \"${peer.name}\", " +
                (peer.lastKnown?.let { "last found at $it." } ?: "at ${peer.addresses.joinToString(", ")} (port ${peer.port})."),
        )
        Text(
            "Certificate ${peer.certSha256.take(16).chunked(2).joinToString(":").uppercase()}…" +
                if (peer.key == null) " · Waiting for the laptop to confirm the pairing." else "",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            "Sync with your Windows laptop over your own network, without a server. On the laptop, open Screen Time, " +
                "choose Pair phone in the Connect tab, then scan the code it shows.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = {
            message = ""
            cameraBlocked = false
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                scan()
            } else {
                explainCamera = true // say why before Android asks
            }
        }) { Text(if (peer == null) "Pair with laptop" else "Pair again") }
        OutlinedButton(enabled = !busy, onClick = { message = ""; manual = true }) { Text("Enter code") }
    }
    if (peer != null) {
        OutlinedButton(enabled = !busy, onClick = { confirmRemove = true }) { Text("Remove pairing") }
        // Step 4 of finding the laptop: tried after the last known address, a broadcast and the Wi-Fi gateway
        var manualText by remember(reload) { mutableStateOf(peer.manual?.toString() ?: "") }
        var manualError by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                manualText, { manualText = it; manualError = "" }, Modifier.weight(1f),
                label = { Text("Laptop's address, if it isn't found") },
                placeholder = { Text("192.168.1.20") },
                singleLine = true,
            )
            TextButton(onClick = {
                if (manualText.isBlank()) {
                    DirectTransport.setManualAddress(null)
                    message = "Address cleared."
                    onChanged()
                } else {
                    val endpoint = Endpoint.parse(manualText, peer.port)
                    if (endpoint == null) {
                        manualError = "Enter an IP address, e.g. 192.168.1.20 or 192.168.1.20:${peer.port}."
                    } else {
                        DirectTransport.setManualAddress(endpoint)
                        message = "Saved. It's tried when the laptop isn't found otherwise."
                        onChanged()
                        Scheduler.syncNow(context)
                    }
                }
            }) { Text("Save") }
        }
        if (manualError.isNotEmpty()) Text(manualError, color = MaterialTheme.colorScheme.error)
        Text(
            "The phone looks for the laptop at its last known address, then with a broadcast on your network, then at " +
                "the Wi-Fi gateway (when the phone uses the laptop's hotspot), and last at this address.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
    if (cameraBlocked) {
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }) { Text("Allow the camera in settings") }
    }

    if (explainCamera) {
        AlertDialog(
            onDismissRequest = { explainCamera = false },
            title = { Text("Use the camera to scan the code?") },
            text = {
                Text(
                    "Screen Time needs the camera to read the pairing QR code on your laptop's screen. It is only on " +
                        "while the scanner is open; the code is read on this phone and no picture is saved or sent. " +
                        "If you'd rather not, choose Enter code instead.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explainCamera = false
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { explainCamera = false }) { Text("Not now") } },
        )
    }

    if (manual) {
        var address by remember { mutableStateOf("") }
        var port by remember { mutableStateOf(PairingCode.DEFAULT_PORT.toString()) }
        var secret by remember { mutableStateOf("") }
        var fingerprint by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { manual = false },
            title = { Text("Enter the pairing code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Copy these from the laptop's pairing window.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Laptop's IP address") }, singleLine = true)
                    OutlinedTextField(port, { port = it }, Modifier.fillMaxWidth(), label = { Text("Port") }, singleLine = true)
                    OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), label = { Text("Pairing secret") }, singleLine = true)
                    OutlinedTextField(
                        fingerprint, { fingerprint = it }, Modifier.fillMaxWidth(),
                        label = { Text("Certificate fingerprint (SHA-256)") },
                    )
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val pairing = PairingCode.manual(address, port, secret, fingerprint)
                        manual = false
                        usePairing(pairing)
                    } catch (e: PairingCodeException) {
                        error = e.message ?: ""
                    }
                }) { Text("Pair") }
            },
            dismissButton = { TextButton(onClick = { manual = false }) { Text("Cancel") } },
        )
    }

    if (confirmRemove && peer != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the pairing?") },
            text = {
                Text(
                    "This phone forgets \"${peer.name}\" and its key, and stops syncing with it. What was already " +
                        "synced stays. To revoke this phone on the laptop too, choose Remove pairing there.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    DirectTransport.removePairing()
                    if (prefs.syncMode != "server" && prefs.connected) prefs.syncMode = "server"
                    message = "Pairing removed." + if (prefs.syncMode == "server") " Sync with is now Server." else ""
                    onChanged()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}


/**
 * Why direct sync needs Android's local network permission, before Android asks. Only shown where the
 * permission exists and applies: Android 17 and later, with the app targeting API 37 or later. Until then
 * (this app targets 36) local network access comes with the internet permission and nothing is asked.
 */
@Composable
private fun LocalNetworkScreen(modifier: Modifier, onDone: () -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf("") }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Scheduler.syncNow(context)
            onDone()
        } else {
            message = "Not allowed. Without it the phone can still sync through your server; to sync with the laptop, " +
                "allow Nearby devices for Screen Time in the system settings."
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("‹ Back") }
            Text("Local network access", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            "To sync with your laptop directly, Screen Time talks to it over your own Wi-Fi: it looks for the laptop " +
                "with a short broadcast, then connects to it. Android asks for permission before an app may reach " +
                "devices on your local network.",
        )
        Text("What it's used for", style = MaterialTheme.typography.titleSmall)
        Text(
            "Only to find your paired laptop and exchange your screen time and sleep periods with it. The connection " +
                "is encrypted and checked against the laptop's certificate from pairing. Nothing else on your network " +
                "is contacted, and nothing goes to the internet this way.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("If you don't allow it", style = MaterialTheme.typography.titleSmall)
        Text(
            "Sync through your server keeps working. You can allow it later here or under Nearby devices in the " +
                "system settings.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { request.launch(LocalNetwork.PERMISSION) }) { Text("Continue") }
            OutlinedButton(onClick = onDone) { Text("Not now") }
        }
        if (message.isNotEmpty()) {
            Text(message, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }) { Text("Open app settings") }
        }
    }
}
