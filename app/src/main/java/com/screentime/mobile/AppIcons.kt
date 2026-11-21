package com.screentime.mobile

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * The names of the phone's launchers (the home screen apps, e.g. "System launcher"), which the app list leaves
 * out. Settings' fallback home screen (priority -1000) only shows while the phone starts, so Settings stays in.
 */
object Launchers {
    @Volatile private var labels: Set<String>? = null

    fun labels(context: Context): Set<String> = labels ?: runCatching {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.queryIntentActivities(home, 0)
            .filter { it.priority >= 0 }
            .map { pm.getApplicationLabel(it.activityInfo.applicationInfo).toString() }
            .toSet()
    }.getOrDefault(emptySet()).also { labels = it }
}

/**
 * App icons for the home screen's app list. App times are saved by the app's name (its label), so the name is
 * looked up among the installed apps; when several share it, one that can be launched is used. An app that is
 * gone gets no icon. Call off the main thread: the first call reads every installed app's label.
 */
object AppIcons {
    private const val SIZE_PX = 96
    private const val RELOAD_MS = 5 * 60_000L // an unknown name reads the installed apps again, at most this often

    private val icons = HashMap<String, ImageBitmap?>() // label -> icon (null: the app has none we can read)
    private var packages: Map<String, String> = emptyMap() // label (or package name) -> package
    private var loadedMs = 0L

    @Synchronized
    fun get(context: Context, label: String): ImageBitmap? {
        if (icons.containsKey(label)) return icons[label]
        if (label !in packages && nowMs() - loadedMs > RELOAD_MS) load(context)
        val pkg = packages[label] ?: return null // not cached, so an app installed later is found on a reload
        val icon = runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(SIZE_PX, SIZE_PX).asImageBitmap()
        }.getOrNull()
        icons[label] = icon
        return icon
    }

    private fun load(context: Context) {
        val pm = context.packageManager
        val byLabel = HashMap<String, MutableList<String>>()
        for (info in pm.getInstalledApplications(0)) {
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrNull() ?: continue
            byLabel.getOrPut(label) { ArrayList() }.add(info.packageName)
        }
        val map = HashMap<String, String>()
        for (list in byLabel.values) for (pkg in list) map[pkg] = pkg // the collector saves the package when there is no label
        for ((label, list) in byLabel) {
            map[label] = if (list.size == 1) list[0] else list.firstOrNull { pm.getLaunchIntentForPackage(it) != null } ?: list[0]
        }
        packages = map
        loadedMs = nowMs()
    }
}
