package com.screentime.mobile

import android.os.Build
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate

class BackupException(message: String) : Exception(message)

/**
 * Backups are a JSON file in the same format as the Windows app's:
 * {"format": "screentime-backup", "version": 1, "exported_at", "device": {install_id, type, name},
 *  "own": {"intervals", "app_usage_daily"}, "remote": {"intervals"}}.
 * Rows are in the format sent to the server and carry updated_at; importing keeps the newer copy of
 * each row, the same rule a sync uses. Deleted rows are included, so an import also learns deletions.
 */
object Backup {
    const val FORMAT = "screentime-backup"
    const val VERSION = 1

    /** [stored] intervals and [usage] rows that the import changed; [skipped] app times of another device. */
    data class Imported(val read: Int, val stored: Int, val usage: Int, val skipped: Int)

    fun fileName(): String = "screentime-backup-${LocalDate.now()}.json"

    /** Writes a backup of everything on the phone. Returns (own rows, rows from other devices). */
    fun export(out: OutputStream): Pair<Int, Int> {
        val json: JSONObject
        val counts: Pair<Int, Int>
        synchronized(SyncEngine.lock) {
            val prefs = Graph.prefs
            val (own, remote) = Graph.db.allIntervals().partition { it.mine }
            val usage = Graph.db.allUsage()
            json = JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("exported_at", Instant.now().toString())
                .put(
                    "device",
                    JSONObject().put("install_id", prefs.installId).put("type", "android")
                        .put("name", prefs.deviceName ?: Build.MODEL ?: "Phone"),
                )
                .put(
                    "own",
                    JSONObject()
                        .put("intervals", JSONArray().also { a -> own.forEach { a.put(Rows.intervalJson(it)) } })
                        .put("app_usage_daily", JSONArray().also { a -> usage.forEach { a.put(Rows.usageJson(it)) } }),
                )
                .put("remote", JSONObject().put("intervals", JSONArray().also { a -> remote.forEach { a.put(Rows.intervalJson(it)) } }))
            counts = own.size + usage.size to remote.size
        }
        out.bufferedWriter(Charsets.UTF_8).use { it.write(json.toString()) }
        return counts
    }

    /**
     * Merges a backup in. Intervals from any backup are merged (from the Windows app, a phone or this
     * phone); this phone's own rows and app times only from a backup of this same install. Throws
     * [BackupException], and then nothing was changed.
     */
    fun import(input: InputStream): Imported {
        val text = try {
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: java.io.IOException) {
            throw BackupException("Couldn't read the file: ${e.message}")
        }
        val backup = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw BackupException("This isn't a Screen Time backup (not a JSON file).")
        }
        if (backup.optString("format") != FORMAT) throw BackupException("This isn't a Screen Time backup.")
        val version = backup.opt("version")
        if (version !is Int) throw BackupException("This isn't a Screen Time backup.")
        if (version > VERSION) throw BackupException("This backup is from a newer version of Screen Time. Update the app to import it.")
        val own = section(backup, "own")
        val remote = section(backup, "remote")
        val device = section(backup, "device")

        val sameInstall = device.optString("install_id") == Graph.prefs.installId
        val intervals = ArrayList<Interval>()
        val usage = ArrayList<Usage>()
        try {
            for (r in rows(remote, "intervals")) intervals.add(Rows.parseInterval(r, mine = false))
            for (r in rows(own, "intervals")) intervals.add(Rows.parseInterval(r, mine = sameInstall))
            if (sameInstall) for (r in rows(own, "app_usage_daily")) usage.add(Rows.parseUsage(r))
        } catch (e: JSONException) {
            throw BackupException("The backup has a damaged row (${e.message}).")
        } catch (e: DateTimeException) {
            throw BackupException("The backup has a damaged row (${e.message}).")
        }
        // Phones keep only their own app times; another device's are left out.
        val skipped = if (sameInstall) 0 else rows(own, "app_usage_daily").size
        val (stored, usageStored) = synchronized(SyncEngine.lock) { Graph.db.mergeBackup(intervals, usage) }
        return Imported(intervals.size + usage.size, stored, usageStored, skipped)
    }

    private fun section(backup: JSONObject, name: String): JSONObject {
        if (backup.isNull(name)) return JSONObject()
        return backup.opt(name) as? JSONObject ?: throw BackupException("The backup is damaged (unexpected sections).")
    }

    private fun rows(section: JSONObject, name: String): List<JSONObject> {
        if (section.isNull(name)) return emptyList()
        val list = section.opt(name) as? JSONArray
            ?: throw BackupException("The backup is damaged (its rows aren't lists of objects).")
        return (0 until list.length()).map {
            list.opt(it) as? JSONObject ?: throw BackupException("The backup is damaged (its rows aren't lists of objects).")
        }
    }
}
