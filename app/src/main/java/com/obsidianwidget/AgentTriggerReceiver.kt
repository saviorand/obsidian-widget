package com.obsidianwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * The "agent:" verb in a panel's action vocabulary: a tap that needs real
 * judgment rather than a fixed effect ("mark this done", "summarize X") gets
 * forwarded here as a free-text payload, which this turns into a fresh
 * prompt against the same agent-host.mjs the chat widget already talks to.
 * Fire-and-forget from the panel's point of view — whatever the agent does
 * in response (edit a file, push updated panel content) is the visible
 * result, not a reply shown here.
 */
class AgentTriggerReceiver : BroadcastReceiver() {
    companion object {
        private const val ACTION_TRIGGER = "com.obsidianwidget.aio.ACTION_AGENT_SIGNAL"
        private const val TIMEOUT_MS = 20_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) return
        val payload = intent.getStringExtra("payload") ?: return
        val panel = intent.getStringExtra("panel")

        val agentManager = AgentManager(context)
        val client = OkHttpClient.Builder().build()
        val request = Request.Builder().url(agentManager.wsUrl).build()

        val pendingResult = goAsync()
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        val finish = { socket: WebSocket? ->
            if (!finished) {
                finished = true
                socket?.close(1000, null)
                pendingResult.finish()
            }
        }

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val text = if (panel != null) "[Home screen: $panel] $payload" else payload
                webSocket.send(JSONObject().apply {
                    put("type", "prompt")
                    put("text", text)
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    if (msg.optString("type") == "host_turn_end") finish(webSocket)
                } catch (_: Exception) {
                    // Not JSON we care about — keep waiting.
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(null)
            }
        })

        handler.postDelayed({ finish(socket) }, TIMEOUT_MS)
    }
}
