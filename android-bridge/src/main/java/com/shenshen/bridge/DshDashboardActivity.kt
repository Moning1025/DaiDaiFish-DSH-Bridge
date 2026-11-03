package com.shenshen.bridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

/**
 * DSH 掌上中控台（v4 / 2.00-dsh）。
 *
 * 深海主题：bg2.jpg 背景 + #990B1320 深色遮罩；标题下副标题「深海之下·鲸落之上·沫宁鱼」带呼吸流光。
 * 功能：四态状态栏 / 多任务标签条（switch-task 回放）/ 审批卡片 / 轨迹四类渲染 / 工作区选择 / 📎 分片上传 / 发送 / 停止 / 重连。
 *
 * 纯代码构建 UI，不依赖任何第三方库（只吃 android.jar）。
 */
class DshDashboardActivity : Activity(), BridgeState.Refreshable {

    // ---------------------------------------------------------- 配色
    private val colBg = Color.parseColor("#060B14")
    private val colScrim = Color.parseColor("#990B1320")
    private val colCard = Color.parseColor("#4D1E293B")
    private val colCardWarn = Color.parseColor("#CC2A2418")
    private val colTrace = Color.parseColor("#B30B1220")
    private val colStroke = Color.parseColor("#33FFFFFF")
    private val colText = Color.WHITE
    private val colSub = Color.parseColor("#E2E8F0")
    private val colDim = Color.parseColor("#94A3B8")
    private val colAccent = Color.parseColor("#38BDF8")
    private val colOk = Color.parseColor("#34D399")
    private val colWarn = Color.parseColor("#FBBF24")
    private val colDanger = Color.parseColor("#FF4D6D")

    // ---------------------------------------------------------- 视图
    private lateinit var glowDot: View
    private lateinit var dot: View
    private lateinit var statusText: TextView
    private lateinit var subtitle: TextView
    private lateinit var taskRow: LinearLayout
    private lateinit var approvalCard: LinearLayout
    private lateinit var approvalTitle: TextView
    private lateinit var approvalBody: TextView
    private lateinit var workspaceSpinner: Spinner
    private lateinit var wsPathText: TextView
    private lateinit var promptInput: EditText
    private lateinit var attachChip: TextView
    private lateinit var uploadText: TextView
    private lateinit var stateText: TextView
    private lateinit var traceHeader: TextView
    private lateinit var traceBox: LinearLayout
    private lateinit var cwdText: TextView
    private lateinit var mainScroll: ScrollView

    // ---------------------------------------------------------- 状态
    private var traceOpen = true
    private val expanded = HashSet<Int>()
    private var spinnerSyncing = false
    private val ui = Handler(Looper.getMainLooper())
    private var glowAnim: android.animation.ValueAnimator? = null
    private var breatheAnim: android.animation.ValueAnimator? = null

    // ---------------------------------------------------------- 生命周期
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        try {
            window.statusBarColor = colBg
            window.navigationBarColor = colBg
        } catch (_: Throwable) {
            // 忽略：部分 ROM 不允许改
        }
        setContentView(buildRoot())
        BridgeService.start(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        BridgeState.addRefreshable(this)
        startAnimations()
        refresh()
        BridgeClient.shared?.let { if (!it.isConnected) it.reconnect() else it.requestTasks() }
    }

    override fun onPause() {
        super.onPause()
        BridgeState.removeRefreshable(this)
        stopAnimations()
    }

    override fun refresh() {
        if (Looper.myLooper() == Looper.getMainLooper()) render() else ui.post { render() }
    }

