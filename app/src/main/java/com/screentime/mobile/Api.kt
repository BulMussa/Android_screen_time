package com.screentime.mobile

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** [status] is the HTTP status, or null if the server couldn't be reached. */
open class SyncException(message: String, val status: Int? = null, val retryAfter: Int? = null) : Exception(message)

/** The server's REST API (see the server's "API contract for clients"). */
object Api {
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1")

    /** The server address cleaned up; the password only goes over HTTPS. */
    fun checkServer(input: String): String {
        val server = input.trim().trimEnd('/')
        val uri = try {
            URI(server)
        } catch (e: Exception) {
            throw SyncException("Enter the server address, e.g. https://myserver.example:8443")
        }
        if (uri.host.isNullOrEmpty()) throw SyncException("Enter the server address, e.g. https://myserver.example:8443")
        if (uri.scheme != "https" && !(uri.scheme == "http" && uri.host in LOCAL_HOSTS)) {
            throw SyncException("The server address must start with https://")
        }
        return server
    }

    fun call(server: String, method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val conn = URL(server + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val detail = try {
                    JSONObject(text).optString("detail")
                } catch (e: JSONException) {
                    ""
                }
                throw SyncException(
                    detail.ifBlank { "The server answered with an error ($code)" },
                    code,
                    conn.getHeaderField("Retry-After")?.toIntOrNull(),
                )
            }
            return JSONObject(text)
        } catch (e: IOException) {
            throw SyncException("Can't reach the server (${e.message})")
        } catch (e: JSONException) {
            throw SyncException("Unexpected answer from the server")
        } finally {
            conn.disconnect()
        }
    }

    /** Logs in, registers this phone as a device and keeps its token. The password is not kept. */
    fun connect(serverInput: String, name: String, password: String, deviceName: String) {
        val server = checkServer(serverInput)
        if (name.isBlank() || password.isEmpty() || deviceName.isBlank()) {
            throw SyncException("Fill in the name, password and device name.")
        }
        try {
            val login = try {
                call(server, "POST", "/v1/auth/login", JSONObject().put("name", name.trim()).put("password", password), null)
            } catch (e: SyncException) {
                if (e.status == 429) {
                    val minutes = maxOf(1, (e.retryAfter ?: 900) / 60)
                    throw SyncException("Too many failed logins. Try again in $minutes min.", 429)
                }
                throw e
            }
            val device = call(
                server, "POST", "/v1/devices",
                JSONObject().put("name", deviceName.trim()).put("type", "android"),
                login.getString("session_token"),
            )
            val token = device.getString("token")
            val deviceId = device.getInt("device_id")
            val registeredName = device.getString("name")
            synchronized(SyncEngine.lock) {
                val prefs = Graph.prefs
                prefs.token = token
                prefs.server = server
                prefs.deviceId = deviceId
                prefs.deviceName = registeredName
                SyncEngine.resetRemote()
                Graph.db.forgetSent("server") // send the history already on the phone
            }
        } catch (e: JSONException) {
            throw SyncException("Unexpected answer from the server")
        }
    }

    /** Forgets the server and token. The phone stays registered on the server. */
    fun disconnect() = synchronized(SyncEngine.lock) {
        val prefs = Graph.prefs
        prefs.token = null
        prefs.server = null
        prefs.deviceId = -1
        prefs.deviceName = null
        SyncEngine.resetRemote()
    }
}
