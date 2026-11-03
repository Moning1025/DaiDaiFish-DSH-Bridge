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
 * 与电脑端 bridge/server.js 的长连接客户端（进程内单例）。
 *
 * 职责：
 *  - 连接 ws://<电脑TailscaleIP>:8080
 *  - 断线后指数退避重连（1s → 2s → 4s … 封顶 30s + 随机抖动）
 *  - 收到 approval-request → 写 BridgeState + 弹带按钮的通知 + 桌宠气泡 + 音效
 *  - 收集 thinking / tool_call 事件到「执行轨迹」
 *  - 提供 sendRun / sendApproval / sendStop / reconnect
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

        /** 全进程唯一实例（BridgeService 创建，UI / 通知按钮共用） */
        @Volatile
        var shared: BridgeClient? = null
            private set

        fun install(client: BridgeClient) {
            shared = client
        }
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
        closeSocket("client stop")
        BridgeState.setStatus(BridgeState.Status.DISCONNECTED)
    }

    /** 【重连】按钮：立刻掐掉旧连接并重连（不等退避） */
    fun reconnect() {
        Log.i(TAG, "manual reconnect")
        stopped.set(false)
        attempt = 0
        reconnectPending.set(false)
        closeSocket("manual reconnect")
        BridgeState.setStatus(BridgeState.Status.CONNECTING)
        connectNow()
    }

    /** 下发一次任务给电脑端 dsh。workspace = 目标工作区绝对路径（空则用服务端默认根目录） */
    fun sendRun(prompt: String, workspace: String? = null): Boolean {
        BridgeState.clearTrace()
        return sendJson { root ->
            root.put("action", "run")
            root.put("prompt", prompt)
            val ws = workspace ?: BridgeState.selectedWorkspace
            if (!ws.isNullOrBlank()) root.put("workspace", ws)
        }
    }

    /** 拉取电脑端工作区列表 */
    fun requestWorkspaces(): Boolean = sendJson { root -> root.put("action", "workspaces") }

    /** 在电脑端新建工作区（创建目录并写入 workspace.json 注册表） */
    fun createWorkspace(path: String): Boolean = sendJson { root ->
        root.put("action", "create-workspace")
        root.put("path", path)
    }

    /** 停止当前任务 */
    fun sendStop(): Boolean = sendJson { root -> root.put("action", "stop") }

    /** 审批回包：outcome ∈ allowed-once / rejected */
    fun sendApproval(callId: String, outcome: String): Boolean {
        val ok = sendJson { root ->
            root.put("action", "approval")
            root.put("id", callId)
            root.put("decision", outcome)
        }
        if (ok) {
            BridgeState.clearPendingApproval()
            cancelApprovalNotification()
        }
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

    private fun closeSocket(reason: String) {
        val old = socket
        socket = null
        runCatching { old?.close(1000, reason) }
        runCatching { old?.cancel() }   // 双保险：close 对已半死的连接不管用
    }

    private fun connectNow() {
        if (stopped.get()) return
        closeSocket("reconnect")     // 先掐掉可能残留的旧连接，避免服务端看到"幽灵在线"
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
            requestWorkspaces()   // 连上就顺手拉一次工作区列表
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

    // ------------------------------------------------------------ 消息分发

    private fun dispatch(text: String) {
        val obj = try {
            JSONObject(text)
        } catch (t: Throwable) {
            Log.w(TAG, "bad json: ${text.take(120)}")
            return
        }
        when (obj.optString("type")) {
            "approval-request" -> handleApprovalRequest(obj)
            "approval-resolved" -> {
                BridgeState.clearPendingApproval()
                cancelApprovalNotification()
            }
            "event" -> handleEvent(obj.optJSONObject("data") ?: obj)
            "task-started" -> {
                BridgeState.setTaskRunning(true)
                val data = obj.optJSONObject("data")
                data?.optString("cwd")?.let { BridgeState.setCwd(it) }
            }
            "task-done" -> {
                BridgeState.setTaskRunning(false)
                BridgeState.clearPendingApproval()
                cancelApprovalNotification()
            }
            "session" -> {
                val data = obj.optJSONObject("data") ?: obj
                (data.optString("cwd").ifEmpty { null })?.let { BridgeState.setCwd(it) }
            }
            "workspaces" -> {
                val arr = obj.optJSONArray("data")
                val list = ArrayList<BridgeState.WorkspaceInfo>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val p = o.optString("path")
                        if (p.isEmpty()) continue
                        list.add(
                            BridgeState.WorkspaceInfo(
                                id = o.optString("id"),
                                title = o.optString("title").ifEmpty { p },
                                path = p,
                                exists = o.optBoolean("exists", true),
                            ),
                        )
                    }
                }
                BridgeState.setWorkspaces(list)
            }
            "workspace-created" -> {
                val data = obj.optJSONObject("data")
                val p = data?.optString("path")
                if (!p.isNullOrEmpty()) BridgeState.selectWorkspace(p)
            }
            "error" -> {
                val data = obj.optJSONObject("data")
                val msg = data?.optString("message") ?: obj.optString("message")
                if (msg.isNotEmpty()) BridgeState.setStatus(BridgeState.status, msg)
            }
            else -> Unit
        }
        onEvent?.invoke(obj)
    }

    private fun handleApprovalRequest(obj: JSONObject) {
        val data = obj.optJSONObject("data") ?: obj
        val id = data.optString("id").ifEmpty { data.optString("callId") }
        val toolName = data.optString("toolName")
        val reason = data.optString("reason")
        val timeout = data.optLong("timeoutMs", Config.APPROVAL_TIMEOUT_MS)
        val pending = BridgeState.PendingApproval(
            id = id,
            toolName = toolName,
            reason = reason,
            receivedAt = System.currentTimeMillis(),
            timeoutMs = timeout,
        )
        BridgeState.setPendingApproval(pending)

        val ctx = notifierContext
        if (ctx != null) {
            runCatching { ApprovalNotifier.notifyApproval(ctx, pending) }
                .onFailure { Log.w(TAG, "notify failed", it) }
        }

        // 桌宠气泡：DSH审批：pwsh | 需要在工作区外写入 test.txt
        val brief = buildString {
            append("DSH审批：")
            append(toolName.ifBlank { "工具" })
            val r = reason.replace('\n', ' ').trim()
            if (r.isNotEmpty()) {
                append(" | ")
                append(if (r.length > 40) r.take(40) + "…" else r)
            }
        }
        BridgeHooks.showBubble(brief)
        BridgeHooks.playClick()
    }

    private fun handleEvent(data: JSONObject) {
        val kind = data.optString("type").ifEmpty { data.optString("event") }
        when (kind) {
            "thinking" -> {
                val text = firstNonEmpty(data, "delta", "text", "content", "message")
                if (text.isNotBlank()) {
                    BridgeState.addTrace(
                        BridgeState.TraceEntry(
                            kind = "thinking",
                            brief = "思考：" + text.replace('\n', ' ').take(60),
                            detail = text,
                            at = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            "tool_call" -> {
                val tool = firstNonEmpty(data, "tool", "name", "toolName").ifBlank { "工具" }
                val args = firstNonEmpty(data, "arguments", "args", "input", "argumentsText")
                BridgeState.addTrace(
                    BridgeState.TraceEntry(
                        kind = "tool_call",
                        brief = "调用 $tool" + if (args.isNotBlank()) "：" + args.replace('\n', ' ').take(50) else "",
                        detail = "$tool\n$args",
                        at = System.currentTimeMillis(),
                    ),
                )
            }
            else -> Unit
        }
    }

    private fun firstNonEmpty(obj: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = obj.opt(k) ?: continue
            val s = when (v) {
                is String -> v
                else -> v.toString()
            }
            if (s.isNotBlank() && s != "null") return s
        }
        return ""
    }

    private fun scheduleReconnect() {
        if (stopped.get()) return
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

    private fun cancelApprovalNotification() {
        val ctx = notifierContext ?: return
        runCatching { ApprovalNotifier.cancelApproval(ctx) }
    }

    /** 由 BridgeService 在启动时注入，避免通知依赖 UI 层。 */
    @Volatile
    var notifierContext: android.content.Context? = null
}