    // ---------------------------------------------------------- 根布局
    private fun buildRoot(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(colBg)

        val bg = ImageView(this)
        bg.scaleType = ImageView.ScaleType.CENTER_CROP
        runCatching {
            assets.open("bg2.jpg").use { input ->
                bg.setImageBitmap(android.graphics.BitmapFactory.decodeStream(input))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 不做模糊（性能优先），仅让底图稍暗一点
        }
        root.addView(
            bg,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val scrim = View(this)
        scrim.setBackgroundColor(colScrim)
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        mainScroll = ScrollView(this)
        mainScroll.isFillViewport = true
        mainScroll.setPadding(0, 0, 0, 0)

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        // 顶部多留 32dp，避开状态栏 / 灵动岛
        content.setPadding(dp(16), dp(32) + insetsTop(), dp(16), dp(28))

        content.addView(buildHeader())
        content.addView(space(14))
        content.addView(buildStatusRow())
        content.addView(space(12))
        content.addView(buildTaskTabs())
        content.addView(space(12))
        content.addView(buildApprovalCard())
        content.addView(space(12))
        content.addView(buildWorkspaceRow())
        content.addView(space(12))
        content.addView(buildInputCard())
        content.addView(space(12))
        content.addView(buildStateRow())
        content.addView(space(12))
        content.addView(buildTracePanel())
        content.addView(space(10))
        content.addView(buildCwdRow())

        mainScroll.addView(content)
        root.addView(
            mainScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        return root
    }

    private fun insetsTop(): Int {
        return 0
    }

    // ---------------------------------------------------------- 头部
    private fun buildHeader(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL

        val title = TextView(this)
        title.text = "DSH 控制台"
        title.setTextColor(colText)
        title.textSize = 26f
        title.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        title.letterSpacing = 0.04f
        box.addView(title)

        subtitle = TextView(this)
        subtitle.text = "深海之下 · 鲸落之上 · 沫宁鱼"
        subtitle.setTextColor(colAccent)
        subtitle.textSize = 13f
        subtitle.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        subtitle.letterSpacing = 0.06f
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(6)
        box.addView(subtitle, lp)
        return box
    }

    // ---------------------------------------------------------- 状态灯 + 重连
    private fun buildStatusRow(): View {
        val card = roundedCard(colCard, 16)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(12), dp(14), dp(12))

        val dotBox = FrameLayout(this)
        glowDot = View(this)
        runCatching {
            glowDot.setBackground(circle(Color.parseColor("#5538BDF8")))
            dot = View(this)
            dot.setBackground(circle(colDim))
        }
        dotBox.addView(
            glowDot,
            FrameLayout.LayoutParams(dp(42), dp(42)).also { it.gravity = Gravity.CENTER },
        )
        dotBox.addView(
            dot,
            FrameLayout.LayoutParams(dp(14), dp(14)).also { it.gravity = Gravity.CENTER },
        )
        row.addView(dotBox, LinearLayout.LayoutParams(dp(46), dp(46)))

        statusText = TextView(this)
        statusText.text = "未连接"
        statusText.setTextColor(colText)
        statusText.textSize = 15f
        statusText.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tlp.leftMargin = dp(6)
        row.addView(statusText, tlp)

        val reconnect = smallButton("重连", colAccent)
        reconnect.setOnClickListener {
            Toast.makeText(this, "正在重连…", Toast.LENGTH_SHORT).show()
            BridgeClient.shared?.reconnect()
        }
        row.addView(reconnect)

        card.addView(row)
        return card
    }

    // ---------------------------------------------------------- 任务标签条
    private fun buildTaskTabs(): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        val label = TextView(this)
        label.text = "任务"
        label.setTextColor(colDim)
        label.textSize = 12f
        head.addView(label)

        val newTask = TextView(this)
        newTask.text = "＋ 新任务"
        newTask.setTextColor(colAccent)
        newTask.textSize = 12f
        newTask.setPadding(dp(10), dp(4), dp(10), dp(4))
        newTask.setOnClickListener {
            BridgeState.setActiveTask(null)
            BridgeState.clearTrace()
            BridgeState.setTaskState("idle")
            promptInput.setText("")
            promptInput.requestFocus()
            Toast.makeText(this, "下一条发送将作为新任务", Toast.LENGTH_SHORT).show()
            render()
        }
        val nlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        nlp.leftMargin = dp(10)
        head.addView(newTask, nlp)
        wrap.addView(head)

        val hsv = HorizontalScrollView(this)
        hsv.isHorizontalScrollBarEnabled = false
        taskRow = LinearLayout(this)
        taskRow.orientation = LinearLayout.HORIZONTAL
        taskRow.setPadding(0, dp(8), 0, 0)
        hsv.addView(taskRow)
        wrap.addView(hsv)
        return wrap
    }

    // ---------------------------------------------------------- 审批卡片
    private fun buildApprovalCard(): View {
        approvalCard = roundedCard(colCardWarn, 16)
        approvalCard.orientation = LinearLayout.VERTICAL
        approvalCard.setPadding(dp(14), dp(12), dp(14), dp(12))
        approvalCard.visibility = View.GONE

        approvalTitle = TextView(this)
        approvalTitle.text = "待审批"
        approvalTitle.setTextColor(colWarn)
        approvalTitle.textSize = 13f
        approvalTitle.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        approvalCard.addView(approvalTitle)

        approvalBody = TextView(this)
        approvalBody.setTextColor(colSub)
        approvalBody.textSize = 13f
        approvalBody.setPadding(0, dp(6), 0, dp(10))
        approvalCard.addView(approvalBody)

        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL

        val allow = smallButton("允许一次", colOk)
        val deny = smallButton("拒绝", colDanger)
        val lp1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val lp2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp2.leftMargin = dp(10)
        btns.addView(allow, lp1)
        btns.addView(deny, lp2)
        allow.setOnClickListener { decide(true) }
        deny.setOnClickListener { decide(false) }
        approvalCard.addView(btns)
        return approvalCard
    }

    // ---------------------------------------------------------- 工作区
    private fun buildWorkspaceRow(): View {
        val card = roundedCard(colCard, 16)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(10), dp(14), dp(12))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        val label = TextView(this)
        label.text = "工作区"
        label.setTextColor(colDim)
        label.textSize = 12f
        head.addView(label)

        workspaceSpinner = Spinner(this)
        val slp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        slp.leftMargin = dp(10)
        head.addView(workspaceSpinner, slp)

        val reload = smallButton("⟳", colDim)
        reload.setOnClickListener { BridgeClient.shared?.requestWorkspaces() }
        head.addView(reload)

        val create = smallButton("＋", colAccent)
        val clp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        clp.leftMargin = dp(8)
        create.setOnClickListener { askCreateWorkspace() }
        head.addView(create, clp)
        card.addView(head)

        wsPathText = TextView(this)
        wsPathText.setTextColor(colDim)
        wsPathText.textSize = 10.5f
        wsPathText.setPadding(0, dp(6), 0, 0)
        card.addView(wsPathText)
        return card
    }

