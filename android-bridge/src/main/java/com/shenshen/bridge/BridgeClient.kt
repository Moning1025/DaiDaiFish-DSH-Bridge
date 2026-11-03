package com.shenshen.bridge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

/**
 * 与电脑端 bridge/server.js 的长连接客户端（进程内单例）。
 *
 * v4（P3）职责：
 *  - 连接 ws://<电脑TailscaleIP>:8080，断线指数退避重连
 *  - hello / tasks / task-state / trace / task-text / task-trace 全套 v3.2 协议
 *  - 审批：approval-request → 通知 + 桌宠气泡 + 音效
 *  - 多任务：requestTasks / switchTask / sendStop(taskId)
 *  - 上传：uploadUri() 走 upload-begin/chunk/end 分片（64KB，base64）
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
        private const val CHUNK_SIZE = 64 * 1024

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

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bridge-upload").apply { isDaemon = true }
    }

    /** uploadId → 任务 id（上传完成时服务端会回 taskId） */
    private val uploadTasks = ConcurrentHashMap<String, String>()

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

    /**
     * 下发任务。
     * @param workspace 目标工作区绝对路径（空 = 服务端默认根目录）
     * @param sessionId 续跑已有会话（空 = 新任务）
     * @param attachments 附件本地路径列表（上传后由服务端返回）
     */
    fun sendRun(
        prompt: String,
        workspace: String? = null,
        sessionId: String? = null,
        attachments: List<String> = emptyList(),
    ): Boolean {
        if (sessionId.isNullOrEmpty()) BridgeState.clearTrace()
        return sendJson { root ->
            root.put("action", "run")
            root.put("prompt", prompt)
            val ws = workspace ?: BridgeState.selectedWorkspace
            if (!ws.isNullOrBlank()) root.put("workspace", ws)
            if (!sessionId.isNullOrBlank()) root.put("sessionId", sessionId)
            if (attachments.isNotEmpty()) {
                val arr = JSONArray()
                attachments.forEach { arr.put(it) }
                root.put("attachments", arr)
            }
        }
    }

    /** 拉取电脑端工作区列表 */
    fun requestWorkspaces(): Boolean = sendJson { root -> root.put("action", "workspaces") }

    /** 拉取电脑端任务列表 */
    fun requestTasks(): Boolean = sendJson { root -> root.put("action", "tasks") }

    /** 切换盯着的任务，服务端会回 task-trace 回放轨迹 */
    fun switchTask(taskId: String): Boolean {
        BridgeState.setActiveTask(taskId)
        return sendJson { root ->
            root.put("action", "switch-task")
            root.put("taskId", taskId)
        }
    }

    /** 在电脑端新建工作区（创建目录并写入 workspace.json 注册表） */
    fun createWorkspace(path: String): Boolean = sendJson { root ->
        root.put("action", "create-workspace")
        root.put("path", path)
    }

    /** 停止任务；taskId 空 = 停最近一个 */
    fun sendStop(taskId: String? = null): Boolean = sendJson { root ->
        root.put("action", "stop")
        if (!taskId.isNullOrBlank()) root.put("taskId", taskId)
    }

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

    /**
     * 分片上传一个文件（后台线程）：upload-begin → upload-chunk × N → upload-end。
     * 成功后 BridgeState.pendingAttachment = 服务端落盘路径，随下一条 run 发送。
     */
    fun uploadUri(context: Context, uri: Uri): Boolean {
        if (socket == null) return false
        val resolver = context.contentResolver
        var name = "upload_${System.currentTimeMillis()}"
        var size = -1L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) {
                        val v = c.getString(ni)
                        if (!v.isNullOrBlank()) name = v
                    }
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }.onFailure { Log.w(TAG, "query uri failed", it) }
        if (size <= 0) return false

        val uploadId = "up-${UUID.randomUUID()}"
        val taskId = BridgeState.activeTaskId
        BridgeState.setUploadProgress(0L, size)
        io.execute {
            try {
                val input: InputStream = resolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法打开文件流")
                input.use { stream -> streamUpload(uploadId, stream, name, size, taskId) }
            } catch (t: Throwable) {
                Log.w(TAG, "upload failed", t)
                sendJson { root ->
                    root.put("action", "upload-abort")
                    root.put("uploadId", uploadId)
                }
                BridgeState.clearUploadProgress()
                BridgeState.setStatus(BridgeState.status, "上传失败：${t.message}")
            }
        }
        return true
    }

    private fun streamUpload(
        uploadId: String,
        stream: InputStream,
        name: String,
        size: Long,
        taskId: String?,
    ) {
        if (taskId != null) uploadTasks[uploadId] = taskId
        sendJson { root ->
            root.put("action", "upload-begin")
            root.put("uploadId", uploadId)
            root.put("name", name)
            root.put("size", size)
            if (!taskId.isNullOrBlank()) root.put("taskId", taskId)
            val ws = BridgeState.selectedWorkspace
            if (!ws.isNullOrBlank()) root.put("workspace", ws)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(CHUNK_SIZE)
        var received = 0L
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            digest.update(buf, 0, n)
            received += n
            val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
            val ok = sendJson { root ->
                root.put("action", "upload-chunk")
                root.put("uploadId", uploadId)
                root.put("dataB64", b64)
            }
            if (!ok) throw IllegalStateException("连接已断，上传中止")
            BridgeState.setUploadProgress(received, size)
        }

        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        sendJson { root ->
            root.put("action", "upload-end")
            root.put("uploadId", uploadId)
            root.put("sha256", sha)
        }
        Log.i(TAG, "upload sent: $name ($received/$size) sha=$sha")
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
            // hello 会带 tasks + workspaces；这里再主动补一发，防止服务端版本差异
            requestWorkspaces()
            requestTasks()
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
            "hello" -> {
                val data = obj.optJSONObject("data") ?: JSONObject()
                BridgeState.setMaxTasks(data.optInt("maxTasks", 2))
                handleTasks(data.optJSONArray("tasks"))
                handleWorkspaces(data.optJSONArray("workspaces"))
            }
            "approval-request" -> handleApprovalRequest(obj)
            "approval-resolved" -> {
                BridgeState.clearPendingApproval()
                cancelApprovalNotification()
            }
            "trace" -> handleTrace(obj.optJSONObject("data"))
            "task-state" -> handleTaskState(obj.optJSONObject("data"))
            "task-text" -> {
                val data = obj.optJSONObject("data") ?: obj
                val t = data.optString("text")
                if (t.isNotEmpty()) BridgeState.appendStreamText(t)
            }
            "task-trace" -> handleTaskTrace(obj.optJSONObject("data"))
            "tasks" -> handleTasks(obj.optJSONArray("data"))
            "task-started" -> {
                BridgeState.setTaskRunning(true)
                val data = obj.optJSONObject("data")
                data?.optString("cwd")?.let { BridgeState.setCwd(it) }
                data?.optString("taskId")?.takeIf { it.isNotEmpty() }?.let { BridgeState.setActiveTask(it) }
            }
            "task-done" -> {
                BridgeState.setTaskRunning(false)
                BridgeState.clearPendingApproval()
                cancelApprovalNotification()
            }
            "task-stopping" -> BridgeState.setTaskState("stopping")
            "session" -> {
                val data = obj.optJSONObject("data") ?: obj
                (data.optString("cwd").ifEmpty { null })?.let { BridgeState.setCwd(it) }
            }
            "workspaces" -> handleWorkspaces(obj.optJSONArray("data"))
            "workspace-created" -> {
                val data = obj.optJSONObject("data")
                val p = data?.optString("path")
                if (!p.isNullOrEmpty()) BridgeState.selectWorkspace(p)
            }
            "upload-accept" -> {
                val data = obj.optJSONObject("data")
                val id = data?.optString("uploadId")
                Log.i(TAG, "upload accepted: $id")
            }
            "upload-progress" -> {
                val data = obj.optJSONObject("data") ?: JSONObject()
                BridgeState.setUploadProgress(data.optLong("received"), data.optLong("size"))
            }
            "upload-done" -> {
                val data = obj.optJSONObject("data") ?: JSONObject()
                val path = data.optString("path")
                val name = data.optString("name")
                val id = data.optString("uploadId")
                uploadTasks.remove(id)
                if (path.isNotEmpty()) {
                    BridgeState.setPendingAttachment(path, name)
                    Log.i(TAG, "upload done → $path")
                }
            }
            "upload-aborted" -> BridgeState.clearUploadProgress()
            "error" -> {
                val data = obj.optJSONObject("data")
                val msg = data?.optString("message") ?: obj.optString("message")
                if (msg.isNotEmpty()) BridgeState.setStatus(BridgeState.status, msg)
                if (BridgeState.uploadProgress != null) BridgeState.clearUploadProgress()
            }
            else -> Unit
        }
        onEvent?.invoke(obj)
    }

    private fun handleTasks(arr: JSONArray?) {
        val list = ArrayList<BridgeState.TaskInfo>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("taskId")
                if (id.isEmpty()) continue
                list.add(
                    BridgeState.TaskInfo(
                        taskId = id,
                        sessionId = o.optString("sessionId").ifEmpty { null },
                        workspaceTitle = o.optString("workspaceTitle").ifEmpty { null },
                        cwd = o.optString("cwd").ifEmpty { null },
                        prompt = o.optString("prompt"),
                        state = o.optString("state", "idle"),
                        turn = o.optInt("turn", 0),
                        step = o.optInt("step", 0),
                        startedAt = o.optLong("startedAt"),
                        traceCount = o.optInt("traceCount", 0),
                    ),
                )
            }
        }
        BridgeState.setTasks(list)
        val active = BridgeState.activeTaskId
        val running = list.firstOrNull { it.state == "running" }
        if (active == null) BridgeState.setActiveTask(running?.taskId ?: list.firstOrNull()?.taskId)
    }

    private fun handleWorkspaces(arr: JSONArray?) {
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

    private fun handleTaskState(data: JSONObject?) {
        if (data == null) return
        val state = data.optString("state")
        if (state.isEmpty()) return
        val reason = data.optString("reason").ifEmpty { null }
        val turn = if (data.has("turn")) data.optInt("turn") else null
        val step = if (data.has("step")) data.optInt("step") else null
        BridgeState.setTaskState(state, reason, turn, step)
        if (state != "running" && state != "stopping") {
            // 任务结束：刷新一次列表，清掉挂起审批
            requestTasks()
            BridgeState.clearPendingApproval()
            cancelApprovalNotification()
        }
    }

    private fun handleTrace(data: JSONObject?) {
        if (data == null) return
        val kind = data.optString("kind")
        if (kind.isEmpty()) return
        val brief = data.optString("brief")
        val detail = data.optString("detail")
        val ok = if (data.has("ok")) data.optBoolean("ok", true) else (data.optString("status") != "failed")
        BridgeState.addTrace(
            BridgeState.TraceEntry(
                kind = kind,
                brief = brief,
                detail = detail,
                at = data.optLong("at", System.currentTimeMillis()),
                ok = ok,
            ),
        )
    }

    private fun handleTaskTrace(data: JSONObject?) {
        if (data == null) return
        val taskId = data.optString("taskId")
        if (taskId.isNotEmpty()) BridgeState.setActiveTask(taskId)
        data.optString("state").takeIf { it.isNotEmpty() }?.let { BridgeState.setTaskState(it) }
        data.optString("cwd").takeIf { it.isNotEmpty() }?.let { BridgeState.setCwd(it) }
        val entries = data.optJSONArray("entries")
        val list = ArrayList<BridgeState.TraceEntry>()
        if (entries != null) {
            for (i in 0 until entries.length()) {
                val o = entries.optJSONObject(i) ?: continue
                list.add(
                    BridgeState.TraceEntry(
                        kind = o.optString("kind"),
                        brief = o.optString("brief"),
                        detail = o.optString("detail"),
                        at = o.optLong("at", 0L),
                        ok = if (o.has("ok")) o.optBoolean("ok", true) else true,
                    ),
                )
            }
        }
        BridgeState.replaceTrace(list)
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
            taskId = data.optString("taskId").ifEmpty { null },
            workspace = data.optString("workspace").ifEmpty { null },
            sessionBrief = data.optString("sessionBrief").ifEmpty { null },
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
    var notifierContext: Context? = null
}
