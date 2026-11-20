package com.screentime.mobile

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/** Settings and the device token. The token is kept encrypted; the password is never stored. */
class Prefs(context: Context) {
    private val plain: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secret: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secret",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var server: String?
        get() = plain.getString("server", null)
        set(value) = plain.edit().putString("server", value).apply()

    var deviceName: String?
        get() = plain.getString("device_name", null)
        set(value) = plain.edit().putString("device_name", value).apply()

    var deviceId: Int
        get() = plain.getInt("device_id", -1)
        set(value) = plain.edit().putInt("device_id", value).apply()

    var token: String?
        get() = secret.getString("token", null)
        set(value) = secret.edit().putString("token", value).apply()

    /** The pairing with the laptop (its pairing secret, key and pinned certificate), kept encrypted; null = not paired. */
    var peer: PeerPairing?
        get() = secret.getString("peer", null)?.let {
            try {
                PeerPairing.fromJson(org.json.JSONObject(it))
            } catch (e: org.json.JSONException) {
                null
            }
        }
        set(value) {
            val edit = secret.edit()
            if (value == null) edit.remove("peer") else edit.putString("peer", value.toJson().toString())
            check(edit.commit()) { "couldn't save the pairing" } // written now: it holds the laptop's key
        }

    /** Where we are in a transport's changes ("0" = from the start). Before transports, the server's was "cursor". */
    fun cursor(transport: String): String =
        plain.getString("cursor_$transport", null)
            ?: (if (transport == "server") plain.getString("cursor", null) else null)
            ?: "0"

    fun setCursor(transport: String, value: String) {
        val edit = plain.edit().putString("cursor_$transport", value)
        if (transport == "server") edit.remove("cursor")
        edit.apply()
    }

    /**
     * Background attempts in a row that didn't find the laptop; it backs off after a few (see [DirectPolicy]).
     * Written at once (commit, off the main thread): a background job's process may end right after the sync,
     * and a value still waiting to be written would be lost, so the backoff would never start.
     */
    var directMisses: Int
        get() = plain.getInt("direct_misses", 0)
        set(value) {
            plain.edit().putInt("direct_misses", value).commit()
        }

    /** When the background last tried the laptop (ms since the epoch); written at once, like [directMisses]. */
    var directLastAttemptMs: Long
        get() = plain.getLong("direct_last_attempt", 0L)
        set(value) {
            plain.edit().putLong("direct_last_attempt", value).commit()
        }

    /** "server", "direct" or "both" (see [SyncEngine.MODES]). */
    var syncMode: String
        get() = plain.getString("sync_mode", null)?.takeIf { it in SyncEngine.MODES } ?: SyncEngine.DEFAULT_MODE
        set(value) {
            require(value in SyncEngine.MODES) { "unknown sync mode $value" }
            plain.edit().putString("sync_mode", value).apply()
        }

    /** "band" (Health Connect) or "manual"; null until the user has answered. */
    var sleepMethod: String?
        get() = plain.getString("sleep_method", null)
        set(value) = plain.edit().putString("sleep_method", value).apply()

    /** The status line; written at once, so a background sync's result isn't lost when its process ends. */
    var status: String
        get() = plain.getString("status", "Not synced yet") ?: "Not synced yet"
        set(value) {
            plain.edit().putString("status", value).commit()
        }

    /** The day (YYYY-MM-DD) the sleep reminder was last shown, so it comes once a day. */
    var reminderDay: String?
        get() = plain.getString("reminder_day", null)
        set(value) = plain.edit().putString("reminder_day", value).apply()

    /** Names this phone's rows on the server; made once and kept when reconnecting. */
    val installId: String
        get() = plain.getString("install_id", null)
            ?: UUID.randomUUID().toString().also { plain.edit().putString("install_id", it).apply() }

    val connected: Boolean
        get() = server != null && token != null
}