    // ---------------------------------------------------------- 输入卡
    private fun buildInputCard(): View {
        val card = roundedCard(colCard, 16)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(12), dp(14), dp(12))

        promptInput = EditText(this)
        promptInput.hint = "告诉 DSH 要做什么…"
        promptInput.setTextColor(colText)
        promptInput.setHintTextColor(colDim)
        promptInput.textSize = 14f
        promptInput.background = rounded(colTrace, 12, colStroke, 1)
        promptInput.setPadding(dp(12), dp(10), dp(12), dp(10))
        promptInput.minLines = 2
        promptInput.maxLines = 6
        promptInput.gravity = Gravity.TOP or Gravity.START
        promptInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        card.addView(promptInput)

        uploadText = TextView(this)
        uploadText.setTextColor(colAccent)
        uploadText.textSize = 11f
        uploadText.setPadding(dp(2), dp(8), 0, 0)
        uploadText.visibility = View.GONE
        card.addView(uploadText)

        attachChip = TextView(this)
        attachChip.text = ""
        attachChip.setTextColor(colSub)
        attachChip.textSize = 11f
        attachChip.setPadding(dp(2), dp(6), 0, 0)
        attachChip.visibility = View.GONE
        attachChip.setOnClickListener {
            BridgeState.clearPendingAttachment()
            render()
        }
        card.addView(attachChip)

        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.gravity = Gravity.CENTER_VERTICAL
        val blp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        blp.topMargin = dp(10)
        card.addView(btns, blp)

