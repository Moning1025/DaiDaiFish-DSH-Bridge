package com.shenshen.bridge

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

/**
 * 与电脑端 bridge/server.js 的长连接客户端。
 *
 * 职责：
 *  - 连接 ws://<电脑TailscaleIP>:8080（手机走 Tailscale 内网）
 *  - 断线后指数退避重连（1s → 2s → 4s … 封顶 30s + 随机抖动）
 *  - 收到 approval-request → 写入 BridgeState.pendingApproval 并弹系统通知
 *  - 提供 sendRun / sendApproval 两个上行动作
 */
class BridgeClient(
    private val serverUrl: String = Config.DEFAULT_SERVER_URL,
    private val onEvent: ((JSONObject) -> Unit)? = null,
) {

    companion object {
        private const val TAG = "BridgeClient"
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val PING_INTERVAL_SEC = 30L
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "bridge-reconnect").apply { isDaemon = true }
    }

    @Volatile
    private var socket: WebSocket? = null

    private val stopped = AtomicBoolean(false)
    private val reconnectPending = AtomicBoolean(false)
    private var attempt = 0

    val isConnected: Boolean
        get() = BridgeState.status == BridgeState.Status.CONNECTED

    fun start() {
        stopped.set(false)
        attempt = 0
        connectNow()
    }

    fun stop() {
        stopped.set(true)
        reconnectPending.set(false)
        socket?.close(1000, "client stop")
        socket = null
        BridgeState.setStatus(BridgeState.Status.DISCONNECTED)
    }

    /** 下发一次任务给电脑端 dsh。 */
    fun sendRun(prompt: String): Boolean = sendJson { root ->
        root.put("action", "run")
        root.put("prompt", prompt)
    }

    /** 审批回包：outcome ∈ allowed-once / rejected。 */
    fun sendApproval(callId: String, outcome: String): Boolean {
        val ok = sendJson { root ->
            root.put("action", "approval")
            root.put("id", callId)
            root.put("decision", outcome)
        }
        if (ok) BridgeState.clearPendingApproval()
        return ok
    }

    private inline fun sendJson(build: (JSONObject) -> Unit): Boolean {
        val ws = socket
        if (ws == null) {
            Log.w(TAG, "send skipped: not connected")
            return false
        }
        val payload = JSONObject().also(build).toString()
        return try {
            ws.send(payload)
        } catch (t: Throwable) {
            Log.w(TAG, "send failed", t)
            false
        }
    }

    private fun connectNow() {
        if (stopped.get()) return
        BridgeState.setStatus(
            if (attempt == 0) BridgeState.Status.CONNECTING else BridgeState.Status.RECONNECTING,
        )
        val request = Request.Builder().url(serverUrl).build()
        socket = http.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            reconnectPending.set(false)
            BridgeState.setStatus(BridgeState.Status.CONNECTED)
            Log.i(TAG, "connected: $serverUrl")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            dispatch(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failure: ${t.message}")
            BridgeState.setStatus(BridgeState.Status.DISCONNECTED, t.message)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "socket closed: $code $reason")
            BridgeState.setStatus(BridgeState.Status.DISCONNECTED, reason)
            scheduleReconnect()
        }
    }

    private fun dispatch(text: String) {
        val obj = try {
            JSONObject(text)
        } catch (t: Throwable) {
            Log.w(TAG, "bad json: ${text.take(120)}")
            return
        }
        when (obj.optString("type")) {
            "approval-request" -> {
                val data = obj.optJSONObject("data") ?: obj
                val id = data.optString("id").ifEmpty { data.optString("callId") }
                val pending = BridgeState.PendingApproval(
                    id = id,
                    toolName = data.optString("toolName"),
                    reason = data.optString("reason"),
                    receivedAt = System.currentTimeMillis(),
                )
                BridgeState.setPendingApproval(pending)
                notifyApprovalAsync(pending)
            }
            "approval-resolved" -> BridgeState.clearPendingApproval()
            else -> Unit
        }
        onEvent?.invoke(obj)
    }

    private fun notifyApprovalAsync(pending: BridgeState.PendingApproval) {
        // 通知需要 Context；由 BridgeService 注入（见 setNotifierContext），这里只做兜底日志。
        val ctx = notifierContext
        if (ctx == null) {
            Log.w(TAG, "no context for notification, pending id=${pending.id}")
            return
        }
        runCatching { ApprovalNotifier.notifyApproval(ctx, pending) }
            .onFailure { Log.w(TAG, "notify failed", it) }
    }

    private fun scheduleReconnect() {
        if (stopped.get()) return
        if (socket != null) socket = null
        if (!reconnectPending.compareAndSet(false, true)) return
        attempt++
        val shift = min(attempt - 1, 5)
        val base = BASE_BACKOFF_MS shl shift
        val delay = min(base, MAX_BACKOFF_MS) + Random.nextLong(0, 500)
        Log.i(TAG, "reconnect in ${delay}ms (attempt=$attempt)")
        scheduler.schedule({
            reconnectPending.set(false)
            connectNow()
        }, delay, TimeUnit.MILLISECONDS)
    }

    /** 由 BridgeService 在启动时注入，避免通知依赖 UI 层。 */
    @Volatile
    var notifierContext: android.content.Context? = null
}
