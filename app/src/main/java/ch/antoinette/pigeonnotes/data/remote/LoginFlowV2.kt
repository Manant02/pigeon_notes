package ch.antoinette.pigeonnotes.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Nextcloud Login Flow v2: the user authenticates in the browser and the server hands us an
 * app password, so the real password never touches this app.
 * https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html#login-flow-v2
 */
class LoginFlowV2(private val http: OkHttpClient) {

    data class Started(val loginUrl: String, val pollEndpoint: String, val token: String)
    data class Credentials(val server: String, val loginName: String, val appPassword: String)

    suspend fun start(serverUrl: String): Started = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/index.php/login/v2")
            .header("User-Agent", WebDavClient.USER_AGENT)
            .post(FormBody.Builder().build())
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Login flow start failed: HTTP ${resp.code}")
            val json = JSONObject(resp.body.string())
            val poll = json.getJSONObject("poll")
            Started(json.getString("login"), poll.getString("endpoint"), poll.getString("token"))
        }
    }

    /** Polls until the browser login completes. Cancel the coroutine to abort. */
    suspend fun awaitCredentials(started: Started, intervalMillis: Long = 2000): Credentials {
        while (true) {
            val creds = pollOnce(started)
            if (creds != null) return creds
            delay(intervalMillis)
        }
    }

    private suspend fun pollOnce(started: Started): Credentials? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(started.pollEndpoint)
            .header("User-Agent", WebDavClient.USER_AGENT)
            .post(FormBody.Builder().add("token", started.token).build())
            .build()
        http.newCall(req).execute().use { resp ->
            when (resp.code) {
                200 -> {
                    val json = JSONObject(resp.body.string())
                    Credentials(json.getString("server"), json.getString("loginName"), json.getString("appPassword"))
                }
                404 -> null // not confirmed yet
                else -> throw IOException("Login flow poll failed: HTTP ${resp.code}")
            }
        }
    }
}