        val attach = actionButton("📎 上传", Color.parseColor("#1E293B"), colAccent)
        attach.setOnClickListener { pickFile() }
        btns.addView(
            attach,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val send = actionButtonGradient("发送")
        send.setOnClickListener { sendPrompt() }
        val sendLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        sendLp.leftMargin = dp(8)
        btns.addView(send, sendLp)

        val stop = actionButton("停止", colDanger, Color.WHITE)
        stop.setOnClickListener {
            BridgeClient.shared?.sendStop(BridgeState.activeTaskId)
            Toast.makeText(this, "已发送停止指令", Toast.LENGTH_SHORT).show()
        }
        val stopLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        stopLp.leftMargin = dp(8)
        btns.addView(stop, stopLp)
        return card
    }

    // ---------------------------------------------------------- 四态状态行
    private fun buildStateRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(4), 0, dp(4), 0)

        stateText = TextView(this)
        stateText.setTextColor(colDim)
        stateText.textSize = 12f
        stateText.text = "空闲"
        row.addView(stateText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val turnText = TextView(this)
        turnText.text = ""
        turnText.setTextColor(colDim)
        turnText.textSize = 11f
        row.addView(turnText)
        this.turnView = turnText
        return row
    }

    private var turnView: TextView? = null

    // ---------------------------------------------------------- 轨迹面板
    private fun buildTracePanel(): View {
        val card = roundedCard(colTrace, 14)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(12), dp(10), dp(12), dp(10))

        traceHeader = TextView(this)
        traceHeader.setTextColor(colAccent)
        traceHeader.textSize = 12f
        traceHeader.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        traceHeader.text = "▼ 执行轨迹"
        traceHeader.setOnClickListener {
            traceOpen = !traceOpen
            render()
        }
        card.addView(traceHeader)

        traceBox = LinearLayout(this)
        traceBox.orientation = LinearLayout.VERTICAL
        traceBox.setPadding(0, dp(8), 0, 0)
        card.addView(traceBox)
        return card
    }

    private fun buildCwdRow(): View {
        cwdText = TextView(this)
        cwdText.setTextColor(colDim)
        cwdText.textSize = 10f
        cwdText.maxLines = 2
        cwdText.typeface = Typeface.MONOSPACE
        return cwdText
    }

    // ---------------------------------------------------------- 渲染
    private fun render() {
        if (!this::statusText.isInitialized) return
        renderStatus()
        renderTaskTabs()
        renderApproval()
        renderWorkspace()
        renderInput()
        renderState()
        renderTrace()
        renderCwd()
    }

    private fun renderStatus() {
        val st = BridgeState.status
        val (color, text) = when (st) {
            BridgeState.Status.CONNECTED -> colOk to "已连接"
            BridgeState.Status.CONNECTING -> colAccent to "连接中…"
            BridgeState.Status.RECONNECTING -> colWarn to "重新连接中…"
            BridgeState.Status.DISCONNECTED -> colDanger to "未连接"
        }
        runCatching { dot.setBackground(circle(color)) }
        runCatching { glowDot.setBackground(circle(withAlpha(color, 0x55))) }
        statusText.text = text
        statusText.setTextColor(color)
        val err = BridgeState.lastError
        if (!err.isNullOrBlank() && st != BridgeState.Status.CONNECTED) {
            statusText.text = "$text · ${err.take(40)}"
        }
    }

    private fun renderTaskTabs() {
        if (!this::taskRow.isInitialized) return
        taskRow.removeAllViews()
        val tasks = BridgeState.tasks
        if (tasks.isEmpty()) {
            val empty = TextView(this)
            empty.text = "还没有任务"
            empty.setTextColor(colDim)
            empty.textSize = 11f
            taskRow.addView(empty)
            return
        }
        val active = BridgeState.activeTaskId
        for (t in tasks) {
            val chip = TextView(this)
            val color = colorForState(t.state)
            chip.text = "${t.taskId} · ${shortState(t.state)}"
            chip.setTextColor(if (t.taskId == active) Color.WHITE else colSub)
            chip.textSize = 11.5f
            chip.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            chip.setPadding(dp(10), dp(5), dp(10), dp(5))
            val bg = rounded(colTrace, 10, if (t.taskId == active) color else colStroke, if (t.taskId == active) 2 else 1)
            chip.background = bg
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.rightMargin = dp(8)
            chip.setOnClickListener {
                BridgeClient.shared?.switchTask(t.taskId)
                render()
            }
            taskRow.addView(chip, lp)
        }
    }

    private fun renderApproval() {
        if (!this::approvalCard.isInitialized) return
        val p = BridgeState.pendingApproval
        if (p == null) {
            approvalCard.visibility = View.GONE
            return
        }
        approvalCard.visibility = View.VISIBLE
        val wsTitle = BridgeState.tasks.firstOrNull { it.taskId == p.taskId }?.workspaceTitle
            ?: p.workspace
        val brief = p.sessionBrief ?: BridgeState.tasks.firstOrNull { it.taskId == p.taskId }?.prompt
        val head = buildString {
            append("待审批 · ")
            append(p.toolName.ifBlank { "工具" })
        }
        approvalTitle.text = head
        val body = buildString {
            if (!wsTitle.isNullOrBlank()) append("[$wsTitle]  ")
            if (!brief.isNullOrBlank()) append(brief.take(28)).append(if (brief.length > 28) "…  " else "  ")
            append("\n")
            append(p.reason.ifBlank { "（无原因描述）" }.take(220))
            append("\n超时 ")
            append(p.timeoutMs / 1000)
            append("s 后自动拒绝")
        }
        approvalBody.text = body
    }

    private fun renderWorkspace() {
        if (!this::workspaceSpinner.isInitialized) return
        val list = BridgeState.workspaces ?: emptyList()
        val titles = list.map { "${it.title}${if (it.exists) "" else "（缺失）"}" }
        spinnerSyncing = true
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, titles) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(colSub)
                tv.textSize = 12f
                tv.setPadding(0, dp(2), 0, dp(2))
                return tv
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getDropDownView(position, convertView, parent) as TextView
                tv.setTextColor(Color.parseColor("#0B1220"))
                tv.textSize = 13f
                tv.setPadding(dp(12), dp(10), dp(12), dp(10))
                tv.setBackgroundColor(Color.parseColor("#E2E8F0"))
                return tv
            }
        }
        workspaceSpinner.adapter = adapter
        val selectedPath = BridgeState.selectedWorkspace
        val idx = list.indexOfFirst { it.path == selectedPath }
        if (idx >= 0) workspaceSpinner.setSelection(idx, false)
        workspaceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (spinnerSyncing) return
                list.getOrNull(position)?.let { BridgeState.selectWorkspace(it.path) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        spinnerSyncing = false
        wsPathText.text = BridgeState.selectedWorkspace ?: "（未选择 · 使用服务端默认根目录）"
    }

    private fun renderInput() {
        if (!this::uploadText.isInitialized) return
        val prog = BridgeState.uploadProgress
        if (prog != null) {
            uploadText.visibility = View.VISIBLE
            uploadText.text = String.format(
                Locale.US,
                "上传中… %s / %s",
                humanSize(prog.first),
                humanSize(prog.second),
            )
        } else {
            uploadText.visibility = View.GONE
        }
        val path = BridgeState.pendingAttachment
        if (!path.isNullOrBlank()) {
            attachChip.visibility = View.VISIBLE
            attachChip.text = "📎 ${BridgeState.pendingAttachmentName ?: "附件"} · 点我移除"
        } else {
            attachChip.visibility = View.GONE
        }
    }

    private fun renderState() {
        if (!this::stateText.isInitialized) return
        val state = BridgeState.taskState
        val color = colorForState(state)
        val label = when (state) {
            "running" -> "执行中"
            "success" -> "已完成"
            "failed" -> "失败"
            "stopped" -> "已停止"
            "stopping" -> "正在停止…"
            else -> "空闲"
        }
        stateText.text = if (state == "idle") label else "$label · ${shortState(state)}"
        stateText.setTextColor(color)
        turnView?.text = "turn ${BridgeState.turn} · step ${BridgeState.step}"
        if (state == "failed" && !BridgeState.taskReason.isNullOrBlank()) {
            stateText.text = "失败 · ${BridgeState.taskReason!!.take(60)}"
        }
    }

    private fun renderTrace() {
        if (!this::traceBox.isInitialized) return
        traceHeader.text = if (traceOpen) "▼ 执行轨迹" else "▶ 执行轨迹"
        traceBox.visibility = if (traceOpen) View.VISIBLE else View.GONE
        if (!traceOpen) return
        traceBox.removeAllViews()
        val entries = BridgeState.trace()
        if (entries.isEmpty()) {
            val empty = TextView(this)
            empty.text = "（暂无）"
            empty.setTextColor(colDim)
            empty.textSize = 10.5f
            empty.typeface = Typeface.MONOSPACE
            traceBox.addView(empty)
            return
        }
        entries.forEachIndexed { index, e ->
            val row = TextView(this)
            val isOpen = expanded.contains(index)
            val arrow = if (isOpen) "▼" else "▶"
            val head = when (e.kind) {
                "thinking" -> "思考"
                "tool_call" -> "调用"
                "tool_result" -> if (e.ok) "结果" else "失败"
                "final" -> "完成"
                else -> e.kind
            }
            row.text = if (isOpen) {
                "$arrow $head · ${e.brief}\n${e.detail.take(1400)}"
            } else {
                "$arrow $head · ${e.brief}"
            }
            row.setTextColor(if (e.ok) colSub else colDanger)
            row.textSize = 10.5f
            row.typeface = Typeface.MONOSPACE
            row.setPadding(0, dp(3), 0, dp(3))
            row.setOnClickListener {
                if (expanded.contains(index)) expanded.remove(index) else expanded.add(index)
                render()
            }
            traceBox.addView(row)
        }
    }

    private fun renderCwd() {
        if (!this::cwdText.isInitialized) return
        cwdText.text = "cwd: ${BridgeState.lastCwd ?: "—"}"
    }

    // ---------------------------------------------------------- 交互
    private fun decide(allow: Boolean) {
        val p = BridgeState.pendingApproval ?: return
        val outcome = if (allow) Config.DECISION_ALLOW_ONCE else Config.DECISION_REJECT
        BridgeClient.shared?.sendApproval(p.id, outcome)
        BridgeState.clearPendingApproval()
        ApprovalNotifier.cancelApproval(this)
        Toast.makeText(this, if (allow) "已允许（一次）" else "已拒绝", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun sendPrompt() {
        val text = promptInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "先说点什么~", Toast.LENGTH_SHORT).show()
            return
        }
        val client = BridgeClient.shared
        if (client == null || !client.isConnected) {
            Toast.makeText(this, "还没连上电脑端", Toast.LENGTH_SHORT).show()
            return
        }
        val attachments = ArrayList<String>()
        BridgeState.pendingAttachment?.let { attachments.add(it) }
        client.sendRun(
            prompt = text,
            workspace = BridgeState.selectedWorkspace,
            attachments = attachments,
        )
        BridgeState.clearPendingAttachment()
        promptInput.setText("")
        expanded.clear()
        Toast.makeText(this, "已发送", Toast.LENGTH_SHORT).show()
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        runCatching { startActivityForResult(Intent.createChooser(intent, "选择要上传的文件"), REQ_FILE) }
            .onFailure { Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val ok = BridgeClient.shared?.uploadUri(this, uri) ?: false
        if (!ok) {
            Toast.makeText(this, "上传未开始（未连接或文件过大）", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "开始分片上传…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askCreateWorkspace() {
        val input = EditText(this)
        input.hint = "D:\\DeepSeekHarness\\my-project"
        input.setTextColor(Color.parseColor("#0B1220"))
        input.setHintTextColor(Color.parseColor("#64748B"))
        input.setPadding(dp(12), dp(10), dp(12), dp(10))
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_NoActionBar)
            .setTitle("新建工作区")
            .setMessage("填电脑上的绝对路径（不存在会自动创建）")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val p = input.text?.toString()?.trim().orEmpty()
                if (p.isEmpty()) {
                    Toast.makeText(this, "路径不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    BridgeClient.shared?.createWorkspace(p)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------- 动画
    private fun startAnimations() {
        stopAnimations()
        val glow = android.animation.ValueAnimator.ofFloat(0.25f, 0.85f)
        glow.duration = 1600
        glow.repeatCount = android.animation.ValueAnimator.INFINITE
        glow.repeatMode = android.animation.ValueAnimator.REVERSE
        glow.interpolator = LinearInterpolator()
        glow.addUpdateListener { glowDot.alpha = it.animatedValue as Float }
        glow.start()
        glowAnim = glow

        val breathe = android.animation.ValueAnimator.ofFloat(0.45f, 1f)
        breathe.duration = 1500
        breathe.repeatCount = android.animation.ValueAnimator.INFINITE
        breathe.repeatMode = android.animation.ValueAnimator.REVERSE
        breathe.addUpdateListener { subtitle.alpha = it.animatedValue as Float }
        breathe.start()
        breatheAnim = breathe
    }

    private fun stopAnimations() {
        runCatching { glowAnim?.cancel() }
        runCatching { breatheAnim?.cancel() }
        glowAnim = null
        breatheAnim = null
    }

    // ---------------------------------------------------------- UI 工具
    private fun space(dp: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(1, this.dp(dp))
        return v
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).roundToInt()

    private fun roundedCard(color: Int, radius: Int): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(color, radius, colStroke, 1)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        card.layoutParams = lp
        return card
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (strokeWidth > 0) setStroke(dp(strokeWidth), stroke)
        }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun smallButton(text: String, color: Int): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(color)
        tv.textSize = 11.5f
        tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(12), dp(6), dp(12), dp(6))
        tv.background = rounded(colTrace, 10, withAlpha(color, 0x66), 1)
        return tv
    }

    private fun actionButton(text: String, fill: Int, textColor: Int): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(textColor)
        tv.textSize = 13f
        tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(16), dp(11), dp(16), dp(11))
        val bg = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), withAlpha(textColor, 0x55))
        }
        tv.background = bg
        return tv
    }

    private fun actionButtonGradient(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.parseColor("#06121F"))
        tv.textSize = 13f
        tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(16), dp(11), dp(16), dp(11))
        val bg = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#38BDF8"), Color.parseColor("#4C8DFF")),
        )
        bg.cornerRadius = dp(12).toFloat()
        tv.background = bg
        return tv
    }

    private fun colorForState(state: String): Int = when (state) {
        "running" -> colAccent
        "success" -> colOk
        "failed" -> colDanger
        "stopped", "stopping" -> colWarn
        else -> colDim
    }

    private fun shortState(state: String): String = when (state) {
        "running" -> "执行中"
        "success" -> "成功"
        "failed" -> "失败"
        "stopped" -> "停止"
        "stopping" -> "停止中"
        else -> state
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1fMB", bytes / 1048576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0fKB", bytes / 1024.0)
        else -> "${bytes}B"
    }

    companion object {
        private const val REQ_FILE = 1001
    }
}
